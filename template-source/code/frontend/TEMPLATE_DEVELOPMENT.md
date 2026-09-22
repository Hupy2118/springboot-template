# 前端模板开发指引

`base/` 是完整的 Base 应用工程，`extensions/<id>/src/` 是增量能力源码，`workspace/` 是 Assembly 生成的完整应用 Workspace。根目录 `package.json` 仅提供模板维护命令；应用运行依赖和脚本属于 `base/package.json`。

## Base

```bash
pnpm dev:base
# 修改 workspace/**
pnpm sync:base
pnpm build:base
```

`sync:base` 会将 Base 已有文件、新文件和删除文件回写到 `base/**`。例如，`workspace/vite.config.ts` 回写到 `base/vite.config.ts`，`workspace/src/App.tsx` 回写到 `base/src/App.tsx`。Assembly 生成的 `workspace/src/generated/extensions/**` 及两个 `.devagentstudio-template-*.json` 文件永远不会写入 Base。

## Extension

Login 和 Authorization 在完整应用 Workspace 中开发：

```bash
pnpm dev:login
# 修改 workspace/src/**
pnpm sync:login
pnpm verify:update:login
```

```bash
pnpm dev:authorization
# 修改 workspace/src/**
pnpm sync:authorization
pnpm verify:update:authorization
```

现阶段 Extension 的 `src/**` 合并到 `workspace/src/**`。已有文件会回写原 owner；新文件写入当前 Profile 的 Extension；删除文件从原 owner 删除。同一路径由多个源码根拥有时会以 `SOURCE_OWNER_CONFLICT` 失败，不存在覆盖优先级。

Provider、Root Route、Page Route、Initializer、Error Reporter 和依赖关系属于 Extension Contract，修改 `extensions/<id>/extension.yaml` 后重新运行 dev 或 build。绝不能直接修改 `workspace/src/generated/extensions/**` 或模板状态文件。

## Full 与发布

`pnpm dev:full` 和 `pnpm build:full` 用于 Base + Login + Authorization 集成验证。Full 没有 `editTarget`，不可 Sync。

`pnpm build:<profile>` 会备份当前 `workspace/`、组装目标 Profile 后在 Workspace 内执行构建，最后恢复原 Workspace。因此未同步的开发修改不会被覆盖。

放弃未同步修改可使用 `pnpm reset:<profile>`。发布前执行：

```bash
pnpm materialize:full
pnpm verify:assembly
pnpm verify:release
git diff --check
```

Release 就是移除两个维护 Metadata 后的 Workspace；不存在 `base.yaml` 或额外的发布边界清单。
