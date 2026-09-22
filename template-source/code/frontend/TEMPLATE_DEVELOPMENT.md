# 前端模板开发指引

`base/src` 和 `extensions/<id>/src` 是唯一源码。根目录 `src` 是 Assembly 生成的开发 Workspace，也是发布前的最终 materialization；它不是独立的 Source Owner。

## Base

Base 与 Extension 使用同一套 Workspace 开发流程：

```bash
pnpm dev:base
# 修改 src/**
pnpm sync:base
pnpm build:base
```

`base/src/**` 是持久化源码，但正常开发不直接修改它。`sync:base` 使用统一的 Workspace Sync：已有文件、新文件和删除文件都归 Base；`providers/AppProviders.tsx`、路由、Initializer 与 Error Reporter 等宿主文件也可正常回写。Assembly 生成的 `src/generated/extensions/*` 和 marker 永远不会写入 `base/src`。

## Extension

Login 和 Authorization 在完整应用 Workspace 中开发：

```bash
pnpm dev:login
# 修改 src/**
pnpm sync:login
pnpm verify:update:login
```

```bash
pnpm dev:authorization
# 修改 src/**
pnpm sync:authorization
pnpm verify:update:authorization
```

同步按当前 Source Tree 的路径归属处理：已有 Base、Login、Authorization 文件分别回写原 owner；新文件写入当前 profile 的 Extension（`login` 或 `authorization`）；删除的文件从原 owner 删除。同一路径出现在多个 Source Root 会以 `SOURCE_OWNER_CONFLICT` 失败，不存在覆盖优先级。

Authorization 自动包含 Login 依赖，因此修改已有 Login 文件仍会回写 Login。Extension Workspace 中修改或删除 Base 文件是允许的，但必须完成下游组合构建验证。

Provider、Root Route、Page Route、Initializer、Error Reporter 和依赖关系属于 Extension Contract，直接修改 `extensions/<id>/extension.yaml`，随后重新运行 dev 或 build。绝不能直接修改 `src/generated/extensions/*` 或 `.devagentstudio-template-generated.json`；它们由 Assembly 管理且 Sync 会忽略。

## Full 与 Workspace 管理

`pnpm dev:full` 和 `pnpm build:full` 用于 Base + Login + Authorization 集成验证。Full 没有 `editTarget`，不可 Sync，也不构成新的源码边界。

`pnpm build:<profile>` 会临时备份当前 `src` 及 `.devagentstudio-template-workspace.json`、组装目标 Profile 并构建，最后原样恢复备份。因此可以在任何开发 Workspace 中运行构建，未同步修改也不会被覆盖。

若明确放弃未同步 Workspace 修改，可使用 `pnpm reset:<profile>`；`materialize:base` 与 `materialize:full` 是面向预览/发布的等价快捷命令。

## 发布前

完成相应验证后，显式生成发布 Workspace：

```bash
pnpm materialize:full
pnpm verify:assembly
git diff --check
```

`base.yaml` 当前仍枚举发布文件。新增 `src` 文件时，先确认 `base.yaml` 已覆盖该文件；Assembly 成功并不自动证明它会进入外层模板发布包。
