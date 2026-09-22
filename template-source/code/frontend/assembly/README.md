# Assembly

Assembly 将完整 `base/` 和所选 `extensions/<id>/src` 静态投影为 `workspace/`。Base 会原样复制，Extension 的 `src/**` 会合并到 `workspace/src/**`。它不在最终 React 应用中引入插件运行时。

`profiles.json` 是 Profile 与 Extension 集合的唯一映射。Profile 可通过 Extension 的 `requires` 自动补齐依赖。

受 Compiler 管理的文件为：

- `providers/AppProviders.tsx`
- `routes/rootRoutes.tsx`
- `routes/systemPageRoutes.ts`
- `bootstrap/appInitializers.ts`
- `observability/errorReporter.ts`

不要直接编辑 Base 或 Extension 以外的 `workspace/` 内容；使用相应的 `pnpm dev:<profile>` 后在 Workspace 开发，并以 `pnpm sync:<profile>` 回写维护源码。
