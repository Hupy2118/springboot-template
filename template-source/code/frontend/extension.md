# 前端模板 Base / Extension 开发更新流程改造实施方案

## 1. 改造范围

本次只允许修改：

```text
template-source/code/frontend/**
```

不修改后端模板、模板引擎及 `template-source/code/frontend` 之外的代码。

本次重点解决模板开发者对 Base、Login、Authorization 等能力的持续开发和更新问题，不重新设计现有 Extension Point，也不引入 Assembly Provenance 文件。

---

# 2. 当前实现基础

当前代码已经具备以下基础能力，应尽量复用而不是推翻：

```text
base/src
+
extensions/<extension>/src
+
extensions/<extension>/extension.yaml
        ↓
assembly/assemble.mjs
        ↓
src/
+
generated/extensions/*
```

当前 `assemble.mjs` 已经：

1. 复制 `base/src`；
2. 按 Profile 加载 Extension；
3. 自动解析 Extension 依赖；
4. 将 Extension `src` 合并到最终 `src`；
5. 禁止 Base/Extension 文件路径冲突；
6. 只生成：

   * providers registry
   * root routes registry
   * page routes registry
   * initializers registry
   * error reporters registry。

这一部分架构保持不变。

本次重点修改：

```text
Base 怎么开发
Extension 怎么开发
Workspace 怎么同步
Build 怎么验证
发布前怎么生成最终 src
```

---

# 3. 最终设计原则

## 3.1 Base 与 Extension 使用不同的开发模式

不要为了形式统一而强制 Base 走 Workspace Sync。

### Base

Base 本身就是完整基础源码：

```text
base/src/**
```

因此 Base 更新直接修改：

```text
base/src/**
```

不再：

```text
base/src
→ assemble
→ src
→ 修改 src
→ sync:base
→ base/src
```

新的 Base 流程：

```text
修改 base/src
      ↓
build:base
      ↓
验证其他 Extension 组合
      ↓
生成最终 full workspace
      ↓
发布
```

---

### Extension

Extension 开发者需要面对完整应用，因此继续使用组装后的：

```text
src/**
```

例如 Login：

```text
base
+
login
   ↓
Assembly
   ↓
src
   ↓
开发者修改完整应用
   ↓
sync:login
   ↓
base/src + extensions/login/src
```

Authorization：

```text
base
+
login
+
authorization
   ↓
Assembly
   ↓
src
   ↓
开发修改
   ↓
sync:authorization
```

---

## 3.2 Source Ownership 不记录 Provenance

不增加每个文件的来源记录。

Source 本身就是 ownership truth。

同步规则固定为：

```text
已有文件
→ 根据相同 relative path 当前存在于哪个 Source Root 判断 owner

新文件
→ 当前 Profile 的 editTarget

Generated 文件
→ Assembly 管理，禁止回写
```

必须继续保持：

```text
一个 relative path 最多只能属于一个 Source Owner
```

Base 与 Extension 不允许通过同路径覆盖实现能力扩展。

---

# 4. Profile 模型调整

修改：

```text
assembly/profiles.json
assembly/profiles.mjs
```

## 4.1 `writeOwner` 改为 `editTarget`

当前：

```json
{
  "base": {
    "extensions": [],
    "writeOwner": "base"
  },
  "login": {
    "extensions": ["login"],
    "writeOwner": "login"
  },
  "authorization": {
    "extensions": ["authorization"],
    "writeOwner": "authorization"
  },
  "full": {
    "extensions": ["login", "authorization"],
    "writeOwner": null
  }
}
```

修改为：

```json
{
  "base": {
    "extensions": [],
    "editTarget": null
  },
  "login": {
    "extensions": ["login"],
    "editTarget": "login"
  },
  "authorization": {
    "extensions": ["authorization"],
    "editTarget": "authorization"
  },
  "full": {
    "extensions": ["login", "authorization"],
    "editTarget": null
  }
}
```

语义明确区分：

```text
extensions
= 当前 Workspace 包含哪些 Extension

editTarget
= Workspace 新建文件默认属于哪个 Extension
```

Base 不允许 Sync，因此：

```text
base.editTarget = null
```

Full 只是集成验证环境，因此：

```text
full.editTarget = null
```

`profiles.mjs` 同步修改校验逻辑，不再检查 `writeOwner`。

---

# 5. 重构 Workspace Sync

主要修改：

```text
assembly/workspace-sync.mjs
```

这是本轮核心修改。

## 5.1 Workspace Sync 只允许 Extension Profile

支持：

```text
sync:login
sync:authorization
```

不支持：

```text
sync:base
sync:full
```

如果执行一个：

```text
editTarget === null
```

的 Profile，应明确失败，例如：

```text
PROFILE_NOT_SYNCABLE: base
PROFILE_NOT_SYNCABLE: full
```

不要静默处理。

---

## 5.2 删除 `syncBaseWorkspace`

当前：

```js
export async function syncBaseWorkspace(...)
```

删除。

Base 不再通过 Workspace 回写。

---

## 5.3 Owner 集合

以 Login 为例：

```text
owners:
base
login
```

Authorization：

```text
owners:
base
login
authorization
```

Authorization 中 Login 来自：

```text
authorization.requires = ["login"]
```

继续复用现有递归依赖解析逻辑。

不要把：

```text
profile.extensions
```

简单等同于 Owner 集合。

必须包含传递依赖。

---

# 6. Workspace 文件同步规则

对每一个：

```text
src/<relativePath>
```

执行以下规则。

## 6.1 已有文件

查找：

```text
base/src/<relativePath>
extensions/<selected-extension>/src/<relativePath>
```

如果仅找到一个：

```text
owner = 该 Source Root
```

例如：

```text
src/layout/index.tsx

base/src/layout/index.tsx                    EXISTS
extensions/login/src/layout/index.tsx        NOT EXISTS
```

则：

```text
→ UPDATE base/src/layout/index.tsx
```

Login 文件：

```text
src/pages/Login/index.tsx

base                                      NOT EXISTS
login                                     EXISTS
```

则：

```text
→ UPDATE extensions/login/src/pages/Login/index.tsx
```

---

## 6.2 多 Owner 冲突

如果同一个 relative path 同时存在于两个 Source：

```text
base/src/foo.ts
extensions/login/src/foo.ts
```

直接失败：

```text
SOURCE_OWNER_CONFLICT: foo.ts
```

不要选择优先级。

虽然 Assembly 已经有 `FILE_COLLISION`，Sync 仍需要防御性校验。

---

## 6.3 新文件

如果：

```text
所有 selected source roots
```

均不存在此 relative path：

```text
owner = profile.editTarget
```

例如：

```text
pnpm dev:login
```

开发者新增：

```text
src/hooks/useLoginState.ts
```

则：

```text
→ CREATE extensions/login/src/hooks/useLoginState.ts
```

Authorization：

```text
pnpm dev:authorization
```

新增：

```text
src/hooks/useRoleEditor.ts
```

则：

```text
→ CREATE extensions/authorization/src/hooks/useRoleEditor.ts
```

---

## 6.4 删除文件

如果某 Source Owner 中存在：

```text
relativePath
```

而 Workspace 中已经不存在：

```text
src/<relativePath>
```

则删除对应 Source 文件。

例如：

```text
src/pages/Login/index.tsx
```

被删除：

```text
→ DELETE extensions/login/src/pages/Login/index.tsx
```

Base 文件在 Extension Workspace 中被删除：

```text
src/layout/foo.ts
```

则：

```text
→ DELETE base/src/layout/foo.ts
```

也就是说 Extension 开发过程中允许修正 Base。

---

## 6.5 Generated 文件

继续复用：

```text
ASSEMBLY_MANAGED_FILES
GENERATED_MARKER
```

以下内容不参与 Sync：

```text
generated/extensions/providers.ts
generated/extensions/rootRoutes.tsx
generated/extensions/systemPageRoutes.ts
generated/extensions/initializers.ts
generated/extensions/errorReporters.ts
.xcodeagent-template-generated.json
```

这些文件只能通过：

```text
extension.yaml
+
Assembly Compiler
```

发生变化。

---

# 7. Sync 只写真正变化的文件

当前 `workspace-sync.mjs` 会把所有已有文件都加入：

```text
UPDATE
```

即使内容完全一致。

本轮建议顺便修正。

对于已有文件：

```text
read workspace file
read source file
```

如果内容完全一致：

```text
NO-OP
```

不复制、不输出 UPDATE。

只有内容变化才：

```text
UPDATE
```

最终执行：

```bash
pnpm sync:login
```

输出应该类似：

```text
UPDATE base/src/layout/index.tsx
UPDATE extensions/login/src/pages/Login/index.tsx
CREATE extensions/login/src/hooks/useLoginState.ts
DELETE extensions/login/src/utils/old.ts
```

而不是几十、几百个无实际变化的 UPDATE。

---

# 8. Base 开发模式调整

## 8.1 Base 唯一开发源码

明确：

```text
base/src/**
```

就是 Base Source of Truth。

Base 开发人员直接修改这里。

不再要求修改：

```text
src/**
```

再同步回来。

---

## 8.2 删除 `sync:base`

修改：

```text
package.json
```

删除：

```json
"sync:base": "node assembly/workspace-sync.mjs --profile=base"
```

同时建议删除容易误用的：

```json
"sync:workspace": "node assembly/workspace-sync.mjs"
```

只保留：

```json
"sync:login": "... --profile=login",
"sync:authorization": "... --profile=authorization"
```

并让 `workspace-sync.mjs` 强制要求：

```text
--profile
```

不要再默认为 Base。

---

# 9. `dev:base` 处理

Base 不再采用：

```text
dev → 修改 src → sync
```

模式。

因此不要继续把 `dev:base` 描述成 Base 的主要开发入口。

建议本轮：

```text
Base 主开发命令：
直接修改 base/src
pnpm build:base
```

可以保留 Base Workspace Materialize 能力用于查看组装结果，但语义必须明确：

```text
它只是 Preview / Materialize，
不是 Base 源码编辑入口。
```

推荐在 `package.json` 增加：

```json
"materialize:base": "node assembly/reset-workspace.mjs --profile=base",
"materialize:full": "node assembly/reset-workspace.mjs --profile=full"
```

原来的：

```text
reset:base
reset:full
```

可以暂时保留兼容。

`dev:base` 可以删除，或者只保留兼容但不再出现在开发文档推荐流程中。

本轮不要求实现 Base Source Watcher。

---

# 10. 必须重构 `build-profile.mjs`

这是 Base 直接开发能够成立的关键。

当前：

```text
build-profile.mjs
↓
assertWorkspaceClean()
↓
assemble 到 src
↓
build
↓
重新 assemble previous profile
```

存在两个问题。

第一：

开发者修改：

```text
base/src
```

之后，当前 `src` 与最新 Source 不一致。

`workspaceDirty()` 会认为：

```text
WORKSPACE_SOURCE_DRIFT
```

导致：

```text
pnpm build:base
```

失败。

第二：

Build 不应该依赖模板开发人员当前 Workspace 是否处于 Login、Authorization 或 Full。

因此 Build 应与开发 Workspace 解耦。

---

# 11. Build 改成“备份 Workspace → 临时构建 → 原样恢复”

修改：

```text
assembly/build-profile.mjs
```

目标：

> Build 可以随时基于 Source 构建指定 Profile，同时不能破坏当前开发者的 `src` Workspace。

建议流程：

```text
当前 src
+
当前 workspace state
        ↓
完整备份
        ↓
assemble target profile → src
        ↓
tsc
        ↓
vite build
        ↓
删除临时 src
        ↓
恢复原 src
+
恢复原 workspace state
```

具体要求：

### Build 前

备份：

```text
frontend/src
frontend/.xcodeagent-template-workspace.json
```

到临时路径，例如：

```text
.build-workspace-backup-<pid>
.build-workspace-state-<pid>
```

如果原文件不存在，也要记录“不存在”的状态。

### Build

Assembly：

```text
assemble.mjs --profile=<profile> --force=true
```

然后：

```text
pnpm exec tsc -b
pnpm exec vite build
```

### finally

无论 Build 成功失败：

```text
删除 Build 使用的 src
恢复原始 src
恢复原始 workspace state
```

必须保证异常情况下也执行恢复。

---

## 11.1 删除 Build 对 `assertWorkspaceClean()` 的依赖

新的 Build 本身不会破坏当前 Workspace，因此：

```js
await assertWorkspaceClean();
```

从 `build-profile.mjs` 删除。

同时删除当前：

```text
读取 previous profile
→ Build 完重新 assemble previous profile
```

的逻辑。

必须恢复的是：

```text
用户原来的 Workspace
```

而不是重新生成一个“理论上等价”的 Workspace。

这一点尤其重要，因为 Extension Workspace 中可能存在：

```text
尚未 sync 的开发修改
```

Build 不应该删除它。

---

# 12. Build 临时文件加入 `.gitignore`

修改：

```text
.gitignore
```

加入本轮 Build 使用的临时目录模式，例如：

```text
.build-workspace-*
.build-workspace-state-*
```

实际名称与 Codex 最终实现保持一致。

---

# 13. Extension 开发流程保持完整 Workspace

Login：

```bash
pnpm dev:login
```

Assembly：

```text
Base
+
Login
+
Generated Registry
        ↓
src
```

开发者直接修改：

```text
src/**
```

完成后：

```bash
pnpm sync:login
```

然后：

```bash
pnpm build:login
```

---

Authorization：

```bash
pnpm dev:authorization
```

实际 Workspace：

```text
Base
+
Login
+
Authorization
```

开发者仍然只修改：

```text
src/**
```

然后：

```bash
pnpm sync:authorization
```

Sync 规则：

```text
已有 Base 文件          → Base
已有 Login 文件         → Login
已有 Authorization 文件 → Authorization

新文件                  → Authorization
```

---

# 14. Extension Contract 的修改方式

Extension 内部代码：

```text
Page
Component
API
Hook
Provider implementation
```

继续：

```text
修改 src
→ sync:<extension>
```

但是以下内容不是普通源码：

```text
Provider contribution
RootRoute contribution
PageRoute contribution
Initializer
ErrorReporter
requires
```

必须直接修改：

```text
extensions/<extension>/extension.yaml
```

例如新增 Login Root Route：

```text
extensions/login/extension.yaml
```

然后重新：

```text
dev / build
```

Assembly 自动生成：

```text
generated/extensions/*
```

不要允许开发者直接修改 Generated Registry。

---

# 15. Full Profile 定位调整

`full` 明确定义为：

```text
Integration Workspace
```

作用：

```text
验证 Base + 所有主要 Extension 的组合
```

保留：

```bash
pnpm dev:full
pnpm build:full
```

但是：

```text
full.editTarget = null
```

因此：

```text
pnpm sync:full
```

必须不存在或者明确失败。

Full 不作为新的源码归属边界。

---

# 16. 重构 `verify-generated.mjs`

当前：

```text
assembly/verify-generated.mjs
```

里的：

```js
expectedSource(relative)
```

硬编码了：

```text
Login 哪些路径属于 login
Authorization 哪些路径属于 authorization
```

例如：

```text
pages/Login/
apis/login.ts
hooks/useGuard.ts
...
```

这种实现会随着 Extension 新增文件立即过期。

必须删除这套硬编码。

建议增加：

```text
assembly/source-ownership.mjs
```

集中提供：

```text
ownerRootsForProfile(profile)
resolveExistingOwner(relativePath, owners)
```

`workspace-sync.mjs` 和 `verify-generated.mjs` 共用。

对于：

```text
generated/extensions/*
```

owner 显示：

```text
assembly
```

对于普通文件，动态根据 Source Root 判断。

不要再维护任何文件名白名单。

---

# 17. 建议增加 `source-ownership.mjs`

建议新增：

```text
assembly/source-ownership.mjs
```

职责只负责 Source Ownership，不负责实际同步。

建议提供类似能力：

```text
ownersForProfile(profile)
```

返回：

```text
[
  { id: "base", root: "base/src" },
  { id: "login", root: "extensions/login/src" }
]
```

Authorization：

```text
[
  base,
  login,
  authorization
]
```

以及：

```text
resolveOwner(relativePath, owners)
```

返回：

```text
{ type: "owned", owner }

{ type: "new" }

throw SOURCE_OWNER_CONFLICT
```

这样不要让 ownership 逻辑分别散落到：

```text
workspace-sync.mjs
verify-generated.mjs
```

未来其他开发工具也可复用。

注意：

> 不在该文件记录或生成 Provenance。

Ownership 每次直接从 Source Tree 计算。

---

# 18. 修改 `dev.mjs`

Extension Profile 保持现有行为：

```text
assemble
→ src
→ vite
```

但输出提示根据 Profile 调整。

Login / Authorization：

```text
Workspace is editable.
Run pnpm sync:<profile> to persist changes.
```

Full：

```text
Full workspace is for integration verification.
Do not use it as a source editing workspace.
```

如果仍允许：

```text
dev.mjs --profile=base
```

则提示：

```text
Base source is base/src.
The assembled src directory is preview-only.
```

不要再提示：

```text
pnpm sync:base
```

---

# 19. `workspace-state.mjs` 本轮尽量不扩大职责

继续用于：

```text
当前 src 是由哪个 profile 生成的
Extension Sync 前检查 Workspace Profile
Extension 开发过程中检查 drift
```

不要加入：

```text
每文件 provenance
owner map
source snapshot
```

Build 已通过 Workspace 备份/恢复机制与它解耦。

因此本轮不需要把 Workspace State 复杂化。

---

# 20. 重写 Workspace Sync 测试

修改：

```text
assembly/test-workspace-sync.mjs
```

当前测试的主要对象是：

```text
syncBaseWorkspace
```

这与新设计相反，必须重写。

至少覆盖以下场景：

### Case 1：更新 Base 文件

Login Workspace 修改已有 Base 文件：

```text
src/layout/index.tsx
```

断言：

```text
base/src/layout/index.tsx
```

被更新。

---

### Case 2：更新 Login 文件

修改：

```text
src/pages/Login/index.tsx
```

断言：

```text
extensions/login/src/pages/Login/index.tsx
```

被更新。

---

### Case 3：Login 新建文件

新增：

```text
src/hooks/useLoginState.ts
```

断言：

```text
extensions/login/src/hooks/useLoginState.ts
```

被创建。

不能进入：

```text
base/src
```

---

### Case 4：删除文件

分别测试：

```text
DELETE base-owned file
DELETE login-owned file
```

确认删除正确 Source。

---

### Case 5：Source Owner 冲突

Base 与 Login 同时存在：

```text
foo.ts
```

必须：

```text
SOURCE_OWNER_CONFLICT
```

---

### Case 6：Generated 文件不回写

修改或存在：

```text
generated/extensions/providers.ts
```

Sync 不得创建到任何 Source。

---

### Case 7：Base 不允许 Sync

调用：

```text
syncWorkspace("base")
```

必须：

```text
PROFILE_NOT_SYNCABLE
```

---

### Case 8：Full 不允许 Sync

同样失败。

---

### Case 9：Authorization 依赖归属

Authorization Workspace 中：

```text
Base existing file
Login existing file
Authorization existing file
```

修改后分别回到：

```text
base
login
authorization
```

新增文件默认：

```text
authorization
```

---

### Case 10：无变化文件不产生 UPDATE

Source 与 Workspace 内容一致：

```text
changes = []
```

或至少该文件不能出现在 UPDATE 列表。

---

# 21. Assembly 测试保持并补充

保留：

```text
assembly/test-assemble.mjs
```

现有：

```text
base-only
login
login + authorization
dependency conflict
```

测试。

补充确认：

```text
Extension 与 Base 同路径
→ FILE_COLLISION
```

这是 Source-by-Path Ownership 能成立的基础契约，必须有自动测试锁定。

---

# 22. Package Scripts 调整

最终建议至少形成：

```json
{
  "dev:login": "...",
  "dev:authorization": "...",
  "dev:full": "...",

  "build:base": "...",
  "build:login": "...",
  "build:authorization": "...",
  "build:full": "...",

  "sync:login": "...",
  "sync:authorization": "...",

  "materialize:base": "... --profile=base",
  "materialize:full": "... --profile=full",

  "verify:assembly": "...",
  "test:assembly": "...",
  "test:workspace-sync": "..."
}
```

删除：

```text
sync:base
```

推荐删除：

```text
sync:workspace
```

避免绕过 Profile 的明确语义。

---

# 23. 增加发布验证快捷命令

为了减少模板开发者记忆成本，可以增加：

```json
"verify:update:base":
  "pnpm build:base && pnpm build:login && pnpm build:authorization && pnpm build:full",

"verify:update:login":
  "pnpm build:login && pnpm build:authorization && pnpm build:full",

"verify:update:authorization":
  "pnpm build:authorization && pnpm build:full"
```

注意这些命令：

```text
只验证
不 materialize
不修改用户 Workspace
```

最终发布前再显式：

```bash
pnpm materialize:full
pnpm verify:assembly
```

这样区分：

```text
Build / Verify
```

与：

```text
生成最终发布 Workspace
```

两个动作。

---

# 24. Base 最终开发更新流程

开发者：

```text
直接修改
base/src/**
```

然后：

```bash
pnpm verify:update:base
```

即验证：

```text
Base
Base + Login
Base + Login + Authorization
Full
```

准备发布：

```bash
pnpm materialize:full
pnpm verify:assembly
```

再进入现有模板发布流程。

整个过程没有：

```text
sync:base
```

---

# 25. Login 最终开发更新流程

启动：

```bash
pnpm dev:login
```

开发者修改：

```text
src/**
```

如果只是实现变化：

```bash
pnpm sync:login
```

如果 Extension Contract 也变化：

```text
修改 extensions/login/extension.yaml
```

完成后：

```bash
pnpm sync:login
pnpm verify:update:login
```

准备发布：

```bash
pnpm materialize:full
pnpm verify:assembly
```

最终持久化源码可能同时包含：

```text
base/src/**
extensions/login/src/**
extensions/login/extension.yaml
```

这是允许的。

---

# 26. Authorization 最终开发更新流程

启动：

```bash
pnpm dev:authorization
```

开发 Workspace 实际包含：

```text
Base
+
Login
+
Authorization
```

修改：

```text
src/**
```

同步：

```bash
pnpm sync:authorization
```

新文件默认归：

```text
authorization
```

已有 Login 文件仍归：

```text
login
```

已有 Base 文件仍归：

```text
base
```

验证：

```bash
pnpm verify:update:authorization
```

发布前：

```bash
pnpm materialize:full
pnpm verify:assembly
```

---

# 27. 发布侧边界

本轮不重新设计模板引擎的发布协议，也不引入 npm Package 式 Extension 发布。

`frontend` 内部要保证：

```text
Base Source
+
Extension Source
        ↓
materialize:full
        ↓
最终 src
        ↓
verify:assembly
```

最终发布仍使用现有外层发布机制。

需要额外检查当前：

```text
base.yaml
```

对于新增 `frontend/src/**` 文件的静态清单是否需要同步。

如果现有模板发布流程确实依赖 `base.yaml.files` 枚举最终文件，则 Extension 新增源码不能只完成 Assembly，还必须保证该清单包含新文件。

本轮不要重新引入“人工维护所有注册点”的模式。

优先方案是：

```text
先增加校验：
最终 full src 中的发布文件
必须全部能被 base.yaml 覆盖
```

如果确认 `base.yaml` 是实际发布输入，再单独把其 frontend/src 文件清单改成自动生成或自动同步。

不要在没有确认其发布语义前大规模重写 `base.yaml`。

---

# 28. TEMPLATE_DEVELOPMENT.md 必须整体重写

现有文档中的核心原则：

```text
所有开发都修改 src
不要修改 base/src
```

已经失效。

新文档必须明确三种模式。

### Base

```text
直接修改 base/src
→ verify:update:base
→ materialize:full
→ release
```

### Extension

```text
dev:<extension>
→ 修改完整 src
→ sync:<extension>
→ verify:update:<extension>
→ materialize:full
→ release
```

### Full

```text
只用于集成验证
不作为新的 Source Owner
不 Sync
```

并明确：

```text
Generated 文件永远不直接修改。
```

---

# 29. 建议实施顺序

Codex 严格按以下顺序实施，避免一次修改过大。

### Step 1：修改 Profile Contract

修改：

```text
profiles.json
profiles.mjs
```

完成：

```text
writeOwner → editTarget
```

验收：

```text
base/full editTarget = null
login editTarget = login
authorization editTarget = authorization
```

---

### Step 2：抽取 Source Ownership

新增：

```text
assembly/source-ownership.mjs
```

实现：

```text
Profile → Owner Roots
relativePath → Existing Owner
```

不增加任何持久化 metadata。

验收：

```text
Base / Login / Authorization owner 可正确解析
依赖 Extension 自动进入 Owners
冲突能失败
```

---

### Step 3：重构 workspace-sync.mjs

实现新的：

```text
existing → source owner
new → editTarget
delete → source owner
generated → ignored
```

删除：

```text
syncBaseWorkspace
```

验收全部 Sync 测试通过。

---

### Step 4：重写 test-workspace-sync.mjs

按第 20 节测试矩阵实施。

在测试通过之前不要修改开发文档。

---

### Step 5：重构 build-profile.mjs

实现：

```text
backup workspace
→ isolated build
→ restore workspace
```

删除：

```text
assertWorkspaceClean
previous profile reassemble
```

验收重点：

1. 修改 `base/src` 后可以直接 `pnpm build:base`；
2. 当前即使是 Login Workspace，也能执行 `build:base`；
3. Build 前后的 `src` 内容完全一致；
4. Build 前后的 `.xcodeagent-template-workspace.json` 完全一致；
5. Build 失败也必须恢复；
6. Build 不产生 Base/Extension Source 修改。

---

### Step 6：调整 package.json

删除：

```text
sync:base
sync:workspace
```

增加：

```text
materialize:base
materialize:full
verify:update:base
verify:update:login
verify:update:authorization
```

---

### Step 7：修正 dev.mjs 提示语义

Extension 可编辑。

Full 只集成。

Base 不再提示 Sync。

---

### Step 8：去掉 verify-generated.mjs 的路径硬编码

复用 Source Ownership Resolver。

确保新增 Login/Authorization 文件无需修改：

```text
verify-generated.mjs
```

---

### Step 9：更新 .gitignore

忽略 Build Backup 临时目录。

---

### Step 10：重写 TEMPLATE_DEVELOPMENT.md

以新的开发者工作流为唯一标准。

---

# 30. 最终验收矩阵

| 场景                        | 预期                                |
| ------------------------- | --------------------------------- |
| 修改 `base/src/App.tsx`     | 直接 `build:base` 成功                |
| Base 更新                   | 不执行任何 `sync:base`                 |
| Login 修改已有 Login 文件       | 回写 Login                          |
| Login 修改已有 Base 文件        | 回写 Base                           |
| Login 新建文件                | 进入 Login                          |
| Authorization 修改 Login 文件 | 回写 Login                          |
| Authorization 新建文件        | 进入 Authorization                  |
| 删除已有文件                    | 从原 Owner 删除                       |
| Base/Extension 同路径        | 明确失败                              |
| 修改 Generated Registry     | 不回写 Source                        |
| `sync:base`               | 命令不存在                             |
| `sync:full`               | 不允许                               |
| Build 时 Workspace 有未同步修改  | Build 后必须原样保留                     |
| 新增 Extension 普通源码         | 不需要维护 `verify-generated.mjs` 路径规则 |
| Full Materialize          | 与 `verify:assembly` 结果完全一致        |

---

# 31. 本轮明确不做

不要顺带实施以下内容：

```text
Extension npm 包化
独立仓库拆分
运行时动态插件
Assembly Provenance
每文件 Ownership Manifest
AST Patch
允许 Extension 覆盖 Base 文件
Extension API Version 体系
后端模板插件化
模板引擎发布机制重构
```

这些都不是解决当前模板开发更新流程所必需的。

---

# 32. 最终目标

改造完成后，模板开发人员只需要形成两个心智模型。

Base：

```text
Base 是源码
→ 直接改
→ Build
→ 验证下游
→ 发布
```

Extension：

```text
Extension 是能力源码边界
但开发时面对完整应用
→ Assemble
→ 修改 src
→ Sync
→ Build
→ 发布
```

内部实现则保持：

```text
Base / Extension
= Source Ownership

src
= Developer Workspace / Release Materialization

Assembly
= Source → Workspace

Workspace Sync
= Extension Workspace → Source Owners

Build
= 与 Workspace 解耦的只读验证过程
```

这是本轮改造必须最终达到的状态。
