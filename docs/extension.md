# 1. 总体架构

整体从当前：

```text
Base
+
Capability Source
+
Anchor / Target
+
Strategy
+
Patch
```

调整为：

```text
Base
      │
      ├── Platform Contract
      └── Extension Point
                ▲
                │
       Extension Contribution
                │
            Extension
```

三个核心边界：

**Base**

负责基础应用和稳定扩展契约，不感知具体 `login / authorization / tracking`。

**Extension**

负责一个完整、可独立维护的功能单元，包括前端、后端、依赖、配置和 Migration 等。

**Extension Tool**

负责 Extension 的开发生命周期，包括：

```text
Create
Edit
Analyze
Build
Publish
```

不再以 Patch 为核心。

---

# 2. 前端改造思路

前端目前缺少类似 Spring 的天然插件 Runtime，因此需要建立自己的：

```text
Extension Runtime
```

核心机制是：

```text
Extension Source
      ↓
extension.tsx
      ↓
import.meta.glob
      ↓
Extension Registry
      ↓
Base 自动装配
```

## 前端 Base

Base 保留：

```text
App
Route Infrastructure
Layout
Identity Contract
Access Contract
Extension Runtime
```

不再直接引用：

```text
Login
Authorization
Tracking
```

例如：

```text
App
```

只负责组合：

```text
IdentityProvider
ExtensionProviders
Routes
```

而不知道其中有哪些具体 Provider。

---

## 前端 Extension

例如 Login Extension 自己拥有：

```text
LoginProvider
LoginPage
LogoutPage
Login API
Login State
```

并提供：

```text
Provider Contribution
RootRoute Contribution
```

Authorization Extension 自己拥有：

```text
AuthorizationProvider
Permission
Authorization API
AuthorizationManagement Page
```

并提供：

```text
Provider Contribution
PageRoute Contribution
```

Tracking 可以提供：

```text
Initializer Contribution
ErrorReporter Contribution
```

---

## 前端稳定 Extension Point

第一阶段建议只保留真正通用的：

| Extension Point | 用途                          |
| --------------- | --------------------------- |
| Provider        | 注入 React Context / Provider |
| Root Route      | Login、Logout、Callback 等独立路由 |
| Page Route      | Extension 自带业务页面            |
| Initializer     | SDK、埋点等应用启动初始化              |
| Error Reporter  | 全局错误上报                      |

页面权限和菜单权限不要分别设计成：

```text
MenuTransform
PageRouteGuard
```

而统一通过 Base 的：

```text
Access Contract
```

解决。

---

## 前端运行时注册

最终工程只要存在：

```text
src/extensions/login/extension.tsx
src/extensions/authorization/extension.tsx
```

Base：

```text
import.meta.glob
```

即可自动发现。

因此前端 Runtime 不再需要：

```text
Anchor
ENSURE_IMPORT
TEXT_ANCHOR_INSERT
strategy-registry
```

来完成标准 Extension 装配。

---

# 3. 后端改造思路

后端不需要重新实现一套 Extension Runtime。

因为：

> **Spring 本身就是 Backend Extension Runtime。**

Spring 已经提供：

```text
@Component
@Service
@Repository
@RestController
@Configuration
Bean Injection
Component Scan
```

因此大多数 Extension Source 只要进入工程即可自动生效。

---

## 后端 Base

Base 只需要定义少量真正必要的稳定 Contract。

例如：

```text
WebMvcExtensionContributor
Identity Contract
```

不需要定义一个庞大的：

```text
BackendExtensionDescriptor
```

---

## 后端 Extension

Login 自己拥有：

```text
Controller
Service
Interceptor
Properties
Identity Implementation
```

Authorization 自己拥有：

```text
Controller
Service
Repository
Entity
Mapper
Annotation
Interceptor
Bootstrap
```

普通 Spring Bean：

```text
@RestController
@Component
@Configuration
```

直接由 Spring 自动发现。

不需要任何额外 Extension Registry。

---

# 4. 后端重点消除 Base Patch

当前最典型的问题是：

```text
CapabilityWebMvcConfiguration
      ↓
Anchor
      ↓
插入 Login / Authorization Interceptor
```

应该改成：

```text
Base
  WebMvcExtensionContributor

        ▲
        │ implements

LoginWebMvcExtension
AuthorizationWebMvcExtension
```

Base：

```text
List<WebMvcExtensionContributor>
```

由 Spring 自动注入并按 order 执行。

这样：

```text
Login Interceptor
Authorization Interceptor
```

不再需要修改 Base。

---

# 5. 前后端统一但不强求技术实现一致

统一的是架构思想：

```text
Base
    ↓
Extension Contract
    ↑
Extension
```

运行机制分别是：

|            | 前端                   | 后端                    |
| ---------- | -------------------- | --------------------- |
| Runtime    | 自建 Extension Runtime | Spring Runtime        |
| 自动发现       | `import.meta.glob`   | Component Scan        |
| 普通能力       | `extension.tsx` 描述   | Spring Bean           |
| 全局扩展       | Extension Contract   | Contributor Interface |
| Base Patch | 原则上取消                | 原则上取消                 |

因此：

> **概念统一，技术实现不强行统一。**

---

# 6. Extension 的长期 Source of Truth

`.workbench` 不应该保存 Extension。

正式 Extension 应有持久 Source，例如：

```text
extension-source/
├── login/
├── authorization/
└── tracking/
```

一个 Extension 逻辑上包含：

```text
Extension
├── Metadata
├── Frontend Source
├── Backend Source
├── Contributions
├── Dependencies
├── Configuration
└── Migrations
```

其中 Metadata 只保存无法从代码推导的信息，例如：

```text
id
requires
compatibility
migration
```

不再保存：

```text
targetId
anchor
strategyId
managedMarker
```

---

# 7. Extension 创建流程

新建 Extension 时：

```text
extension create tracking
```

工具生成：

```text
Workbench
├── baseline
└── project
```

其中：

```text
baseline
=
最新 Base + requires

project
=
baseline 可编辑副本
```

开发者只在：

```text
project
```

中像开发正常应用一样编写前后端代码。

如果需要参与 Base Extension Point：

前端使用：

```text
Provider
Route
Initializer
ErrorReporter
```

后端使用：

```text
Spring Bean
WebMvcExtensionContributor
Identity Contract
```

开发者不处理模板注入细节。

---

# 8. Extension 分析

开发完成后：

```text
baseline
   VS
project
```

工具不再识别：

```text
新增 import
Anchor 插入
```

而是识别：

```text
Extension Source
+
Extension Contribution
```

例如 Tracking：

```text
Frontend
  Initializer
  ErrorReporter

Backend
  TrackingConfiguration
  TrackingService
```

形成：

```text
Extension IR
```

---

# 9. Extension Build

`build` 的职责是：

> **把当前开发工程重新编译成完整的 Extension，而不是在旧 Extension 上继续打 Patch。**

流程：

```text
project
   ↓
Extension Analyzer
   ↓
Extension Source
+
Extension IR
+
Metadata
   ↓
Round-trip Verify
```

然后更新：

```text
extension-source/<id>
```

因此每次 Build 都产生当前 Extension 的完整状态。

---

# 10. Extension 更新流程

更新已有 Login：

```text
extension edit login
```

工具重新构建：

```text
baseline
=
最新 Base
+
login.requires
```

以及：

```text
project
=
最新 Base
+
login.requires
+
当前 Login Extension
```

开发者继续修改：

```text
Provider
Page
API
Controller
Service
```

然后重新：

```text
Analyze
Build
Verify
```

因此 Workbench 可以随时删除。

Extension 的真实状态始终存在：

```text
extension-source/login
```

---

# 11. Base 升级后的维护

如果 Extension 最初开发于：

```text
Base V1
```

现在 Base 已升级至：

```text
Base V5
```

执行：

```text
extension edit login
```

会自动在：

```text
Base V5
```

上重新 Materialize Login。

只要依赖的 Extension Contract 仍兼容：

```text
Provider
RootRoute
WebMvcExtensionContributor
Identity Contract
```

Extension 不需要调整。

只有 Contract 真正 Breaking Change 时才报：

```text
EXTENSION_CONTRACT_INCOMPATIBLE
```

而不是：

```text
ANCHOR_MISSING
TARGET_MISSING
```

---

# 12. Extension 依赖

Extension 依赖统一通过：

```text
requires
```

描述。

例如：

```text
authorization
    ↓ requires
login
```

工具在 Create / Edit / Publish 时都必须进行：

```text
依赖解析
拓扑排序
循环依赖检测
缺失依赖检测
版本兼容检测
```

用户选择：

```text
authorization
```
