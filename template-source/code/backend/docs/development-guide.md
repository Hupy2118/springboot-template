# 后端模板维护开发指引

## 适用范围

本文适用于 `template-source/code/backend` 的维护者。此目录是后端模板的
**新开发维护模型**，用来组合和验证 Base、Login、Authorization 等 Extension。

当前 Template Engine 仍消费旧的 `template-source/base/backend`、
`template-source/capabilities/*/backend` 与 Strategy Registry。因此，完成本目录
的维护与验证并不等同于将变更发布到现有 Engine；两套目录的过渡并存是当前阶段
的既定边界，禁止通过脚本双写或手工同步它们。

## 前置条件与入口

仅需 JDK 和此目录内的 Maven Wrapper；不需要安装 Node.js、npm、pnpm 或全局 Maven。

在 `template-source/code/backend` 下执行：

```bash
./backend-template <command> [profile]
```

Windows CMD 或 PowerShell 使用：

```bat
backend-template.cmd <command> [profile]
```

不要直接把业务逻辑写入 `backend-template` 或 `backend-template.cmd`；二者只是
启动 `assembly` Maven 模块中 Java CLI 的薄封装。

## 模型与源码归属

```text
base/                         所有 Profile 都需要的稳定源码
extensions/login/             Login 专属源码与声明
extensions/authorization/     Authorization 专属源码与声明
assembly/                     Java 版组装、同步与验证工具
workspace/                    可运行的组装产物，不是源码来源
```

`base/` 和 `extensions/*/` 是唯一 Source Owner。`workspace/` 仅用于编辑、运行和
验证；修改 workspace 后必须通过 `sync` 回写，才能成为模板源码变更。

同一个相对路径只能由一个 Owner 持有。若 Base 和 Extension，或两个 Extension，
提供同一路径，Assembly 会失败并报 `FILE_COLLISION`。不要依赖“后复制覆盖前复制”。

## Profile 选择规则

| Profile | 组装内容 | 可写回 Owner |
| --- | --- | --- |
| `base` | Base | `base` |
| `login` | Base + Login | `login` |
| `authorization` | Base + Login + Authorization | `authorization` |
| `full` | Base + Login + Authorization | 无，仅集成验证 |

Authorization 在 `extension.yaml` 中声明 `requires: [login]`，因此其 Profile 会
自动包含 Login。`full` 没有 `editTarget`：可以查看、运行、构建，但不能在其中创建
新文件并 Sync；否则会失败并报 `NO_EDIT_TARGET`。

## 日常开发流程

### 1. 选择最小 Profile 并组装

```bash
./backend-template assemble base
./backend-template assemble login
./backend-template assemble authorization
```

`assemble` 在 workspace 有未同步的人工修改时会失败，避免静默覆盖。先检查状态并
同步，或在确认可丢弃 workspace 修改时使用 `reset`：

```bash
./backend-template status
./backend-template sync authorization
./backend-template reset authorization
```

`reset` 会重新生成 workspace，应视为丢弃未同步 workspace 修改的操作。

### 2. 在 workspace 中开发与本地运行

```bash
./backend-template dev authorization
```

该命令组装对应 Profile 后，通过 workspace 内 Maven Wrapper 执行
`spring-boot:run`。日常修改可以先在 workspace 中完成，但不能把 workspace
当作长期维护的模板来源。

### 3. 查看变更并回写

```bash
./backend-template status
./backend-template sync authorization
```

`status` 是只读操作，展示修改、创建、删除及生成专用差异。`sync` 的规则是：

- 已有 Base 文件：回写 `base/`。
- 已有 Login 文件：回写 `extensions/login/`。
- 已有 Authorization 文件：回写 `extensions/authorization/`。
- 新文件：写入当前 Profile 的 `editTarget`。
- 删除文件：从原 Owner 中删除。

`Application.java` 是例外：Sync 会先移除 Assembly 注入的
`applicationAnnotations` 与对应 import，再将剩余内容回写 Base。因此，不要在
workspace 中通过修改注解反向维护 manifest；Contribution 的唯一真相是 Extension
自己的 `extension.yaml`。

## 如何维护 Base

Base 只应包含所有 Profile 都需要的内容，例如：

- `Application.java`、`pom.xml`、`application.yml`；
- 通用 `common/**` 配置、异常、分页和响应模型；
- 共享文档、环境示例及工程说明。

推荐流程：

```bash
./backend-template reset base
# 编辑 workspace 中的 Base 文件
./backend-template status
./backend-template sync base
./backend-template verify-update base
```

Base 的影响面最大。`verify-update base` 会依次验证 `base`、`login`、
`authorization` 与 `full`，因此只有所有下游 Profile 都通过才可合入。

不要把 Login 或 Authorization 的 Controller、Service、Mapper、拦截器配置等放入
Base，也不要恢复 `CapabilityWebMvcConfiguration` 或任何中央能力注册 Anchor。

## 如何维护 Login

Login 自己拥有其 Java、资源、测试、文档、`LoginWebMvcConfiguration` 及
`extension.yaml`。在 Login Profile 中开发：

```bash
./backend-template reset login
# 编辑 workspace 中 Login 的文件，或直接编辑 extensions/login/
./backend-template status
./backend-template sync login
./backend-template verify-update login
```

Login 的影响面包括其自身、Authorization 和 Full；`verify-update login` 会验证
`login`、`authorization`、`full`。

Login 的 `UserWebMvcInterceptor` 必须由
`LoginWebMvcConfiguration` 自行注册，并保持：order `100`、路径 `/**`、排除
`/api/login/mock`。不要把注册逻辑移回 Base。

如果 Login 需要给启动类增加注解，只在 `extensions/login/extension.yaml` 的
`contributes.applicationAnnotations` 增加合法且已由项目依赖提供的全限定类名。不要
声明文件路径、行号、文本 Anchor 或自行创造不存在的业务注解。Assembly 会在
workspace 的 `Application.java` 投影 import 和注解，Base 源文件保持不变。

## 如何维护 Authorization

Authorization 自己拥有其 Controller、Application Service、DTO、领域模型、Mapper、
XML、测试、迁移、文档、`AuthorizationWebMvcConfiguration` 和 manifest。推荐流程：

```bash
./backend-template reset authorization
# 编辑 workspace 中 Authorization 文件，或直接编辑 extensions/authorization/
./backend-template status
./backend-template sync authorization
./backend-template verify-update authorization
```

Authorization 的 manifest 必须保留对 Login 的 `requires` 声明。其影响面为
`authorization` 与 `full`，由 `verify-update authorization` 统一验证。

`ResourcePermissionInterceptor` 必须由
`AuthorizationWebMvcConfiguration` 自行注册，并保持：order `200`、路径 `/**`、排除
`/api/login/mock` 与 `/actuator/**`。迁移文件应继续由 Authorization Owner 管理，
放在 `extensions/authorization/migrations/`。

## 构建与验证

```bash
./backend-template build base
./backend-template build login
./backend-template build authorization
./backend-template build full

./backend-template test-maintenance
```

`build` 会重建 workspace，并通过 Maven Wrapper 在该 workspace 执行 `verify`；因此，
先 Sync 任何需要保留的 workspace 修改。`test-maintenance` 则只验证 `assembly` 中的
Java 维护工具本身。

提交前至少执行与改动范围相符的 `verify-update`，再执行：

```bash
git diff --check
```

## 发布边界

本阶段的“发布”是指提交经过验证的 `code/backend` 源码与维护工具，供后端模板维护者
继续使用；它尚不会自动改变 Engine 的 generate/update 产物。

在下一阶段 Template Engine 直接消费 Base + Extension + Assembly 模型之前：

- 不修改 Engine、旧 `base`、旧 `capabilities` 或 `strategy-registry-v2.yaml`；
- 不建立 Publisher、双写脚本或临时兼容层；
- 不在后端模型中引入 Strategy Registry、通用 Java/AST Patch 或 Runtime Registry。

等 Engine 发布模型统一迁移后，才会有一次性的旧目录退役和正式 Engine 发布流程。
