# XcodeAgent 可插拔模板工程重构方案

本文是模板工程的设计与实施基线。后续任何接口、状态模型、Capability、验收条件或实施范围的调整，必须同步更新本文件。

# 第一章：方案设计

## 1. 目标与边界

目标是将当前模板拆分为稳定的 `Base` 与可组合的 `Capability`，由 Engine 根据配置生成或收敛目标工程；上层系统只负责请求编排，XcodeAgent 只负责在本地工作区落地变更。

V1 必须满足：

- Base 可独立构建、测试、运行；
- `login`、`authorization` 可独立启用、组合启用和升级；
- 同一输入、同一源码版本和同一锁定版本产生相同的期望状态；
- 变更先被计划、校验和存档，再由执行端应用；
- 本地 CLI 可以完整调试组合结果，不依赖第三阶段服务接口。

V1 不包含多仓库事务、自动合并人工改动、未声明的能力组合，以及把 XcodeAgent 纳入本模板工程的交付物。

## 2. 分层职责与不可跨越的边界

| 层 | 职责 | 不负责 |
|---|---|---|
| Template Source | Base、Capability、迁移、结构化贡献声明 | 读取用户工程、写入工作区 |
| Core Engine | 校验配置、解析能力、计算期望状态与 ChangeSet | HTTP、Git 拉取、文件落地 |
| CLI | 以本地方式调用 Core、输出可调试产物 | 复制第二套组合逻辑 |
| Engine Service | 源码快照、持久化、幂等、鉴权、HTTP | 文件落地、重新实现 Core |
| XcodeAgent | 获取 ChangeSet、工作区锁定、应用、构建验证、提交 | 能力解析、依赖选择、二次编排 |

唯一的组合入口是 Core。CLI、Service 和 XcodeAgent 均不得实现另一套 Capability 解析、依赖合并或模板渲染规则。

## 3. 仓库结构与发布单元

```text
template-source/
  base/
  capabilities/
    login/
    authorization/
  migrations/
  catalog.yaml
template-engine/
  engine-core/
  engine-cli/
  engine-service/
docs/
  REFACTOR.md
```

- `template-source` 是能力和迁移的发布单元；Base 与全部 Capability 必须来自同一不可变 `sourceRef`。
- `engine-core` 是无副作用的组合内核；`engine-cli` 和 `engine-service` 只是适配层。
- 当前工程仅交付前三层；XcodeAgent 的协议在本章保留，但其实现不属于本仓库。

## 4. Base、Capability 与文件归属

Base 提供应用骨架、最小配置、公共异常与响应结构、数据库和构建基线；不得引用任一可选能力的类、Bean、路由、表、权限或前端入口。

Capability 是可独立识别、可声明依赖、可升级、可校验的增量包。每个 Capability 包含：

```text
capabilities/<id>/
  capability.yaml
  backend/
  frontend/
  docs/
  migrations/
```

`capability.yaml` 至少声明 `id`、`version`、`requires`、`provides`、`configSchema`、`files`、`dependencies`、`extensions` 和 `migrations`。文件归属应完整覆盖 Capability 自身新增或接管的文件。

文件归属规则：

- Base 文件仅由 Base 拥有；Capability 文件仅由一个 Capability 拥有；
- 对既有文件的共同扩展只能通过预定义 Extension Point；
- 发生文件所有权、路径或扩展点冲突时，规划失败，不能按安装顺序覆盖；
- 被 Capability 管理的文件，状态中需记录 `owner`、`source`、`contentHash`、`formatVersion` 与用户改动标识。

## 5. 配置、依赖和解析结果

输入配置分为两层：`requested` 是用户表达的选择；`effective` 是经依赖闭包、版本约束与冲突校验后的实际安装集合。响应、状态和 ChangeSet 均必须返回 `effective`，并标识隐式引入项。

`requires` 使用三态语义：

- `required`：必须存在；
- `optional`：存在时集成，不存在不报错；
- `forbidden`：存在即冲突。

每个 Capability 的配置用 JSON Schema 描述。Core 在渲染前完成默认值填充、类型校验、必填校验、条件约束和未知字段校验；配置错误不得进入渲染或 ChangeSet 阶段。

## 6. 依赖与共享文件的结构化贡献

Capability 不得提交原始 `pom.xml`、`package.json` 或任意共享配置片段后再进行文本拼接。依赖和共享文件变更必须转换为结构化 Contribution。

| 目标 | 稳定键 | 合并规则 |
|---|---|---|
| Maven 依赖 | `groupId:artifactId:type:classifier` | 版本由 Base 或显式裁决策略确定 |
| npm 依赖 | `packageName` | 相同包的版本范围必须相容 |
| Spring Bean | 类名或显式 Bean 名 | 重名即冲突，除非扩展点允许 |
| HTTP 路由 | method + path | 完全相同即冲突 |
| 数据表/字段 | 逻辑对象名 | 不兼容定义即冲突 |

共享扩展点由 Base 的 `extension-registry.yaml` 定义，包含标识、目标文件、锚点、允许的贡献类型、顺序策略和冲突策略。未注册的扩展点不可使用。贡献必须在解析后排序并以稳定格式写入，避免输入遍历顺序导致输出漂移。

### 6.1 前端受管组合层

Base 的 `App`、主路由和布局只能依赖 `frontend/src/generated/` 中的稳定入口，不能直接导入 Capability。Core 按有效 Capability 集完整生成 `capabilityProviders.tsx`、`capabilityRoutes.tsx` 和 `capabilityMenus.ts`；空能力集生成恒等 Provider、空路由贡献和原样菜单。Capability 只能贡献 Provider、根路由、页面路由、页面包装器或菜单过滤器，且按 `(order, capabilityId, contributionId)` 稳定排序。

`login` Provider 的顺序为 100，`authorization` 为 200；因此组合时登录上下文始终位于权限 Provider 外层。授权资源键、守卫和 API 类型是 authorization 私有实现，不进入 Base 的公共路由类型或常量文件。

## 7. Migration、文档与工程状态

迁移以版本目录及 `migration.yaml` 管理，声明来源/目标版本、前置条件、结构化步骤、回滚能力和人工介入要求。Core 只计划迁移；执行端负责实际执行和结果回传。

文档按对象归属：Base 文档仅说明 Base；Capability 文档仅说明自身功能、配置和迁移；最终工程的 `AGENTS.md` 描述实际工程结构、已启用能力、构建命令及扩展约定，不承载模板设计全文。

工作区状态至少包含：

- `.template-engine/state.json`：来源、请求与生效配置、能力版本、文件归属和哈希；
- `.template-engine/lock.json`：解析后的精确能力版本与源码提交；
- `.template-engine/changesets/<id>.json`：可审计的变更计划和执行结果。

## 8. 确定性、ChangeSet 与并发控制

期望状态由以下输入唯一决定：Base 版本、`effective` 配置、Capability 精确版本、迁移集合、渲染器版本、格式化器版本和 `sourceRef`。所有配置、状态和 ChangeSet 在哈希前采用确定性序列化。

ChangeSet 是执行端唯一的写入依据，至少包含：

- 元数据：`changeSetId`、输入/输出状态哈希、锁定版本、创建时间；
- 文件操作：新增、更新、删除、移动、每项前置哈希和目标内容或受控内容引用；
- 结构化操作：依赖、扩展点、迁移和配置操作；
- 验证计划、风险和人工介入项。

写入采用 compare-and-set：当前内容或状态与 ChangeSet 前置哈希不一致时必须停止并报告冲突；不得无条件覆盖。人工改动默认保留并进入冲突或人工介入流程。

## 9. 对外接口与 XcodeAgent 协议

Engine Service 的最小接口为：

- `POST /v1/projects/plan`：解析并返回 ChangeSet；
- `POST /v1/projects/simulate`：不产生可应用写入，返回预览和风险；
- `POST /v1/projects/apply`：记录执行请求并返回执行任务或 ChangeSet；
- `GET /v1/projects/{projectId}/state`：读取状态；
- `GET /v1/changesets/{changeSetId}`：读取可审计计划。

所有写接口使用 `Idempotency-Key`；同一调用方和相同请求体返回同一结果，不同请求体使用同一键必须拒绝。服务持久化源快照、锁定版本、状态、幂等记录、ChangeSet 与审计事件。

XcodeAgent 的保留设计是：获取 ChangeSet → 获取工作区锁 → 检查前置哈希 → 应用文件与结构化变更 → 执行验证 → 回传结果 → 可选提交。它不属于本模板工程实施范围，且不得绕过 ChangeSet 直接解释配置或改写模板逻辑。

## 10. 验证与错误契约

Core 必须覆盖配置校验、依赖闭包、冲突检测、确定性、迁移路径、Contribution 合并和 ChangeSet 生成。CLI/Service 必须覆盖适配层与 Core 输出一致性；模板组合至少覆盖 Base、login、authorization、组合安装、升级、冲突和用户改动冲突。

错误统一返回机器可读的 `code`、`message`、`details`、`traceId`。最低错误集合包括：`CONFIG_INVALID`、`CAPABILITY_NOT_FOUND`、`CAPABILITY_CONFLICT`、`VERSION_UNSATISFIABLE`、`EXTENSION_POINT_INVALID`、`MIGRATION_PATH_MISSING`、`STATE_CONFLICT`、`IDEMPOTENCY_CONFLICT` 和 `SOURCE_REF_INVALID`。

# 第二章：实施计划

实施顺序固定为“能力定义与拆分 → 本地组合与调试 → 对外接口”。每一步的验收针对本阶段边界；后续阶段不得替前一阶段补齐其验收缺口。

## 阶段一：模板能力定义与代码拆分

本阶段只建立 `template-source` 与可独立启停的模板能力，不实现配置组合器、HTTP 服务或 XcodeAgent。

| 步骤 | 实施内容 | 可验收结果 |
|---|---|---|
| 1.1 | 建立 `template-source/base`、`capabilities`、`migrations`、`catalog.yaml` 的目录和版本规范 | 新目录可被独立定位；catalog 能列出 Base、`login`、`authorization` 和精确版本 |
| 1.2 | 将现有工程收敛为纯 Base，移除对登录、授权、角色成员等可选功能的编译期和运行期依赖 | Base 后端测试通过，前端构建通过；启动后不暴露登录/授权专属路由或菜单 |
| 1.3 | 将登录的后端、前端、配置、文档和迁移移入 `capabilities/login`，编写完整 manifest | login 文件均由 manifest 覆盖；Base 不再引用 login；单独将 login 叠加到 Base 后可构建运行 |
| 1.4 | 将授权及角色成员能力移入 `capabilities/authorization`，并显式声明与 login 的关系 | authorization manifest 可表达 required/optional/forbidden；独立与组合样例均按声明工作 |
| 1.5 | 修复搬迁前已有的基础测试缺口，例如 `RoleMemberMapper.findByMemberId` 与其 XML 查询保持一致 | `mvn -f template-source/base/backend/pom.xml test` 通过；失败不再由缺失 Mapper 方法导致 |
| 1.6 | 补齐 Base、两个 Capability 的职责、配置、文件归属、迁移和扩展点文档；建立前端受管组合入口与默认欢迎页 | Base 的 App/路由/布局不导入 Capability；空能力集可构建并展示欢迎页；Provider、路由和菜单均只经生成入口接入 |

阶段一退出条件：Base 和两个 Capability 都有明确边界、可独立构建验证，且源码目录中没有以运行开关伪装模块化的残留耦合。

## 阶段二：本地组合引擎与可调试 CLI

本阶段实现 `engine-core` 与 `engine-cli`。目标是用本地命令生成、预览和验证最终工程，不依赖 Service 或 XcodeAgent。

| 步骤 | 实施内容 | 可验收结果 |
|---|---|---|
| 2.1 | 建立无副作用的 `engine-core` 模块，读取 catalog 和 manifests，输出 `requested`、`effective`、诊断信息 | 单元测试验证 required、optional、forbidden、版本冲突和未知 Capability；解析结果稳定 |
| 2.2 | 实现配置 Schema 校验、默认值填充、结构化依赖合并、Extension Point 注册与冲突检测 | 无效配置、未注册扩展点、Maven/npm/路由冲突均在渲染前失败并返回指定错误码 |
| 2.3 | 实现模板渲染、迁移规划、确定性状态、lock 和 ChangeSet 生成 | 相同输入重复执行产生字节级一致的状态和 ChangeSet；输出记录精确 `sourceRef` 和能力版本 |
| 2.4 | 实现 CLI：`validate`、`plan`、`simulate`、`generate`、`verify` | 每个命令可在本地以目录和配置文件运行；`simulate` 不写目标工程，`plan` 可读，`generate` 按 ChangeSet 写入 |
| 2.5 | 实现本地冲突与回归验证：前置哈希检查、人工改动识别、构建测试执行 | 改动受管文件后再次 generate 会停止并报告 `STATE_CONFLICT`；未改动样例可生成并通过后端测试、前端构建 |
| 2.6 | 建立可重复的能力组合矩阵和黄金产物 | 覆盖 Base、login、authorization、组合、升级和冲突；CLI 结果与黄金产物一致且不依赖网络服务 |

阶段二退出条件：开发者只通过 CLI 即可验证“配置 → 有效能力集 → ChangeSet → 生成工程 → 构建测试”的完整链路；Service 尚未存在时也不影响组合正确性验证。

## 阶段三：对外 Engine Service

本阶段仅将已验证的 Core 能力封装为服务，并实现协议规定的持久化、审计和幂等；不得把组合规则复制进 Controller 或任务执行器。

| 步骤 | 实施内容 | 可验收结果 |
|---|---|---|
| 3.1 | 建立 `engine-service`，实现源码快照/Ref 校验、状态、ChangeSet、幂等和审计的持久化模型 | 计划请求可追溯到不可变 sourceRef；重启后可读取既有状态与 ChangeSet |
| 3.2 | 实现 `plan`、`simulate`、`apply`、`state`、`changeset` 五个接口及统一错误模型 | OpenAPI 或等价接口契约可执行；错误码、traceId、鉴权和幂等行为可由集成测试验证 |
| 3.3 | 让 Service 通过 Core 生成结果，并与 CLI 进行一致性回归 | 对同一 sourceRef 和配置，Service 的 effective、stateHash、ChangeSet 与 CLI 相同 |
| 3.4 | 实现服务级并发、失败恢复和审计测试 | 相同幂等键同载荷重试返回同一结果；不同载荷拒绝；过期 stateHash 和并发请求不覆盖既有状态 |
| 3.5 | 形成 XcodeAgent 接入契约和端到端模拟，不在本仓库实现 Agent | 模拟客户端仅消费 ChangeSet；验证 Agent 无法通过接口触发未计划的工作区写入 |

阶段三退出条件：服务只是 Core 的受控入口；CLI 与 Service 对同一输入等价；XcodeAgent 可按稳定 ChangeSet 协议接入而不需要知晓模板内部实现。

## 跨阶段验收原则

- 阶段一验证“能力能被定义和拆分”，不以手工复制项目成功替代验收；
- 阶段二验证“引擎本地组合正确”，不以 HTTP 返回成功替代构建和冲突验证；
- 阶段三验证“协议与运行治理正确”，不以重新实现 Core 逻辑替代一致性验证；
- 任何新增 Capability、扩展点、状态字段、错误码或实施范围变更，都先更新第一章，再在第二章增加相应验收步骤。
