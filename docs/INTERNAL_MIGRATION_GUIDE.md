# 行内模板移植与维护指南

## 1. 目的与适用范围

本文面向将本仓库模板适配到行内环境的模板维护者、引擎维护者和项目接入方，说明如何归类行内差异、判断是否需要新增 Extension 注册点、迁移已有项目，以及验证模板发布。

本指南面向唯一的 V3 Template Engine。Runtime Source 为 `template-source/code/`，Release Revision 为 `template-source/code/template-revision.txt`，接口为 `/v1/generate` 与 `/v1/update`：

| 版本 | 生成接口 | 更新接口 | Runtime Source | Revision |
| --- | --- | --- | --- | --- |
| V3 | `/v1/generate` | `/v1/update` | `template-source/code/` | `template-source/code/template-revision.txt` |

契约索引见 [REFACTOR.md](REFACTOR.md)，V3 完整语义见 [v3.md](v3.md)。

## 2. 先理解职责边界

V3 Runtime Source 的所有权如下：

| 路径 | 职责 | 典型内容 |
| --- | --- | --- |
| `template-source/code/frontend/base/` | 所有前端项目共同拥有的基线 | 应用入口、通用布局、主题、身份和访问抽象 |
| `template-source/code/backend/base/` | 所有后端项目共同拥有的基线 | Spring Boot 工程结构、通用配置和共享代码 |
| `template-source/code/frontend/extensions/<id>/` | 前端能力自己的源码与声明 | 页面、Provider、路由和前端初始化逻辑 |
| `template-source/code/backend/extensions/<id>/` | 后端能力自己的源码、文档、迁移和声明 | Controller、Service、配置、依赖、数据库迁移 |
| `template-source/code/template-revision.txt` | V3 Runtime Release 版本 | 发生 V3 Runtime Source 改动时递增 |

`frontend/assembly/`、`backend/assembly/`、两端的 `workspace/`、profiles 和维护工具用于模板开发，不属于 V3 Engine Runtime 输入。只修改这些目录，不会改变 Engine 的生成结果。Runtime 输入清单见 [V3 完整契约](v3.md) 第 15 节。

Engine Service 是无状态计算入口：它读取部署配置指定的 Source Root，接收请求并返回 ZIP。它不读取调用方工程目录，也不替调用方应用更新包、执行构建、回滚文件或提交 State。Workspace Apply 和 State 生命周期由调用方负责。

## 3. 将差异放到正确的位置

移植前先把需求逐项归类，不要从“改哪个文件”直接开始：

1. **所有行内项目都需要的公共能力**：放入 Frontend 或 Backend Base。
2. **可独立启用的能力**：放入相应 Extension，例如行内 SSO、登录、权限、审计。
3. **单个项目的业务逻辑或特殊配置**：留在该项目自身仓库，由项目团队维护。
4. **由多个 Extension 共同接入的基础机制**：评估是否需要给 Base 增加稳定注册点。

Base 的影响面最大。不要把仅 Login 或 Authorization 需要的代码放入 Base，也不要为了某个项目的业务差异扩展全局模板契约。

Extension 之间和 Base 之间不能依赖文件覆盖。每个 Runtime 文件只能有一个 Source Owner；Base 与 Extension 或两个 Extension 提供相同目标路径时，必须解决所有权冲突，不能依赖复制顺序覆盖。

## 4. 是否需要新增注册点

先区分“使用已有注册点”和“设计一个新注册点”。增加 Extension 的功能不等于要新增注册点。

### 4.1 当前已有的 V3 注册面

Frontend Manifest 可以贡献：

- Providers
- 根路由
- 系统页面路由
- Initializers
- Error Reporters

Backend Manifest 可以贡献：

- `applicationAnnotations`
- `mavenDependencies`

Extension 也可以将自己的 Backend 配置类和业务源码放在自己的 `src/` 中。只有 Base 必须调用可替换行为、且现有贡献方式无法表达时，才需要考虑新的 Base 接口或注册面。

### 4.2 决策检查

对每项候选注册点回答以下问题：

1. **谁需要变化？** 如果只是一个 Extension 自己的行为，优先把逻辑留在该 Extension。
2. **变化发生在运行时还是生成时？** 当前 V3 的 Extension 是生成工程时选择和组合的；它不是应用运行时动态加载插件的机制。
3. **Base 是否需要调用这项变化？** 如果 Base 不需要知道它，就不必在 Base 中增加钩子。
4. **现有注册面是否确实不够？** 先试用 Provider、路由或 Backend Contribution 等现有机制。
5. **是否会复用？** 多个 Extension 或多个行内系统都会需要同一接入方式时，抽象的收益更高。
6. **契约能否稳定定义？** 需要能说明默认行为、执行顺序、冲突处理、错误行为和升级方式。

可以按下面的判断表做初筛：

| 需求 | 建议 |
| --- | --- |
| 所有工程都使用相同布局、HTTP 基线或日志行为 | 修改 Base，不新增注册点 |
| Login Extension 新增登录页、Provider、依赖或自有后端配置 | 使用 Login 自身文件和已有 Manifest 注册面 |
| Base 需要读取可替换的用户身份或权限实现，且现有 Provider 契约不够 | 设计通用身份/权限接口后，再评估注册点 |
| 单个项目增加业务页面或特殊接口 | 留在项目仓库 |
| Base 出现按行内系统名称分支，或多个 Extension 反复要求同一处 Base 改动 | 这是新增注册点的信号，先验证是否存在稳定、可复用的共同契约 |

反向检查抽象成本：如果只有一个固定实现，新增注册面会增加 Manifest 字段、Loader 校验、排序、冲突、更新语义和测试负担。此时直接维护行内专用 Extension 通常更简单。

### 4.3 新增注册点的设计要求

确认需要新注册点后，按契约先行的方式设计：

1. 在 Base 中定义 Extension 可依赖的稳定接口或受管注册文件。
2. 定义 Manifest 字段、类型、默认值、必填规则和向后兼容规则。
3. 定义多个 Extension 同时贡献时的顺序、去重和冲突行为。
4. 由 V3 Core Loader 通用解析和聚合，不要在 Service 中按 Extension ID 写业务分支。
5. 定义 Update Package 如何应用、如何识别用户修改、失败如何回滚。
6. 补齐 Loader、Materialization、OpenAPI/JSON Schema（如适用）、Fixture、契约测试和开发文档。

若新增 State 字段、Operation、Package 字段、错误码或 HTTP 参数，先更新权威 Schema/OpenAPI、Fixture 和契约测试，再实施代码。不要直接扩大冻结的 State 或 Package 结构。

## 5. 建立行内 Runtime Release

建议按以下顺序形成行内版本：

1. **盘点目标环境**：Java/Spring 版本、Maven 仓库、前后端依赖、统一认证协议、用户身份字段、权限模型、数据库迁移规范、网络和配置约束。
2. **确定公共基线**：把每个行内项目都需要的内容纳入 Base。
3. **确定能力边界**：把登录、授权或审计等可选能力放入 Extension；通过 `requires` 声明能力依赖。
4. **核对文件路径和启动入口**：当前 Backend Contribution 投影目标是 `backend/src/main/java/com/cmbchina/backend/Application.java`。若行内工程包名或启动类不同，应先适配这一契约和实现，再验收相关 Extension。
5. **用 Generate 生成全新工程**：至少生成 Base、Login，以及实际会使用的能力组合。检查文件、Manifest 投影和 State。
6. **验证工程本身**：按生成工程自己的说明安装依赖、构建和运行；Engine 返回 ZIP 不代表工程已构建成功。
7. **递增 V3 Revision 并走门禁**：Runtime Source 变化时递增 `template-source/code/template-revision.txt`，运行 V3 Revision Gate。

若只有一套行内基线，可以将它作为该环境部署的 Runtime Source。若多个项目需要互不兼容的基线，当前 V3 没有通过请求选择 profile/source 的能力；应由部署侧选择不同 Source Root 或服务实例，或先设计经过契约评审的产品能力。不要把任意 Source Root 选择参数直接加到 HTTP 请求。

## 6. 迁移已有项目

### 6.1 先识别项目当前状态

记录以下信息：

- 项目是否由 V3 Generate 创建。
- 项目是否保存了 `.devagentstudio/template-state.json`，其 `schemaVersion` 和 `templateRevision` 是什么。
- 项目当时使用的 Base 和 Extension Release。
- 项目相对该模板做过哪些本地修改。
- 项目是否有尚未提交的修改、环境配置或密钥。

当前没有面向任意既有工程的导入/识别接口。缺少有效的 V3 State 时，不能只凭项目文件直接调用 `update`；Service 也不会扫描项目并推断 State。

### 6.2 使用三方合并审查差异

将已有项目、它最初使用的模板版本、目标行内 Runtime Release 作为三方比较对象，在独立 Git 分支或临时工作区中逐项合并：

- 公共基线改动：审查并迁移到行内 Base。
- Login 或其他 Extension 改动：审查并迁移到对应 Extension。
- 项目业务代码和部署配置：保留在项目仓库。
- 密钥和环境专属值：只通过安全配置渠道迁移，不写入模板源码。

不要把完整 Generate ZIP 直接覆盖已有工程。对每项冲突记录决策和验证结果，合并后构建并测试目标项目。

### 6.3 当前 V3 更新能力与限制

`update` 根据 `currentTemplateState`、请求配置和当前 Release 规划 Package，不读取调用方文件内容。当前普通文件规则如下：

| Release 变化 | 当前更新行为 |
| --- | --- |
| Base 文件修改、新增或删除 | 不会同步到已有项目 |
| 已安装 Extension 文件同路径内容修改 | 不覆盖已有文件 |
| 当前 Release 新增 Extension 文件 | 可生成 `ADD_FILE` |
| 当前 Release 删除 Extension 文件 | 不自动删除项目文件 |
| Frontend 五个受管 Registry | Release 更新时可重绘；`RECONCILE` 用于修复受管面 |
| Java Annotation / Maven Dependency Contribution | 按 Contribution State 计算增删或确保操作 |

`installedArtifacts` 不包含 Base 文件或普通文件的内容摘要；因此引擎不能判断已有 Login 文件是否被项目人员修改，也不能安全地对同路径文件做自动三方合并。Revision 增长本身不代表 Base 内容已写入已有项目。

`RECONCILE` 只修复契约列出的受管 Surface，并要求 State 与当前 Release 一致；它不能承担 Base 升级或普通 Login 文件合并。

如果调用方应用 V3 Update Package，必须先验证当前 State Digest、Package Schema、路径、payload 校验值和 Operation 顺序；应用失败要整体回滚。所有 Operation 成功后再提交 `nextTemplateState`。这部分属于调用方的 Workspace Executor，不是 Engine Service 的职责。

## 7. 验收流程

### 7.1 自动化门禁

Engine Service 改动至少执行：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
git diff --check
```

涉及 Template Source 或 Core 行为时，同时执行：

```sh
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-code-template-release-revision.sh <base-ref>
```

`<base-ref>` 使用当前分支实际的共同基线。Release Gate 只验证 V3 Runtime Inputs；它不代替 V3 Generate、Update 和行内工程构建验证。

涉及 HTTP、ZIP 或启动配置时，按仓库说明启动真实 JAR，使用 `template-engine/engine-service/config/application-local.yml`，显式传入绝对 `TEMPLATE_ENGINE_SOURCE_ROOT`。Local Service 固定监听 `127.0.0.1`。

### 7.2 V3 功能验收

至少覆盖：

- Base Generate。
- Login Generate。
- Authorization Generate，并确认依赖闭包中包含 Login。
- 相同请求重复 Generate，ZIP bytes 一致。
- Base → Login 或 Base → 行内认证能力的 Update Package。
- Update 的 No Change、RECONCILE、非法 State 和不支持移除行为。
- 调用方执行器应用更新包后的工程构建，以及失败时 Workspace/State 原子回滚。

接口、ZIP 内部 JSON、错误码和确定性细节以 [V3 契约](v3.md) 与 [Engine Service OpenAPI](../template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml) 为准。

## 8. 发布与协作清单

每次行内模板发布前确认：

- [ ] 变更属于 V3 Runtime Source 或 Maintenance-only 内容，并遵守相应边界。
- [ ] Base、Extension 和项目业务代码所有权清楚，没有重复目标路径。
- [ ] 新注册点有明确默认值、排序、冲突和更新语义；未把业务分支放进 Service。
- [ ] V3 Runtime Source 改动递增了 `template-source/code/template-revision.txt`。
- [ ] Generate 的 ZIP 内容、State 和 Extension 依赖符合预期。
- [ ] Update Package 的限制已被调用方理解；没有把 `200` 当成文件已合并的证明。
- [ ] 真实行内工程完成构建、测试和运行验收。
- [ ] `git diff --check` 和适用的 Revision Gate 通过。

## 9. 关键参考

- [V3 契约索引](REFACTOR.md)
- [V3 完整实施契约](v3.md)
- [V3 HTTP OpenAPI](../template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml)
- [CI 脚本说明](../scripts/ci/README.md)
- [仓库 README 与本地 JAR 启动方式](../README.md)
