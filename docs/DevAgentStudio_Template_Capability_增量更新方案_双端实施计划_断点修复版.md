# Template Service Capability Reconcile 方案设计与实施计划（V2｜实施前总回检冻结版）

> 适用对象：Template Service / Template Engine 与 DevAgent Studio Template Reconcile V2
> 核心职责：根据 `Requested Config + Current TemplateState + Current Template Release` 生成确定性的 Capability Modification Strategy。
> 明确边界：Template Service **不感知 Workspace，不读取 Workspace，不接收 Workspace 文件内容，不执行实际文件修改**。
> **协议冻结决策：以 DevAgent Studio 当前 `StrategyUpdatePackageV2` 为唯一最终 Wire Contract。** Template Service 的 `/v1/update` 必须直接生成 DevAgent Studio 可以严格解析和执行的协议，不再维护另一套 Service 私有 Update Package Wire Schema。

---

## 本版变更说明

本版在原《DevAgent Studio_Template_Capability_增量更新方案_双端实施计划_断点修复版》基础上进行协议收口，核心变化如下：

1. `/v1/update` Request 固定增加 `protocolVersion: "2"`，与 DevAgent Studio 当前调用一致。
2. Update ZIP 从原先四个独立元数据文件：

   ```text
   manifest.json
   modification-strategy.json
   next-template-state.json
   validation-plan.json
   ```

   收口为唯一：

   ```text
   strategy-update-package.json
   ```

   以及显式声明的 `payload/**`。
3. `strategy-update-package.json` 的 DTO 以 DevAgent Studio 当前 `StrategyUpdatePackageV2` 为唯一权威定义。
4. `Modification Strategy` 的 Wire Type 固定为 DevAgent Studio 当前支持的有限 DSL；`TRANSFORM_FILE`、`RENDER_EXTENSION` 不允许继续作为 Wire Type。
5. Strategy 的 `order` 仅作为 Service 内部排序依据；Wire Contract 使用从 `0` 开始连续的 `index` 表示最终执行顺序。
6. Validation Plan 改为 DevAgent Studio 当前 `ValidationPlanItemV2[]` 的严格结构，不再返回 `{ "validators": [...] }` 包装结构。
7. 增加 `currentStateDigest / nextStateDigest / payloadManifest / packageId / diagnostics` 等双端绑定字段。
8. 冻结 TemplateState digest 算法，确保 Service 与 DevAgent Studio 对同一 State 得到完全相同的摘要。
9. `RECONCILE` 固定为**不改变 TemplateState 的健康修复模式**；任何需要 State 变化的场景必须进入 `APPLY`。
10. TS-0、TS-4、TS-7、TS-8、TS-11、TS-13 的实施与验收标准同步调整，优先完成双端协议一致性后再继续后续功能实施。
11. `StrategyRegistryV2` 收口为 **Wire Atomic Strategy Registry**：一个 `strategyId` 必须唯一对应一个 `StrategyDescriptorV2`，禁止运行时隐式 1:N 拆分。
12. 冻结 10 种 `StrategyTypeV2` 的 per-type 参数契约；`parameters` 虽是 JSON object，但其行为语义由 DevAgent Studio 当前 executor 冻结，Service 不得自定义同名字段含义。
13. `astSelector` 固定采用 DevAgent Studio 当前 `nodeType / position / name` 协议，删除 `kind: react-provider-root` 等 Service 私有 Selector DSL。
14. `RECONCILE` 增加 effective Capability 后置条件硬约束；同时新增 Cross-language TemplateState Digest Golden Fixture，作为 Java/Python canonical digest 一致性的协议门禁。
15. 一次性冻结 **Base Extension Surface Contract**：所有 `existingTargets` 必须命中 Base Project 中预先存在、语法有效、可唯一定位的稳定扩展面；不允许由 Generate 阶段临时合成后 Update 再假设其存在。
16. 当前 login / authorization 的 Provider、Route、Wrapper、Menu、WebMvc 集成统一改为“Base 预留稳定 Anchor + Atomic Strategy 受限追加”，彻底移除 `RENDER_EXTENSION` 聚合重写语义。
17. `ValidatorRegistryV2` 同样收口为 Atomic Validation Registry；`scope: frontend/backend` 不再作为运行时 Validator DSL。
18. 冻结 Generate / Update 语义等价要求：Generate 不再使用第二套高阶 renderer，必须消费同一 Atomic Registry；当前 Release 的 Generate Materializer 输出必须与 DevAgent Studio 在 pristine Base 上执行同一 Strategy 集合得到的结果一致。
19. 修正 DevAgent Studio mode 选择语义：正常 Template Preparation 固定走 `APPLY`，`RECONCILE` 只允许显式健康修复；不得因 requestedConfig 与当前 State 相同就自动切换为 `RECONCILE`。
20. 冻结 NPM lockfile 策略：当前内置 Capability 所需 NPM 依赖统一预置到 Base `package.json + pnpm-lock.yaml`；当前内置能力不再动态使用 `ENSURE_NPM_DEPENDENCY`，避免 frozen-lockfile 与 package.json 漂移。
21. 当前 `authorization.auth-provider` 的 MAINTAIN 基线收口为 `NO_OP`；在没有明确可执行 Atomic Strategy 前，不保留虚假的 `reconcile-auth-provider / TRANSFORM_FILE` 维护语义。
22. 冻结 Error Contract、Package determinism 边界、Precondition 空语义、Release Cutover 与 pre-cutover Workspace 处理规则，作为进入实施前最后一轮架构门禁。
23. `TEXT_ANCHOR_INSERT` 不再以“完整 insertion 文本已存在”作为唯一幂等判断；改为 `managedMarker` 所界定的受管块。相同 Package 重试仍 no-op；Release Refresh 内容变化时必须原位替换同一受管块，禁止旧、新块并存。
24. Migration 固定拆分为“Migration Asset 投放”与“Migration Execution 执行时机”两份契约。资产目标路径必须与 Bootstrap 的实际消费路径显式一致；不得把 V1 `migrations:` 机械迁入 V2 后假设 Bootstrap 会自动执行。

---

# 第一章 方案设计

## 1. 目标与核心原则

Template Service 的职责必须收敛为：

```text
Requested Config
+
Current TemplateState
+
Current Template Release
        ↓
Capability Reconcile Decision
        ↓
Deterministic Modification Strategy
+
Candidate nextTemplateState
+
Validation Plan
        ↓
StrategyUpdatePackageV2
```

核心原则：

> Template Service 不维护 Workspace 文件历史所有权，也不要求 Workspace 与任何历史模板版本一致。Service 只负责根据 Capability 状态和当前 Template Release 生成确定性的修改意图；真正的代码事实源始终是 DevAgent Studio 所持有的当前 Workspace。

因此 Service 不应包含：

```text
Workspace Snapshot
Workspace Context
Workspace SHA
Current File Content
Journal
Apply
Rollback
Workspace Lock
AST / 文本实际修改
```

这些全部属于 DevAgent Studio。

### 1.1 Wire Contract 单一事实源

V2 双端协议只允许存在一套权威 Wire Contract：

```text
DevAgent Studio StrategyUpdatePackageV2
```

其中协议事实源进一步分为：

```text
Wire 数据结构
→ DevAgent Studio protocol_v2.py::StrategyUpdatePackageV2

ZIP / payload 完整性
→ DevAgent Studio strategy_update_package.py

Strategy parameters 实际执行语义
→ DevAgent Studio executor_v2.py + strategy_ast_v2.py

Validation 执行语义
→ DevAgent Studio validation_v2.py
```

即：`StrategyUpdatePackageV2` 是唯一 Wire Schema；DevAgent Studio 当前 Validator / Executor 对其中字段的消费语义，是该 Wire Schema 的行为契约。

Template Service 可以存在自己的 Domain Model、Decision Model、Registry Model，但在 HTTP 边界前必须编译成该 Wire Contract。

禁止：

```text
Service 自己定义一套 Update Package
+
DevAgent Studio 再做第二次适配
```

禁止继续保留两套并行 Wire Schema：

```text
Service Package Contract
!=
DevAgent Studio Package Contract
```

正确结构：

```text
Service Domain / Metadata
        ↓
ReconcileDecisionEngine
        ↓
Wire Contract Compiler
        ↓
StrategyUpdatePackageV2
        ↓
DevAgent Studio strict validate
```

---

## 2. Service 职责边界

Template Service 负责：

```text
Capability Definition
Capability Dependency Resolution
Capability Config Canonicalization
Reconcile Reason 判断
Capability Removal Guard
Addition CREATE / MAINTAIN 分类
Template Revision 判断
Migration Asset 投放计划与 Execution Contract 解析
Modification Strategy 生成
Strategy Wire Type 编译
Validation Plan 生成
Candidate nextTemplateState 生成
State Digest 生成
Payload Manifest 生成
StrategyUpdatePackageV2 生成
```

Template Service 不负责：

```text
读取 Workspace
判断当前文件实际内容
比较 Workspace 文件 SHA
执行 Transformer
执行 AST 修改
执行文本修改
生成 Workspace Journal
Apply 文件
Rollback 文件
执行构建 / 测试命令
提交 TemplateState
```

---

## 3. TemplateState V2

正式 State：

```json
{
  "schemaVersion": 2,
  "templateRevision": "R3",
  "requested": {
    "authorization": {
      "enabled": true,
      "config": {}
    }
  },
  "effective": {
    "login": {
      "enabled": true,
      "config": {}
    },
    "authorization": {
      "enabled": true,
      "config": {}
    }
  },
  "appliedAdditions": {
    "login.login-page": {
      "capabilityId": "login",
      "target": "frontend/src/pages/Login/index.tsx",
      "installedRevision": "R3"
    }
  }
}
```

### 3.1 State 只保存

```text
Capability 状态
Addition 生命周期事实
不可复用的 Template Revision
```

### 3.2 State 不保存

```text
managedFiles
Workspace 文件内容
Workspace 文件 SHA
历史模板文件内容
历史代码基线
origin / GENERATED / UPDATED 等历史来源字段
```

`appliedAdditions` 的 Map Key 固定为稳定 `additionId`，不是 `capabilityId`。

### 3.3 TemplateState Digest 冻结算法

双端必须使用同一算法计算：

```text
currentStateDigest
nextStateDigest
```

算法固定为：

```text
1. 将 TemplateStateV2 序列化为 JSON
2. UTF-8
3. ensure_ascii = false
4. object key 全量 sort_keys = true
5. separators = (",", ":")，不包含多余空白
6. 对最终 bytes 计算 SHA-256
7. 输出：sha256:<64 lowercase hex>
```

等价伪代码：

```text
sha256(
  compact_sorted_utf8_json(TemplateStateV2)
)
```

任何一端不得使用 pretty JSON、字段插入顺序或其他 canonicalization 算法计算 State Digest。

### 3.4 Cross-language Digest Golden Fixture

仅在文档中描述算法不足以证明 Java / Python 的 canonicalization 完全一致，因此 TS-0 必须冻结一组跨语言 Golden Fixture：

```text
template-state-v2-golden.json
+
template-state-v2-golden.sha256
```

其中：

```text
template-state-v2-golden.json
→ 固定完整 TemplateStateV2 JSON

template-state-v2-golden.sha256
→ 对上述 State 按 3.3 算法计算得到的唯一 expected digest
```

验收硬约束：

```text
DevAgent Studio Python digest(fixture)
==
Template Service Java digest(fixture)
==
fixture expected digest
```

任何一端产生不同 digest 都视为 Wire Contract 不兼容；不得通过在调用端重新计算并覆盖 Service 输出的方式掩盖差异。

---

## 4. Template Revision

Template Release 仅由不可复用的：

```text
templateRevision
```

标识。

判断规则：

```text
State templateRevision != Current templateRevision
→ RELEASE_REFRESH

State templateRevision == Current templateRevision
→ Template Release 一致
```

`templateRevision` 是不可变发布标识，禁止复用。Base Surface、Capability Metadata、Strategy Registry、Validator Registry、NPM lockfile 或其他任何影响 Template 行为的内容发生变化时，必须提升 `templateRevision`；不得在同一 revision 下发布不同实现。

注意：

```text
currentStateDigest / nextStateDigest
```

只绑定完整 TemplateStateV2，不标识 Template Release。

---

## 5. Capability Definition、Atomic Registry 与 Base Extension Surface

### 5.1 Capability Metadata

Capability V2 Metadata 只声明 Capability 关系和引用，不重复定义 Strategy 的 target/type/order，也不保存 Strategy 的执行参数。

当前 authorization 推荐结构固定为：

```yaml
id: authorization
schemaVersion: 2

defaultConfig: {}

requires:
  - id: login

existingTargets:
  - strategyId: frontend.authorization.import-provider
  - strategyId: frontend.authorization.register-provider
  - strategyId: frontend.authorization.import-page-route
  - strategyId: frontend.authorization.register-page-route
  - strategyId: frontend.authorization.import-page-wrapper
  - strategyId: frontend.authorization.register-page-wrapper
  - strategyId: frontend.authorization.import-menu-transform
  - strategyId: frontend.authorization.register-menu-transform
  - strategyId: backend.authorization.register-interceptor

additions:
  - id: authorization.role-page
    source: frontend/src/pages/System/AuthorizationManagementPage/index.tsx
    target: frontend/src/pages/System/AuthorizationManagementPage/index.tsx
    maintainPolicy:
      mode: NO_OP

  - id: authorization.auth-provider
    source: frontend/src/providers/AuthProvider.tsx
    target: frontend/src/providers/AuthProvider.tsx
    maintainPolicy:
      mode: NO_OP

validators:
  - validatorId: authorization.postcondition
  - validatorId: frontend.build
  - validatorId: backend.test
```

本轮明确：

```text
authorization.auth-provider
→ CREATE 时作为 Addition 安装
→ MAINTAIN 固定 NO_OP
```

原因不是“永远不允许维护”，而是当前没有一个已经冻结、可由 DevAgent Studio Atomic Executor 精确执行的 AuthProvider 维护规则。V2 不允许为了保留旧设计而继续声明空参数 `TRANSFORM_FILE`。未来如确有维护需求，必须先定义新的明确 Atomic Strategy，再将该 Addition 的 `maintainPolicy` 升级为 `STRATEGY`。

### 5.2 StrategyRegistryV2 是 Wire Atomic Strategy 的唯一事实源

StrategyRegistryV2 只保存以下事实：

```text
strategyId
targetId
Wire Type
Internal order
Wire parameters / inline content / payload source
```

Capability Metadata 只引用 `strategyId`。

V2 固定采用：

```text
一个 strategyId
=
一个 Registry Strategy Entry
=
一个 StrategyDescriptorV2
```

禁止：

```text
一个高阶 Registry Strategy
→ 运行时隐式拆成多个 Wire Strategy

一个 strategyId
→ 根据 target / 文件后缀 / Workspace 状态选择不同 Wire Type

RENDER_EXTENSION
→ 运行时猜测应该拆成 ENSURE_IMPORT + Provider / Route / Menu / Interceptor
```

如果一个逻辑动作需要多个原子动作，例如 Provider 注册需要“先 import，再追加 Provider”，则 Registry 必须显式定义两个稳定 `strategyId`：

```yaml
strategies:
  - id: frontend.authorization.import-provider
    targetId: frontend.capability-providers
    type: ENSURE_IMPORT
    order: 190
    parameters:
      importStatement: "import { AuthProvider } from '@/providers/AuthProvider'"

  - id: frontend.authorization.register-provider
    targetId: frontend.capability-providers
    type: TEXT_ANCHOR_INSERT
    order: 200
    parameters:
      anchor: "// devagentstudio:capability-providers"
      position: before
      managedMarker: "authorization-provider"
      content: |-
        /* devagentstudio:authorization-provider:begin */
        AuthProvider,
        /* devagentstudio:authorization-provider:end */
```

因此 WireStrategyCompiler 只负责：

```text
读取已确定 Registry Entry
→ resolve target
→ resolve parameters / inline content / payload
→ stable sort
→ assign index
→ StrategyDescriptorV2
```

不得承担隐藏的 1:N DSL 编译职责。

### 5.3 旧 Renderer Registry 只允许作为一次性迁移输入

V2 Runtime Registry 不允许继续保存：

```text
RENDER_EXTENSION
TRANSFORM_FILE
point: frontend.providers
point: frontend.root-routes
point: frontend.page-routes
point: frontend.page-wrappers
point: frontend.menu-hooks
point: backend.spring-interceptors
```

上述字段仅用于把当前旧 Registry **一次性迁移**为 Wire Atomic Entry，迁移完成后运行时不得再解释它们。

当前迁移关系固定为：

| 旧聚合语义 | V2 Atomic Strategy |
| --- | --- |
| `frontend.providers` | `ENSURE_IMPORT` + `TEXT_ANCHOR_INSERT` 到 Provider Registry |
| `frontend.root-routes` | `ENSURE_IMPORT` + `TEXT_ANCHOR_INSERT` 到 Root Route Registry |
| `frontend.page-routes` | `ENSURE_IMPORT` + `TEXT_ANCHOR_INSERT` 到 Page Route Registry |
| `frontend.page-wrappers` | `ENSURE_IMPORT` + `TEXT_ANCHOR_INSERT` 到 Wrapper Registry |
| `frontend.menu-hooks` | `ENSURE_IMPORT` + `TEXT_ANCHOR_INSERT` 到 `useCapabilityMenus` 顶层 Hook Anchor |
| `backend.spring-interceptors` | `TEXT_ANCHOR_INSERT` 到固定 `addInterceptors` 扩展面 |
| 内置 NPM dependency | 迁入 Base `package.json + pnpm-lock.yaml`，当前不再动态修改 |
| `authorization.reconcile-auth-provider` | 删除；`authorization.auth-provider` 改为 `MAINTAIN + NO_OP` |

这里选择 Anchor-based Atomic Strategy 是有意的：这些文件全部属于 Template Source 自有的 platform-managed integration surface，稳定 Anchor 比让跨语言 Generate/Update 同时复制任意 AST 变换更简单、更确定。`ENSURE_REACT_PROVIDER / ENSURE_ROUTE / ENSURE_INTERCEPTOR` 等结构化 Wire Type 仍然保留在协议中，可用于未来没有稳定 Anchor 的目标，但当前 login / authorization 不强制使用它们。

### 5.4 Base Extension Surface Contract

所有被 Capability `existingTargets` 引用的 Target 必须满足以下硬约束：

```text
1. 在“空 Capability”的 Base Project 中就已经存在
2. 文件语法有效，可以独立编译/解析
3. Strategy 所需 anchor 或 astSelector 在 pristine Base 上唯一命中
4. 执行前序 Strategy 后，后续 selector / anchor 仍保持唯一、稳定
5. Target 不能只在 Generate renderer 中临时合成
6. Target path 由 Target Registry 唯一解析，不允许根据 capability 动态猜路径
7. Platform-managed surface 不允许业务 Page Task / 普通 Coding Agent 改写协议骨架
```

这条约束将 `existingTargets` 的定义彻底固定为：

> **Capability 启用前就独立存在、并由 Base Template 提供的稳定集成面。**

如果目标只有 Capability 启用后才第一次出现，它必须是 Addition，而不是 existingTarget。

### 5.5 Frontend Provider Surface

Base 必须从第一天提供：

```tsx
import type { ComponentType, PropsWithChildren, ReactNode } from 'react';

type CapabilityProvider = ComponentType<{ children?: ReactNode }>;

export const capabilityProviders: CapabilityProvider[] = [
  // devagentstudio:capability-providers
];

export function CapabilityProviders({ children }: PropsWithChildren) {
  return capabilityProviders.reduceRight<ReactNode>(
    (current, Provider) => <Provider>{current}</Provider>,
    children,
  );
}
```

login / authorization 的 Provider 只做：

```text
ENSURE_IMPORT
+
TEXT_ANCHOR_INSERT(anchor = // devagentstudio:capability-providers)
```

按 dependency + Registry order，Provider 数组最终保持：

```text
GlobalContextProvider
→ AuthProvider
→ children
```

不再让 Strategy 去“重写 return JSX”或“包裹已有 JSX”。

### 5.6 Frontend Route / Wrapper Surface

Base 固定提供：

```tsx
import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';

type CapabilityPageWrapper = (
  element: ReactNode,
  page: PageRouteDefinition,
) => ReactNode;

export const capabilityRootRoutes: RouteObject[] = [
  // devagentstudio:capability-root-routes
];

export const capabilityPageRoutes: PageRouteDefinition[] = [
  // devagentstudio:capability-page-routes
];

export const capabilityPageWrappers: CapabilityPageWrapper[] = [
  // devagentstudio:capability-page-wrappers
];

export const capabilityEntryPath: string | undefined =
  capabilityRootRoutes.find((route) => route.handle?.capabilityEntry)?.path;

export const wrapCapabilityPage = (
  element: ReactNode,
  page: PageRouteDefinition,
): ReactNode => capabilityPageWrappers.reduceRight(
  (current, wrapper) => wrapper(current, page),
  element,
);
```

Root Route 使用 `RouteObject.handle.capabilityEntry` 标识默认 Capability 入口；不再单独维护一个会被多方覆盖的 `capabilityEntryPath` 常量。

当前 Atomic 映射：

```text
Login / Logout Root Route
→ ENSURE_IMPORT + TEXT_ANCHOR_INSERT(root-routes anchor)

Authorization Page Route
→ ENSURE_IMPORT + TEXT_ANCHOR_INSERT(page-routes anchor)

Login / Authorization Page Wrapper
→ ENSURE_IMPORT + TEXT_ANCHOR_INSERT(page-wrappers anchor)
```

Wrapper 数组按确定性顺序追加，`reduceRight` 保持与旧 renderer 相同的包装语义。

### 5.7 Frontend Menu Surface

Menu Transform 可能本身是 React Hook（当前 `useAuthorizationMenuTransform` 内部会调用 `usePermission / useMemo`），因此**不能**放入数组后通过 `reduce / loop` 动态调用，否则会违反 Rules of Hooks。

Base 固定提供静态 Hook 插入面：

```tsx
import type { Route } from '@/typings/workbench';

export const useCapabilityMenus = (menus: Route[]): Route[] => {
  let current = menus;
  // devagentstudio:capability-menu-hooks
  return current;
};
```

authorization 当前菜单能力使用：

```text
ENSURE_IMPORT
+
TEXT_ANCHOR_INSERT(anchor = // devagentstudio:capability-menu-hooks)
```

插入内容固定为静态、无条件的顶层 Hook 调用，例如：

```tsx
/* devagentstudio:authorization-menu-transform */
current = useAuthorizationMenuTransform(current);
```

这样每个 Capability Hook 调用都在 `useCapabilityMenus` 这个 custom hook 的顶层按固定源码顺序执行，不进入条件分支、循环或动态函数数组。

不再整体重写 `useCapabilityMenus` 函数，也不使用 `capabilityMenuTransforms.reduce(...)`。

### 5.8 Backend WebMvc Surface

`CapabilityWebMvcConfiguration.java` 必须迁入 Base Template，不能再由 Generate renderer 在启用 Capability 后临时生成。

推荐固定骨架：

```java
package com.cmbchina.backend.common.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CapabilityWebMvcConfiguration implements WebMvcConfigurer {
    private final ApplicationContext applicationContext;

    public CapabilityWebMvcConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // devagentstudio:capability-interceptors
    }
}
```

当前 login / authorization interceptor 本身已经是 Spring `@Component`，因此 Atomic Strategy 不需要修改字段或构造器，只需在固定 Anchor 前追加注册语句，并直接用 FQCN 获取 Bean：

```java
/* devagentstudio:login-current-user */
registry.addInterceptor(applicationContext.getBean(
        com.cmbchina.backend.auth.common.interceptor.UserWebMvcInterceptor.class))
    .order(100)
    .addPathPatterns("/**")
    .excludePathPatterns("/api/login/mock");
```

authorization 同理，使用独立 marker 与 order。

### 5.9 Base NPM Dependency / Lockfile Contract

DevAgent Studio 当前 `NPM_BUILD / NPM_TEST` 会先执行：

```text
pnpm install --frozen-lockfile --ignore-scripts
```

因此 V2 当前必须保证：

```text
Base frontend/package.json
+
Base frontend/pnpm-lock.yaml
```

始终同步。

当前内置 login / authorization 所需 NPM 依赖必须全部预置在 Base；现有 Registry 中唯一动态引入的 `ahooks ^3.8.1` 迁入 Base `package.json`，并使用固定 `pnpm@11.9.0` 重新生成、提交 `pnpm-lock.yaml`。

当前内置 Capability 不再输出 `ENSURE_NPM_DEPENDENCY`。

`ENSURE_NPM_DEPENDENCY` Wire Type 保留，但在当前 V2 Release 中只有满足以下任一条件时才允许注册：

```text
1. 依赖已经存在于 Base package.json + lockfile，此 Strategy 实际只会 no-op
或
2. 后续协议新增 lockfile-aware 的确定性更新方案
```

在没有 lockfile-aware 协议前，禁止通过“只改 package.json + frozen install”伪装为完整依赖更新。

### 5.10 Surface Release Gate

Template Release 在加载/发布前必须执行 Surface Contract Test：

```text
每个 existingTarget.target 文件在 Base 中存在
每个 TEXT_ANCHOR_INSERT.anchor 在 pristine Base 中恰好出现一次
每个 TEXT_ANCHOR_INSERT 的 managedMarker 在其 content/payload 中恰有一对 begin/end 边界
每个 ENSURE_IMPORT target 至少存在可定位 import 区
每个 structural astSelector 用 DevAgent Studio 当前 parser 在 Base 上唯一命中
按最终顺序执行所有当前 Capability Strategy 后仍可重复执行且全部 no-op
所有 managed marker / insertion content 唯一
每个 Migration Asset 的 deployment target 与 bootstrapConsumerPath 完全一致，execution trigger 可解析
```

任何一项失败，Template Release 本身无效，不允许等到真实 Workspace 执行阶段再发现。

---

## 6. Config 与依赖解析

统一 Capability State：

```json
{
  "enabled": true,
  "config": {}
}
```

Config 规则：

```text
显式 requested config
>
Capability defaultConfig
```

依赖自动引入：

```text
authorization
requires login
```

则：

```text
requested:
  authorization

effective:
  login
  authorization
```

执行顺序按：

```text
dependency topology
→ capabilityId stable order
```

Config 不允许在 normalize 阶段丢失。

---

## 7. Reconcile Reason 与 Mode 语义

Service 判断：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
HEALTH_REPAIR
NO_CHANGE
```

### 7.1 APPLY

`APPLY` 是**正常 Template Preparation 的默认模式**，允许改变 TemplateState，可处理：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
Addition CREATE
以及无变化时的 NO_CHANGE → 204
```

即使 requestedConfig 与当前 `TemplateState.requested` 完全相同，正常调用也仍然使用 `APPLY`。是否存在 Release Refresh 必须由 Template Service 根据当前不可复用的 `templateRevision` 判断，而不能由 DevAgent Studio 仅根据 requestedConfig 猜测。

### 7.2 RECONCILE

`RECONCILE` 只表示**显式健康修复**：

> 对当前 State 所描述的 effective Capability 重放幂等维护 Strategy 与后置条件，但不得改变 TemplateState 的任何语义内容。

因此必须满足：

```text
requested unchanged
effective unchanged
templateRevision unchanged
appliedAdditions unchanged
currentStateDigest == nextStateDigest
```

`RECONCILE` 可以：

```text
重放 Existing Target Strategy
重放 MAINTAIN + STRATEGY
重放 Validation Plan
```

但不能：

```text
ENABLE 新 Capability
修改 Config
执行 Release Refresh State Commit
新增 appliedAdditions
```

如果显式 `RECONCILE` 发现当前 Template Release 已要求 State 变化，Service 必须 fail-closed：

```text
HTTP 409
code = RECONCILE_STATE_CHANGE_REQUIRED
```

调用方不得在同一个 RECONCILE Attempt 内隐式升级为 APPLY。

### 7.3 RECONCILE Validation 不变式

RECONCILE Package 除 State digest 不变外，还必须为 `nextTemplateState.effective` 中的**每一个 Capability**提供至少一个：

```text
CAPABILITY_POSTCONDITION
```

且 `checks` 非空。完整结构见第 14 节。

### 7.4 DevAgent Studio Mode 选择规则

当前 DevAgent Studio 中“requestedConfig 与 State.requested 相同 → 自动 RECONCILE”的推导必须删除。

最终规则固定为：

```text
正常新建 / 正常 Template Preparation / 配置更新 / Release 检查
→ APPLY

用户或平台明确发起健康修复
→ RECONCILE
```

因此正常链路具备以下确定行为：

```text
相同 config + 相同 release + APPLY
→ 204 NO_CHANGE

相同 config + 新 release + APPLY
→ RELEASE_REFRESH Package

显式 RECONCILE + 相同 release
→ health repair Package

显式 RECONCILE + 新 release
→ RECONCILE_STATE_CHANGE_REQUIRED
```

### 7.5 NO_CHANGE

只有 `APPLY` 在以下条件全部成立时返回：

```text
无 ENABLE
无 CONFIG_CHANGE
无 RELEASE_REFRESH
无其他 State 变化
```

结果：

```http
204 No Content
```

`RECONCILE` 不因 State 无变化自动返回 204；它的调用目的就是显式健康修复。

---

## 8. Capability Removal

V2 当前不支持 Capability Removal。

若：

```text
current effective - target effective != empty
```

返回：

```text
CAPABILITY_REMOVAL_UNSUPPORTED
```

Service 不生成：

```text
DELETE_FILE
```

也不生成逆向 Strategy。

---

## 9. Addition 生命周期

Addition 使用稳定：

```text
additionId
```

Service 根据：

```text
TemplateState.appliedAdditions
```

分类：

```text
不存在
→ CREATE

已存在
→ MAINTAIN
```

### 9.1 CREATE

CREATE 生成 Wire Strategy：

```json
{
  "strategyId": "authorization.role-page",
  "index": 0,
  "schemaVersion": 1,
  "type": "ADD_FILE",
  "target": "frontend/src/pages/System/AuthorizationManagementPage.tsx",
  "precondition": {},
  "parameters": {},
  "payloadRef": "payload/additions/authorization.role-page"
}
```

注意：

- `precondition` 在 Wire Contract 中固定为 JSON object，禁止继续返回字符串 `"TARGET_MUST_NOT_EXIST"`。
- 当前 ADD_FILE 的 retry-safe 行为由 DevAgent Studio 根据“目标不存在 / 已存在且内容相同 / 已存在且内容冲突”三分支执行。
- Service 不读取 Workspace，因此不在服务端判断目标是否存在。
- 当前 DevAgent Studio `ADD_FILE` executor 的执行事实只依赖 `target + payloadRef`；`parameters` 当前固定输出 `{}`，Addition identity 只属于 Service Decision/State，不复制成执行 DSL。
- `precondition` 当前固定输出 `{}`；现行 Executor 不消费 Service 私有 precondition DSL。

### 9.2 MAINTAIN

Service 不把 Addition Source 当作覆盖当前文件的完整目标态。

MAINTAIN 行为必须由：

```text
Addition Metadata.maintainPolicy
```

唯一确定：

```text
NO_OP
或
STRATEGY
```

Decision Engine 不允许自行选择。

---

## 10. Addition MAINTAIN 确定性契约

### 10.1 NO_OP

语义：

```text
Addition 已安装后
Service 不再为该 Addition 本身生成修改 Strategy
```

因此：

```text
MAINTAIN + NO_OP
→ 不产生 Addition Strategy
→ candidate nextState 保持该 additionId
```

文件健康由 `CAPABILITY_POSTCONDITION` 或 Capability 的其他 existingTarget Strategy 负责。

当前 login / authorization 从旧模板迁移得到的**文件型 Addition 基线全部使用 NO_OP**。其中 `authorization.auth-provider` 也固定为 NO_OP，不再保留 `frontend.authorization.reconcile-auth-provider`。

### 10.2 STRATEGY

`STRATEGY` 保留为未来扩展能力，其语义固定为：

```text
Addition 已安装后
每次该 Capability 被本次 Decision 选中
都使用 maintainPolicy.strategyId 对应的唯一 Wire Atomic Strategy
```

前提是该维护行为可以被当前 Wire Type 完整、无歧义地表达。

如果不能表达：

```text
不得退回 TRANSFORM_FILE
不得把 Service 私有 transform 名称塞进 parameters
不得由 Decision Engine 猜测
```

而应继续使用 `NO_OP`，或在双端协议明确升级后再引入新的维护 Strategy。

### 10.3 Schema 硬约束

每个 Addition：

```text
maintainPolicy 必填
```

规则：

```text
mode=NO_OP
→ 不允许 strategyId
→ 不允许 order

mode=STRATEGY
→ strategyId 必填
→ strategyId 必须存在于 StrategyRegistryV2
→ strategyId 必须唯一对应一个受支持 Wire Atomic Strategy
→ 不允许在 maintainPolicy 中重复定义 order
```

Strategy 顺序唯一由 StrategyRegistryV2 决定。

禁止：

```text
maintainPolicy 缺失 → Service 猜测
maintainPolicy 缺失 → 默认 NO_OP
source 变化 → 自动覆盖 Workspace
target 后缀 → 猜 Strategy Type
一个 strategyId → 隐式拆成多个 Wire Strategy
maintainPolicy.order 与 Registry.order 双重事实源
```

### 10.4 Source 演进

```text
CREATE
→ 使用当前 Release source 形成 payload

MAINTAIN + NO_OP
→ 不使用 source

MAINTAIN + STRATEGY
→ 使用固定 strategyId
→ 由 Registry 决定唯一 Wire Strategy
→ 不把 source 当作完整目标文件覆盖
```

未来如果需要“重新以 Source 为基准维护”，必须新增显式 Policy / Wire 能力，不能复用现有 STRATEGY 语义。

---

## 11. Addition Identity

固定：

```text
Addition Identity
=
(additionId, capabilityId, target)
```

如果同一 `additionId` 在新 Release 中修改：

```text
capabilityId
或
target
```

返回：

```text
ADDITION_IDENTITY_CHANGED
```

V2 当前不支持：

```text
Move
Delete old target
Rename
silent retarget
```

---

### 11.1 Migration Asset 与 Execution Contract

Migration 不是普通 Addition 的别名，也不能只把 V1 `migrations:` 字段迁入 V2。V2 必须把以下两个责任显式拆开：

```text
Migration Asset Deployment
→ 将 SQL（或其他 migration 资产）投放到确定的 Workspace target，形成可审计的 Package payload / ADD_FILE 意图

Migration Execution
→ 不属于 Strategy Executor，也不记录在 TemplateStateV2；由 Capability 自己的 Bootstrap 显式消费已投放资产
```

当前已核实的旧链路存在路径断裂：

```text
Authorization Bootstrap 实际读取
→ backend/docs/auth/sql/ddl.sql

V1 migration 声明曾投放
→ backend/src/main/resources/devagentstudio/migrations/authorization/001-schema.sql
```

两者不是同一路径。因此，V2 Migration Registry 必须为每一项资产同时声明并在加载时校验：

```text
migrationId
capabilityId
asset source
deployment target
bootstrapConsumerPath
execution trigger / phase
```

硬约束：

```text
deployment target == bootstrapConsumerPath
→ 才允许发布该 Template Release

任一字段缺失、目标与消费路径不一致、执行时机无法解析
→ MIGRATION_CONTRACT_INVALID，fail-closed
```

资产投放与执行时机的幂等性也分别归属：

```text
Asset Deployment
→ 由 Package / Executor 的文件策略保证

Migration Execution
→ 由 Bootstrap / DevAgent Studio 的显式执行协议保证
```

不得用“SQL 文件已被投放”推断“SQL 已执行”，也不得由 Service 猜测 Bootstrap 应扫描的目录或执行时机。

当前 `StrategyUpdatePackageV2` 未定义通用的 Migration Execution Wire 字段或 Wire Type。因此：

```text
Migration Execution Contract
→ 只能作为 Template Release / Bootstrap 的已冻结配置在 Release load 时校验
→ 不得临时塞入 StrategyDescriptorV2.parameters、diagnostics 或 Service 私有 ZIP 文件

Update Package
→ 只负责已声明 Migration Asset 的投放
```

若未来需要由 Update Package 动态改变执行时机，必须先扩展 DevAgent Studio `StrategyUpdatePackageV2`，再同步修改本方案；不得绕过当前唯一 Wire Contract。

Authorization 当前冻结映射：

```text
001-schema.sql
→ Schema Asset
→ ADD_FILE target = backend/docs/auth/sql/ddl.sql
→ AuthorizationBootstrapCommand 默认 DDL consumer 显式读取

002-initialization.sql
→ V1 静态初始化遗留
→ 不迁入 V2 Migration Asset
→ 不投放、不执行、不写入 TemplateStateV2
→ 初始角色、资源与管理员数据由基于 TechnicalPlan / Application 的动态 Bootstrap 完整承担
```

---

## 12. Modification Strategy Wire Contract

### 12.1 唯一允许的 Wire Type

固定为 DevAgent Studio 当前 `StrategyTypeV2`：

```text
ADD_FILE
TEXT_ANCHOR_INSERT
ENSURE_IMPORT
ENSURE_NPM_DEPENDENCY
ENSURE_MAVEN_DEPENDENCY
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
```

禁止作为 Wire Type：

```text
TRANSFORM_FILE
RENDER_EXTENSION
任意脚本类型
任意未注册字符串
```

### 12.2 StrategyDescriptorV2

每条 Strategy 固定结构：

```json
{
  "strategyId": "frontend.authorization.import-provider",
  "index": 0,
  "schemaVersion": 1,
  "type": "ENSURE_IMPORT",
  "target": "frontend/src/generated/capabilityProviders.tsx",
  "precondition": {},
  "parameters": {
    "importStatement": "import { AuthProvider } from '@/providers/AuthProvider'"
  },
  "payloadRef": null
}
```

字段语义：

```text
strategyId
→ Package 内唯一逻辑标识

index
→ 最终全局执行顺序，必须从 0 开始连续

schemaVersion
→ 当前固定为 1

type
→ 受支持的 StrategyTypeV2

target
→ Workspace 相对路径

precondition
→ JSON object；不得输出字符串或隐式 DSL

parameters
→ 对应 Strategy Type 的严格参数；行为语义固定见 12.5

payloadRef
→ 可选；若存在必须指向 payloadManifest 中的条目
```

### 12.3 Service 内部 order 与 Wire index

Registry 中可以保留：

```text
order
```

但它不是 Wire 字段。

Service 最终必须先完成确定性排序：

```text
dependency topology
→ capability stable order
→ registry order
→ strategyId
```

然后将排序结果编译为：

```text
index = 0, 1, 2, ... N-1
```

DevAgent Studio 不再根据 `order` 二次排序。

### 12.4 Strategy Id 唯一性

同一个 Package：

```text
strategyId 不得重复
index 不得跳号
index 必须与数组顺序一致
```

同时，一个 `strategyId` 在 Registry 中只能描述一个 Wire Atomic Strategy，不得在不同场景切换 Wire Type。

### 12.5 Strategy Type Parameter Contract

虽然 `StrategyDescriptorV2.parameters` 在 Wire DTO 中是 JSON object，但其字段并不是开放扩展点。V2 固定以 DevAgent Studio 当前 executor 的实际消费行为作为参数契约。

#### 12.5.1 `ADD_FILE`

必须：

```text
payloadRef != null
payloadRef 存在于 payloadManifest
```

执行语义：

```text
目标不存在
→ 创建

目标已存在且内容 == payload
→ retry-safe no-op

目标已存在且内容 != payload
→ ADDITION_TARGET_CONFLICT
```

`ADD_FILE` 当前不依赖 `parameters.content`，也不依赖 Service 私有 `precondition` DSL 决定上述三分支。Template Service 当前固定输出 `parameters: {}`。

#### 12.5.2 `TEXT_ANCHOR_INSERT`

参数：

```text
anchor: 非空字符串，目标文件中必须唯一
position: before | after，可省略，默认 before
managedMarker: 非空受管块标识；只允许 [a-z0-9][a-z0-9-]*
```

插入内容必须且只能来自一个来源：

```text
parameters.content
XOR
payloadRef
```

两者同时存在或同时不存在均拒绝。

`content`（或 payload 内容）必须包含且只包含一对完整边界：

```text
/* devagentstudio:<managedMarker>:begin */
... managed content ...
/* devagentstudio:<managedMarker>:end */
```

DevAgent Studio Executor 的冻结语义：

```text
目标中不存在该 begin/end 块
→ anchor 唯一命中后插入完整受管块

目标中恰有一对完整且匹配的 begin/end 块
→ 相同内容：no-op
→ 内容变化：原位替换该受管块

begin/end 缺失、孤立、不匹配，或同一 managedMarker 出现多对
→ fail-closed
```

因此“完整 insertion 文本已存在”的 substring 判断只能作为上述**相同受管块**的实现细节，不能作为唯一幂等语义；它不能解决 Release Refresh 中内容变化导致的重复插入。

该替换语义只适用于已经带有 V2 受管边界的 Workspace。旧 V1 markerless insertion 不得被自动认领或以 substring 推断所有权；它属于既有的 pre-cutover Workspace 重新 Generate 范围，执行 Release Refresh 时必须 fail-closed。

#### 12.5.3 `ENSURE_IMPORT`

参数：

```text
importStatement: 非空字符串
```

当前执行器直接消费 `importStatement`，不通过 payload 生成 import。

#### 12.5.4 `ENSURE_NPM_DEPENDENCY`

参数：

```text
name: 非空字符串
version: 非空字符串
section: dependencies | devDependencies，可省略，默认 dependencies
```

旧字段：

```text
dependency
```

不是 V2 Wire 参数，禁止继续输出。

当前内置 Capability 不使用动态 `ENSURE_NPM_DEPENDENCY`；其 NPM 依赖已按第 5.9 节迁入 Base `package.json + pnpm-lock.yaml`。该 Wire Type 保留给未来 lockfile-aware 场景。

#### 12.5.5 `ENSURE_MAVEN_DEPENDENCY`

参数：

```text
groupId: 非空字符串
artifactId: 非空字符串
version: 非空字符串
```

DevAgent Studio 对相同 `groupId + artifactId` 已存在但版本不同的情况 fail-closed。

#### 12.5.6 五类结构化 Strategy

适用于：

```text
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
```

必须提供：

```text
managedMarker: 非空字符串
astSelector: object
```

插入内容必须且只能来自：

```text
parameters.content
XOR
payloadRef
```

并且插入内容本身必须包含 `managedMarker`。

`astSelector` 固定结构：

```json
{
  "nodeType": "<non-empty Tree-sitter node type>",
  "position": "before",
  "name": "<optional non-empty AST name>"
}
```

`position` 只允许：

```text
before
after
beforeEnd
```

`name` 可省略；若提供必须为非空字符串。Selector 必须在目标文件 AST 中**唯一命中一个节点**，否则 DevAgent Studio fail-closed。

禁止：

```text
kind: react-provider-root
kind: route-root
Service 私有 selector 名称
依赖文件后缀猜 selector
```

### 12.6 Producer 端参数门禁

Template Service 必须在 Package Builder 之前对 Registry 产物做 per-type 参数校验，不能依赖 DevAgent Studio 执行到一半才发现参数错误。

原则：

```text
Service validation
与
DevAgent Studio executor validation
使用同一冻结语义
```

若参数无法满足 12.5，Package 构建 fail-closed，不返回半包。

### 12.7 Parameters Exact Allow-list

`parameters` 虽然在 Pydantic DTO 中是 `dict[str, Any]`，但 Template Service Producer 必须对每个 type 使用**精确字段 allow-list**。

例如：

```text
ADD_FILE
→ {}

TEXT_ANCHOR_INSERT
→ anchor, position?, managedMarker, content?；content XOR payloadRef

ENSURE_IMPORT
→ importStatement

ENSURE_NPM_DEPENDENCY
→ name, version, section?

ENSURE_MAVEN_DEPENDENCY
→ groupId, artifactId, version

结构化 ENSURE_*
→ managedMarker, astSelector, content?；content XOR payloadRef
```

未知参数不得静默透传，避免 Service 与 Executor 对“同名 Strategy”的行为理解逐渐漂移。

### 12.8 Precondition 当前冻结语义

虽然 Wire DTO 保留：

```json
"precondition": {}
```

但当前 DevAgent Studio Executor 不消费任何 precondition DSL，因此 Template Service V2 当前统一输出空对象：

```text
precondition = {}
```

禁止输出：

```text
TARGET_MUST_NOT_EXIST
TARGET_HASH_EQUALS
任意 Service 私有 precondition key
```

未来如要启用 precondition，必须先修改 DevAgent Studio Executor / Validator 并重新冻结双端 Contract。

### 12.9 当前内置 Strategy 的 Content Policy

为降低 payload 数量和 Generate / Update 漂移，当前 Base integration surface 的小型代码片段统一使用：

```text
parameters.content
```

`payloadRef` 当前主要用于：

```text
ADD_FILE
```

Wire Contract 仍允许结构化 Strategy / TEXT_ANCHOR_INSERT 使用 payloadRef，但当前 login / authorization Registry 不混用两种风格。


---

## 13. Strategy 执行责任

Service 只定义：

```text
要实现什么结构
```

DevAgent Studio 负责：

```text
读取当前 Workspace 文件
Working Copy
AST / JSON / XML / Text 修改
幂等判断
冲突检测
最终 Apply
失败恢复
```

同一路径允许有多个 Strategy；DevAgent Studio 必须严格按 Package `index` 串行作用于同一 Working Copy。

---

## 14. Validation Plan V2 与 ValidatorRegistryV2

Validation Plan 不再返回：

```json
{
  "validators": []
}
```

Wire Contract 固定为：

```text
validationPlan: ValidationPlanItemV2[]
```

### 14.1 支持的 Validation Type

```text
CAPABILITY_POSTCONDITION
FILE_EXISTS
STRUCTURE_CHECK
JSON_STRUCTURE_CHECK
NPM_BUILD
NPM_TEST
MAVEN_TEST
MAVEN_PACKAGE
```

### 14.2 ValidationPlanItemV2 基础规则

```text
validationId 唯一
index 从 0 开始连续
blocking 必填
timeoutSeconds > 0
```

Type 规则：

```text
FILE_EXISTS
→ path 必填
→ executionMode = REAL_WORKSPACE

STRUCTURE_CHECK
→ path + containsAll 必填
→ executionMode = REAL_WORKSPACE

JSON_STRUCTURE_CHECK
→ path + pointer 必填
→ executionMode = REAL_WORKSPACE

CAPABILITY_POSTCONDITION
→ capabilityId + 非空 checks 必填
→ executionMode = REAL_WORKSPACE

NPM_BUILD / NPM_TEST / MAVEN_TEST / MAVEN_PACKAGE
→ 使用 workingDirectory
→ executionMode = SANDBOX
→ 不携带 path / containsAll / pointer / checks
```

Service 只生成计划，不执行；DevAgent Studio 是唯一 Validation Executor。

### 14.3 RECONCILE 的 Capability Postcondition 硬约束

当：

```text
mode = RECONCILE
```

必须满足：

```text
nextTemplateState.effective 中的每个 capabilityId
⊆
validationPlan 中 type=CAPABILITY_POSTCONDITION 的 capabilityId 集合
```

每个 effective Capability 至少存在一个 `CAPABILITY_POSTCONDITION`，且 `checks` 非空。

当前建议至少检查：

```text
Capability 关键 Addition 存在
Base integration surface 中包含对应 managed marker / insertion snippet
必要 JSON 配置存在
```

只有 build/test 而没有 Capability Postcondition 的 RECONCILE Package 非法。

### 14.4 ValidatorRegistryV2 收口为 Atomic Validation Registry

当前旧 Registry 中：

```yaml
{ scope: frontend }
{ scope: backend }
```

不能继续作为 V2 Runtime Validator DSL。

最终一个 `validatorId` 必须唯一映射到一个 `ValidationPlanItemV2` Template，例如：

```yaml
validators:
  - id: login.postcondition
    type: CAPABILITY_POSTCONDITION
    order: 100
    capabilityId: login
    workingDirectory: .
    blocking: true
    timeoutSeconds: 30
    executionMode: REAL_WORKSPACE
    checks:
      - type: FILE_EXISTS
        path: frontend/src/pages/Login/index.tsx
      - type: STRUCTURE_CHECK
        path: frontend/src/generated/capabilityProviders.tsx
        containsAll:
          - devagentstudio:login-provider

  - id: authorization.postcondition
    type: CAPABILITY_POSTCONDITION
    order: 110
    capabilityId: authorization
    workingDirectory: .
    blocking: true
    timeoutSeconds: 30
    executionMode: REAL_WORKSPACE
    checks:
      - type: FILE_EXISTS
        path: frontend/src/pages/System/AuthorizationManagementPage/index.tsx
      - type: STRUCTURE_CHECK
        path: frontend/src/generated/capabilityRoutes.tsx
        containsAll:
          - devagentstudio:authorization-page-route

  - id: frontend.build
    type: NPM_BUILD
    order: 900
    workingDirectory: frontend
    blocking: true
    timeoutSeconds: 300
    executionMode: SANDBOX

  - id: backend.test
    type: MAVEN_TEST
    order: 910
    workingDirectory: backend
    blocking: true
    timeoutSeconds: 300
    executionMode: SANDBOX
```

共享 `frontend.build / backend.test` 可以被多个 Capability Metadata 引用；Validation Compiler 按 `validatorId` 去重后只执行一次。

`CAPABILITY_POSTCONDITION` 不共享，每个 Capability 使用自己唯一的 `validatorId + capabilityId`。

### 14.5 Validation 确定性排序

Validation Compiler 固定流程：

```text
收集被本次 Package 需要的 validatorId
→ 按 validatorId 去重
→ order
→ validatorId
→ index = 0..N-1
```

RECONCILE 必须额外确认所有 effective Capability 的 postcondition 已被收集。

### 14.6 NPM Frozen-lock 硬约束

DevAgent Studio 对 `NPM_BUILD / NPM_TEST` 固定先执行：

```text
pnpm install --frozen-lockfile --ignore-scripts
```

因此：

```text
frontend/pnpm-lock.yaml 必须存在
package.json 与 pnpm-lock.yaml 必须同步
```

这也是第 5.9 节把当前内置 NPM 依赖迁入 Base 的直接原因。

任何 Release 若修改 Base `package.json`，必须在同一个 Release 中更新并提交 `pnpm-lock.yaml`，否则 Release Contract Test 直接失败。

---

## 15. `/v1/update` Wire Contract

当前正式入口：

```text
POST /v1/update
Content-Type: application/json
Accept: application/zip
```

### 15.1 Request 固定结构

```json
{
  "protocolVersion": "2",
  "currentTemplateState": {
    "schemaVersion": 2,
    "templateRevision": "R3",
    "requested": {},
    "effective": {},
    "appliedAdditions": {}
  },
  "requestedConfig": {
    "capabilities": {}
  },
  "mode": "APPLY"
}
```

允许字段必须严格等于：

```text
protocolVersion
currentTemplateState
requestedConfig
mode
```

`protocolVersion`：

```text
必须为字符串 "2"
```

`mode`：

```text
APPLY
或
RECONCILE
```

未知字段一律拒绝。

### 15.2 Service 内部流程

```text
Validate protocolVersion
→ Validate TemplateStateV2
→ Validate requestedConfig
→ Resolve Config
→ Resolve Dependencies
→ Check Template Revision
→ Resolve Mode / Reconcile Reason
→ Removal Guard
→ Classify Additions
→ Resolve Wire Atomic Strategies
→ Validate per-type Strategy parameters
→ Build ValidationPlanItemV2[]
→ Build candidate nextTemplateState
→ Calculate State Digests
→ Build payloadManifest
→ Build StrategyUpdatePackageV2
→ Build immutable ZIP
```

### 15.3 NO_CHANGE

仅 `APPLY` 在没有任何变更时：

```http
204 No Content
```

不得返回空 ZIP。

### 15.4 CHANGE / RECONCILE

返回：

```http
200 OK
Content-Type: application/zip
```

ZIP 必须严格符合第 17 节。


### 15.5 Error Response Contract

非 2xx 响应固定使用现有扁平 JSON 结构：

```json
{
  "code": "RECONCILE_STATE_CHANGE_REQUIRED",
  "message": "...",
  "details": {},
  "traceId": "..."
}
```

当前至少冻结：

```text
400
→ BAD_REQUEST / TEMPLATE_STATE_SCHEMA_UNSUPPORTED / protocol metadata error

409
→ RECONCILE_STATE_CHANGE_REQUIRED
→ ADDITION_IDENTITY_CHANGED
→ CAPABILITY_REMOVAL_UNSUPPORTED

500
→ PACKAGE_BUILD_FAILED / INTERNAL_ERROR
```

DevAgent Studio `TemplateEngineClient` 对非 2xx 不得只保留 HTTP status；应解析上述结构并把 `code` 带入 `TemplateEngineError`，以便上层区分协议冲突和服务故障。

但本轮**不实现 RECONCILE → APPLY 自动 fallback**；模式由调用意图显式决定。

---

## 16. `/v1/update/plan`

预留接口，只用于未来：

```text
查询变更
预览变更
展示将影响哪些文件
展示 Capability 变化
```

它不是当前 DevAgent Studio 更新前置步骤。

未来如果开放：

```text
/v1/update/plan
```

必须复用：

```text
ReconcileDecisionEngine
Wire Strategy Compiler
```

但只返回 Read-only Preview。

真正执行 `/v1/update` 时必须重新计算 Decision。

---

## 17. StrategyUpdatePackageV2

### 17.1 ZIP 固定布局

唯一合法结构：

```text
update-package.zip
├── strategy-update-package.json
└── payload/
    └── **
```

其中 payload 可以为空。

明确废弃并禁止继续输出：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
change-set.json
```

### 17.2 `strategy-update-package.json` 固定结构

```json
{
  "protocolVersion": "2",
  "packageId": "pkg-20260911-001",
  "mode": "APPLY",
  "sourceRevision": "R3",
  "currentStateDigest": "sha256:...",
  "nextStateDigest": "sha256:...",
  "strategies": [],
  "validationPlan": [],
  "payloadManifest": {},
  "nextTemplateState": {
    "schemaVersion": 2,
    "templateRevision": "R4",
    "requested": {},
    "effective": {},
    "appliedAdditions": {}
  },
  "diagnostics": []
}
```

字段语义：

```text
protocolVersion
→ 固定 "2"

packageId
→ 单次 Package 唯一标识；可用于 Attempt 绑定

mode
→ APPLY | RECONCILE

sourceRevision
→ 请求 currentTemplateState.templateRevision

currentStateDigest
→ 请求 currentTemplateState 的 canonical digest

nextStateDigest
→ nextTemplateState 的 canonical digest

strategies
→ 最终 Wire Strategy 数组

validationPlan
→ 最终 ValidationPlanItemV2 数组

payloadManifest
→ ZIP payload 的完整 manifest

nextTemplateState
→ 仅在 Validation 成功后由 DevAgent Studio 提交的候选 State

diagnostics
→ 非执行事实的诊断信息；不得承载 Workspace 内容
```

### 17.3 Payload Manifest

结构：

```json
{
  "payload/authorization/role-page.tsx": {
    "size": 1234,
    "sha256": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

硬约束：

```text
payloadManifest key 集合
==
ZIP 中实际 payload/** 文件集合
```

每个 payload：

```text
size 必须匹配实际 bytes
sha256 必须匹配实际 bytes
```

Strategy 的 `payloadRef` 非空时：

```text
必须存在于 payloadManifest
```

不得存在：

```text
未声明 payload
声明但 ZIP 缺失 payload
payload SHA 不匹配
payload size 不匹配
```

### 17.4 Package Digest

`packageDigest` 不写入 `StrategyUpdatePackageV2`。

DevAgent Studio 在下载完成后对**整个 ZIP 原始 bytes**计算：

```text
sha256:<64 lowercase hex>
```

并将其作为 immutable Package / Attempt 恢复事实使用。

### 17.5 RECONCILE Package 不变式

当：

```text
mode = RECONCILE
```

必须同时满足：

```text
currentStateDigest == nextStateDigest
```

```text
nextTemplateState 与 current State 在语义上保持一致
```

以及：

```text
nextTemplateState.effective 中每个 capabilityId
都至少存在一个
validationPlan[type=CAPABILITY_POSTCONDITION, capabilityId=<same id>]
```

每个 `CAPABILITY_POSTCONDITION` 必须包含非空 `checks`。

因此以下 Package 即使 digest 相等仍然非法：

```text
RECONCILE
+
只有 NPM_BUILD / MAVEN_TEST
+
没有某个 effective Capability 的 CAPABILITY_POSTCONDITION
```

Service 必须在输出 ZIP 前完成此不变式校验，DevAgent Studio 仍会进行独立 strict validation。


### 17.6 Package Determinism 边界

“确定性输出”必须区分**语义确定性**和**单次传输身份**。

相同：

```text
Current State
+
Requested Config
+
Mode
+
Template Release
```

必须得到完全一致的：

```text
nextTemplateState
strategies（除 index 外内容及排序都一致，index 也由排序确定）
validationPlan
payload bytes
payloadManifest
```

当前 `diagnostics` 基线固定输出：

```json
[]
```

`packageId` 是单次 Response 的 opaque 唯一标识，可以不同；因此不要求两次相同请求的 ZIP bytes / packageDigest 完全相同。

`packageDigest` 只绑定一次已经下载的 immutable ZIP 与 Attempt，不能被当作“相同语义请求”的稳定业务 ID。

Builder 仍应固定 ZIP entry 排序与 timestamp，减少无意义字节差异，但这不是替代上述语义确定性的手段。

---

## 18. `/v1/generate` 与 Update 语义一致性

Generate 负责：

```text
Base 工程物化
+
Capability Addition 首次物化
+
同一 Atomic Strategy 的初始集成
+
TemplateState V2 Bootstrap
```

Generate 可以直接生成完整新工程，因为此时还不存在用户 Workspace 历史代码；但它不能因此继续保留另一套高阶 `RENDER_EXTENSION` renderer。

### 18.1 空 Capability 场景

即使：

```json
{
  "capabilities": {}
}
```

Generate 也必须成功生成：

```text
完整 Base Project
+
有效 TemplateStateV2
```

不得通过“空初始 State → Reconcile NO_CHANGE → nextTemplateState=null”路径生成。

Generate 必须先直接构造 canonical target State，再决定是否存在 Capability 物化动作。

### 18.2 GenerateMaterializerV2

当前 `V2ProjectGenerator` 中按 `point` 聚合并整体重写 Providers / Routes / Menus / WebMvc 的 renderer 必须移除。

新 Generate 路径固定为：

```text
Resolve requested/effective
→ Build canonical target TemplateStateV2
→ Copy Base
→ Copy Addition source
→ 从同一 StrategyRegistryV2 解析 Atomic Strategy
→ 使用与 Update 相同排序
→ GenerateMaterializerV2 应用当前 Release 支持的 Atomic Strategy
→ Validation / Contract Test
→ 写入 .devagentstudio/template-state.json
→ ZIP
```

`GenerateMaterializerV2` 不允许重新解释旧 `point/module/export/...` DSL；它消费的必须是与 Update 相同的 Atomic Registry Entry / 编译结果。

当前 login / authorization 已收口为 `ENSURE_IMPORT + TEXT_ANCHOR_INSERT` 的稳定 Base Surface，因此 Java 端可以实现简单、确定性的 in-memory materialization，而不需要复制 DevAgent Studio 的任意 AST Executor。

### 18.3 Generate / Update Golden Parity

必须增加跨仓库 Golden Test：

```text
pristine Base target
+
相同 Atomic StrategyDescriptorV2[]
+
相同 insertion content
```

分别执行：

```text
Java GenerateMaterializerV2
```

和：

```text
Python DevAgent Studio ModificationStrategyExecutorV2
```

对于当前 Release 实际使用到的 Strategy Type，相关 target 最终内容必须 byte-for-byte 一致。

任何未来 Capability 如果注册了 GenerateMaterializer 尚不支持的 Wire Type，Template Release load / CI 必须 fail-closed：

```text
GENERATE_STRATEGY_UNSUPPORTED
```

不能等到用户首次 Generate 时再失败。

### 18.4 Generate / Update 共享事实

Generate 与 Update 必须共享：

```text
Capability Definition
StrategyRegistryV2
ValidatorRegistryV2
Target Registry
Template Revision
requested / effective 语义
Addition Identity
Atomic Strategy content
排序规则
```

允许不同的是执行环境：

```text
Generate
→ Service 内 pristine Base tree

Update
→ DevAgent Studio current Workspace Working Copy
```

不允许存在第二套业务语义。

---

## 19. 双端协议与 Release 不变式

最终必须同时满足：

```text
Service OpenAPI
=
Service Runtime DTO
=
Service Package Builder
=
Service Strategy Parameter Validator
=
DevAgent Studio ProtocolV2 Model
=
DevAgent Studio Package Validator
=
DevAgent Studio Strategy Executor Semantics
```

同时：

```text
Base Extension Surface
=
StrategyRegistryV2 target / anchor contract
=
GenerateMaterializerV2 input contract
=
DevAgent Studio Update Executor input contract
```

Validator 同样满足：

```text
ValidatorRegistryV2
→ ValidationPlanItemV2
→ DevAgent Studio Validation Executor
```

任何一处 schema 或行为契约变化必须同步修改：

```text
Template Service Contract Test
+
DevAgent Studio Contract Test
+
Surface Contract Test
+
Generate/Update Golden Parity Test
+
Cross-repo E2E Test
```

不得只修改其中一端。

### 19.1 Release Revision 发布规则

Base Surface、Capability Metadata、Strategy Registry、Validator Registry、NPM lockfile 或其他任何影响 Template 行为的内容变化，都属于 Template Release 变化，必须提升 `templateRevision`。

同一个 `templateRevision` 不允许发布不同实现。

本轮实施应在所有代码与 metadata 收口完成后，**一次性 bump `template-source/template-revision.txt`**；不要在中间迭代阶段反复发布同 revision 的不同内容。

### 19.2 Pre-cutover Workspace 处理

当前 `template_refactor` 分支已经生成过基于旧 aggregate renderer 的 V2 Workspace。这些 Workspace 的 generated surface 并不满足本版稳定 Anchor Contract。

本轮把 Atomic Surface 视为一次 breaking pre-production cutover：

```text
旧 aggregate-renderer 开发 Workspace
→ 不做 silent Update migration
→ 重新 Generate / Bootstrap
```

第一版正式 Atomic Surface Release 之后，后续 Release 必须保持 Extension Surface 向后兼容；如果未来确需改变 Surface 形状，必须显式设计 Migration，而不能继续假设老 Workspace 自动兼容。

---

# 第二章 实施计划

本章是**实施前总回检后的最终执行清单**。下面列出的现状差距都是代码改造任务，不再是待讨论的架构问题。

当前代码已确认存在的实现差距包括：

```text
EngineController /v1/update 尚未接受 protocolVersion
PackageBuilder 仍输出旧四文件 ZIP
ReconcileDecisionEngine 仍生成 TRANSFORM_FILE / Service 私有参数
StrategyRegistryV2 仍包含 RENDER_EXTENSION / TRANSFORM_FILE
V2ProjectGenerator 仍使用 point-based aggregate renderer
Base 尚未稳定提供 CapabilityWebMvcConfiguration
Providers / Routes / Menus 尚未改成 Atomic Anchor Surface
Validator Registry 仍是 scope-based 高阶语义
Base 缺少 pnpm-lock.yaml
DevAgent Studio 仍会按 requested equality 自动选择 RECONCILE
TemplateEngineClient 非 2xx 时仍丢失 Engine error code
```

这些差距均已在第一章给出唯一目标形态，可以直接进入实现。

---

## TS-0：冻结双端 Contract 与 Golden Fixture

在修改生产链路前先落测试 fixture，冻结：

```text
TemplateStateV2
UpdateRequestV2
StrategyDescriptorV2
StrategyTypeV2
Strategy Type Parameter Contract
Precondition = {}
ValidationPlanItemV2
StrategyUpdatePackageV2
ZIP Layout
State Digest Algorithm
Error Response JSON
Base Extension Surface
Atomic Strategy mapping
Atomic Validator mapping
```

### TS-0.1 Contract Fixture

至少包含：

```text
UpdateRequestV2 fixture
TemplateStateV2 fixture
StrategyUpdatePackageV2 APPLY fixture
StrategyUpdatePackageV2 RECONCILE fixture
ADD_FILE payload fixture
每种 StrategyTypeV2 的最小合法/非法参数 fixture
每种 ValidationTypeV2 的最小合法/非法 fixture
TEXT_ANCHOR_INSERT：首次插入、相同内容重试、同 marker 内容刷新替换、边界缺失/重复拒绝 fixture
Migration Asset：资产投放路径、Bootstrap 消费路径、执行 trigger 解析与路径不一致拒绝 fixture
```

### TS-0.2 Cross-language State Digest Golden

固定：

```text
template-state-v2-golden.json
template-state-v2-golden.sha256
```

必须满足：

```text
Java Template Service digest
==
Python DevAgent Studio digest
==
expected golden digest
```

### TS-0.3 Surface Golden Fixture

固定 pristine Base Surface fixture：

```text
capabilityProviders.tsx
capabilityRoutes.tsx
capabilityMenus.ts
CapabilityWebMvcConfiguration.java
```

并保存 login / authorization Atomic Strategy 执行后的 expected 文件，用于 Generate/Update parity。

TS-0 全绿前，不进入生产 Controller / Package Builder 切换。

---

## TS-1：收敛 V2 Domain Model

保留：

```text
TemplateStateV2
CapabilityState
AppliedAdditionState
ReconcileReason
AtomicStrategyIntent
AtomicValidationIntent
UpdateResult / ReconcileDecision
```

删除 V2 Domain 中：

```text
managedFiles
origin
Workspace file content / SHA
TRANSFORM_FILE
RENDER_EXTENSION
Service-private precondition DSL
```

Domain 与 Wire 可以分层，但 Wire Compiler 必须唯一。

---

## TS-2：迁移 Capability / Strategy / Validator Metadata

实现：

```text
CapabilityV2Loader
CapabilityRegistryV2
TargetRegistryV2
StrategyRegistryV2
ValidatorRegistryV2
```

### TS-2.1 Strategy Atomic 化

当前旧 `point` Entry 一次性拆为多个 stable strategyId；一个 strategyId 只产生一个 Wire Strategy。

删除 Runtime：

```text
RENDER_EXTENSION
TRANSFORM_FILE
registryType
point/module/export 作为 Runtime DSL
```

当前 login / authorization integration 全部迁到：

```text
ENSURE_IMPORT
TEXT_ANCHOR_INSERT
```

### TS-2.2 Addition Metadata

硬约束：

```text
maintainPolicy 必填
NO_OP 不允许 strategyId/order
STRATEGY 只允许 strategyId，不允许 order
order 唯一来自 StrategyRegistryV2
```

当前 `authorization.auth-provider` 改为：

```yaml
maintainPolicy:
  mode: NO_OP
```

删除 `frontend.authorization.reconcile-auth-provider`。

### TS-2.3 Validator Atomic 化

删除 Runtime：

```text
scope: frontend
scope: backend
```

建立：

```text
login.postcondition
authorization.postcondition
frontend.build
backend.test
```

共享 build/test validator 按 validatorId 去重；Postcondition 按 Capability 唯一。

### TS-2.4 Metadata Load Gate

以下全部 fail-closed：

```text
duplicate additionId / strategyId / validatorId
unknown strategyId / validatorId / targetId
strategyId 多动作映射
strategyId 被多个 incompatible owner 重复发射
invalid per-type parameters
unknown parameters
maintainPolicy invalid
unsafe path
dependency cycle
existingTarget target 不在 Base
anchor 不唯一
```

---

## TS-2.5：重构 Base Atomic Extension Surface

这是本轮实施的必做前置，不是可选优化。

修改 Base：

```text
frontend/src/generated/capabilityProviders.tsx
frontend/src/generated/capabilityRoutes.tsx
frontend/src/generated/capabilityMenus.ts
backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java
```

使其严格符合第 5.5～5.8 节。

同时：

```text
frontend/package.json
→ 预置当前内置 Capability 所需依赖（含 ahooks ^3.8.1）

frontend/pnpm-lock.yaml
→ 使用 packageManager=pnpm@11.9.0 生成并提交
```

删除当前内置 `frontend.login.ensure-dependency` 的动态依赖 Strategy。

在 Template / AGENTS 管理说明中标记上述 integration surface 为 platform-managed，普通业务代码生成任务不得重写其骨架和 Anchor。

验收：

```text
empty capability Base 可 build
所有 anchor 在 pristine Base 唯一
重复 Atomic Strategy 执行 no-op
login + authorization 顺序执行后 build/test 可通过
```

---

## TS-3：重写 ReconcileDecisionEngine

输入：

```text
TemplateStateV2
RequestedConfig
Mode
Current Template Release
CapabilityRegistryV2
```

### TS-3.1 APPLY

顺序：

```text
normalize requested
→ dependency closure
→ removal guard
→ templateRevision
→ reasons
→ addition CREATE / MAINTAIN
→ selected capabilities
→ atomic strategy intents
→ validation intents
→ candidate nextTemplateState
```

无任何变化：

```text
APPLY → NO_CHANGE → 204
```

### TS-3.2 RECONCILE Early Gate

在生成任何 Strategy 之前先判断：

```text
requested 是否与 current.requested 相同
目标 effective 是否与 current.effective 相同
current templateRevision 是否与 State 相同
是否需要新增 appliedAdditions
```

只要任一项要求 State 变化：

```text
RECONCILE_STATE_CHANGE_REQUIRED
```

不得先生成“修复 Strategy”再构造变化后的 nextState。

### TS-3.3 Strategy 排序

最终排序键固定：

```text
dependency topology rank
→ capability stable order
→ phase（Addition CREATE 优先于 Registry Strategy）
→ registry order / stable additionId
→ strategyId
```

然后统一：

```text
index = 0..N-1
```

Decision Engine 不读取 Workspace。

---

## TS-4：实现 WireStrategyCompiler 与 Producer Gate

Compiler 固定为机械一对一：

```text
Atomic Strategy Intent
→ target path
→ type
→ exact parameters
→ precondition={}
→ inline content / payloadRef
→ StrategyDescriptorV2
```

Producer Gate 必须执行：

```text
Wire Type allow-list
per-type exact parameter allow-list
content XOR payloadRef
payloadRef existence
strategyId uniqueness
index continuity
safe target
TEXT_ANCHOR_INSERT managedMarker 及完整且唯一的 begin/end 边界
Migration Asset deployment target == bootstrapConsumerPath，且 execution trigger 可解析
```

当前 Base integration 小片段统一 inline `parameters.content`；Addition CREATE 使用 payloadRef。

不得出现：

```text
astSelector.kind
TARGET_MUST_NOT_EXIST string
registryType
sourceRef 作为 Wire 执行参数
TRANSFORM_FILE
RENDER_EXTENSION
```

---

## TS-5：实现 Addition CREATE / MAINTAIN

CREATE：

```text
strategyId = stable addition strategy id
ADD_FILE
target = addition.target
precondition = {}
parameters = {}
payloadRef = deterministic payload path
```

并写入 candidate State：

```text
appliedAdditions[additionId]
```

MAINTAIN：

```text
NO_OP
→ 无 Addition Strategy

STRATEGY
→ 仅在明确 Atomic Strategy 存在时使用
```

验收：

```text
首次 CREATE
重复 CREATE same-content retry-safe
目标不同内容 conflict
NO_OP repeat
identity changed reject
```

### TS-5.1：实现 Migration Asset Deployment 与 Execution Contract

Migration 实现必须独立于 Addition 的 `CREATE / MAINTAIN` 逻辑，按 11.1 节分别落地：

```text
Migration Asset
→ 通过显式 payload / ADD_FILE 投放到 Registry 声明的 deployment target

Migration Execution Contract
→ 作为 Template Release / Bootstrap 的明确 consumer path 与 execution trigger 在 Release load 时解析、校验
→ 不由 Template Service 执行 SQL、记录执行历史或向当前 Update Wire 私自扩字段
```

不得复用 V1 `migrations:` 的旧 target；首先以 Bootstrap 实际消费的 `backend/docs/auth/sql/ddl.sql` 为校验对象，只有 Registry 的 `deployment target` 与 `bootstrapConsumerPath` 完全一致时才允许构建 Package。任何不一致均在 Release load / Producer Gate 失败，禁止生成“资产已投放但 Bootstrap 不会消费”的 Package。

---

## TS-6：实现 Config / Release Refresh

支持：

```text
ENABLE
CONFIG_CHANGE
RELEASE_REFRESH
```

正常 Template Preparation 一律由 DevAgent Studio 请求：

```text
mode = APPLY
```

因此：

```text
same config + same release → 204
same config + new release → RELEASE_REFRESH
```

显式 RECONCILE 遇到 Release Refresh：

```text
409 RECONCILE_STATE_CHANGE_REQUIRED
```

---

## TS-7：切换 `/v1/update` Request / Error Contract

Controller 只接受：

```text
protocolVersion="2"
currentTemplateState
requestedConfig
mode
```

未知字段拒绝。

Controller 流程：

```text
validate request
→ ReconcileDecisionEngine
→ APPLY NO_CHANGE: 204
→ CHANGE / RECONCILE: StrategyUpdatePackageV2 ZIP
```

### TS-7.1 Error JSON

统一：

```json
{
  "code": "...",
  "message": "...",
  "details": {},
  "traceId": "..."
}
```

`RECONCILE_STATE_CHANGE_REQUIRED` 固定 409。

### TS-7.2 DevAgent Studio Caller

修改 DevAgent Studio：

```text
正常 reconcile/template preparation 调用
→ 显式 APPLY

显式 repair API/操作
→ RECONCILE
```

删除或停止使用：

```text
requestedConfig == current.requested
→ 自动 RECONCILE
```

同时修改 `TemplateEngineClient`：非 2xx 时解析 Engine JSON error，并保留 `code/status/message`。

不得实现任意错误自动 fallback；尤其不得把 RECONCILE 失败静默升级为 APPLY。

---

## TS-8：重写 StrategyUpdatePackageV2 Builder

最终 ZIP 只能包含：

```text
strategy-update-package.json
payload/**
```

Builder 固定生成：

```text
protocolVersion
packageId
mode
sourceRevision
currentStateDigest
nextStateDigest
strategies
validationPlan
payloadManifest
nextTemplateState
diagnostics=[]
```

写 ZIP 前必须完成：

```text
State digest binding
Strategy id/index/parameter validation
Validation id/index validation
Payload manifest exact match
RECONCILE State invariant
RECONCILE effective Capability Postcondition invariant
```

Payload bytes 先生成，再计算 size/sha256，再序列化 metadata。

`packageId` 可以每次 Response 不同；不得因此弱化 Strategy / State / Validation / Payload 的语义确定性。

---

## TS-9：重写 GenerateCoreV2 / GenerateMaterializerV2

删除当前 `V2ProjectGenerator` 的：

```text
point(all, ...)
providers(...)
routes(...)
menus(...)
interceptors(...)
基于 RENDER_EXTENSION 的整体文件 renderer
```

新流程：

```text
canonical target State
→ copy Base
→ copy Additions
→ deploy Migration Assets 到已校验的 bootstrapConsumerPath
→ resolve same Atomic Strategy set
→ same ordering
→ GenerateMaterializerV2
→ write TemplateStateV2
```

### TS-9.1 Empty Capability

必须直接生成：

```text
Base + valid empty requested/effective/appliedAdditions State
```

不能依赖 `UpdateResult.nextTemplateState()` 非空。

### TS-9.2 Generate / Update Parity

对当前 Release 实际使用的每种 Strategy：

```text
Java GenerateMaterializer result
==
Python DevAgent Studio Executor result
```

按目标文件 byte-for-byte 比较。

未来 Registry 使用新的 Wire Type 前，必须先补 Generate parity 支持；否则 Release load fail。

---

## TS-10：切换生产 `/v1/generate`

新工程从第一天使用：

```text
Atomic Base Surface
TemplateStateV2
Atomic Registry
```

Generate ZIP：

```text
frontend/**
backend/**
.devagentstudio/template-state.json
```

必须能被 DevAgent Studio `validate_template_package` 直接接受。

---

## TS-11：切换生产 `/v1/update`

生产 Update：

```text
V2-only Request
V2-only State
StrategyUpdatePackageV2-only Response
```

中间不得存在协议 Adapter。

旧：

```text
manifest.json
modification-strategy.json
next-template-state.json
validation-plan.json
change-set.json
```

不得继续出现在生产 Update ZIP。

---

## TS-12：`/v1/update/plan`

仍为预留能力，本轮可以不实现。

未来如果实现，只能复用：

```text
ReconcileDecisionEngine
WireStrategyCompiler
ValidatorCompiler
```

不得形成第二套 Decision 语义。

---

## TS-13：Acceptance Matrix

实施完成前至少一次性覆盖以下门禁：

```text
[Generate]
Generate empty capability
Generate login
Generate authorization
Generate login + authorization
Generate State strict parse
Generate / Update surface golden parity

[Base Surface]
all existingTargets exist in pristine Base
all anchors unique
provider order stable
root/page routes stable
page wrapper order stable
menu transform hook calls are static top-level (no loop/reduce)
CapabilityWebMvcConfiguration exists in Base
repeat insertion idempotent
pre-cutover surface fixture rejected / regenerated

[NPM]
Base packageManager = pnpm@11.9.0
Base pnpm-lock.yaml exists
package.json / lockfile frozen install passes
ahooks already in Base
current built-in registry emits no dynamic NPM dependency

[Mode]
normal unchanged config uses APPLY
APPLY same release → 204
APPLY newer release → RELEASE_REFRESH
explicit RECONCILE same release → health repair
explicit RECONCILE newer release → 409 RECONCILE_STATE_CHANGE_REQUIRED
DevAgent Studio no longer auto-infers RECONCILE from requested equality

[State / Release]
Config Change
Removal reject
release revision reused with different digest reject
Addition CREATE
Addition MAINTAIN + NO_OP
authorization.auth-provider repeat → NO_OP
Addition identity changed reject
Cross-language State Digest Golden

[Strategy]
one registry strategyId → exactly one StrategyDescriptorV2
no RENDER_EXTENSION / TRANSFORM_FILE runtime
precondition always {}
ADD_FILE parameters={}
exact parameter allow-list
unknown parameter reject
content XOR payloadRef
strategyId duplicate reject
strategy index continuous
TEXT_ANCHOR_INSERT anchor missing/ambiguous reject
TEXT_ANCHOR_INSERT managedMarker begin/end missing, orphaned, mismatched or duplicated reject
TEXT_ANCHOR_INSERT same package retry → no-op
TEXT_ANCHOR_INSERT Release Refresh same managedMarker with changed content → in-place replace, never duplicate insert
ENSURE_IMPORT idempotent
all 10 Wire Type contract fixtures remain green

[Migration]
Migration Asset deployment target == Bootstrap consumer path
legacy `backend/src/main/resources/devagentstudio/migrations/authorization/001-schema.sql` target is not silently accepted for a Bootstrap consuming `backend/docs/auth/sql/ddl.sql`
asset deployment does not imply execution
Bootstrap / DevAgent Studio only consumes an explicit, parseable execution trigger frozen in the Release / Bootstrap contract
unresolvable migration asset / consumer path / trigger rejects the Release and Package

[Validation]
ValidatorRegistry contains concrete Wire Validation type
no runtime scope-only validator
validation index continuous
shared frontend.build/backend.test deduped
read-only validator uses REAL_WORKSPACE
build/test uses SANDBOX
RECONCILE every effective Capability has postcondition
empty postcondition checks reject
NPM_BUILD frozen install passes
MAVEN_TEST passes

[Package]
ZIP allow-list exact
payloadManifest exact set
payload SHA mismatch reject
payload size mismatch reject
payloadRef missing reject
nextStateDigest mismatch reject
RECONCILE digest mismatch reject
diagnostics baseline []
Package build failure returns no partial ZIP

[Error]
flat code/message/details/traceId shape
RECONCILE_STATE_CHANGE_REQUIRED = 409
DevAgent Studio preserves Engine error code

[Cross-repo E2E]
real Service /v1/generate
→ DevAgent Studio validate_template_package
→ Bootstrap
→ real Service /v1/update APPLY
→ DevAgent Studio validate_strategy_update_package
→ Executor
→ Validation
→ State Commit
→ second APPLY returns 204
→ explicit RECONCILE succeeds without State change
```

### TS-13.1 真实包要求

以下不能算最终验收：

```text
Service MockMvc 自己生成自己解析
DevAgent Studio 使用手工 Mock ZIP
只测 JSON DTO 不执行 Strategy
```

至少一条 E2E 必须消费真实 Service 返回包。

---

## TS-14：清理 Legacy

Cross-repo E2E 全绿后删除：

```text
managedFiles legacy update semantics
DELETE_FILE legacy path
workspaceSnapshot / workspaceContext
CorePlanResult update package
old four-file PackageBuilder
legacy TemplateState mapper
RENDER_EXTENSION
TRANSFORM_FILE
point-based aggregate renderer
scope-based Validator runtime DSL
Service-private astSelector.kind
Service-private precondition string
frontend.authorization.reconcile-auth-provider
frontend.login.ensure-dependency（当前内置能力）
V1 migration target mapping
```

DevAgent Studio 清理：

```text
requested equality → RECONCILE 自动模式推导
非 2xx 丢失 Engine error code 的处理
```

Architecture Test 必须证明：

```text
ReconcileDecisionEngine
WireStrategyCompiler
ValidatorCompiler
GenerateMaterializerV2
UpdateController
StrategyUpdatePackageV2Builder
```

均不依赖 Workspace Runtime 或 Legacy Renderer DSL。

---

## TS-15：Release Cutover

完成上述实现后：

```text
1. 确认 Base / Capability / Strategy / Validator / Migration Asset + Execution Contract / lockfile 全部稳定
2. 一次性 bump template-source/template-revision.txt
4. 重新跑全部 Golden / Contract / Cross-repo E2E
5. 将该 revision 作为第一版 Atomic Surface Release
```

本轮之前由 aggregate renderer 生成的开发 Workspace 统一重新 Generate，不设计 silent migration。

从第一版 Atomic Surface Release 开始，后续 Release Refresh 必须保持 Base Surface Contract 向后兼容，或显式设计 Migration。

---

# 第三章 本轮实施顺序

顺序固定如下：

```text
P0-1  冻结本版文档 + Wire / State / Error / Surface Contract
  ↓
P0-2  建立 State Digest + Surface + Strategy/Validation Golden Fixture
  ↓
P0-3  重构 Base Providers / Routes / Menus / WebMvc Surface
       + Base package.json / pnpm-lock.yaml
  ↓
P0-4  迁移 Capability / Strategy / Validator Registry
       删除 Runtime RENDER_EXTENSION / TRANSFORM_FILE / scope DSL
  ↓
P0-5  重写 ReconcileDecisionEngine
       + WireStrategyCompiler + ValidatorCompiler
  ↓
P0-6  重写 GenerateMaterializerV2，并通过 Java/Python Surface Golden Parity
  ↓
P0-7  修改 OpenAPI / EngineController / Error Contract
       + 重写 StrategyUpdatePackageV2 Builder
  ↓
P0-8  修改 DevAgent Studio mode 选择与 TemplateEngineClient error parsing
  ↓
P0-9  打通 Service Contract Test + DevAgent Studio Contract Test
  ↓
P0-10 一次性 bump Template Revision
  ↓
P0-11 真实 Generate → APPLY Update → Validation → State Commit → 204 → RECONCILE E2E
  ↓
P1     清理 Legacy
```

从本版开始，**可以进入实施**。后续如果实现严格遵循上述 Contract，不再因为文档中已知的 Wire、Surface、Generate、Validation、Mode 或 Lockfile 歧义返回重新设计。

只有出现以下情况才需要重新做架构决策：

```text
DevAgent Studio Wire Contract 本身要新增/修改 Strategy Type
需要支持 pre-cutover Workspace 原地迁移
需要动态修改 pnpm-lock.yaml
需要 Capability Removal / Move / Rename
需要改变 Base Extension Surface Contract
```

---

# 第四章 最终固定数据流

Template Release：

```text
Base Stable Extension Surface
+
Capability Metadata
+
Atomic Strategy Registry
+
Atomic Validator Registry
+
Base package.json / pnpm-lock.yaml
        ↓
Release Contract Gate
        ↓
templateRevision
```

Template Service APPLY：

```text
Requested Config + Current State + Current Release
        ↓
Deterministic Decision
        ↓
Addition CREATE / MAINTAIN
+
Atomic Strategy Intent
+
Atomic Validation Intent
        ↓
WireStrategyCompiler / ValidatorCompiler
        ↓
StrategyUpdatePackageV2
        ↓
Immutable ZIP
```

Template Service Generate：

```text
Requested Config + Current Release
        ↓
Canonical Initial State
        ↓
Base + Additions
        ↓
Same Atomic Strategy Set
        ↓
GenerateMaterializerV2
        ↓
Project ZIP + TemplateStateV2
```

DevAgent Studio：

```text
Normal Preparation = APPLY
Explicit Health Repair = RECONCILE
        ↓
Strict Package Validation
        ↓
Current State Binding
        ↓
Working Copy Atomic Strategy Execution
        ↓
Validation
        ↓
Atomic TemplateStateV2 Commit
        ↓
Attempt Finalize / Roll-forward Recovery
```

最终只有一套事实：

```text
State Fact       = TemplateStateV2
Update Wire      = StrategyUpdatePackageV2
Strategy Fact    = StrategyRegistryV2 Atomic Entry
Validation Fact  = ValidatorRegistryV2 Atomic Entry
Integration Fact = Base Extension Surface Contract
```

不再存在第二套 Template Service 私有 Update Package、Renderer DSL 或 Validator DSL。

---

# 第五章 实施前总回检结论

本轮已对以下层面一次性回检并完成文档收口：

```text
Wire Schema
Strategy per-type parameters
AST selector contract
State digest
Package / payload integrity
RECONCILE invariants
Capability / Addition lifecycle
Strategy Registry
Validator Registry
Base Extension Surface
Frontend Provider / Route / Wrapper / Menu integration
Backend WebMvc interceptor integration
NPM package.json / pnpm-lock.yaml consistency
Generate / Update semantic parity
DevAgent Studio mode selection
Engine error transport
Package determinism boundary
Release revision / cutover
Pre-cutover Workspace scope
Cross-repo E2E acceptance
Legacy cleanup boundary
```

结论：

```text
方案层：可以冻结
协议层：可以冻结
实施边界：已明确
当前代码：仍需按第二章改造
是否可以正式进入实施：可以
```

本版之后，实施过程中如果只是发现“当前代码还没做到文档要求”，按实施任务修复，不再把它视为新的方案断点。
