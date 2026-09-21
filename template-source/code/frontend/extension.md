# Base 模板可持续维护修复方案

## 1. 改造目标

当前 `pnpm dev:base` 生成的 `src` 中，有部分运行时代码由 Assembly 直接生成，例如：

```text
providers/AppProviders.tsx
routes/rootRoutes.tsx
routes/systemPageRoutes.ts
bootstrap/appInitializers.ts
observability/errorReporter.ts
```

这些文件被视为 Assembly Managed Files，导致开发者即使在 `src` 中修复，也无法通过 `sync:base` 持久化到 `base/src`。

需要调整为：

```text
Base
= 可持续维护的完整基础应用源码

Extension
= 独立能力源码

Assembly
= 只负责 Extension 组合

Generated
= 只保存组合结果
```

最终保证：

```text
pnpm dev:base
→ 修改完整 src
→ pnpm sync:base
→ 更新 base/src
→ 下一次 dev:base 继续基于最新 Base 开发
```

---

# 2. 核心改造原则

Assembly 不再生成完整应用逻辑文件，只生成 Extension Contribution Registry。

判断标准：

> 没有任何 Extension 时仍然有意义的代码，应属于 Base，而不是 Assembly Generated。

因此以下文件应正式进入 `base/src`：

```text
base/src/providers/AppProviders.tsx
base/src/routes/rootRoutes.tsx
base/src/routes/systemPageRoutes.ts
base/src/bootstrap/appInitializers.ts
base/src/observability/errorReporter.ts
```

这些文件后续都允许：

```text
dev:base
→ 修改 src
→ sync:base
→ 覆盖 base/src
```

---

# 3. 调整 Assembly 输出

当前 `assemble.mjs` 中：

```text
appProviders()
rootRoutes()
systemPageRoutes()
initializers()
errorReporters()
```

会生成完整运行时代码。

需要调整为只生成 Extension Registry，例如：

```text
src/generated/extensions/providers.ts
src/generated/extensions/rootRoutes.tsx
src/generated/extensions/systemPageRoutes.ts
src/generated/extensions/initializers.ts
src/generated/extensions/errorReporters.ts
```

建议对应函数调整为：

```text
generateProviderRegistry()
generateRootRouteRegistry()
generatePageRouteRegistry()
generateInitializerRegistry()
generateErrorReporterRegistry()
```

它们只负责描述：

```text
当前 Profile 中有哪些 Extension Contribution
```

不再负责定义应用最终行为。

---

# 4. Base 文件改造

## 4.1 AppProviders

新增正式 Base 文件：

```text
base/src/providers/AppProviders.tsx
```

由 Base 自己控制 Provider 组合逻辑。

示例：

```tsx
import type { PropsWithChildren } from 'react';
import { IdentityProvider } from '@/platform/identity/IdentityContext';
import { extensionProviders } from '@/generated/extensions/providers';

export function AppProviders({ children }: PropsWithChildren) {
  const content = extensionProviders.reduceRight(
    (current, Provider) => <Provider>{current}</Provider>,
    children,
  );

  return <IdentityProvider>{content}</IdentityProvider>;
}
```

Assembly 只生成：

```text
generated/extensions/providers.ts
```

Base Profile：

```ts
export const extensionProviders = [];
```

Login / Authorization Profile 则生成对应 Provider 列表。

---

## 4.2 Root Routes

新增：

```text
base/src/routes/rootRoutes.tsx
```

由 Base 负责最终路由组合逻辑。

Assembly 只生成：

```text
generated/extensions/rootRoutes.tsx
```

保存 Extension 提供的 Root Route。

---

## 4.3 System Page Routes

新增：

```text
base/src/routes/systemPageRoutes.ts
```

Base 负责最终页面路由组合。

Assembly 只生成：

```text
generated/extensions/systemPageRoutes.ts
```

---

## 4.4 Initializers

新增：

```text
base/src/bootstrap/appInitializers.ts
```

Base 决定：

```text
初始化顺序
异常处理
Base 初始化逻辑
Extension 初始化调用方式
```

Assembly 只生成：

```text
generated/extensions/initializers.ts
```

---

## 4.5 Error Reporter

新增：

```text
base/src/observability/errorReporter.ts
```

Base 负责：

```text
错误处理策略
Reporter 隔离
fallback
调用顺序
```

Assembly 只生成：

```text
generated/extensions/errorReporters.ts
```

---

# 5. 调整 Assembly Managed Files

当前这 5 个文件：

```text
providers/AppProviders.tsx
routes/rootRoutes.tsx
routes/systemPageRoutes.ts
bootstrap/appInitializers.ts
observability/errorReporter.ts
```

从 `ASSEMBLY_MANAGED_FILES` 中移除。

新的 Assembly Managed Files 只包括：

```text
generated/extensions/providers.ts
generated/extensions/rootRoutes.tsx
generated/extensions/systemPageRoutes.ts
generated/extensions/initializers.ts
generated/extensions/errorReporters.ts
```

Workspace Metadata：

```text
.xcodeagent-template-generated.json
```

单独作为 Workspace Metadata 管理，不写入 Base。

---

# 6. 修改 sync

`pnpm sync:base` 应采用完整 Mirror Sync。

同步范围：

```text
src/**
-
generated/extensions/**
-
.xcodeagent-template-generated.json
↓
base/src/**
```

支持：

```text
CREATE
UPDATE
DELETE
```

因此开发者修改：

```text
src/providers/AppProviders.tsx
```

执行：

```bash
pnpm sync:base
```

后必须更新：

```text
base/src/providers/AppProviders.tsx
```

其他 Base 文件同理。

---

# 7. 修改 assemble.mjs

Assembly 流程调整为：

```text
1. copy base/src → src

2. copy selected extensions → src

3. 读取 extension.yaml contributions

4. 生成 generated/extensions/**

5. 写入 .xcodeagent-template-generated.json
```

不得再覆盖：

```text
src/providers/AppProviders.tsx
src/routes/rootRoutes.tsx
src/routes/systemPageRoutes.ts
src/bootstrap/appInitializers.ts
src/observability/errorReporter.ts
```

---

# 8. Base Profile 行为

执行：

```bash
pnpm dev:base
```

应得到：

```text
base/src
+
空 Extension Registry
↓
src
```

例如：

```text
generated/extensions/providers.ts
```

内容为空列表。

开发者可以正常修改：

```text
src/providers/AppProviders.tsx
src/routes/rootRoutes.tsx
src/bootstrap/appInitializers.ts
...
```

然后：

```bash
pnpm sync:base
```

完整更新 Base。

---

# 9. Login / Authorization 行为

Login：

```text
Base
+
Login Source
+
Login Contribution Registry
↓
src
```

Authorization：

```text
Base
+
Login
+
Authorization
+
对应 Contribution Registry
↓
src
```

已有 Base 文件仍然属于 Base。

Extension Registry 始终由 Assembly 生成，不允许反向同步。

---

# 10. 需要修改的文件

主要修改范围：

```text
template-source/code/frontend/

base/src/
  providers/AppProviders.tsx
  routes/rootRoutes.tsx
  routes/systemPageRoutes.ts
  bootstrap/appInitializers.ts
  observability/errorReporter.ts

assembly/
  assemble.mjs
  assembly-contract.mjs
  workspace-sync.mjs
  verify-generated.mjs
```

如存在相关测试，同时修改：

```text
assembly/test-assemble.mjs
```

---

# 11. 验收标准

## Base 可修改

执行：

```bash
pnpm dev:base
```

修改：

```text
src/providers/AppProviders.tsx
```

执行：

```bash
pnpm sync:base
```

要求：

```text
base/src/providers/AppProviders.tsx
```

内容完全更新。

---

## Base 可恢复

执行：

```bash
pnpm dev:base
```

应重新得到刚才修改后的：

```text
src/providers/AppProviders.tsx
```

不得被 Assembly 恢复成旧版本。

---

## Assembly 不覆盖 Base

Assembly 后：

```text
src/providers/AppProviders.tsx
```

必须来自：

```text
base/src/providers/AppProviders.tsx
```

而不是 `assemble.mjs` 动态生成。

---

## Extension Registry 正常生成

Base：

```text
extensionProviders = []
```

Login：

```text
extensionProviders = [LoginProvider]
```

Authorization：

```text
extensionProviders = [LoginProvider, AuthorizationProvider]
```

其他 Route、Initializer、Reporter Registry 同理。

---

## sync 排除生成目录

执行：

```bash
pnpm sync:base
```

不得在：

```text
base/src/generated/extensions/**
```

中保存 Assembly 生成结果。

也不得写入：

```text
base/src/.xcodeagent-template-generated.json
```

---

## Build 验证

以下命令全部通过：

```bash
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
pnpm test:assembly
```

---

# 12. 最终开发模型

改造完成后，Base 开发流程固定为：

```text
pnpm dev:base
↓
修改完整 src
↓
pnpm sync:base
↓
更新完整 base/src
↓
pnpm build:base
↓
提交
```

核心约束：

> Assembly 只生成 Extension 组合信息，不生成或覆盖 Base 的可维护应用逻辑。

这样后续即使 `AppProviders`、Route、Initializer、ErrorReporter 等最初设计存在问题，也可以通过正常 Base 开发流程持续修改和演进。
