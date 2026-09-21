# Assembly

Assembly 将 `base/src` 和所选 `extensions/<id>/src` 静态投影为根目录 `src`。它不在最终 React 应用中引入插件运行时。

`profiles.json` 是 Profile 与 Extension 集合的唯一映射。Profile 可通过 Extension 的 `requires` 自动补齐依赖。

受 Compiler 管理的文件为：

- `providers/AppProviders.tsx`
- `routes/rootRoutes.tsx`
- `routes/systemPageRoutes.ts`
- `bootstrap/appInitializers.ts`
- `observability/errorReporter.ts`

不要直接编辑根目录 `src`；修改维护源码后运行 `pnpm assemble` 或相应的 `pnpm dev:<profile>`。
