# XCodeAgent Template Capability 增量更新方案与双端实施计划

> 适用范围：XCodeAgent `template_refactor` 分支及配套 Template Service / Template Engine  
> 目标：将当前基于 `managedFiles + SHA + Workspace 漂移校验` 的模板更新方式，改造为 **以当前 Workspace 最新代码为唯一代码事实源、按 Capability 做持续增量更新** 的模型。
>
> 核心原则：
>
> **Template Capability Reconcile 不要求 Workspace 与历史模板文件保持一致。每次更新都以事务开始时的当前 Workspace 为代码基线，在当前最新代码上执行幂等的 ADD / UPDATE 增量变换。TemplateState 只记录 Capability 状态，不再记录文件内容基线或 SHA。并发安全由 XCodeAgent 的 Workspace 单写事务保证，失败恢复由本次变更的文件级 Journal 保证。**

---

# 第一章 方案设计

## 1. 背景与问题

当前 Reconcile 模型大致为：

```text
TemplateState.managedFiles
        ↓
校验 Workspace 是否仍等于历史模板内容
        ↓
必须一致
        ↓
Template Service /v1/update
        ↓
ChangeSet Apply
        ↓
再次全量校验 managedFiles
```

这一模型的隐含假设是：

```text
模板管理过的文件
必须持续保持和历史 TemplateState 完全一致
```

但 XCodeAgent 的应用在 Bootstrap 之后会持续被：

```text
Business Agent
Build Agent
Repair Agent
Platform Projection
人工开发
```

修改。

因此，文件偏离首次模板不是异常，而是应用正常演进。

现有模型会导致：

```text
某个与本次 Capability 完全无关的文件变化
        ↓
整个 Capability Update 被阻断
```

例如新增 `login` 时，`frontend/src/index.css` 即使不属于 login Capability，也可能因为历史文件一致性检查导致整个 Reconcile 失败。

本次改造需要把核心模型从：

```text
Historical Template First
```

调整为：

```text
Current Workspace First
```

---

## 2. 新模型：Current Workspace First

最终链路：

```text
TechnicalPlan.template_capabilities
        ↓
Desired Capability
        ↓
Current TemplateState
        ↓
Capability Diff
        ↓
Template Service 确定 Candidate Files
        ↓
XCodeAgent 获取 Workspace 写锁
        ↓
读取 Candidate Files 当前最新内容
        ↓
Template Service /v1/update
        ↓
基于当前代码执行 Capability Transformer
        ↓
ADD / UPDATE ChangeSet
        ↓
XCodeAgent 事务应用
        ↓
Capability Validation
        ↓
原子更新 TemplateState
        ↓
成功
```

核心事实源：

```text
Current Workspace
=
当前代码唯一事实源

TemplateState
=
Capability 状态事实源

Template Service Capability Metadata
=
Capability 影响范围事实源
```

---

## 3. 双方职责边界

### 3.1 Template Service / Template Engine

负责：

```text
Capability 定义
Capability 依赖解析
Capability Diff
Candidate Files 计算
当前代码上的增量 Transformation
ChangeSet 生成
next TemplateState 生成
Capability 幂等
```

不负责：

```text
Workspace 加锁
读取本地 Workspace
直接写用户 Workspace
Git 操作
本地事务回滚
```

### 3.2 XCodeAgent

负责：

```text
读取 TechnicalPlan Desired Capability
读取当前 TemplateState
调用 Template Service
获取 Workspace Mutation Lock
读取当前 Candidate Files
校验 Update Package
事务 Apply ChangeSet
文件级 Journal
失败回滚
Capability Result Validation
原子落盘 TemplateState
生命周期与 UI 状态
```

不负责：

```text
硬编码某个 Capability 需要哪些文件
解释 Capability 内部依赖
自行生成 Capability Patch
维护第二套 Capability Metadata
```

---

## 4. TemplateState 新契约

### 4.1 当前结构

```json
{
  "templateRevision": "2026.09.04.1",
  "managedFiles": {
    "frontend/src/index.css": "...",
    "frontend/src/App.tsx": "..."
  },
  "requested": {},
  "effective": {}
}
```

### 4.2 新结构

V1 建议：

```json
{
  "templateRevision": "2026.09.04.1",
  "requested": {
    "login": {
      "enabled": true
    }
  },
  "effective": {
    "login": {
      "enabled": true
    }
  }
}
```

字段职责：

| 字段 | 说明 |
|---|---|
| `templateRevision` | Template Service 当前模板/协议版本 |
| `requested` | 当前应用明确请求的 Capability |
| `effective` | Template Service 最终确认已生效的 Capability |

正式移除：

```text
managedFiles
file content baseline
file SHA
workspace drift state
```

SHA 如保留，只用于：

```text
日志
排错
审计
```

不再作为 Capability Update 准入条件。

---

## 5. Capability 新语义

Capability 不再表示：

```text
目录覆盖
最终模板文件快照
```

而表示：

```text
Additions
+
Transformers
```

例如 Login Capability：

```text
login
├── additions
│   ├── LoginPage.tsx
│   ├── AuthProvider.tsx
│   └── backend auth files
│
└── transformers
    ├── App.tsx
    ├── routes.tsx
    ├── package.json
    └── pom.xml
```

### 5.1 Additions

用于：

```text
当前不存在的新文件
```

输出：

```text
ADD_FILE
```

### 5.2 Transformers

用于：

```text
当前已经存在的文件
```

输入必须是：

```text
当前 Workspace 最新文件内容
```

输出：

```text
UPDATE_FILE
```

禁止：

```text
直接拿 Base Template 文件覆盖 Workspace
直接拿 Capability 目录中的完整文件覆盖 Workspace
使用历史 managedFiles 作为 Current File
```

---

## 6. Candidate Files 契约

每个 Capability 由 Template Service 唯一维护影响范围。

例如：

```yaml
id: login

existingTargets:
  - frontend/src/App.tsx
  - frontend/src/routes.tsx
  - frontend/package.json
  - backend/pom.xml

addTargets:
  - frontend/src/providers/AuthProvider.tsx
  - frontend/src/pages/LoginPage.tsx
  - backend/src/main/java/**/auth/**
```

Candidate Files 的作用：

```text
1. 告诉 XCodeAgent 本次需要读取哪些当前文件
2. 限制 Template Service 可返回的 UPDATE_FILE 范围
3. 避免全 Workspace 扫描
4. 避免无关文件参与本次更新
```

Candidate Files 只能由 Template Service / Capability Metadata 定义，XCodeAgent 不复制维护。

---

## 7. 接口约定

接口协议统一在本章定义，后续实施步骤只引用本契约。

---

### 7.1 `/v1/update/plan`

用途：

```text
根据 Current TemplateState + RequestedConfig
计算本次需要新增哪些 Capability
以及需要读取哪些 Workspace 当前文件
```

请求：

```json
{
  "currentTemplateState": {
    "templateRevision": "2026.09.04.1",
    "requested": {},
    "effective": {}
  },
  "requestedConfig": {
    "capabilities": {
      "login": {
        "enabled": true,
        "config": {}
      }
    }
  }
}
```

CHANGE 响应：

```json
{
  "kind": "CHANGE",
  "capabilitiesToApply": [
    "login"
  ],
  "requiredWorkspaceFiles": [
    "frontend/src/App.tsx",
    "frontend/src/routes.tsx",
    "frontend/package.json",
    "backend/pom.xml"
  ]
}
```

NO_CHANGE 响应：

```json
{
  "kind": "NO_CHANGE",
  "capabilitiesToApply": [],
  "requiredWorkspaceFiles": []
}
```

约束：

```text
requiredWorkspaceFiles
必须由 Template Service 根据 Capability Metadata 计算
```

---

### 7.2 `/v1/update`

用途：

```text
在当前 Workspace 最新文件基础上执行 Capability 增量 Transformation
```

请求：

```json
{
  "currentTemplateState": {
    "templateRevision": "2026.09.04.1",
    "requested": {},
    "effective": {}
  },
  "requestedConfig": {
    "capabilities": {
      "login": {
        "enabled": true,
        "config": {}
      }
    }
  },
  "workspaceFiles": {
    "frontend/src/App.tsx": "...当前真实内容...",
    "frontend/src/routes.tsx": "...当前真实内容...",
    "frontend/package.json": "...当前真实内容...",
    "backend/pom.xml": "...当前真实内容..."
  }
}
```

核心语义：

```text
workspaceFiles[path]
=
本次 UPDATE_FILE 的 Current Code
```

Template Service 不允许使用：

```text
历史 TemplateState 文件内容
Base Template 文件内容
Capability Source 中完整旧文件
```

代替 `workspaceFiles[path]`。

---

### 7.3 `/v1/update` 返回

传输形式 V1 继续使用：

```text
HTTP 200 + application/zip
HTTP 204 = NO_CHANGE
```

ZIP：

```text
update-package.zip
├── change-set.json
├── next-template-state.json
└── payload/
```

`change-set.json`：

```json
{
  "operations": [
    {
      "type": "ADD_FILE",
      "path": "frontend/src/pages/LoginPage.tsx",
      "content": "..."
    },
    {
      "type": "UPDATE_FILE",
      "path": "frontend/src/App.tsx",
      "content": "...基于 workspaceFiles 中当前 App.tsx 生成..."
    }
  ]
}
```

V1 只支持：

```text
ADD_FILE
UPDATE_FILE
```

暂不支持：

```text
DELETE_FILE
```

`next-template-state.json`：

```json
{
  "templateRevision": "2026.09.04.2",
  "requested": {
    "login": {
      "enabled": true
    }
  },
  "effective": {
    "login": {
      "enabled": true
    }
  }
}
```

---

## 8. 接口安全与完整性要求

Template Service 必须验证：

```text
workspaceFiles 覆盖全部 requiredWorkspaceFiles
workspaceFiles 不得包含非法路径
ChangeSet UPDATE_FILE 只能作用于 allowed existingTargets
ChangeSet ADD_FILE 只能作用于 allowed addTargets
禁止路径穿越
禁止 .git/**
限制 .xcodeagent/**
```

XCodeAgent 必须再次验证：

```text
返回 ChangeSet 路径合法
Operation 类型仅为 ADD / UPDATE
UPDATE_FILE 路径必须来自本次 Candidate Files
ADD_FILE 路径必须属于本次 Capability 允许新增范围
next TemplateState Schema 合法
```

双方都要做边界校验，不能只依赖单边。

---

## 9. 幂等要求

每个 Capability Transformer 必须满足：

```text
apply(apply(code)) == apply(code)
```

例如：

```text
import 已存在 → 不重复插入
dependency 已存在 → 不重复增加
AuthProvider 已包装 → 不重复包装
route 已存在 → 不重复增加
配置项已存在 → 不重复写入
```

重复请求已经生效的 Capability：

```text
/v1/update/plan
→ NO_CHANGE
```

或 `/v1/update`：

```text
HTTP 204
```

---

## 10. 并发一致性

不再通过 SHA 判断“文件是否被别人改过”。

统一使用：

```text
Workspace Mutation Lock
```

执行边界：

```text
Acquire Lock
        ↓
读取 Candidate Files
        ↓
调用 /v1/update
        ↓
Apply ChangeSet
        ↓
Capability Validation
        ↓
写 TemplateState
        ↓
Release Lock
```

锁范围必须覆盖：

```text
Read Current Files
→ Remote Update
→ Local Apply
→ State Commit
```

否则仍会发生：

```text
读取 A
→ 其他任务改成 B
→ Template Service 根据 A 返回 C
→ C 覆盖 B
```

---

## 11. 失败恢复

不再使用：

```text
git reset --hard
```

改为：

```text
文件级 Reconcile Journal
```

记录：

```text
UPDATE_FILE → 写入前原始 bytes
ADD_FILE    → 本次新增路径
```

失败时：

```text
UPDATE_FILE → 恢复原始 bytes
ADD_FILE    → 删除本次新增文件
TemplateState 不更新
```

只回滚本次 Reconcile 自己产生的影响。

---

## 12. Capability Validation

更新完成后，不再执行：

```text
Whole Workspace 是否与历史模板一致
```

而是执行：

```text
本次 Capability 是否完整生效
```

例如 login：

```text
AuthProvider 存在
LoginPage 存在
登录 route 存在
必要 dependency 存在
后端认证入口存在
```

最终语义：

```text
Capability Health
≠
Template File Historical Consistency
```

---

# 第二章 Template Service 实施计划

> 本章只包含 Template Service / Template Engine 的工作。
>
> 每一步必须支持人工验证，前一步未验收通过不进入下一步。

---

## TS-1：调整 TemplateState Schema

### 实施内容

从 TemplateState 删除：

```text
managedFiles
```

保留：

```text
templateRevision
requested
effective
```

同步修改：

```text
Core Model
OpenAPI
Package Builder
Contract Test
Fixture
```

### 人工验证

调用 `/v1/generate`，解压 ZIP：

```bash
unzip template-package.zip -d /tmp/template-check
cat /tmp/template-check/.xcodeagent/template-state.json
```

人工确认：

```text
不存在 managedFiles
requested / effective 正常
```

再用 OpenAPI Schema 校验返回内容。

### 验收标准

```text
Template Service 不再持久化 Workspace 文件内容基线
```

---

## TS-2：为 Capability 增加 Candidate Files Metadata

### 实施内容

为：

```text
login
authorization
```

定义：

```text
existingTargets
addTargets
```

Template Service 内部建立统一读取接口，例如：

```text
CapabilityDefinition
CapabilityRegistry
```

### 人工验证

增加一个 CLI/Test Harness：

```bash
template-engine capability inspect login
```

预期输出：

```text
existingTargets:
...
addTargets:
...
```

人工确认：

```text
所有 login 真正影响的文件已覆盖
index.css 等无关文件不存在
```

### 验收标准

```text
Candidate Files 只有一份事实源，位于 Template Service
```

---

## TS-3：实现 `/v1/update/plan`

### 实施内容

输入：

```text
currentTemplateState
requestedConfig
```

输出：

```text
kind
capabilitiesToApply
requiredWorkspaceFiles
```

不得读取 Workspace。

### 人工验证

Case 1：

```text
effective={}
requested=login
```

预期：

```text
CHANGE
capabilitiesToApply=[login]
requiredWorkspaceFiles=login existingTargets
```

Case 2：

```text
effective={login}
requested={login}
```

预期：

```text
NO_CHANGE
```

### 验收标准

```text
XCodeAgent 无需知道 Capability 内部文件定义
```

---

## TS-4：升级 `/v1/update` Request Contract

### 实施内容

增加：

```text
workspaceFiles
```

服务端必须验证：

```text
requiredWorkspaceFiles 全部已提供
不得遗漏
不得越权提供非法路径
```

### 人工验证

使用 curl / Postman：

1. 缺少一个 required file；
2. 提供完整 required files；
3. 提供一个非法路径 `../x`；
4. 提供 `.git/config`。

预期：

```text
1 → 4xx WORKSPACE_CONTEXT_INCOMPLETE
2 → 正常进入 Transformation
3 → 4xx
4 → 4xx
```

### 验收标准

```text
/update 明确以 workspaceFiles 为 Current Code 输入
```

---

## TS-5：将 Capability 从 Overlay 改为 Additions + Transformers

### 实施内容

对于已有文件：

```text
App.tsx
routes.tsx
package.json
pom.xml
...
```

不得直接输出模板完整文件。

必须：

```text
workspaceFiles[path]
        ↓
Transformer
        ↓
new content
```

对于新文件：

```text
Capability Addition Template
        ↓
ADD_FILE
```

### 人工验证

人工准备一个已经明显修改过的 `App.tsx`。

调用 `/v1/update` 增加 login。

检查 ChangeSet：

```text
业务原有 Provider 保留
业务 JSX 保留
只增加 AuthProvider 等 login 所需结构
```

### 验收标准

```text
UPDATE_FILE 永远基于请求中的 Current Workspace 内容生成
```

---

## TS-6：实现 login Transformer 幂等

### 实施内容

至少覆盖：

```text
App.tsx
routes
package.json
pom.xml
login 相关配置
```

### 人工验证

对同一份已注入 login 的 workspaceFiles 再调用一次 Transformer。

预期：

```text
不重复 import
不重复 AuthProvider
不重复 route
不重复 dependency
```

### 验收标准

```text
apply(apply(code)) == apply(code)
```

---

## TS-7：限制 ChangeSet V1 为 ADD / UPDATE

### 实施内容

删除或禁用：

```text
DELETE_FILE
```

同步：

```text
Core Operation Model
OpenAPI
Package Builder
Contract Tests
```

### 人工验证

构造 DELETE operation fixture。

预期：

```text
协议校验失败
```

正常 ADD / UPDATE fixture：

```text
正常生成 ZIP
```

### 验收标准

```text
V1 不存在删除文件路径
```

---

## TS-8：取消 SHA / managed-file drift 作为服务端 Update Gate

### 实施内容

删除：

```text
历史文件版本一致性判断
managedFiles diff
SHA mismatch 拒绝
```

SHA 如保留，仅记录日志。

### 人工验证

给 `/v1/update` 传入一个与 Base Template 完全不同但语法合法的当前文件。

预期：

```text
仍然执行 Transformation
不返回 FILE_VERSION_MISMATCH
```

### 验收标准

```text
当前文件已被修改不再是 Template Service 拒绝更新的理由
```

---

## TS-9：生成新 next TemplateState

### 实施内容

只有在完整 ChangeSet 可生成时才生成：

```text
next requested
next effective
next templateRevision
```

### 人工验证

Case 1：正常 login Transformation。

预期：

```text
nextState.effective.login=true
```

Case 2：Transformer 主动失败。

预期：

```text
不产生“login 已生效”的 next TemplateState
```

### 验收标准

```text
next TemplateState 与服务端实际生成能力结果一致
```

---

## TS-10：Template Service 端到端验收

人工覆盖：

```text
空能力 → login
已修改 App.tsx → login
无关 index.css 修改 → login
重复 login → NO_CHANGE
缺失 workspaceFiles → 拒绝
非法路径 → 拒绝
```

### 验收标准

```text
Template Service 能独立证明：
“当前代码 + Desired Capability → 幂等增量 ChangeSet”
```

---

# 第三章 XCodeAgent 实施计划

> 本章只包含 XCodeAgent `template_refactor` 的工作。
>
> XCodeAgent 不实现 Capability Transformer，也不硬编码 Candidate Files。

---

## XC-1：升级 TemplateState Reader

### 实施内容

修改：

```text
Backend/app/services/template_state.py
Backend/app/services/template_reconcile/models.py
```

适配不含：

```text
managedFiles
```

的新 TemplateState。

### 人工验证

将新的 TemplateState fixture 写到：

```text
.xcodeagent/template-state.json
```

运行：

```bash
pytest Backend/tests/test_template_state.py
```

并人工读取：

```text
templateRevision
requested
effective
```

### 验收标准

```text
XCodeAgent 正式运行代码不再要求 managedFiles
```

---

## XC-2：增加 Update Plan Client

### 实施内容

扩展 Template Engine Client：

```text
plan_update(...)
```

调用：

```text
POST /v1/update/plan
```

返回：

```text
kind
capabilitiesToApply
requiredWorkspaceFiles
```

### 人工验证

在真实 Workspace 中触发 login Reconcile。

日志输出：

```text
capabilitiesToApply=[login]
requiredWorkspaceFiles=[...]
```

确认列表来自 Template Service。

### 验收标准

```text
XCodeAgent 不存在 login 文件清单硬编码
```

---

## XC-3：实现 Current Workspace Snapshot

### 建议新增

```text
Backend/app/services/template_reconcile/workspace_snapshot.py
```

只读取：

```text
requiredWorkspaceFiles
```

### 人工验证

手工修改：

```text
frontend/src/App.tsx
```

不 commit。

触发 snapshot，打印 Debug 内容摘要或测试断言。

确认读取的是当前未提交版本。

### 验收标准

```text
dirty Workspace 仍能读取最新真实代码
```

---

## XC-4：升级 `/v1/update` Client

### 实施内容

当前请求：

```json
{
  "currentTemplateState": {},
  "requestedConfig": {}
}
```

改为：

```json
{
  "currentTemplateState": {},
  "requestedConfig": {},
  "workspaceFiles": {}
}
```

保持：

```text
200 ZIP
204 NO_CHANGE
```

### 人工验证

抓取 Backend Debug 日志。

确认：

```text
workspaceFiles 已发送
文件内容来自当前 Workspace
```

### 验收标准

```text
Template Service 收到的 Current Code 与本地文件一致
```

---

## XC-5：移除 managedFiles / SHA 全量健康门禁

### 移除正式链路

```text
assert_managed_workspace_healthy()
MANAGED_WORKSPACE_UNHEALTHY
currentStateContentSha256
nextStateContentSha256
SHA-based Reconcile Gate
```

SHA 可以留 Debug 工具。

### 人工验证

人工改：

```text
frontend/src/index.css
```

触发 login Update。

预期：

```text
index.css 不参与本次门禁
login 正常继续
```

### 验收标准

```text
无关文件变化不再阻断 Reconcile
```

---

## XC-6：移除 Git Clean 前置条件

### 移除

```text
assert_clean_worktree()
```

### 人工验证

执行：

```bash
echo "/* local change */" >> frontend/src/index.css
git status --short
```

确认 dirty，再触发 login。

预期：

```text
Reconcile 正常执行
原 dirty 修改保留
```

### 验收标准

```text
Git dirty 不再作为 Capability Update 拒绝条件
```

---

## XC-7：接入 Workspace Mutation Lock

### 实施内容

锁必须覆盖：

```text
Update Plan 后的正式 Reconcile
或至少
Read Current Files
→ /v1/update
→ Apply
→ Validation
→ State Commit
```

推荐统一纳入现有 Workspace Mutation Coordinator。

### 人工验证

在 Reconcile 中注入 10 秒延迟。

同时触发：

```text
第二个 Reconcile
Build 写任务
Platform Projection
```

预期：

```text
后续 mutation 等待或明确拒绝
```

### 验收标准

```text
同一个 Workspace 不存在并发写事务
```

---

## XC-8：实现 ChangeSet 白名单校验

### 实施内容

校验：

```text
UPDATE_FILE 必须属于 requiredWorkspaceFiles / allowed existing targets
ADD_FILE 必须属于服务端声明的可新增范围或 Update Package Contract
禁止路径穿越
禁止 .git/**
限制 .xcodeagent/**
```

如 `/update/plan` V1 只返回 `requiredWorkspaceFiles`，建议同时扩展返回：

```text
allowedAddFiles
```

或让 Update Package 自带不可伪造的 Capability Scope 元数据。

### 人工验证

构造恶意 ChangeSet：

```text
UPDATE ../../x
UPDATE .git/config
UPDATE 无关 index.css
```

均应被拒绝。

### 验收标准

```text
Template Service 出错也不能突破 Workspace 写边界
```

---

## XC-9：实现文件级 Reconcile Journal

### 实施内容

Apply 前保存：

```text
UPDATE_FILE → original bytes
ADD_FILE → added path
```

### 人工验证

故障注入：

```text
UPDATE App.tsx 成功
ADD LoginPage 前抛错
```

预期：

```text
App.tsx 恢复
本次新增文件删除
用户其他 dirty 文件完全不变
```

### 验收标准

```text
只回滚本次 Reconcile
```

---

## XC-10：移除 Git hard reset 回滚

### 移除

```text
pre_reconcile_head
git reset --hard
Git-based rollback
```

### 人工验证

准备：

```text
File A = 用户未提交修改
```

Reconcile 修改：

```text
File B
File C
```

制造失败。

预期：

```text
File A 保留
File B/C 恢复到 Reconcile 前
```

### 验收标准

```text
Reconcile 失败不会损坏用户已有未提交代码
```

---

## XC-11：实现 Capability Result Validation

### 实施内容

不再调用 Whole Workspace Drift Validation。

改为按本次 Capability 验证：

```text
login 是否真正存在
authorization 是否真正存在
```

验证逻辑应优先基于：

```text
明确的模板能力契约
确定性结构检查
```

不要由 LLM 自由判断。

### 人工验证

正常 login：

```text
Validation PASS
```

手工删除：

```text
AuthProvider 或必要 login route
```

再次运行 Validator：

```text
LOGIN_CAPABILITY_INCOMPLETE
```

### 验收标准

```text
Validation 判断能力是否完成，而不是文件是否等于模板历史
```

---

## XC-12：调整 TemplateState Commit 顺序

### 固定事务顺序

```text
Apply ChangeSet
        ↓
Capability Validation
        ↓
atomic write next-template-state.json
```

失败：

```text
Journal Rollback
TemplateState 保持原状态
```

### 人工验证

制造：

```text
Apply 成功
Validation 失败
```

确认：

```text
文件已恢复
TemplateState.effective 未增加 login
```

### 验收标准

```text
TemplateState 永远只描述真实成功状态
```

---

## XC-13：删除旧 Ownership / Drift 体系

### 清理

```text
ReconcileOwnershipRegistry
OwnershipKind
managedFiles ownership
Whole Workspace Health
SHA Gate
Git Clean Gate
DELETE_FILE
Git Hard Reset Rollback
```

### 人工验证

全仓搜索：

```bash
grep -R "managedFiles" Backend/app Backend/tests
grep -R "MANAGED_WORKSPACE_UNHEALTHY" Backend/app Backend/tests
grep -R "assert_clean_worktree" Backend/app Backend/tests
grep -R "DELETE_FILE" Backend/app Backend/tests
```

人工判断剩余命中：

```text
是否仅历史文档 / migration / debug
```

### 验收标准

```text
正式运行链路只剩 Current Workspace First 一套语义
```

---

## XC-14：XCodeAgent 端到端验收

人工至少覆盖：

### Case 1：新应用首次增加 login

预期：

```text
ADD / UPDATE 正常
TemplateState.effective.login=true
```

### Case 2：dirty Workspace 增加 login

人工修改：

```text
App.tsx
index.css
业务页面
```

不 commit。

预期：

```text
已有修改保留
login 正常注入
```

### Case 3：无关文件变化

彻底修改：

```text
index.css
```

预期：

```text
不参与 login ChangeSet
不阻断 login
```

### Case 4：Candidate File 已修改

修改：

```text
App.tsx
```

新增业务 Provider。

预期：

```text
login 在当前 App.tsx 上注入
业务 Provider 保留
```

### Case 5：重复增加 login

预期：

```text
NO_CHANGE
```

### Case 6：中途失败

预期：

```text
文件回滚
TemplateState 不变
原 dirty changes 保留
```

### Case 7：并发 mutation

预期：

```text
串行
```

---

# 第四章 双端联调顺序

推荐按以下顺序推进，避免双方互相阻塞：

```text
1. Template Service
   TS-1 TemplateState 新 Schema

2. 双方
   固定 /v1/update/plan 与 /v1/update 契约

3. Template Service
   TS-2 ~ TS-4
   Candidate Files + Plan + workspaceFiles Request

4. XCodeAgent
   XC-1 ~ XC-4
   新 State Reader + Plan Client + Snapshot + Update Client

5. Template Service
   TS-5 ~ TS-9
   Additions + Transformers + 幂等 + 新 next State

6. XCodeAgent
   XC-5 ~ XC-12
   去旧门禁 + Lock + Journal + Validation + State Commit

7. 双方
   TS-10 + XC-14
   完整 E2E 联调

8. XCodeAgent
   XC-13
   最后清理旧体系
```

不建议一开始就删除全部旧代码。

更安全的切换方式：

```text
新协议跑通
        ↓
新 E2E 验收通过
        ↓
正式链路切换
        ↓
再删除 managedFiles / SHA / old rollback 等旧实现
```

---

# 第五章 最终目标状态

最终职责：

```text
Current Workspace
= 代码唯一事实源

TemplateState
= Capability 状态事实源

Template Service Capability Metadata
= Capability 影响范围事实源

Capability Transformer
= 增量变换规则

XCodeAgent Workspace Mutation Lock
= 并发一致性保障

XCodeAgent Reconcile Journal
= 事务失败恢复保障
```

最终链路：

```text
Desired Capability
        ↓
Current TemplateState
        ↓
Template Service Update Plan
        ↓
Candidate Files
        ↓
XCodeAgent Acquire Workspace Lock
        ↓
Read Current Workspace
        ↓
Template Service Incremental Transform
        ↓
ADD / UPDATE ChangeSet
        ↓
XCodeAgent Transaction Apply
        ↓
Capability Validation
        ↓
TemplateState Commit
        ↓
Release Lock
```

最终不再存在：

```text
必须和历史模板文件一致
SHA 不一致即拒绝
任意 managed file 漂移即失败
Git dirty 即拒绝
Git reset --hard 回滚
```

最终原则：

> **Template Service 负责把 Capability 增量作用到“当前代码”；XCodeAgent 负责安全地把这些增量应用到“当前 Workspace”。两边都不再以历史模板文件版本一致性作为 Capability Update 的前提。**
