# 前端模板长期维护闭环改造方案

## 1. 改造目标

当前已经完成：

```text
base/src
extensions/login
extensions/authorization
assembly/assemble.mjs
```

以及五类 Extension Point：

```text
Provider
RootRoute
PageRoute
Initializer
ErrorReporter
```

本阶段不再调整上述总体架构。

本阶段目标是解决当前仍存在的长期维护问题：

```text
base / extensions 已成为新的源码模型

但：

src                     仍容易被开发者直接修改
pnpm dev / build        仍直接使用 src
base.yaml               仍把完整 src 当作 Base 内容
extension schema        尚未真正参与校验
Extension 版本          尚未定义
跨 Extension 依赖边界  尚未自动校验
Assembly 测试           尚未覆盖真实 build 和错误场景
```

最终目标：

```text
Base Source
Login Extension
Authorization Extension
       │
       └──── 唯一 Source of Truth
                │
                ↓
         Assembly Compiler
                │
                ↓
          Generated src
                │
          ┌─────┴─────┐
          ↓           ↓
        Dev/Test     Publish
```

开发者以后必须直接修改：

```text
base/src/**
extensions/<id>/src/**
extensions/<id>/extension.yaml
```

而不是修改最终 `src/**`。

---

# 2. Step 1：明确 Source of Truth 与 Generated Source 边界

## 2.1 改造目标

首先解决当前最大的长期风险：

```text
base/extensions
和
src
```

都可能被开发者认为是源码。

正式规定：

```text
Source of Truth:
  base/**
  extensions/**
  assembly/**

Generated:
  src/**
```

`src` 只是当前选择 Extension 后的 Assembly 结果。

默认 profile：

```text
login + authorization
```

因此当前根目录 `src` 默认仍可保持完整模板形态，但不得成为人工维护入口。

---

## 2.2 实施内容

新增：

```text
template-source/code/frontend/TEMPLATE_DEVELOPMENT.md
```

明确：

```text
base/src
    Base 源码

extensions/**
    Extension 源码

assembly/**
    Assembly Contract 与 Compiler

src/**
    Generated React Source
    禁止直接维护
```

同时修改仓库内模板开发说明，避免模板开发者继续运行：

```text
直接修改 src/**
```

建议在：

```text
assembly/README.md
```

补充 Assembly 规则。

---

## 2.3 增加 Generated Marker

Assembly 完成后写入：

```text
src/.xcodeagent-template-generated.json
```

内容例如：

```json
{
  "generated": true,
  "profile": "full",
  "extensions": [
    "login",
    "authorization"
  ],
  "assemblySchemaVersion": 1
}
```

不要加入：

```text
生成时间
随机 ID
本机路径
```

保证确定性。

该文件仅用于模板仓库开发阶段标识，不应进入最终用户应用 Artifact。

---

## 2.4 增加防止直接修改 `src` 的校验

新增：

```text
assembly/verify-generated.mjs
```

执行逻辑：

```text
当前 Source
      ↓
临时 Assembly
      ↓
与当前 src 逐文件比较
      ↓
完全一致 → PASS
存在差异 → GENERATED_SOURCE_DRIFT
```

比较范围包括：

```text
文件集合
文件内容
Assembly 文件
Extension 投影文件
Base 投影文件
```

忽略：

```text
node_modules
build
dist
Generated Marker
```

如果发现：

```text
src/pages/Login/index.tsx
```

与：

```text
extensions/login/src/pages/Login/index.tsx
```

不一致，应明确输出：

```text
GENERATED_SOURCE_DRIFT

src/pages/Login/index.tsx
expected source:
extensions/login/src/pages/Login/index.tsx
```

---

## 2.5 package.json

增加：

```json
"verify:assembly": "node assembly/verify-generated.mjs"
```

---

## 2.6 验收标准

执行：

```bash
pnpm assemble
pnpm verify:assembly
```

必须：

```text
PASS
```

人工修改：

```text
src/pages/Login/index.tsx
```

但不修改：

```text
extensions/login/src/pages/Login/index.tsx
```

再次执行：

```bash
pnpm verify:assembly
```

必须失败：

```text
GENERATED_SOURCE_DRIFT
```

然后重新：

```bash
pnpm assemble
```

应恢复一致。

---

# 3. Step 2：补齐长期开发命令

## 3.1 改造目标

当前：

```bash
pnpm dev
```

直接开发根目录 `src`。

以后开发者应能够明确开发：

```text
Base
Login
Authorization
Full
```

而不需要关心 Assembly 细节。

---

## 3.2 定义四种开发 Profile

固定支持：

```text
base
login
authorization
full
```

实际 Extension 集合：

```text
base
    []

login
    [login]

authorization
    [authorization]
    ↓ dependency resolution
    [login, authorization]

full
    [login, authorization]
```

注意：

```text
authorization
```

开发 Profile 可以自动补齐 Login。

这与用户显式配置：

```text
login=false
authorization=true
```

是两个不同概念。

开发 Profile 中没有显式禁用，因此允许依赖闭包。

---

## 3.3 新增 Profile 配置

建议增加：

```text
assembly/profiles.json
```

例如：

```json
{
  "base": [],
  "login": ["login"],
  "authorization": ["authorization"],
  "full": ["login", "authorization"]
}
```

不要在：

```text
dev.mjs
```

里写：

```js
if (profile === 'authorization') ...
```

开发工具同样通过通用 Profile 配置工作。

---

## 3.4 新增开发 Runner

新增：

```text
assembly/dev.mjs
```

职责：

```text
读取 profile
    ↓
Assembly
    ↓
输出到根目录 src
    ↓
启动 Vite
    ↓
监听：
  base/src/**
  extensions/**
  extension.yaml
    ↓
变化后重新 Assembly
```

Vite 继续使用现有：

```text
src
```

路径。

不要在这一阶段重写：

```text
vite.config.ts
tsconfig.json
```

去支持动态 Workspace Source Root。

优先保持现有 React 工程工具链不变。

---

## 3.5 Watch 行为

建议新增依赖：

```text
chokidar
```

监听：

```text
base/src/**
extensions/*/src/**
extensions/*/extension.yaml
assembly/profiles.json
```

发生普通 Base / Extension 文件变化时，可以先采用：

```text
完整重新 Assembly
```

不要第一阶段实现复杂增量算法。

当前模板规模有限，完整 Assembly 更简单、更可靠。

后续如实际性能不足再优化。

---

## 3.6 Assembly 失败保护

开发模式下不要：

```text
Assembly 失败
→ 删除当前可运行 src
```

正确流程：

```text
Source Change
    ↓
Assembly 到临时目录
    ↓
Assembly 成功
    ↓
替换 src
```

如果失败：

```text
保留上一个有效 src
+
终端输出错误
```

例如：

```text
FILE_COLLISION
INVALID_EXTENSION_DEFINITION
```

修复 Source 后自动重新尝试。

---

## 3.7 package.json

增加：

```json
{
  "dev:base": "node assembly/dev.mjs --profile=base",
  "dev:login": "node assembly/dev.mjs --profile=login",
  "dev:authorization": "node assembly/dev.mjs --profile=authorization",
  "dev:full": "node assembly/dev.mjs --profile=full",

  "dev": "pnpm dev:full"
}
```

默认：

```bash
pnpm dev
```

继续获得当前完整模板体验。

---

## 3.8 验收标准

执行：

```bash
pnpm dev:base
```

要求：

```text
Home 可访问
无 /login
无 /logout
无 AuthorizationManagement
```

运行期间修改：

```text
base/src/layout/index.tsx
```

浏览器能够看到修改结果。

执行：

```bash
pnpm dev:login
```

要求：

```text
/login
/logout
```

存在。

运行期间修改：

```text
extensions/login/src/pages/Login/index.tsx
```

Assembly 自动更新，页面变化可被 Vite 感知。

执行：

```bash
pnpm dev:authorization
```

要求自动组合：

```text
Login
+
Authorization
```

开发者不得手工执行：

```bash
pnpm assemble
```

才能看到每次源码修改。

---

# 4. Step 3：增加 Profile Build 能力

## 4.1 改造目标

当前：

```bash
pnpm build
```

只证明当前根 `src` 可以构建。

这不足以证明：

```text
Base-only
Login
Full
```

全部成立。

增加 Profile Build。

---

## 4.2 新增 build runner

新增：

```text
assembly/build-profile.mjs
```

流程：

```text
选择 profile
    ↓
Assembly 到临时工作目录
    ↓
使用当前 package / Vite / TS 配置
    ↓
tsc
    ↓
vite build
```

为了降低改造量，也可以第一阶段采用：

```text
备份当前 src
→ Assembly profile 到 src
→ build
→ finally 恢复 src
```

但更推荐：

```text
Assembly 到临时 workspace
```

避免测试过程中污染开发目录。

如果采用临时 workspace，需要保证：

```text
tsconfig
vite alias
```

正确指向 workspace。

如果实现成本明显较高，本阶段允许先使用：

```text
assemble → src → build
```

但必须保证 finally 恢复默认 full profile。

---

## 4.3 package.json

增加：

```json
{
  "build:base": "...",
  "build:login": "...",
  "build:authorization": "...",
  "build:full": "..."
}
```

最终：

```bash
pnpm build
```

仍可映射：

```text
build:full
```

---

## 4.4 验收标准

以下全部通过：

```bash
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
```

并检查：

Base：

```text
无 Login import
无 Authorization import
```

Login：

```text
存在 LoginProvider
不存在 AuthorizationProvider
```

Authorization：

```text
Provider 顺序：
Identity
→ Login
→ Authorization
```

Full 与 Authorization 当前暂时相同，也仍保留独立 Profile，为以后增加其他 Extension 做准备。

---

# 5. Step 4：让 Extension Schema 成为正式 Contract

## 5.1 改造目标

当前同时存在：

```text
extension.schema.json
```

和：

```text
validateManifest()
```

但 Schema 没有真正参与运行。

必须消除双重 Contract。

---

## 5.2 引入正式 JSON Schema 校验

增加：

```text
ajv
```

Assembly 读取 Manifest 后首先执行：

```text
extension.schema.json
        ↓
AJV Validation
```

Schema Validation 负责：

```text
字段存在
字段类型
字段格式
additionalProperties
数组结构
```

JavaScript 只负责语义校验：

```text
dependency 是否存在
route 是否冲突
source 是否存在
export 是否存在
file collision
```

---

## 5.3 修正 RootRoute Schema

当前：

```text
sourceContribution
+
allOf(path)
```

与：

```text
additionalProperties:false
```

组合存在问题。

改成独立定义：

```json
{
  "type": "object",
  "required": [
    "id",
    "source",
    "export",
    "path"
  ],
  "properties": {
    "id": {},
    "source": {},
    "export": {},
    "path": {}
  },
  "additionalProperties": false
}
```

不要继续用当前 `allOf` 继承方式。

---

## 5.4 增加 Schema Version

Manifest 增加：

```text
schemaVersion
```

例如：

```json
{
  "schemaVersion": 1,
  "id": "login"
}
```

Schema 要求：

```text
schemaVersion = 1
```

未来 Contract 升级：

```text
V1
→ V2
```

时可以明确处理。

---

## 5.5 YAML/JSON 统一

当前：

```text
extension.yaml
```

实际通过：

```js
JSON.parse
```

读取。

建议正式改成真实 YAML。

增加：

```text
yaml
```

依赖，并使用 YAML Parser。

这样 Manifest 可以写：

```yaml
schemaVersion: 1
id: authorization
version: 1.0.0

requires:
  - login
```

不要继续依赖：

> JSON 是 YAML 子集

这种隐式约定。

---

## 5.6 验收标准

正确 Manifest：

```bash
pnpm test:assembly
```

PASS。

人工增加未知字段：

```yaml
xxx: true
```

必须失败：

```text
INVALID_EXTENSION_DEFINITION
```

删除：

```text
schemaVersion
```

必须失败。

RootRoute：

```yaml
path: /login
```

必须正常通过 Schema。

---

# 6. Step 5：补齐 Assembly 语义校验

## 6.1 Base Route 与 Extension Route 冲突

当前主要检查 Extension 之间冲突。

增加 Base Route Metadata。

不要在 Compiler 中写：

```js
if (path === 'home')
```

建议从 Base 中建立一个明确的 Assembly Contract，例如：

```text
base/base-contract.json
```

包含：

```json
{
  "pageRoot": "page",
  "reservedRootRoutes": [
    "/",
    "/page",
    "*"
  ],
  "basePageRoutes": [
    "home"
  ]
}
```

注意：

这个 Contract 是 Assembly 读取的 Base 元数据，不是最终用户代码。

---

## 6.2 校验

Extension Page：

```text
authorization_management
```

不得与：

```text
basePageRoutes
```

重复。

Extension Root Route：

```text
/login
```

不得占用：

```text
/
/page
```

等 Base 核心入口。

同时检测 Extension 之间：

```text
RootRoute ↔ RootRoute
PageRoute ↔ PageRoute
```

---

## 6.3 Source 校验

继续保留已有：

```text
MISSING_SOURCE
```

并补充：

PageRoute 必须通过当前统一规则：

```text
pageDirectoryFromPath
```

不要在 Compiler 内长期维护另一套：

```js
split('_').map(...)
```

当前 `assemble.mjs` 自己实现了一套 PascalCase 转换：

```js
route.path.split('_')...
```

应收敛。

建议抽出模板级共享纯函数，例如：

```text
assembly/page-identity.mjs
```

并与当前应用：

```text
src/utils/pageIdentity.ts
```

使用同一组测试向量。

避免：

```text
Assembly 认为目录 A 正确
React Runtime 认为目录 B 正确
```

---

## 6.4 验收标准

构造：

```text
Extension page path = home
```

必须：

```text
DUPLICATE_ROUTE
```

构造：

```text
RootRoute = /page
```

必须失败。

非法：

```text
AuthorizationManagement
```

Page path 必须：

```text
INVALID_EXTENSION_DEFINITION
```

---

# 7. Step 6：增加 Extension Boundary 校验

## 7.1 改造目标

防止长期维护后出现：

```text
Authorization
    ↓
直接 import Login 私有代码
```

破坏：

```text
Identity
Access
```

Contract 边界。

---

## 7.2 建立文件 Ownership Map

Assembly 已经知道：

```text
base/src/**
→ base

extensions/login/src/**
→ login

extensions/authorization/src/**
→ authorization
```

利用该信息建立：

```text
output path
→ owner
```

例如：

```text
context/LoginContext.tsx
→ login

platform/access/useAccess.ts
→ base

hooks/usePermission.ts
→ authorization
```

---

## 7.3 扫描 Extension Import

至少扫描：

```text
.ts
.tsx
.js
.jsx
```

识别：

```ts
import ... from '@/xxx'
import ... from '@constants/xxx'
import ... from '@typings/xxx'
import ... from '@utils/xxx'
```

解析实际 owner。

规则：

Extension 可以引用：

```text
自己
Base
第三方 package
```

禁止：

```text
Extension A
→ Extension B 私有文件
```

例如 Authorization：

```ts
import { useLogin } from '@/context/LoginContext';
```

应失败：

```text
CROSS_EXTENSION_PRIVATE_IMPORT
```

---

## 7.4 `requires` 不豁免源码边界

即使：

```text
authorization requires login
```

也不能：

```text
authorization
→ LoginContext
```

`requires` 只影响：

```text
选择
依赖闭包
排序
生命周期
```

运行时能力共享仍走：

```text
Base Contract
```

---

## 7.5 验收标准

当前：

```text
Login → Identity
Authorization → Access
```

全部 PASS。

人为加入：

```ts
// authorization
import { useLogin } from '@/context/LoginContext';
```

执行：

```bash
pnpm test:assembly
```

必须失败：

```text
CROSS_EXTENSION_PRIVATE_IMPORT
authorization
→ login
```

并输出具体文件。

---

# 8. Step 7：建立模板版本模型

## 8.1 改造目标

支持：

```text
昨天发布 V1
今天在 V1 基础上修改
明天发布 V2
```

同时为以后：

```text
extension update login
```

准备版本事实。

---

## 8.2 Extension 增加 version

Login：

```yaml
schemaVersion: 1
id: login
version: 1.0.0
```

Authorization：

```yaml
schemaVersion: 1
id: authorization
version: 1.0.0
```

---

## 8.3 Base Version

当前：

```text
base.yaml
version
```

不要让它同时承担：

```text
Template Version
Base Version
```

建议增加：

```text
base/base.json
```

或者：

```text
base/base.yaml
```

例如：

```yaml
schemaVersion: 1
id: frontend-base
version: 1.0.0
```

这表示：

```text
Base Source Version
```

---

## 8.4 顶层 Template Version

新增：

```text
template-version.yaml
```

例如：

```yaml
templateVersion: 1.0.0

base:
  version: 1.0.0

extensions:
  login: 1.0.0
  authorization: 1.0.0

assembly:
  schemaVersion: 1
```

当前阶段不需要构建复杂 SemVer 自动升级机制。

先解决：

```text
一个模板版本由什么组成
```

的问题。

---

## 8.5 一致性检查

增加：

```text
assembly/verify-version.mjs
```

验证：

```text
template-version.yaml
```

声明的 Extension：

```text
login 1.0.0
```

与：

```text
extensions/login/extension.yaml
```

一致。

---

## 8.6 验收标准

修改 Login：

```text
1.0.0
→ 1.0.1
```

但没有更新：

```text
template-version.yaml
```

发布校验必须失败：

```text
TEMPLATE_VERSION_MISMATCH
```

这样版本发布不会出现：

```text
代码已经变了
版本事实仍旧
```

---

# 9. Step 8：修正 `base.yaml` 与发布链路

这是本阶段最重要、同时风险最大的步骤，建议在前面开发与验证机制完成后再做。

## 9.1 当前问题

当前：

```text
base.yaml
```

仍列出了：

```text
Login
Authorization
Assembly Generated Files
```

例如：

```text
frontend/src/apis/login.ts
frontend/src/providers/LoginProvider.tsx
frontend/src/providers/AuthorizationProvider.tsx
frontend/src/pages/AuthorizationManagement/**
```

这意味着发布系统仍把：

```text
完整 src
```

视为 Base Source。

需要解除。

---

## 9.2 正确模型

发布必须变成：

```text
Template Source
    ↓
选择配置
    ↓
Assembly
    ↓
完整 Frontend Artifact
    ↓
交给现有模板发布机制
```

即：

```text
base.yaml
```

不能再承担“源码所有权”的作用。

它如果继续存在，应描述：

```text
发布 Artifact
```

而不是：

```text
模板开发 Source
```

---

## 9.3 推荐分两阶段实施

### 阶段 A：先保兼容

暂时保留现有：

```text
base.yaml
```

但规定：

> `frontend/src/**` 部分禁止人工维护。

新增：

```text
assembly/update-artifact-manifest.mjs
```

Assembly Full Profile 后：

```text
扫描最终 src
    ↓
重新生成 base.yaml 中 frontend/src 文件清单
```

这样至少消除：

```text
base.yaml
与
src
```

手工同步问题。

当前 Template Engine 行为不需要立即大改。

---

### 阶段 B：后续接入 Template Engine

当配置化 Assembly 接入真正应用生成流程后：

```text
用户配置
    ↓
选 Extension
    ↓
Assembly
    ↓
生成 Artifact Manifest
    ↓
Template Engine 输出
```

此时再彻底移除：

```text
base.yaml
```

中固定的完整前端 `src` 清单。

不要在当前阶段为了纯化架构一次性修改整个 Template Engine。

---

## 9.4 当前阶段验收

必须至少做到：

```text
base.yaml 中 frontend/src 列表
=
Assembly Full 输出
```

且：

```text
列表由脚本生成
```

不是人工维护。

人工新增：

```text
extensions/login/src/components/X.tsx
```

执行发布准备命令后：

```text
最终 src/components/X.tsx
```

以及 Artifact 文件清单都自动包含该文件。

不允许开发者再手动修改：

```text
base.yaml
```

注册它。

---

# 10. Step 9：补齐 Assembly 测试矩阵

## 10.1 正常场景

至少验证：

```text
Base-only
Login
Authorization
Full
```

并检查：

```text
文件集合
Provider 顺序
RootRoute
PageRoute
Generated Assembly 文件
```

---

## 10.2 错误场景

必须增加测试：

```text
DUPLICATE_EXTENSION_ID

MISSING_EXTENSION_DEPENDENCY

EXPLICIT_EXTENSION_CONFLICT

CIRCULAR_EXTENSION_DEPENDENCY

INVALID_EXTENSION_DEFINITION

FILE_COLLISION

DUPLICATE_CONTRIBUTION_ID

DUPLICATE_ROUTE

MISSING_SOURCE

CROSS_EXTENSION_PRIVATE_IMPORT
```

每一个错误必须：

```text
有固定 error code
有具体 Extension ID
有具体文件 / Route / Dependency
```

不要只：

```text
throw new Error('invalid')
```

---

## 10.3 确定性测试

同一：

```text
Base
Extension
Profile
```

连续 Assembly 两次。

计算所有输出文件 hash。

要求：

```text
完全一致
```

包括：

```text
文件顺序
import 顺序
Provider 顺序
Route 顺序
生成代码
```

---

## 10.4 Drift Test

测试：

```text
Source
→ Assembly
→ 修改 generated src
→ verify:assembly
```

必须检测漂移。

---

# 11. Step 10：加入真实 Build Matrix

仅检查生成文本不够。

必须增加：

```text
Assembly
+
TypeScript
+
Vite Build
```

联合验收。

建议增加：

```bash
pnpm test:matrix
```

内部执行：

```text
assemble base
→ build

assemble login
→ build

assemble authorization
→ build

assemble full
→ build
```

任意一个失败：

```text
整体失败
```

测试结束后恢复：

```text
full
```

作为默认开发 `src`。

---

# 12. Step 11：接入 CI

针对修改以下目录：

```text
template-source/code/frontend/base/**
template-source/code/frontend/extensions/**
template-source/code/frontend/assembly/**
template-source/code/frontend/package.json
```

CI 至少执行：

```bash
pnpm install --frozen-lockfile

pnpm test:assembly

pnpm verify:assembly

pnpm test:matrix
```

如果仓库现有测试成本允许，再执行：

```bash
pnpm test
```

---

## CI 必须阻止以下提交

```text
修改 generated src 但没有修改 Source

Extension Manifest 非法

跨 Extension 私有依赖

某个 Profile 无法 build

Assembly 非确定性

Artifact Manifest 漂移
```

---

# 13. Step 12：调整开发文档

最终更新：

```text
TEMPLATE_DEVELOPMENT.md
README.md
```

模板开发者工作流必须明确。

---

## 修改 Base

```bash
pnpm dev:base
```

修改：

```text
base/src/**
```

---

## 修改 Login

```bash
pnpm dev:login
```

修改：

```text
extensions/login/**
```

---

## 修改 Authorization

```bash
pnpm dev:authorization
```

修改：

```text
extensions/authorization/**
```

---

## 验证完整模板

```bash
pnpm dev:full
```

或：

```bash
pnpm build:full
```

---

## 发布前

统一：

```bash
pnpm verify:assembly
pnpm test:assembly
pnpm test:matrix
pnpm prepare:template
```

其中：

```text
prepare:template
```

负责：

```text
Full Assembly
Artifact Manifest
Version Verification
```

---

# 14. Codex 实施顺序

要求按以下顺序实施，不建议并行大改：

```text
Step 1
Source of Truth / verify:assembly

        ↓

Step 2
dev profile + watch

        ↓

Step 3
build profile

        ↓

Step 4
Schema Contract

        ↓

Step 5
Route / Source 语义校验

        ↓

Step 6
Extension Boundary

        ↓

Step 7
Version Model

        ↓

Step 8
base.yaml / Artifact 发布链路

        ↓

Step 9
Assembly Test Matrix

        ↓

Step 10
Real Build Matrix

        ↓

Step 11
CI

        ↓

Step 12
Documentation
```

不要先改 Template Engine 发布逻辑，再回头处理 Source of Truth。

开发闭环必须先稳定。

---

# 15. 本阶段禁止事项

Codex 本阶段禁止：

```text
重新引入 Runtime Extension Registry

使用 import.meta.glob 自动发现 Extension

新增 Login / Authorization 专属 Compiler 分支

通过 AST Patch 修改 AppProviders

通过 Anchor 修改 rootRoutes

让 Extension 直接修改 Assembly 文件

让 Authorization 直接依赖 LoginContext

为了开发方便重新把 Login / Authorization 放回 Base

把 base/src 定义成最终用户工程 Source of Truth
```

同时不得为了保持：

```text
pnpm dev
```

行为而继续要求开发者修改：

```text
src/**
```

---

# 16. 最终验收状态

完成本阶段后，应形成明确生命周期：

```text
昨天发布 Template 1.0

        ↓

今天创建开发分支

        ↓

直接修改：
base
或
extensions/login
或
extensions/authorization

        ↓

dev:<profile>

        ↓

Assembly 自动投影

        ↓

测试 / Build

        ↓

版本更新

        ↓

发布 Template 1.1
```

整个过程中不再发生：

```text
从最终 src 重新拆 Base

从旧版本重新创建 Login Extension

人工同步 src 和 Extension

人工维护 Provider 注册

人工维护 Root Route

人工维护 Extension Page 注册
```

最终 Source of Truth 固定为：

```text
base/**
extensions/**
assembly/**
版本 Manifest
```

而：

```text
src/**
```

只负责：

```text
开发预览
测试
构建
发布 Artifact
```

不再承担模板源码维护职责。
