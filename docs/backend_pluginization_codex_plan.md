# `template-source/code/backend` 后端插件化 Codex 实施方案

## 1. 改造目标

本次只改造：

```text
template-source/code/backend
```

目标是先建立后端模板的插件化开发与维护体系，不改动 `template-engine`，也不在本阶段迁移或删除：

```text
template-source/base
template-source/capabilities
template-source/strategy-registry-v2.yaml
```

后续再统一调整 Template Engine，使其直接消费新的 Base + Extension + Assembly 模型。

当前后端真实扩展场景只有两类：

```text
1. 新增文件
   → Extension Source Tree 直接合并

2. 修改 Base 的 Application.java
   → 通过 applicationAnnotations Extension Point 投影
```

因此本次不建设通用 Java Patch、AST Patch、Strategy Registry 或 Backend Runtime Registry。

核心原则：

> Assembly 决定哪些 Extension 源码进入完整工程；Spring 负责消费已经进入工程的源码。

---

## 2. 本次范围

### 2.1 本次实施

需要在 `template-source/code/backend` 内完成：

```text
Base 源码拆分
Login Extension 拆分
Authorization Extension 拆分
Extension Manifest
Profile / requires
Assembly
applicationAnnotations Extension Point
Source Ownership
Workspace Sync
Java/Maven 维护 CLI
开发命令
构建验证
Assembly 单元测试
维护文档
```

### 2.2 本次不实施

不要修改：

```text
template-engine/**
template-source/base/**
template-source/capabilities/**
template-source/strategy-registry-v2.yaml
template-source/base.yaml
现有 generate / update API
```

不要为了兼容当前 Engine 在 `code/backend` 内重新引入：

```text
strategy-registry
anchor
TEXT_ANCHOR_INSERT
capability-v2.yaml
```

`code/backend` 必须是一套独立、干净的新插件开发模型。

---

## 3. 现有后端代码分类

当前后端代码来自：

```text
template-source/base/backend
template-source/capabilities/login/backend
template-source/capabilities/authorization/backend
```

按新模型重新分类。

### 3.1 Base

以下继续属于 Base：

```text
Application.java

common/config/CrosConfig.java
common/config/MybatisPlusConfiguration.java

common/exception/**
common/page/**
common/response/**

application.yml
pom.xml

README.md
AGENTS.md
docs/**
.env.example
.gitignore
```

当前：

```text
common/config/CapabilityWebMvcConfiguration.java
```

本质上是旧 Capability Anchor 宿主，不应进入新的 Base。

新的 `code/backend/base` 中不要保留该文件。

---

### 3.2 Login Extension

当前 Login 后端代码全部迁入：

```text
extensions/login
```

包括现有：

```text
MockLoginController
MockJwtService
BaseUserData
BaseUserDataThreadHodler
UserWebMvcInterceptor
LoginPropertiesConfiguration
MockLoginProperties
```

同时新增 Login 自有的：

```text
LoginWebMvcConfiguration.java
```

负责注册：

```text
UserWebMvcInterceptor
```

因此 Login 不再依赖 Base 中的 `CapabilityWebMvcConfiguration`。

Login 对 Base 唯一允许的结构性贡献是：

```text
Application.java 上的登录相关注解
```

该贡献通过 `applicationAnnotations` Extension Point 实现。

---

### 3.3 Authorization Extension

当前 Authorization 的后端文件整体迁入：

```text
extensions/authorization
```

包括：

```text
Controller
Application Service
DTO / Assembler
Bootstrap
Annotation
Interceptor
Domain
Repository
Mapper
PO
Converter
RepositoryImpl
Mapper XML
Test
Migration
```

同时新增：

```text
AuthorizationWebMvcConfiguration.java
```

负责注册：

```text
ResourcePermissionInterceptor
```

Authorization 不再修改任何 Base Java 文件。

Authorization 保持：

```text
requires:
  - login
```

---

## 4. 目标目录结构

建议生成：

```text
template-source/code/backend/
│
├── base/
│   ├── pom.xml
│   ├── .env.example
│   ├── .gitignore
│   ├── README.md
│   ├── AGENTS.md
│   ├── docs/
│   └── src/
│       ├── main/
│       └── test/
│
├── extensions/
│   ├── login/
│   │   ├── extension.yaml
│   │   ├── docs/
│   │   └── src/
│   │       ├── main/
│   │       └── test/
│   │
│   └── authorization/
│       ├── extension.yaml
│       ├── docs/
│       ├── migrations/
│       └── src/
│           ├── main/
│           └── test/
│
├── assembly/
│   ├── pom.xml
│   ├── profiles.yaml
│   └── src/
│       ├── main/java/com/cmbchina/template/backend/maintenance/
│       │   ├── BackendTemplateCli.java
│       │   ├── ProfileResolver.java
│       │   ├── ExtensionManifestLoader.java
│       │   ├── AssemblyContract.java
│       │   ├── BackendAssembler.java
│       │   ├── ApplicationAnnotationCompiler.java
│       │   ├── WorkspaceState.java
│       │   ├── WorkspaceStatus.java
│       │   ├── WorkspaceSync.java
│       │   ├── VerifyUpdate.java
│       │   └── MavenRunner.java
│       └── test/java/com/cmbchina/template/backend/maintenance/
│
├── workspace/
│   └── <Assembly 生成的完整 Spring Boot 工程>
│
├── .mvn/wrapper/
├── mvnw
├── mvnw.cmd
├── backend-template
├── backend-template.cmd
└── README.md
```

说明：

```text
base/**
extensions/**
```

是唯一持久化 Source Owner。

```text
workspace/**
```

只是 Assembly 生成的完整开发工程，不是新的源码来源。

```text
assembly/**
```

只承载后端模板维护工具本身；维护工具统一使用 Java 实现，由 Maven Wrapper 启动。后端维护链路不得依赖 Node/npm/pnpm，也不保留 `package.json` 或 `.mjs` 脚本。

根目录：

```text
backend-template
backend-template.cmd
```

是模板开发者唯一推荐的 CLI 入口；二者只负责启动 Java CLI，不承载 Assembly / Sync 业务逻辑。

不要直接把 `workspace` 当模板发布源码维护。

## 5. Base / Extension Source Ownership

### Base Owner

Base 只拥有：

```text
所有 Profile 都需要的文件
```

例如：

```text
Application.java
pom.xml
application.yml
common/**
```

### Login Owner

Login 只拥有：

```text
Login 自己新增的 Java / Resource / Test / Doc
LoginWebMvcConfiguration
Login applicationAnnotations 声明
```

### Authorization Owner

Authorization 只拥有：

```text
Authorization 自己的 Java / Resource / Test / Migration / Doc
AuthorizationWebMvcConfiguration
```

### 强约束

同一个相对路径不能同时存在于两个 Source Owner。

例如：

```text
base/src/main/java/.../Foo.java
extensions/login/src/main/java/.../Foo.java
```

Assembly 必须失败：

```text
FILE_COLLISION
```

禁止依赖“后复制覆盖前复制”。

---

## 6. Extension Manifest

Backend Extension Manifest 只声明 Assembly 必须知道的信息。

不要登记：

```text
Controller
Service
Repository
Mapper
Interceptor
Configuration
```

这些由文件树和 Spring 自己处理。

### Login

建议：

```yaml
id: login
requires: []

contributes:
  applicationAnnotations:
    - annotationClass: "<实际登录启动注解的全限定类名>"
```

如果当前仓库中尚不存在实际登录启动注解，不要由 Codex 猜测或创建业务注解名称。

实现 Assembly 对 `applicationAnnotations` 的支持即可；Manifest 中的真实类名应使用项目实际约定。

### Authorization

```yaml
id: authorization

requires:
  - login

contributes:
  applicationAnnotations: []
```

Migration 可以独立保留为 Extension 自有资源：

```text
extensions/authorization/migrations/001-schema.sql
```

本阶段不必设计 Engine 发布格式。

---

## 7. Profile

与前端保持相同心智模型：

```json
{
  "base": {
    "extensions": [],
    "editTarget": "base"
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

依赖解析必须自动完成：

```text
authorization
    ↓ requires
login
```

因此：

```text
authorization Profile
=
Base + Login + Authorization
```

禁止仅复制 Authorization 而漏掉 Login。

---

## 8. Assembly 模型

Assembly 执行顺序：

```text
读取 Profile
↓
解析 Extension Manifest
↓
解析 requires
↓
拓扑排序
↓
复制 Base
↓
按依赖顺序合并 Extension Source Tree
↓
执行 applicationAnnotations Compiler
↓
写入 Workspace State
↓
生成完整 workspace
```

### 文件合并

普通文件直接按项目相对路径合并。

例如：

```text
base/src/main/java/...
extensions/login/src/main/java/...
extensions/authorization/src/main/java/...
```

最终生成：

```text
workspace/src/main/java/...
```

Extension 是否进入最终工程完全由 Assembly 决定。

未选择 Authorization 时：

```text
workspace
```

中不得出现任何 Authorization 源码、Mapper XML、测试、Migration 或文档。

---

## 9. Login / Authorization Interceptor 改造

旧模型：

```text
Extension
↓
strategy-registry-v2.yaml
↓
CapabilityWebMvcConfiguration
↓
注册 Interceptor
```

新模型：

```text
Extension
↓
自身 Configuration
↓
Spring
```

### Login

新增：

```text
extensions/login/src/main/java/.../LoginWebMvcConfiguration.java
```

负责注册现有：

```text
UserWebMvcInterceptor
```

保持当前行为：

```text
order = 100
path = /**
exclude = /api/login/mock
```

### Authorization

新增：

```text
extensions/authorization/src/main/java/.../AuthorizationWebMvcConfiguration.java
```

负责注册：

```text
ResourcePermissionInterceptor
```

保持当前行为：

```text
order = 200
path = /**
exclude =
  /api/login/mock
  /actuator/**
```

新的 Base 中删除：

```text
CapabilityWebMvcConfiguration.java
```

这样新的 `code/backend` 中不再存在任何“能力注册 Anchor”。

---

## 10. applicationAnnotations Extension Point

这是 Backend V1 唯一自定义 Extension Point。

Base：

```java
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Login Manifest：

```yaml
contributes:
  applicationAnnotations:
    - annotationClass: "com.xxx.EnableLogin"
```

Assembly 后：

```java
import com.xxx.EnableLogin;

@EnableLogin
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 设计约束

Extension 只声明：

```text
annotationClass
```

不要声明：

```text
文件路径
行号
文本 Anchor
插入字符串
AST Selector
```

`Application.java` 始终属于 Base。

Login 只拥有：

```text
applicationAnnotations Contribution
```

---

## 11. Application Annotation Compiler

`ApplicationAnnotationCompiler` 在 Assembly 阶段实现一个专用 Compiler。

职责：

```text
收集选中 Extension 的 applicationAnnotations
↓
去重
↓
生成 import
↓
在 @SpringBootApplication 前生成注解
```

要求：

### 11.1 幂等

同一 Profile 连续 Assembly 多次结果完全一致。

### 11.2 去重

相同 `annotationClass` 只能生成一次。

### 11.3 冲突检查

Manifest 中存在：

```text
空 annotationClass
非法 Java 全限定类名
重复 Contribution ID（如果后续增加 id）
```

必须失败：

```text
INVALID_EXTENSION_DEFINITION
```

### 11.4 不污染 Base

Compiler 只修改：

```text
workspace/Application.java
```

不得修改：

```text
base/Application.java
```

---

## 12. Workspace State

Assembly 后写入状态文件，例如：

```text
workspace/.xcodeagent/backend-workspace-state.json
```

记录：

```json
{
  "profile": "authorization",
  "extensions": ["login", "authorization"],
  "editTarget": "authorization",
  "owners": {
    "src/main/java/com/cmbchina/backend/Application.java": "base"
  }
}
```

实际 owners 应覆盖全部文件。

另外记录 Assembly 管理信息：

```text
Application.java 的 applicationAnnotations
```

用于 Sync 时区分 Base 内容和生成内容。

---

## 13. Workspace Sync

Backend Sync 与前端保持同一规则。

### 已有文件修改

```text
回写原 Owner
```

例如：

```text
修改 Login 文件
→ extensions/login

修改 Authorization 文件
→ extensions/authorization

修改 Base 普通文件
→ base
```

### 新增文件

```text
写入当前 Profile 的 editTarget
```

例如：

```text
authorization Profile 新增文件
→ extensions/authorization
```

### 删除文件

```text
从原 Owner 删除
```

### Full Profile

```text
editTarget = null
```

Full 仅用于集成验证，不允许产生新的 Source Owner 文件。

新建文件时直接失败，例如：

```text
NO_EDIT_TARGET
```

---

## 14. Application.java 的 Sync 特殊处理

`Application.java` 是唯一不能直接整文件回写 Base 的文件。

例如 Login Workspace：

```java
import com.xxx.EnableLogin;

@EnableLogin
@SpringBootApplication
public class Application {
    ...
}
```

其中：

```text
@EnableLogin
对应 import
```

是 Assembly Contribution。

其余部分属于 Base。

因此 Sync 时：

```text
读取当前选中 Extension 的 applicationAnnotations
↓
从 Workspace Application.java 中剥离对应注解和 import
↓
得到 Pure Base Application.java
↓
再与 base/Application.java 对比
↓
回写 Base
```

### 重要约束

不要：

```text
从 Workspace Annotation 反向修改 extension.yaml
```

Manifest 是 Contribution 的 Source of Truth。

如果开发者需要修改 Login 注解配置，应直接修改：

```text
extensions/login/extension.yaml
```

---

## 15. Workspace Status

通过统一 CLI：

```bash
./backend-template status
```

Windows CMD / PowerShell：

```bat
backend-template.cmd status
```

至少展示：

```text
Profile
Selected Extensions
Edit Target

Modified Base Files
Modified Extension Files
New Files
Deleted Files
Generated-only Differences
Conflicts
```

对 `Application.java`：

```text
仅 applicationAnnotations 产生的差异
```

不应被显示成 Base 被人工修改。

`status` 只读取 Workspace State 和 Source Owner，不修改任何文件。

## 16. 开发命令与后端维护工具去 Node 化

### 16.1 技术约束

后端模板维护环境只要求：

```text
JDK
Maven Wrapper（仓库内置 mvnw / mvnw.cmd）
```

不得要求模板开发者安装：

```text
Node.js
npm
pnpm
```

因此 `template-source/code/backend` 内不要新增或保留：

```text
package.json
*.mjs
*.cjs
node_modules
```

前端已有 Assembly / Sync 的设计思想可以复用，但后端必须用 Java 重新实现运行时，不形成“Java 工程 + Node 维护工具”的双运行时依赖。

### 16.2 统一 CLI

模板开发者统一使用：

```text
./backend-template <command> [profile]
```

Windows 使用：

```text
backend-template.cmd <command> [profile]
```

支持命令：

```bash
./backend-template assemble base
./backend-template assemble login
./backend-template assemble authorization
./backend-template assemble full

./backend-template dev base
./backend-template dev login
./backend-template dev authorization
./backend-template dev full

./backend-template sync base
./backend-template sync login
./backend-template sync authorization

./backend-template reset base
./backend-template reset login
./backend-template reset authorization
./backend-template reset full

./backend-template build base
./backend-template build login
./backend-template build authorization
./backend-template build full

./backend-template status

./backend-template verify-update base
./backend-template verify-update login
./backend-template verify-update authorization

./backend-template test-maintenance
```

`full` 的 `sync` 不提供写回能力，因为：

```text
editTarget = null
```

如调用：

```bash
./backend-template sync full
```

必须直接失败并返回明确错误，例如：

```text
NO_EDIT_TARGET
```

### 16.3 CLI 的实际调用方式

`backend-template` / `backend-template.cmd` 是薄封装，实际启动：

```text
com.cmbchina.template.backend.maintenance.BackendTemplateCli
```

Shell 入口等价于：

```bash
./mvnw -q \
  -f assembly/pom.xml \
  exec:java \
  -Dexec.mainClass=com.cmbchina.template.backend.maintenance.BackendTemplateCli \
  -Dexec.args="assemble authorization"
```

Windows 入口使用同一 `assembly/pom.xml` 和同一 Main Class，只把 `./mvnw` 替换成 `mvnw.cmd`。

开发者日常不需要直接输入上述 Maven 命令；该形式用于明确 CLI 的真实执行链路和排障边界。

### 16.4 职责边界

```text
backend-template / backend-template.cmd
→ 参数透传、选择 mvnw 或 mvnw.cmd

BackendTemplateCli
→ 命令解析、Profile 校验、流程编排

BackendAssembler / WorkspaceSync / WorkspaceStatus / VerifyUpdate
→ 纯维护逻辑

MavenRunner
→ 仅负责调用仓库内 Maven Wrapper 执行 workspace 的 Spring Boot 生命周期
```

不要把业务规则写进 shell / bat 文件。

不要在 Java CLI 中重新实现 Maven 生命周期。Spring Boot 的启动、测试、打包仍调用 Maven Wrapper。

## 17. dev 流程

例如：

```bash
./backend-template dev authorization
```

执行：

```text
检查 Workspace 是否有未同步修改
↓
Assembly authorization
↓
得到 Base + Login + Authorization
↓
Assembly Contract 验证
↓
调用仓库内 Maven Wrapper
↓
mvnw -f workspace/pom.xml spring-boot:run
```

具体由 `MavenRunner` 根据操作系统选择：

```text
Unix/macOS/Git Bash → mvnw
Windows CMD/PowerShell → mvnw.cmd
```

必须使用仓库内 Maven Wrapper，不要求系统全局安装 Maven。

如果 Workspace 存在未同步修改，默认不要静默覆盖；应失败并提示先执行：

```bash
./backend-template status
./backend-template sync <profile>
```

除非后续明确增加 `--force`，V1 不要默认提供覆盖式重置。

## 18. build 流程

例如：

```bash
./backend-template build authorization
```

执行：

```text
Assembly authorization
↓
Assembly Contract 验证
↓
调用仓库内 Maven Wrapper
↓
mvnw -f workspace/pom.xml verify
```

`verify` 负责正常 Maven 生命周期中的编译、测试和打包验证。

不要从 Java CLI 自己编译 Java 源码，也不要重新实现测试或打包逻辑。

维护工具自身的构建和测试独立执行：

```bash
./backend-template test-maintenance
```

等价于：

```bash
./mvnw -f assembly/pom.xml test
```

这样区分两类验证：

```text
assembly/pom.xml
→ 验证维护工具本身

workspace/pom.xml
→ 验证组装后的模板工程
```

## 19. 影响范围验证

保持前端现有的依赖式验证思路，但入口统一走 Java/Maven CLI。

### Base 修改

```bash
./backend-template verify-update base
```

验证：

```text
base
login
authorization
full
```

### Login 修改

因为 Authorization 依赖 Login：

```bash
./backend-template verify-update login
```

验证：

```text
login
authorization
full
```

### Authorization 修改

```bash
./backend-template verify-update authorization
```

验证：

```text
authorization
full
```

`verify-update` 对每个受影响 Profile 执行：

```text
Assembly
→ Assembly Contract
→ mvnw -f workspace/pom.xml verify
```

任一 Profile 失败则整体失败，不得只验证当前 editTarget。

## 20. Codex 实施步骤

### Step 1：创建 `code/backend`

建立：

```text
template-source/code/backend
```

及：

```text
base
extensions
assembly
workspace
```

目录。

同时加入：

```text
.mvn/wrapper/**
mvnw
mvnw.cmd
backend-template
backend-template.cmd
```

增加独立 README，明确：

```text
code/backend 是后端模板唯一新的开发维护入口。
```

以及：

```text
后端模板维护不依赖 Node/npm/pnpm；统一通过 Java + Maven Wrapper CLI 完成。
```

本阶段不要删除旧 `template-source/base/backend` 和 `capabilities/*/backend`。

---

### Step 2：初始化 Base

从当前：

```text
template-source/base/backend
```

复制到：

```text
template-source/code/backend/base
```

然后从新 Base 中删除：

```text
CapabilityWebMvcConfiguration.java
```

其他代码保持行为不变，不在本阶段顺手做业务重构。

---

### Step 3：迁移 Login

从：

```text
template-source/capabilities/login/backend
```

复制 Login 后端源码到：

```text
extensions/login
```

建立：

```text
extension.yaml
```

新增：

```text
LoginWebMvcConfiguration.java
```

将原来 `CapabilityWebMvcConfiguration` 中的 Login Interceptor 注册行为迁移到该文件。

不要修改现有 `UserWebMvcInterceptor` 核心业务逻辑。

---

### Step 4：迁移 Authorization

从：

```text
template-source/capabilities/authorization/backend
```

复制到：

```text
extensions/authorization
```

包含：

```text
main source
resources
tests
migration
docs
```

建立：

```text
extension.yaml
```

声明：

```text
requires: [login]
```

新增：

```text
AuthorizationWebMvcConfiguration.java
```

迁移现有 ResourcePermissionInterceptor 注册逻辑。

---

### Step 5：建立 Java/Maven 维护工具骨架

在：

```text
assembly/pom.xml
```

建立独立 Java 工具模块。

实现统一入口：

```text
BackendTemplateCli.java
```

CLI 第一版只支持本方案定义的：

```text
assemble
dev
sync
reset
build
status
verify-update
test-maintenance
```

`backend-template` / `backend-template.cmd` 只负责把参数透传给该 Main Class。

不要增加 `package.json`、JavaScript/TypeScript 维护脚本或 Node 运行时依赖。

---

### Step 6：实现 Manifest Loader

实现：

```text
ExtensionManifestLoader.java
```

第一版只需要支持：

```text
id
requires
contributes.applicationAnnotations
```

验证：

```text
Extension ID 唯一
requires 类型正确
依赖存在
无循环依赖
annotationClass 合法
```

Manifest YAML 可通过 Maven 依赖的 YAML 解析库读取；不要为了读取 YAML 引入 Node。

---

### Step 7：实现 Profile 和依赖解析

实现：

```text
ProfileResolver.java
profiles.yaml
```

复用前端设计思想：

```text
profile
→ requested extensions
→ requires closure
→ topological order
```

错误至少包括：

```text
MISSING_EXTENSION_DEPENDENCY
CIRCULAR_EXTENSION_DEPENDENCY
EXPLICIT_EXTENSION_CONFLICT
```

如 Backend V1 没有 disabled 功能，可暂不实现 `EXPLICIT_EXTENSION_CONFLICT`。

---

### Step 8：实现文件 Assembly

实现：

```text
BackendAssembler.java
AssemblyContract.java
```

核心逻辑：

```text
Base full tree copy
+
selected Extension tree merge
```

Extension 内以下目录可以整体映射到 workspace：

```text
src/**
docs/**
migrations/**
```

但不要把：

```text
extension.yaml
```

复制给最终用户工程。

如果项目希望 Migration 最终映射到特定目录，先在 Backend Assembly 内定义固定规则，不依赖旧 capability-v2。

---

### Step 9：实现 `applicationAnnotations`

实现：

```text
ApplicationAnnotationCompiler.java
```

在普通文件 Merge 后执行：

```text
读取 workspace/Application.java
↓
收集 applicationAnnotations
↓
加入 import
↓
加入 class annotation
↓
写回 workspace/Application.java
```

实现必须可重复执行且结果稳定。

不要使用：

```text
strategy-registry-v2.yaml
TEXT_ANCHOR_INSERT
xcodeagent managed marker
```

这是新的 Assembly Compiler 能力。

---

### Step 10：实现 Workspace State / Status

实现：

```text
WorkspaceState.java
WorkspaceStatus.java
```

Assembly 时生成：

```text
owner map
profile
selected extensions
edit target
generated contributions
```

为后续 Sync 提供依据。

`status` 必须能够区分人工修改和 generated-only difference。

---

### Step 11：实现 Workspace Sync

实现：

```text
WorkspaceSync.java
```

复用前端 Workspace Sync 的算法思想与错误语义，重点增加 Backend 特殊处理：

```text
Application.java contribution stripping
```

同时覆盖：

```text
UPDATE
CREATE
DELETE
FILE_COLLISION
NO_EDIT_TARGET
```

后端 Sync 的业务逻辑全部在 Java 中实现，不通过 shell 文本处理 Java 源码。

---

### Step 12：实现 MavenRunner 与开发/构建流程

实现：

```text
MavenRunner.java
VerifyUpdate.java
```

`MavenRunner` 只负责定位并调用仓库内：

```text
mvnw
mvnw.cmd
```

用于执行：

```text
workspace spring-boot:run
workspace verify
assembly test
```

不要调用系统全局 `mvn` 作为唯一方案，不要引入 npm script 作为二次封装。

---

### Step 13：补齐维护工具测试

测试全部放入：

```text
assembly/src/test/java/**
```

至少覆盖两组核心能力：

```text
Assembly / Manifest / Profile / Contract
Workspace Sync / Status / Application Annotation stripping
```

执行入口：

```bash
./backend-template test-maintenance
```

不依赖 Template Engine，也不依赖 Node。

## 21. 必须覆盖的测试场景

### Assembly

1. Base Profile 只包含 Base。
2. Login Profile 包含 Base + Login。
3. Login Profile 不包含 Authorization 文件。
4. Authorization Profile 自动包含 Login。
5. Full Profile 包含 Login + Authorization。
6. 未启用 Authorization 时，任何 Authorization Java/XML/Test/Migration 不出现在 Workspace。
7. Extension 与 Base 同路径时失败。
8. Login 与 Authorization 同路径时失败。
9. 循环 `requires` 时失败。
10. 缺失依赖时失败。

### Application Annotation

11. Base Profile 的 `Application.java` 不包含 Login Annotation。
12. Login Profile 正确生成 import。
13. Login Profile 正确生成 annotation。
14. Authorization Profile 因依赖 Login，同样包含 Login Annotation。
15. 重复 Assembly 不重复 import / annotation。
16. Base `Application.java` 永远不被 Assembly 修改。

### WebMvc

17. Login Profile 存在 `LoginWebMvcConfiguration`。
18. Login Interceptor 注册 order / include / exclude 与旧行为一致。
19. Authorization Profile 存在 `AuthorizationWebMvcConfiguration`。
20. Authorization Interceptor 注册行为与旧实现一致。
21. 新 Base 不存在 `CapabilityWebMvcConfiguration`。

### Sync

22. 修改 Base 普通文件回写 Base。
23. 修改 Login 文件回写 Login。
24. Authorization Profile 新建文件写入 Authorization。
25. 删除 Login 文件会删除 Login Owner 文件。
26. Full Profile 新建文件失败。
27. Login Workspace 中修改 `Application.java` 主体，Sync 后只回写 Pure Base 内容。
28. Login Annotation 和对应 import 不得进入 `base/Application.java`。
29. Sync 不反推或修改 `extension.yaml`。
30. `status` 能将仅由 `applicationAnnotations` 产生的差异识别为 generated-only difference。

### Build

31. Base `./backend-template build base` 成功。
32. Login `./backend-template build login` 成功。
33. Authorization `./backend-template build authorization` 成功。
34. Full `./backend-template build full` 成功。
35. `verify-update base` 会验证 base/login/authorization/full。
36. `verify-update login` 会验证 login/authorization/full。
37. `verify-update authorization` 会验证 authorization/full。

### 去 Node 化

38. 在未安装 Node.js/npm/pnpm 的环境中，`assemble/status/sync/reset/build/verify-update` 均可执行。
39. `template-source/code/backend` 不存在 `package.json`、`.mjs`、`.cjs` 或 `node_modules` 运行时依赖。
40. Unix/macOS/Git Bash 可通过 `./backend-template` 执行；Windows CMD/PowerShell 可通过 `backend-template.cmd` 执行。
41. CLI 实际由 `assembly/pom.xml + BackendTemplateCli` 提供，shell/bat 不包含 Assembly/Sync 业务规则。
42. Workspace 的启动、测试、打包使用仓库内 Maven Wrapper，而不是 Node script 或必须依赖全局 Maven。

## 22. 验收标准

完成后必须达到：

### 开发结构

```text
template-source/code/backend
├── base
├── extensions
│   ├── login
│   └── authorization
├── assembly
│   ├── pom.xml
│   └── src/main/java/**
├── workspace
├── .mvn/wrapper
├── mvnw
├── mvnw.cmd
├── backend-template
└── backend-template.cmd
```

### 能力隔离

```text
Base
→ 不包含 Login / Authorization 专用源码

Login
→ 只在选中 Login 时进入 Workspace

Authorization
→ 只在选中 Authorization 时进入 Workspace
```

### Base 修改

除：

```text
Application.java 的 applicationAnnotations 投影
```

之外，不存在其他 Extension 对 Base 的代码修改机制。

### 无 Strategy Registry

`template-source/code/backend` 内不得出现对：

```text
strategy-registry-v2.yaml
capability-v2.yaml
TEXT_ANCHOR_INSERT
Anchor
```

的依赖。

### Spring 注册

Login / Authorization 的 Interceptor：

```text
由各 Extension 自己的 Spring Configuration 注册
```

不再依赖 Base 中央注册器。

### 无 Node 维护依赖

后端模板维护链路必须满足：

```text
JDK + 仓库内 Maven Wrapper 即可运行
```

不得要求：

```text
Node.js
npm
pnpm
```

不得以 `package.json` 或 JavaScript 脚本作为 Assembly / Sync / Build 的必要入口。

### 统一 CLI

开发者只需要记住：

```text
./backend-template <command> [profile]
```

Windows 对应：

```text
backend-template.cmd <command> [profile]
```

CLI 后端统一进入：

```text
assembly/pom.xml
→ BackendTemplateCli
```

### 维护闭环

Backend 模板维护能够完成：

```text
assemble
→ dev
→ edit workspace
→ status
→ sync
→ build
```

且 Source Ownership 正确。

### 构建职责清晰

```text
Java CLI
→ Assembly / Sync / Status / Verify orchestration

Maven Wrapper
→ Spring Boot compile / test / package / run
```

禁止在 CLI 中重新实现 Maven 生命周期。

## 23. 本阶段结束后的状态

本阶段完成后会暂时存在两套目录：

```text
新的模板开发模型：
template-source/code/backend

旧的 Engine 发布模型：
template-source/base/backend
template-source/capabilities/*/backend
strategy-registry-v2.yaml
```

这是预期的过渡状态。

本阶段不要尝试自动同步两者，也不要引入 Publisher 做双写。

原因是下一阶段会统一改造 Template Engine 层，届时应直接确定：

```text
Engine 如何消费 code/backend 的 Base + Extension + Assembly
```

再一次性退役旧：

```text
base/capabilities/strategy-registry
```

避免本阶段先建立一套临时兼容链路，下一阶段又删除。

---

## 24. Codex 实施约束

Codex 实施时必须遵守：

1. 只修改 `template-source/code/backend`，除非为了新增该目录必须读取旧代码作为来源。
2. 不修改 `template-engine`。
3. 不修改旧 `template-source/base`、`template-source/capabilities` 和 `strategy-registry-v2.yaml`。
4. 不顺手重构 Login / Authorization 业务逻辑。
5. 不引入通用 Java Patch Engine。
6. 不引入 Backend Plugin Runtime Registry。
7. 新增文件采用 Source Tree Merge。
8. 唯一 Base EP 为 `applicationAnnotations`。
9. Manifest 只描述 Assembly 无法从文件树自行推断的信息。
10. 复用前端已有 Assembly、Profile、Workspace State、Source Ownership、Sync 的设计和错误语义，但后端实现必须使用 Java/Maven，不复制前端的 Node 运行时。
11. `template-source/code/backend` 不新增 `package.json`、`.mjs`、`.cjs` 或其他 Node 维护入口。
12. 后端维护工具统一放在 `assembly` Maven 模块中，由 `BackendTemplateCli` 提供命令入口。
13. `backend-template` / `backend-template.cmd` 只能做启动和参数透传，不写 Assembly、Sync、Source Ownership 等业务逻辑。
14. 所有 Spring Boot 启动、测试、打包都通过仓库内 `mvnw` / `mvnw.cmd` 调用 Maven 生命周期。
15. 不要求模板开发者安装全局 Maven；Maven Wrapper 是标准入口。
16. 不要求模板开发者安装 Node.js/npm/pnpm。

最终目标不是“重新实现一个后端插件框架”，而是形成一个简单稳定、单技术栈的模板维护模型：

```text
Base
+
Selected Extensions
+
Small Java Assembly Compiler
        ↓
Clean Spring Boot Workspace
        ↓
Maven Wrapper Run / Test / Package
```
