# 前端模板维护指南

本文面向维护 `template-source/code/frontend` 的模板开发者。它说明如何修改模板源码、验证组合结果，以及为模板仓库发布变更做好准备。

## 1. 源码边界

模板采用“维护源码 → 静态 Assembly → 生成 React 源码”的单向流程：

```text
base/src + extensions/*/src + assembly
                    ↓
                  src
```

| 路径 | 职责 | 是否手工编辑 |
| --- | --- | --- |
| `base/src/**` | 不依赖具体 Extension 的应用骨架与平台 Contract | 是 |
| `extensions/<id>/src/**` | Extension 私有源码 | 是 |
| `extensions/<id>/extension.yaml` | Extension 依赖与 Contribution 声明 | 是 |
| `assembly/**` | Compiler、Profile、Assembly Contract 与验证工具 | 是 |
| `src/**` | 当前 Profile 生成的普通 React 工程 | 否 |

根目录 `src/.xcodeagent-template-generated.json` 是确定性生成标记。它仅用于模板维护期识别生成目录，不属于最终用户项目 Artifact。

> 不要直接修改 `src/**`。修改会在下一次 Assembly 时丢失，并由 `pnpm verify:assembly` 报为 `GENERATED_SOURCE_DRIFT`。

## 2. 首次准备

在本目录执行：

```bash
pnpm install --frozen-lockfile
pnpm assemble
pnpm verify:assembly
```

运行环境由 `package.json` 约束：Node.js `>=20.19 <23`，包管理器为 pnpm `11.9.0`。

`pnpm assemble` 默认生成 `full` Profile，即 Login + Authorization；`pnpm verify:assembly` 会重新组装到临时目录并逐文件比较当前 `src`。

## 3. 日常开发

### 选择 Profile

Profile 定义位于 `assembly/profiles.json`：

| Profile | 初始 Extension | 实际组装结果 |
| --- | --- | --- |
| `base` | 无 | Base-only |
| `login` | `login` | Base + Login |
| `authorization` | `authorization` | Base + Login + Authorization（自动补齐依赖） |
| `full` | `login`、`authorization` | Base + Login + Authorization |

启动对应开发环境：

```bash
pnpm dev:base
pnpm dev:login
pnpm dev:authorization
pnpm dev:full
```

`pnpm dev` 只直接启动当前根目录已生成的 `src`，不会执行 Assembly 或监听维护源码；它适用于查看已经生成好的工程。需要从维护源码开始开发时，应显式运行 `pnpm dev:base`、`pnpm dev:login`、`pnpm dev:authorization` 或 `pnpm dev:full`。这些开发 Runner 会先将所选 Profile 组装到暂存目录；只有组装成功才替换根目录 `src` 并由 Vite 提供页面。它会监听 `base/src`、`extensions` 与 `assembly` 的变更；Assembly 失败时会保留上一个可运行的 `src`。

### 修改规则

- 修改通用布局、Identity/Access Contract、路由基础设施或通用工具：编辑 `base/src/**`。
- 修改登录、登出与认证私有逻辑：编辑 `extensions/login/**`。
- 修改权限 API、权限页面、权限 Provider 或 Permission 组件：编辑 `extensions/authorization/**`。
- 新增需要参与 Provider、Root Route、Page Route、Initializer 或 Error Reporter 的能力：修改对应 `extension.yaml`，不要直接 Patch `App.tsx`、路由或 Layout。
- 仅在手动 Assembly、排查或非 Watch 模式下执行 `pnpm assemble`；运行 `pnpm dev:<profile>` 时无需手工组装。

## 4. 测试与验证

测试命令分为两类：Profile 验证与默认 full 交付物验证。不要混用。

### Profile 开发期验证

正在运行 `pnpm dev:base`、`pnpm dev:login` 或其他 `dev:<profile>` 时，Runner 已负责将对应 Profile 组装到 `src`。此时不要执行无参数的 `pnpm assemble`，因为它固定生成 full Profile，会覆盖当前开发中的 Profile。

使用以下命令验证当前 Profile：

```bash
pnpm test:assembly
pnpm build:<profile>
```

例如 Base 开发期：

```bash
pnpm test:assembly
pnpm build:base
```

`build:<profile>` 会在构建结束后恢复 full `src`。因此如果仍要继续使用该 Profile 开发，应重新启动相应的 `pnpm dev:<profile>`。

`pnpm verify:assembly` 仅验证 full Profile；在 Base、Login 或其他非 full Profile 开发期运行它会报告预期的文件差异，不能作为该 Profile 的验证手段。

### 默认 full 交付物验证

当完成某个 Profile 的开发、准备提交或发布完整模板时，切换回 full 交付物后执行：

```bash
pnpm assemble
pnpm verify:assembly
pnpm test:assembly
pnpm build:full
```

命令含义如下：

| 命令 | 验证内容 |
| --- | --- |
| `pnpm verify:assembly` | `src` 是否与 full Profile 的确定性 Assembly 输出一致 |
| `pnpm test:assembly` | Base、Login、Full 和显式依赖冲突等 Assembly 场景 |
| `pnpm test` | 前端 Jest 测试 |
| `pnpm test:source-path` | Source Path 插件行为 |
| `pnpm test:route-projector` | Route Projector 行为 |
| `pnpm build` | full Profile 的 TypeScript 与 Vite 生产构建 |

涉及 Base、Manifest、Provider、路由或 Extension 源码变更时，应执行完整 Build Matrix：

```bash
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
```

每个 Profile Build 完成后都会恢复根目录 `src` 为 full Profile，因此不会把日常开发目录停留在测试 Profile。

### 验证 Base-only 输出

Base-only 是维护期的组合 Profile，不是直接编辑 `base/src` 后就天然可运行的目录。应先执行 Assembly，再检查生成结果：

```bash
# 生成 Base-only src；此命令会暂时替换根目录 src
pnpm assemble -- --profile=base

# 确认选择了正确 Profile
cat src/.xcodeagent-template-generated.json

# Base-only 不得携带 Login / Authorization 私有实现
test ! -e src/constants/yst.ts
test ! -e src/providers/LoginProvider.tsx
test ! -e src/providers/AuthorizationProvider.tsx
test ! -e src/pages/Login/index.tsx
test ! -e src/pages/AuthorizationManagement/index.tsx

# 执行 Base-only TypeScript 与 Vite 构建；结束时自动恢复 full src
pnpm build:base

# 确认恢复后的默认 full 生成物未漂移
pnpm verify:assembly
```

预期 Base-only Marker 为：

```json
{
  "generated": true,
  "profile": "base",
  "extensions": []
}
```

不要在 Base-only 状态执行 `pnpm verify:assembly`：该命令当前固定验证 full Profile，因此会把有意移除的 Extension 文件报告为漂移。

## 5. 发布前流程

本前端 package 标记为 `private`，不发布为 npm 包。这里的“发布”指将模板源变更交付给 Template Engine/模板仓库。

在提交或发起合并请求前，从本目录执行：

```bash
pnpm assemble
pnpm verify:assembly
pnpm test:assembly
pnpm test
pnpm test:source-path
pnpm test:route-projector
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
git diff --check
```

若改动了模板文件声明、Template Source 或 Core 行为，还需在仓库根目录执行：

```bash
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-base-frontend.sh
```

发布前确认以下事项：

- `src` 已由 `pnpm assemble` 重新生成，且 `pnpm verify:assembly` 通过；
- `src` 中不含 Extension Runtime、Registry、Manifest 或模板维护目录；
- `base.yaml` 中的文件声明与最终交付的前端文件一致；
- 没有将 `build/`、依赖目录或临时 Assembly 目录纳入变更；
- 变更说明写清影响的 Profile 与 Extension。

### 发布 Base 源码变更

“发布 Base”通常是指将 `base/src/**` 的变更作为完整模板的一部分交付，而不是发布一个 npm 包，也不是把 `base/src` 原样复制给最终用户。推荐流程如下：

```bash
# 1. 先证明 Base-only 仍然成立
pnpm build:base

# 2. 验证受影响的组合 Profile；Base 改动通常至少影响全部 Profile
pnpm build:login
pnpm build:authorization
pnpm build:full

# 3. 重新生成并校验最终默认交付物
pnpm assemble
pnpm verify:assembly

# 4. 执行模板级检查
git diff --check
```

若 Base 变更新增、删除或重命名了最终用户工程中的文件，必须同步更新 `base.yaml` 的 `files` 声明。提交时应包含维护源变更与重新生成的默认 full `src` 变更；不要提交临时 Base-only `src`。

### Base-only 的发布边界

当前 Template Engine 的最终前端文件声明仍以默认完整 `frontend/src/**` 为交付对象；它不会在项目生成时调用 `assembly/assemble.mjs` 并选择 `base` Profile。

因此，现阶段可以发布“包含 Base 改动的完整模板”，但不能只通过运行 `pnpm assemble -- --profile=base` 就发布一个供最终用户选择的 Base-only 模板。若产品需要交付 Base-only 工程，需要先扩展 Template Engine/Template Source 契约，使它能在生成期选择 Profile、执行或等价实现 Assembly，并为该 Profile 定义独立的最终文件声明与验收流程。

## 6. 后续更新流程

### 更新 Base

1. 编辑 `base/src/**`。
2. 若新增、删除或重命名最终交付文件，同步更新 `base.yaml`。
3. 执行完整 Build Matrix；至少确认 Base-only 不含 Login/Authorization 的实现依赖。

### 更新已有 Extension

1. 编辑 `extensions/<id>/src/**`；普通源码按相对路径投影到最终 `src/**`。
2. 如果全局装配需求变化，更新 `extensions/<id>/extension.yaml` 中的 Contribution。
3. 不要直接依赖其他 Extension 的私有源码；跨能力交互应使用 Base Contract。
4. 运行目标 Profile 和 full Profile 的 Assembly Test/Build。

### 新增 Extension

1. 新建 `extensions/<id>/extension.yaml` 与 `extensions/<id>/src/**`。
2. 在 Manifest 声明 `requires` 和所需的五类 Contribution；不要登记普通源码文件列表。
3. 将需要验证的开发组合加入 `assembly/profiles.json`；Profile 是数据配置，不在 Runner 中增加 Capability 专属分支。
4. 验证依赖闭包、Provider 顺序、路由冲突、文件碰撞及全量 Build Matrix。
5. 重新生成 full `src`，更新 `base.yaml` 中最终交付文件声明，并完成发布前流程。

## 7. 常见问题

**`GENERATED_SOURCE_DRIFT`**：说明 `src` 与维护源码的组装结果不同。不要在 `src` 修复；先检查提示中的 `expected source`，修正维护源后运行 `pnpm assemble`。

**Assembly 失败但页面仍能打开**：这是开发 Runner 的保护机制。终端输出的错误才是当前源码问题；修复后它会自动再次 Assembly。

**想检查某个组合而不启动开发服务**：运行 `pnpm build:<profile>`。它会组装、构建，并在结束时恢复 full `src`。
