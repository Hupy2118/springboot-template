# 内置 RBAC 权限体系分阶段实施计划

## 前置设计

权限资源点分为三种：

- page：控制页面、菜单和路由访问。
- operation：控制前端操作入口及对应后端业务能力。
- data：定义可以访问的业务数据集合。

### 页面资源点

页面资源点直接复用正式 `pageId`，权限契约不再重复保存前端实现使用的 PageKey：

```json
{
  "resourceKey": "page_order_list",
  "origin": "business",
  "type": "page",
  "name": "订单列表",
  "description": "访问订单列表页面",
  "sourceRuleIds": ["<ruleId>"],
  "targetResourceRef": "page:page_order_list"
}
```

规则：

- `resourceKey = pageId`，二者均为 `lower_snake_case`。
- 页面路由和菜单引用相同的页面资源点。
- 父菜单只用于组织展示，不自动授予子页面。
- 获得页面权限不代表自动获得页面中的操作权限。

### 操作资源点

操作资源点代表一项前后端共同的业务能力：

```json
{
  "resourceKey": "approve_order",
  "origin": "business",
  "type": "operation",
  "name": "审批订单",
  "description": "发起并完成订单审批",
  "sourceRuleIds": ["<ruleId>"],
  "targetResourceRef": "action:approve_order"
}
```

使用规则：

- `resourceKey = actionId`，二者均为 `lower_snake_case`，不再增加重复的 operationKey。
- 前端按钮、菜单操作或交互入口通过相同 resourceKey 控制展示。
- 对应后端 Endpoint 使用相同 resourceKey 强制鉴权。
- 用户绕过前端直接请求 Endpoint 时仍然必须被拦截。
- 纯 UI 行为，如展开面板、切换页签，不创建操作资源点。
- PageImplementationContract 和 EndpointDetail 只能引用 ProductPlan 已确认的操作资源点，不能临时创建。
- 一个操作资源点可以被多个页面和多个 Endpoint 复用。

### 数据资源点

数据资源点只定义“可授权的数据集合是什么”：

```json
{
  "resourceKey": "created_orders",
  "origin": "business",
  "type": "data",
  "name": "本人创建的订单",
  "description": "当前登录成员创建的订单集合",
  "sourceRuleIds": ["<ruleId>"],
  "targetResourceRef": "order_api#/schemas/Order",
  "definition": {
    "includes": "创建人为当前登录成员的订单",
    "excludes": "由其他成员创建且与当前成员不存在其他授权关系的订单"
  },
  "requiredSubjectAttributes": ["user_id"],
  "policyKey": "created_orders_policy"
}
```

字段含义：

- `resourceKey = dataRuleKey`，`policyKey = <dataRuleKey>_policy`，均为 `lower_snake_case`。
- targetResourceRef：该数据集合保护的正式 API Schema。
- definition：供用户确认和策略设计使用的精确业务边界。
- requiredSubjectAttributes：策略依赖的认证用户属性。
- policyKey：连接独立 DataPolicyDetail 和后端策略实现的稳定标识。

数据资源点为后续代码提供：

- 可授权的数据集合标识。
- 受保护的数据类型。
- 数据集合的业务边界。
- 策略详设的唯一索引。

数据资源点不保存数据库表名、SQL、Java 表达式或任意可执行 DSL。具体实现由 DataPolicyDetail 定义。

### 角色资源授权

三类资源点通过同一张授权关系分配；RequirementSpec 中每条权限规则使用 `defaultGrantedRoleIds` 明确首次默认授予哪些已确认业务角色：

```json
{
  "roles": [
    {
      "roleSeedKey": "manager",
      "name": "审批人员",
      "isSystemRole": false,
      "isInitialAdminRole": false
    }
  ],
  "roleResourceGrants": [
    {
      "roleSeedKey": "manager",
      "resourceKeys": ["page_order_list", "approve_order", "created_orders"]
    }
  ]
}
```

授权表只回答角色拥有哪些资源点，不包含 Endpoint 实现逻辑。

### 权限管理固定资源

权限管理模块使用平台固定的 `system` 资源类型和 snake_case 命名，只注入一个固定资源点：

```text
system_authorization_management  system，targetResourceRef=authorization-api.v1#management-control-plane
```

- `/roles` 菜单、路由、页面内操作和所有权限管理 Endpoint 统一绑定 `system_authorization_management`；固定系统页面是业务页面/操作资源规则的唯一例外。
- `/api/authorization/status` 和 `/api/authorization/me/effective-permissions` 只要求可信身份认证，不绑定系统管理资源。
- 系统管理员角色默认获得该唯一系统资源；后端仍须独立鉴权，不能只依赖前端入口隐藏。

## 文档地位与当前状态

本文是 XCodeAgent 权限体系改造的唯一实施依据。后续模型实施权限相关工作时，必须先读取本文，再读取对应阶段列出的代码入口；对话中的历史方案、旧测试假设和未写入本文的临时结论都不能覆盖本文。

最新确认优先级固定为：

1. 本文“不可违背的业务不变量”和决策表。
2. 当前阶段的产物、禁止事项和启动验收。
3. 公共契约中的字段、稳定标识和追踪关系。
4. 模型提示词或现有实现细节。

如果现有代码、测试或提示词与更高优先级规则冲突，必须先修正冲突，不能通过增加默认规则、兼容分支或自然语言关键词匹配绕过。

当前实施状态：

| 阶段                            | 状态   | 说明                                                                                                                                |
| ------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| 1. 原子修正应用权限配置         | 已实施 | 已切换到 v5 内置 RBAC 配置，删除 provider/独立运行态页面字段；配置冲突通过现有 AG-UI 前置澄清并原子持久化后再继续 RequirementSpec。 |
| 2. RequirementSpec 权限语义     | 已实施 | 权限候选、来源、隐藏稳定 `ruleId` 与 Markdown/编辑器校验已落地；阶段 4 实施前置收敛会删除旧行为字段并增加默认角色引用。             |
| 3. ProductPlan 与 UiDesign 边界 | 已实施 | ProductPlan 已升级 v5，只保存业务页面/action 及规则到目标的稳定映射；角色、资源键和固定页面均被拒绝，UiDesign 只消费业务页面。      |
| 4. TechnicalPlan 固定资源编译   | 未实施 | 按本计划先收敛上游当前契约，再实现 `authorization-manifest.v2`、统一资源键、默认角色授权及初始系统管理员选择。                      |
| 5. EndpointDetail 数据权限      | 未实施 | 必须等待固定数据资源和策略键。                                                                                                      |

### 当前工作流适配（本次实施）

需求与产品规划现在构成一个用户可见的“需求文档”联合节点：RequirementSpec 先记录业务角色及其职责和权限候选，ProductPlan 在同一节点内消费已校验草稿生成页面与操作；二者只能通过一次 `requirement_document_confirmation` 联合确认后，UiDesign、TechnicalPlan 才能读取正式文件。内部继续保留各自 Markdown/JSON，ProductPlan 的 `requirement_spec_sha256` 必须等于同轮已确认 RequirementSpec 的确定性哈希；不生成 `requirement-document-manifest.json`。因此本文所有“RequirementSpec 确认后再生成 ProductPlan”的旧描述均以此规则为准替换。
| 6. 工程初始化接入内置权限基础 | 未实施 | 分为后端模板、前端模板和 XCodeAgent 生命周期三个独立验收包；必须等待 TechnicalPlan 资源 manifest。 |
| 7. Build DAG 和授权运行时 | 未实施 | 使用 MyBatis + Flyway 实现运行态；必须等待阶段 3 至 6 确认。 |
| 8. 失效规则与完整回归 | 未实施 | 必须在前序阶段完成后执行。 |

## 不可违背的业务不变量

### 实施范围固定为内置权限

- 本计划只实现内置数据库 RBAC，不实现外部授权 provider、外部权限资源同步、外部角色/成员适配或 provider capability 协商。
- 从授权领域的类型、表单、持久化、规划、模板、Build 和测试中删除 `providerMode` 与授权语义的 `external_api` 分支。
- 业务数据源类型 `external_api` 与外部接口实体设计不属于授权 provider，必须完整保留，不能因本计划被删除或改名。
- 内置 RBAC 依赖应用数据库。静态应用不能启用 RBAC；用户必须改为数据库应用或移除权限需求。

### 三层事实必须分离

- `application.json.authorization` 只声明是否启用内置 RBAC 和首次初始化的管理员成员种子，不是业务页面、操作或数据权限的来源。
- RequirementSpec 只保存用户明确提出的页面、操作和数据范围权限、首次默认授权业务角色及系统管理员角色选择，是权限业务语义的唯一产品事实来源。
- TechnicalPlan 将已确认需求规则确定性编译为固定资源目录和目标绑定；资源定义不进入运行态人工配置。
- TechnicalPlan 可以保存由已确认事实确定性编译的首次角色种子和默认角色资源关系；运行时代码仍不得按角色名称、角色 ID 或系统属性分支，首次初始化后角色、成员和资源关系动态配置。

权限能力开启不等于业务对象自动受控。身份认证也不等于 RBAC 资源控制。

### 未提及功能默认不受 RBAC 控制

- 用户未明确提出控制的页面，不生成页面资源点，不生成菜单或路由权限守卫。
- 用户未明确提出控制的操作，不生成操作资源点，不隐藏或禁用该操作。
- 用户未明确提出控制的数据范围，不生成数据资源点，不生成数据过滤算法。
- 对已认证成员而言，未生成资源点的业务功能默认可见可用；是否要求登录仍由独立身份认证契约决定。
- `/roles` 与 `system_authorization_management` 是平台固定控制面，不受“未提及业务功能默认可见”规则影响。

### 资源固定、关系动态

- 系统资源和业务资源共同构成固定资源目录。
- 系统资源由平台确定性注入；业务资源由 TechnicalPlan 根据已确认规则和稳定目标 ID 确定性编译。
- 资源定义包括 `resourceKey`、类型、语义、目标和数据策略；TechnicalPlan 确认后冻结，运行态管理 API 不提供资源创建、修改或删除能力。
- 生成应用运行态只允许读取资源目录，以及创建、修改、启停角色，配置角色资源关系和成员角色关系。
- 应用规划修订只有经过重新确认、重新 Build 和目录协调后才能改变资源目录；普通运行态操作不能改变资源定义。

### 运行态授权代数固定

- 首版只支持 allow 关系，不支持显式 deny。
- 不支持角色继承、成员直接授权和按角色名称或角色 ID 的代码分支。
- 成员有效资源是其全部启用角色所绑定资源的并集。
- 多个数据策略作用于同一业务目标时采用 OR/可见范围并集；对象校验任一策略通过即可。
- 无角色、角色停用、资源未知或受控目标没有有效资源授权时默认拒绝。

### RequirementSpec 决策表

| 权限能力 | 用户业务描述                       | RequirementSpec 结果                       | 是否澄清                               |
| -------- | ---------------------------------- | ------------------------------------------ | -------------------------------------- |
| 关闭     | 未提及权限控制                     | `enabled=false`，三类候选为空              | 否                                     |
| 开启     | 未提及页面、操作或数据控制         | `enabled=true`，三类候选为空               | 只确认系统管理员角色选择               |
| 开启     | 明确页面、操作或数据控制并说明角色 | 只生成明确候选及其 `defaultGrantedRoleIds` | 否                                     |
| 开启     | 明确受控目标但未说明授权角色       | 保留已确认候选，不推断角色                 | 是，只确认该候选首次默认授予哪些角色   |
| 开启     | 存在一个管理员类业务角色           | 保留业务角色和明确业务授权                 | 是，确认复用该角色或新建系统管理员角色 |
| 开启     | 存在多个管理员类业务角色           | 保留全部业务角色                           | 是，选择唯一系统管理员角色或新建       |
| 开启     | 明确提出权限但业务含义不完整       | 保留已确认事实，不推断缺失语义             | 是，只询问该业务歧义                   |
| 关闭     | 业务描述明确要求权限控制           | 不得静默丢弃，也不得自动开启               | 是，确认启用并补齐配置，或移除该需求   |

三个候选数组彼此独立，任何一个数组为空都是合法状态。禁止为了“结构完整”补写 `scope=all`、默认受控页面、默认受控操作或默认业务授权。

### 代码职责边界

| 层级                        | 允许职责                                                                                                       | 禁止职责                                                     |
| --------------------------- | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| 需求模型                    | 理解用户业务语言；提取明确权限候选和角色授权事实；提出真实业务歧义                                             | 根据权限开关、初始管理员或登录配置推断业务权限；生成技术 ID  |
| RequirementSpec service     | 归一化当前字段；生成和保留 `ruleId/dataRuleKey`；校验角色引用和业务语义                                        | 使用关键词解释权限语义；补默认授权；生成页面/实体/资源绑定   |
| requirement-document node   | 执行配置冲突、业务角色/职责、权限语义、授权角色和系统管理员选择澄清，并联合确认 RequirementSpec 与 ProductPlan | 检查问题文本关键词；自动选择角色；静默修改应用配置           |
| RequirementSpec UI/Markdown | 用业务语言展示候选、默认业务角色和系统管理员选择；保存不可见稳定标记                                           | 要求用户填写技术 ID、资源键、策略键、SQL 或数据库字段        |
| ProductPlan                 | 承接已确认页面和业务 action/step，建立稳定目标 ID                                                              | 新增权限语义、资源键、角色或固定权限管理页                   |
| TechnicalPlan               | 确定性编译资源键、策略键、目标绑定、首次角色种子和默认授权矩阵                                                 | 让模型决定最终键或角色授权；创建无来源资源；写入特定角色判断 |
| EndpointDetail              | 细化已声明数据策略的执行算法和失败模式                                                                         | 新建资源、改变 policy 或扩大数据范围                         |
| 生成应用运行时              | 读取固定资源目录；动态配置角色和成员关系；执行最终裁决                                                         | CRUD 资源定义；直接授权成员；按固定角色判断                  |

### 确定性逻辑允许与禁止清单

确定性代码允许：

- 读取系统生成的结构化配置事实。
- 归一化当前契约字段和空数组。
- 为新权限候选生成 UUID `ruleId`，为已有候选保留稳定 ID。
- 按稳定 `pageId`、`actionId` 和 `dataRuleKey` 编译资源键与策略键。
- 校验 enum、条件必填、来源覆盖、目标绑定和资源目录一致性。
- 权限关闭时清空业务权限候选和管理员种子。
- 校验 `defaultGrantedRoleIds`、初始系统管理员角色和角色系统属性之间的一致性。
- 拒绝手写 `resourceKey`、手写 `policyKey`、SQL 和数据库字段进入 RequirementSpec。

确定性代码禁止：

- 使用“全部、本人、自己、部门、组织”等关键词或正则判断自由业务语言中的权限语义。
- 从整段需求文本自动推断 `all`、`own`、`organization` 或 `custom`。
- 因为 `enabled=true`、登录开启或存在 `/roles` 而生成任何业务资源候选。
- 自动选择第一个页面、操作或实体绑定权限规则。
- 对用户未提及的权限维度发起澄清。
- 为页面或操作候选补写默认未授权行为。
- 根据“管理员”等角色名称自动选择系统管理员角色，或生成按角色名称、角色 ID 和系统属性判断的裁决逻辑。
- 在授权领域重新引入 `providerMode` 或外部 provider 分支。

受控系统配置解析与自由业务语言解释必须区分：前者只能读取平台生成的固定事实，后者只能由需求模型完成。后续结构化规划上下文必须优先使用结构化字段，不能依赖文本标记作为长期契约。

### 权限候选来源与稳定追踪

- 每个权限候选在内部 JSON 中包含系统生成、不可变且唯一的 `ruleId`。
- `ruleId` 不在普通确认 UI 中显示，也不能由用户手填；Markdown 使用不渲染的内部标记保留 ID，以支持编辑同步。
- 结构化编辑保留原 `ruleId`；新增候选生成新 ID；删除后重新新增视为新规则，不复用旧 ID。
- Markdown 同步必须携带当前 JSON 隐藏状态；保留标记的候选保留 ID，缺失标记的新候选生成新 ID，重复或未知标记拒绝确认。
- 每个候选的 `sourceRefs` 必须指向用户原始业务描述、权限澄清回答或 RequirementSpec 确认修改。
- 权限开关和初始管理员配置不能作为业务候选的 `sourceRefs`。
- TechnicalPlan 的 `sourceRuleIds` 直接引用 RequirementSpec 的稳定 `ruleId`。
- 用户未提出的候选不存在“默认来源”，也不能使用 `authorization_default` 等伪来源。

## 模型实施执行协议

后续使用 Luna 或其他模型实施任一阶段时，必须按以下顺序执行：

1. 读取 `AGENTS.md`、`docs/CODEBASE_INDEX.md` 和本文完整内容。
2. 只读取当前阶段列出的入口文件及其直接依赖，不提前实现后续阶段。
3. 在修改前列出当前阶段的事实来源、允许推导、禁止推导和不会改动的边界。
4. 先补正向、负向和冲突场景测试，再实现代码；测试必须同时证明“应生成什么”和“不应多生成什么”。
5. 每个编号阶段作为独立工作包，完成后项目必须仍可启动，不允许用只能在未来阶段验证的中间结构作为交付结果。
6. 运行阶段要求的后端测试、前端构建和启动验收；失败必须在进入下一阶段前解决。
7. 搜索新增代码中的自然语言权限关键词表、角色名分支、默认权限规则、无来源资源、资源写接口和授权 provider 分支；发现即停止验收。
8. 更新本文阶段状态和必要的 `docs/CODEBASE_INDEX.md`，再开始下一阶段。
9. 每个阶段完成后必须向用户提交修改文件、启动命令、测试结果和遗留问题，由用户明确验收通过后才能开始下一阶段；模型不得把多个未验收阶段合并执行。
10. 阶段 6 必须按 `6A 后端模板 → 6B 前端模板 → 6C XCodeAgent 生命周期` 顺序独立交付和验收。
11. 修改模板前必须确认 XCodeAgent、前端模板和后端模板三个仓库均有本地可写工作区；缺少写权限时停止并报告，不能通过临时复制仓库规避。
12. 本计划不授权模型推送远端、创建 PR 或提交到模板仓库；这些外部写操作必须另行取得用户授权。

## 核心流程与事实来源

权限流程固定为：

```text
新建应用内置权限开关与管理员种子
→ RequirementSpec 确认明确提出的权限业务逻辑、默认授权角色和唯一初始系统管理员角色
→ ProductPlan 建立稳定业务页面与 action
→ UiDesign 只设计业务页面
→ TechnicalPlan 确定性编译固定资源目录、目标绑定和首次默认授权
→ EndpointDetail 细化已声明数据策略
→ 工程初始化接入模板固定 /roles 页面和内置权限基础
→ Build 生成授权运行时和业务守卫
→ 运行态配置角色、角色资源关系和成员角色关系
```

事实归属固定为：

- 新建应用配置：声明是否启用内置 RBAC 和首次管理员成员种子。
- RequirementSpec：权限业务语义、默认授权业务角色和初始系统管理员角色选择的唯一产品事实来源。
- ProductPlan：保持产品行为并建立稳定 `pageId/actionId`，不定义资源键、角色或角色分配。
- TechnicalPlan：编译固定资源点、策略键、目标绑定、角色种子和首次默认授权，不改变已确认权限业务含义。
- 固定权限管理页：系统拥有，不属于业务页面，不进入 ProductPlan、UiDesign、EndpointDetail 或普通页面 Build。
- EndpointDetail：只细化 TechnicalPlan 已声明的数据策略执行算法。
- 生成应用后端：最终授权裁决者和运行态关系事实来源。

应用配置与 RequirementSpec 的 `enabled` 在确认时必须一致。应用配置继续作为管理员种子的基础设施事实来源，但不得用于推导业务资源。

## 公共契约调整

### 新建应用配置 v5

应用配置升级为当前 `schemaVersion: 5`：

```ts
type ApplicationAuthorizationSeed = {
  enabled: boolean;
  initialAdministratorSubjects: string[];
};
```

表单规则：

- 认证区域后保留独立“权限控制”区域，只展示“涉及权限控制”开关和初始管理员成员标识。
- 启用权限会自动启用身份认证；权限开启期间不能单独关闭认证。
- 启用权限要求当前应用使用数据库，并至少填写一个认证系统中真实存在或可预配置的 `subjectId`。
- 不预填、不识别也不持久化 `current-user` 等魔法占位符；初始管理员成员标识按普通字符串精确匹配认证系统返回的 subject。
- 关闭权限时清空初始管理员种子。
- 删除权限 provider 和运行态页面选项；启用权限即确定性接入内置权限服务与 `/roles`。
- 打开工作区时只接受当前 v5 权限字段，不为旧结构增加探测、转换或回退。

应用创建后，表单值写入 `application.json.authorization` 和首次规划请求。若新建时关闭权限、业务描述又明确要求权限控制，前置澄清必须：

1. 确认启用权限或移除该业务要求。
2. 选择启用时确认数据库前提和初始管理员成员。
3. 通过现有 AG-UI 规划动作原子更新认证与 authorization 配置。
4. 配置持久化成功后重新生成 RequirementSpec，不允许只修改内存草稿。

### 可信认证身份边界

生成应用的认证方案固定通过 Spring Security 向授权模块提供可信身份：

- 一号通认证实现负责在服务端校验 Cookie/会话并建立 Spring Security `Authentication`，不能把前端解析结果视为可信身份。
- 授权模块通过 `CurrentSubjectProvider` 读取 `SecurityContext` 中已认证的 `Authentication.getName()`，并将其作为唯一当前 `subjectId`。
- 前端不解析 Cookie，不在请求体、请求头或查询参数中提交“当前用户 subjectId”；成员管理接口路径中的 `subjectId` 只表示被管理对象。
- 一号通认证未能提供经过验证的 `Authentication` 时，权限运行时不能标记为 ready，阶段 7 不能通过验收。
- 未认证访问由 Spring Security 返回 401；身份认证成功后才进入资源裁决并可能返回 403。
- RBAC 模块不得自行复制一号通协议实现，也不得从 `authnSource`、`clientId` 或任意未验证字符串推导当前成员。

### RequirementSpec 权限需求

正式字段调整为：

```ts
type AuthorizationRuleBase = {
  ruleId: string;
  name: string;
  description: string;
  rationale: string;
  sourceRefs: string[];
  defaultGrantedRoleIds: string[];
};

type AuthorizationRequirements = {
  enabled: boolean;
  restrictedPages: AuthorizationRuleBase[];
  restrictedOperations: AuthorizationRuleBase[];
  dataRules: Array<
    AuthorizationRuleBase & {
      dataRuleKey: string;
      includes: string;
      excludes: string;
    }
  >;
  initialAdminRoleId?: string;
};

type RequirementRole = {
  id: string;
  name: string;
  description: string;
  isSystemRole: boolean;
  isInitialAdminRole: boolean;
};
```

`RequirementRole` 是现有 RequirementSpec 顶层 `user_roles[]` 的当前结构，不是在 `AuthorizationRequirements` 内复制第二份角色列表。其 `id` 必须是稳定 `lower_snake_case`，后续直接作为 `roleSeedKey`。

当前契约校验：

```text
每条页面、操作或数据规则
→ defaultGrantedRoleIds 必须非空，且每项都引用 user_roles[].id

每条数据规则
→ dataRuleKey 必须为 RequirementSpec 内唯一的 lower_snake_case
→ includes/excludes 必须共同给出精确业务边界

authorization.enabled=true
→ initialAdminRoleId 必须引用唯一 isInitialAdminRole=true 的 user_roles[]
→ isInitialAdminRole=true 必然同时 isSystemRole=true

authorization.enabled=false
→ 三类候选为空，所有角色的两个系统属性为 false，initialAdminRoleId 不存在
```

补充约束：

- RequirementSpec 不保存 `unauthorizedBehavior` 或任何同义行为字段；未登录访问统一由身份认证层处理。
- 页面无权行为固定为菜单和入口隐藏，直接访问路由返回 403 禁止页。
- 操作无权行为固定为入口隐藏，后端 Endpoint 独立校验并返回 403。
- 数据规则的列表过滤、对象校验和失败模式在 EndpointDetail 确认，不加入页面/操作展示行为。
- RequirementSpec 不携带 `pageId`、`entityId`、`operationId`、路由、资源键或角色关系。
- `ruleId` 是内部稳定追踪字段；`dataRuleKey` 是数据规则的稳定业务标识，二者都不是用户手填的技术绑定。
- 新数据规则的 `dataRuleKey` 由 RequirementSpec 需求模型基于已确认业务语义提出；服务端在归一化前校验 `lower_snake_case`、唯一性和非空。语义匹配到已有规则时强制保留原 key，模型缺失、改写或输出非法 key 时进入修复/确认，不能静默另造 key。
- 用户明确提出受控目标却未说明默认授权角色时，必须通过现有 AG-UI 澄清，不得猜测或留空。
- 业务描述中存在管理员类角色时，必须询问是否让其中一个角色承担系统权限管理；用户明确不合并时才新增独立系统管理员角色。
- 有多个管理员类角色时必须让用户选择唯一角色或新建独立角色；不得根据名称自动选择。
- `isSystemRole`、`isInitialAdminRole` 仅是角色种子元数据，不产生隐式授权，也不得用于业务裁决分支。

### 固定权限管理页

当 XCodeAgent 选择前后端 `auth` 分支时，模板确定性增加：

```text
route: /roles
pageId: system_authorization_management
name: 权限管理
```

固定页面包含：

- 只读页面、操作、数据和系统资源目录。
- 角色列表、创建、修改、启用、停用和删除。
- 角色资源矩阵。
- 成员列表、预配置成员、JIT 成员和成员角色设置。
- 成员最终有效权限。
- 授权审计和当前 revision。
- 明暗主题完整状态。

固定系统资源键由平台确定性注入，且只包含：

```text
system_authorization_management  system
```

资源定义全部只读。拥有 `system_authorization_management` 的成员可以使用同一组管理 API 查看和维护资源关系、角色、成员角色关系、有效权限与审计，不再为页面、读写动作或接口拆分资源点。页面菜单对无该资源的成员隐藏；直接访问路由或调用管理 API 均返回 403。`/api/authorization/status` 和当前成员自己的 `/api/authorization/me/effective-permissions` 只要求可信认证，不绑定该系统资源。

页面所有权规则：

- 不加入 RequirementSpec `pages`，只在权限需求章节展示为系统固定页面。
- 不加入 ProductPlan `pages`，不生成 UiDesign。
- 不出现在业务页面设计入口和开发任务规划中。
- 不生成普通 PageImplementationContract 或 EndpointDetail。
- 模板初始化直接复制完整默认实现，不创建待 Agent 填充的占位页。
- `/roles` 是保留系统路由，所有业务页面必须避免冲突。

### ProductPlan 与 TechnicalPlan 衔接

ProductPlan 使用 `product-plan.v5`，不保存资源键、策略键或角色矩阵。

确定性约束：

- ProductPlan 中带授权引用的页面和 action/step 必须能追溯到已确认的页面或操作规则；未受控业务页面和操作不要求权限规则。
- `pageId/actionId/stepId` 在 ProductPlan 阶段生成，不能反向要求用户填写技术 ID。
- ProductPlan 不得出现 `allowed_roles`、`allowedRoleIds` 或任何特定角色判断。
- ProductPlan 不包含固定权限管理页；UiDesign 只处理 ProductPlan 业务页面。

TechnicalPlan 资源点契约：

```ts
type PermissionResourcePoint = {
  resourceKey: string;
  origin: "system" | "business";
  type: "page" | "operation" | "data" | "system";
  name: string;
  description: string;
  sourceRuleIds: string[];
  targetResourceRef: string;
  definition?: {
    includes: string;
    excludes: string;
  };
  requiredSubjectAttributes?: string[];
  policyKey?: string;
};
```

确定性资源键：

```text
页面资源：resourceKey = pageId
操作资源：resourceKey = actionId
数据资源：resourceKey = dataRuleKey
数据策略：policyKey = <dataRuleKey>_policy
系统控制面：system_authorization_management（type=system）
```

编译与校验规则：

- 所有 `pageId`、`actionId`、`dataRuleKey`、`resourceKey` 和 `policyKey` 使用 `lower_snake_case`；禁止点号前缀和同义重复键。
- 页面权限 manifest 只保存 `pageId`，不再保存 PageKey/pageKey；前端实现可有组件名，但不能进入权限契约。
- 模型只为数据规则提供 `entityIds`、`endpointIds`、`targetSchemaRef` 和 `requiredSubjectAttributes` 技术绑定；确定性服务生成全部资源键、策略键和授权关系。
- 每个受控页面规则必须绑定一个页面资源；多个规则指向同一页面时复用资源并聚合 `sourceRuleIds`。
- 每个受控操作规则必须绑定一个操作资源；多个规则指向同一 action 时复用资源并聚合来源。
- 每个数据规则独立产生一个数据资源和策略键。
- 所有业务资源必须 `origin=business` 且至少包含一个有效 `sourceRuleIds`。
- 唯一系统资源必须 `origin=system`、`type=system`、`sourceRuleIds=[]`，由平台注入，模型不能改名、删除或拆分。
- 资源键重复但类型、目标或语义不一致时拒绝确认。
- TechnicalPlan 不能改变数据范围含义、默认角色授权或初始系统管理员选择，也不能写入角色名/角色 ID 裁决逻辑。

TechnicalPlan 生成带稳定 fingerprint 的权限资源 manifest：

```ts
type AuthorizationManifestV2 = {
  schema_version: "authorization-manifest.v2";
  enabled: boolean;
  resources: PermissionResourcePoint[];
  bindings: {
    pages: Array<{ pageId: string; resourceKey: string }>;
    actions: Array<{ actionId: string; resourceKey: string }>;
    endpoints: Array<{
      endpointId: string;
      operationResourceKeys: string[];
      dataPolicyKeys: string[];
    }>;
    dataRules: Array<{
      ruleId: string;
      dataRuleKey: string;
      resourceKey: string;
      policyKey: string;
      entityIds: string[];
      endpointIds: string[];
      targetSchemaRef: string;
      requiredSubjectAttributes: string[];
    }>;
  };
  defaultRoleAuthorization: {
    roles: Array<{
      roleSeedKey: string;
      name: string;
      description: string;
      isSystemRole: boolean;
      isInitialAdminRole: boolean;
    }>;
    roleResourceGrants: Array<{ roleSeedKey: string; resourceKeys: string[] }>;
    initialAdminRoleSeedKey: string;
  };
  fingerprint: string;
};
```

- `roleSeedKey` 确定性复用已确认 `user_roles[].id`，不得由模型改写。
- 业务资源只按每条规则的 `defaultGrantedRoleIds` 聚合到对应角色；不得把未明确授权的业务资源加入角色。
- 被选为初始系统管理员的角色固定获得唯一系统资源；若复用业务管理员角色，则同时保留该角色明确获得的业务资源；若新建独立角色，则默认只有该系统资源。
- PageImplementationContract、EndpointDetail、Build DAG、前后端守卫和运行态资源投影必须引用同一 manifest。
- TechnicalPlan Markdown 必须按页面、操作、数据资源和角色默认授权分组展示人类可读表格，同时保留 `ruleId → target → resourceKey` 追踪关系；用户不需要手工编辑技术 ID。

### 生成应用运行态 OpenAPI 契约

本仓库的 [`contracts/authorization-api.v1.yaml`](../contracts/authorization-api.v1.yaml) 是前后端共享的权限管理接口唯一事实源，契约版本固定为 `authorization-api.v1`。后端 `auth` 分支提交与该文件字节一致的 `src/main/resources/openapi/authorization-api.v1.yaml` 副本；前端从该本地契约确定性生成 TypeScript 类型，但所有 HTTP 请求必须复用模板现有 `src/apis/service.ts` 的 axios 实例，不生成或引入第二个 HTTP 客户端。XCodeAgent 必须校验两个 YAML 文件的 SHA-256 一致，并验证前端生成类型无漂移。

固定接口：

```text
GET  /api/authorization/status
GET  /api/authorization/me/effective-permissions
GET  /api/authorization/resources
GET  /api/authorization/resources/{resourceKey}
GET  /api/authorization/roles
POST /api/authorization/roles
GET  /api/authorization/roles/{roleId}
PUT  /api/authorization/roles/{roleId}
DELETE /api/authorization/roles/{roleId}
PUT  /api/authorization/roles/{roleId}/status
GET  /api/authorization/roles/{roleId}/resources
PUT  /api/authorization/roles/{roleId}/resources
GET  /api/authorization/members
GET  /api/authorization/members/{subjectId}
PUT  /api/authorization/members/{subjectId}/roles
DELETE /api/authorization/members/{subjectId}
GET  /api/authorization/members/{subjectId}/effective-permissions
GET  /api/authorization/audit
```

- 除 `/status` 和 `/me/effective-permissions` 外，上述权限管理 Endpoint 全部绑定唯一的 `system_authorization_management`，不按页面、读写动作或接口继续拆分资源。
- `/roles` 页面也绑定 `system_authorization_management`，前端入口控制和后端不可绕过校验引用同一资源。
- `POST /roles` 请求为 `{name, description?, expectedRevision}`；`PUT /roles/{roleId}` 只允许更新 `{name, description?, expectedRevision}`；`PUT /roles/{roleId}/status` 为 `{active, expectedRevision}`。
- `PUT /roles/{roleId}/resources` 为 `{resourceKeys, expectedRevision}`，按全量替换处理；`DELETE /roles/{roleId}` 为 `{expectedRevision}`，不接受资源或系统属性字段。
- `PUT /members/{subjectId}/roles` 为 `{roleIds, displayName?, expectedRevision}`，按全量替换成员角色处理，并可作为精确 subject 的预配置入口；`DELETE /members/{subjectId}` 为 `{expectedRevision}`，只撤销本地授权记录。

响应与并发契约：

- 列表响应统一包含 `items`、`total`、`page`、`pageSize` 和读取时的 `revision`。
- 资源 DTO 至少包含 `resourceKey`、`origin`、`type`、`name`、`description`、`semanticDefinition`、可选 `targetResourceRef` 和可选 `policyKey`。
- 角色 DTO 至少包含 `roleId`、`name`、可选 `description`、`active`、`deleted`、`isSystemRole`、`isInitialAdminRole` 和关联 `resourceKeys`；成员 DTO 至少包含 `subjectId`、可选 `displayName`、来源状态和关联 `roleIds`。
- `isSystemRole` 和 `isInitialAdminRole` 由首次种子创建过程写入，在普通角色创建、更新和资源关系 API 中只读；客户端提交修改时必须拒绝，不能静默忽略。
- 有效权限响应包含 `subjectId`、最终 `resourceKeys` 和 `revision`。
- 创建/更新/删除角色、角色状态替换、角色资源全量替换、成员角色全量替换和删除本地成员授权记录请求都必须携带 `expectedRevision`；成功响应返回最新 `revision`。
- `DELETE /roles/{roleId}` 只允许删除未拥有 `system_authorization_management` 的角色，采用逻辑删除并撤销其关联关系；`DELETE /members/{subjectId}` 只删除本地授权记录及角色关系，不删除外部身份。
- 错误响应统一包含 `code`、`message`、可选 `currentRevision` 和可选 `details`。
- 固定错误码至少包含 `unauthenticated`、`forbidden`、`authorization_not_ready`、`authorization_revision_conflict` 和 `last_administrator_required`。

接口注册与就绪状态矩阵：

| 状态                                    | `/api/authorization/status`                                                            | 当前成员有效权限                               | 权限管理接口                                   |
| --------------------------------------- | -------------------------------------------------------------------------------------- | ---------------------------------------------- | ---------------------------------------------- |
| auth 分支、Build 运行时未就绪           | 公开 200，返回 `ready=false`、`contractVersion=authorization-api.v1` 和非敏感 `reason` | 在解析身份前统一 503 `authorization_not_ready` | 在解析身份前统一 503 `authorization_not_ready` |
| auth 分支、运行时就绪、未认证           | 公开 200，返回 `ready=true`                                                            | 401 `unauthenticated`                          | 401 `unauthenticated`                          |
| auth 分支、运行时就绪、已认证但无资源   | 公开 200，返回 `ready=true`                                                            | 200，仅返回当前成员有效资源，可以为空          | 403 `forbidden`                                |
| auth 分支、运行时就绪、拥有系统管理资源 | 公开 200，返回 `ready=true`                                                            | 200                                            | 200                                            |

未就绪 `reason` 只允许公开非敏感枚举：`runtime_not_built`、`database_not_initialized`、`resource_manifest_mismatch`；就绪时 `reason=null`。不得返回数据库地址、异常堆栈、Cookie、token 或内部配置。`AuthorizationReadinessFilter` 必须先于身份认证过滤器处理权限管理接口：未就绪时除 `/status` 外统一返回 503；就绪后再执行 Spring Security 的 401 和 RBAC 的 403 判定。

### 运行态数据与服务契约

内置权限运行时固定使用 MyBatis + Flyway。Flyway 只维护当前契约的初始化迁移，不增加历史 schema 探测、旧表读取或双写兼容；MyBatis 负责权限查询、关系全量替换、revision 锁和事务内持久化。

运行态至少包含：

```text
authorization_resources       resourceKey 主键、固定 manifest 投影与 fingerprint，只允许部署协调器写入
authorization_roles           UUID roleId 主键、标准化名称唯一、说明、active/inactive、两个只读系统属性、创建更新时间
authorization_role_resources  roleId + resourceKey 联合主键的 allow 关系
authorization_subjects        subjectId 主键、展示信息、seed/preconfigured/jit 来源和发现时间
authorization_subject_roles   subjectId + roleId 联合主键的成员角色关系
authorization_audit           操作者、动作、目标、变更前后 JSON 摘要、时间和 revision
authorization_revision        单例记录，保存当前 revision、初始化状态和 manifest fingerprint
```

稳定管理服务能力：

```text
listResources
listRoles
createRole
updateRole
setRoleStatus
replaceRoleResources
listSubjects
replaceSubjectRoles
getEffectivePermissions
listAudit
deleteRole
deleteSubjectAuthorization
```

运行态约束：

- 不提供 `createResource`、`updateResource` 或 `deleteResource`。
- `roleId` 是稳定 UUID；角色名称按标准化值唯一，可改名。
- 普通运行态新建角色的 `isSystemRole=false`、`isInitialAdminRole=false`；这两个属性不是授予权限的替代机制，也不能被普通角色 API 修改。
- 角色可启用、停用和逻辑删除；拥有 `system_authorization_management` 的角色不可删除，其他角色删除时撤销关联关系并保留审计历史。停用或已删除角色不贡献有效权限。
- `replaceRoleResources` 和 `replaceSubjectRoles` 是全量替换，不支持成员直接资源授权。
- 可以通过精确 subject 标识预配置尚未登录的成员；首次认证时 JIT 补充展示信息，但不得覆盖已有角色关系。
- 所有写操作携带 `expectedRevision`；服务在事务中校验 revision、计算变更后有效权限、执行防锁死检查、写关系与审计并递增 revision。
- 并发 revision 冲突不做自动合并，返回可重新加载的冲突结果。
- 权限 DDL、Flyway migration 和 MyBatis Mapper 只由阶段 7 的 Build 生成；阶段 6 后端模板只提供稳定端口和未就绪实现，不能提前创建表或内存模拟持久化。

### 初始管理员与防锁死

- RequirementSpec 必须恰有一个 `isInitialAdminRole=true` 的角色，且该角色同时 `isSystemRole=true`；其他角色可以是系统角色，但不能再标记为初始管理员角色。
- 若用户选择复用业务管理员角色，首次初始化沿用其角色种子；若用户明确不合并，首次初始化创建独立系统管理员角色。两种情况都不得根据角色名称自动判断。
- 将 `system_authorization_management` 绑定给所选角色，再把 `initialAdministratorSubjects` 作为该角色的默认成员；复用业务角色时还保留其明确的业务资源，不能自动获得全部业务资源。
- 两个系统属性只记录角色来源和系统归属，不产生隐式权限；最终裁决始终只读取角色资源关系。
- 初始管理员角色后续可以改名、调整资源或停用；`initialAdministratorSubjects` 也可以通过成员角色配置调整，不要求这些成员或该初始角色永久承担管理职责。
- 每次管理写事务完成后，系统必须仍至少存在一个活跃成员，通过一个或多个启用角色的资源并集拥有 `system_authorization_management`；该成员和角色不必是初始种子。
- 移除自己的管理权限、停用最后有效管理角色、删除最后有效管理员成员、删除最后有效成员关系或撤销最后一组系统资源授权时，整笔事务拒绝。
- 管理员种子只在首次初始化生效；后续 Build 或启动不得重新覆盖运行态角色与成员配置。

## 分阶段实施与启动验收

### 1. 原子修正应用权限配置

主要入口：`Frontend/src/renderer/src/typings/application.ts`、新建应用表单与默认值、规划请求、`Frontend/src/main/managedWorkspace.ts` 及相关测试。

改造内容：

- 将应用配置原子切换为 schema v5。
- 删除授权 `ApplicationAuthorizationProviderMode`、`providerMode`、外部授权选项和运行态页面开关。
- 启用权限时确定性启用认证和 `/roles`，校验数据库应用与管理员种子。
- 规划请求只携带权限开关和初始管理员，不再携带 provider 或页面开关。
- 增加权限关闭但需求要求权限时的前置配置澄清和原子持久化。
- 只删除授权 provider 逻辑，不修改业务数据源 `external_api`。

启动验收：

- 表单中没有 provider 和运行态页面选项。
- 权限开启后不能关闭认证，静态应用不能启用权限。
- 权限开启时至少存在一个真实管理员 `subjectId`，空值和 `current-user` 占位符均拒绝保存。
- 新工作区只写入 v5 当前字段，旧授权字段被拒绝。
- 配置冲突选择启用后，`application.json` 持久化成功才继续 RequirementSpec。
- 前端相关测试、`pnpm build`、后端 `/health` 和桌面开发态通过。

### 2. RequirementSpec 生成并确认权限逻辑

主要入口：需求分析器、`Backend/app/services/requirement_spec.py`、requirements node、Markdown 同步、RequirementSpec 权限摘要与编辑器。

阶段状态：已实施（基线能力已完成）。

已实施结果：

- 需求分析器只提取用户明确提及的页面、操作和数据候选，三类候选独立可空，未提及维度不生成规则。
- 服务端不采信模型传入的陌生 `ruleId`，以语义匹配保留已有 ID 或生成 UUID。
- Markdown 同步拒绝未知或重复隐藏标记；结构化编辑新增候选会记录 RequirementSpec 确认修改来源。
- 草稿保存与正式确认门禁已分离；固定 `/roles` 只作为系统页面说明展示。

当前实现与最新契约仍有差异：旧页面/操作行为字段尚未删除，`defaultGrantedRoleIds`、`dataRuleKey/includes/excludes`、角色系统属性和初始管理员角色选择尚未接入。因为阶段 2 已完成其原始基线，本计划不回退其状态；这些当前契约收敛项统一作为阶段 4 的前置子阶段 4A 实施并回归阶段 2/3。

### 3. 固化 ProductPlan 和 UiDesign 边界

主要入口：ProductPlan 生成/校验、ProductPlan Markdown、UI 设计生成池和确认面板。

- 页面候选映射稳定业务页面，操作候选映射稳定 action/step。
- ProductPlan 不保存角色、角色关系、资源键、策略键或固定权限管理页。
- 没有对应 RequirementSpec 规则的页面/action 不得新增权限语义。
- UI 设计只处理业务页面；`/roles` 不产生 UI 设计任务，UI skip 也不影响固定页面。

启动验收：

- 三类候选为空时 ProductPlan 不新增授权引用。
- 只存在单一候选类型时，不扩展到其他页面、操作或数据维度。
- 修改目标稳定 ID 导致规则无法映射时不能确认。
- `ui-designs.json` 中永远没有 `system_authorization_management`。

实施结果：`product-plan.v5` 不再持久化 `user_roles` 或页面 `allowed_roles`。服务仅把已确认的 `restrictedPages`、`restrictedOperations` 确定性映射为 `authorizationTargets.pageRules[{ruleId,pageId}]`、`operationRules[{ruleId,actionId}]`；候选无法一对一映射、目标被删除或映射被篡改都会阻止确认。映射不包含角色、资源键或策略键，也不会在 ProductPlan Markdown 或 UiDesign 中展示为角色判断。

### 4. TechnicalPlan 确定性编译固定资源

主要入口：TechnicalPlan planner、planning node、project plan service、plan documents、PageImplementationContract 和 API contract。

阶段状态：未实施。当前工作区中即使已有部分试验性 TechnicalPlan 代码或 v1 字段，也不得视为本阶段完成；实施时按当前契约直接替换，不增加 v1 兼容读取或双写。

#### 4A. 收敛已实施阶段的上游当前契约

子阶段状态：已实施。阶段 4 的 4B–4D 仍未实施，`authorization-manifest.v2` 尚未生成。

- RequirementSpec 删除 `unauthorizedBehavior`、`unauthorizedPage`、`unauthorizedOperation` 及所有同义字段、澄清问题、Markdown 和 UI 编辑项，统一采用本文固定 403/隐藏行为。
- `user_roles[]` 增加必填只读语义字段 `isSystemRole`、`isInitialAdminRole`；权限开启时确认唯一初始管理员角色，支持复用业务管理员或新建独立系统管理员。
- 收敛 `user_roles[].id`、ProductPlan `pageId/actionId` 为唯一 `lower_snake_case` 当前契约；禁止 CamelCase、kebab-case、重复 PageKey/operationKey 和兼容别名。已有未确认产物重新生成，已确认产物按正式修订流程失效并重新确认，不做旧键迁移。
- 三类权限规则增加非空 `defaultGrantedRoleIds` 并校验全部引用 `user_roles[].id`；缺少授权角色时通过现有 AG-UI 问题澄清，不能猜测。
- 数据规则增加稳定 `dataRuleKey` 和精确 `includes/excludes`；需求模型只为新规则提出语义化 `lower_snake_case` key，服务端校验并为已有规则强制保留，用户不手填，删除后重建不得复用旧规则身份。
- 更新 RequirementSpec JSON/Markdown 同步、编辑器、摘要、确认门禁、提示词和测试；确认 Markdown 修改时保留隐藏 `ruleId/dataRuleKey` 及角色引用。
- ProductPlan 继续只保存 `authorizationTargets.pageRules[{ruleId,pageId}]` 与 `operationRules[{ruleId,actionId}]`，不得复制角色和数据规则；它可在同一需求文档节点消费已校验 RequirementSpec 草稿，但必须与该草稿联合确认并写入匹配的 `requirement_spec_sha256`，不得恢复已删除字段。
- 清除 planner 和相关提示词中的旧角色示例、点号资源前缀、PageKey 权限字段和行为字段，避免模型继续产生旧结构。

4A 验收：

- 权限开启时先通过 JSON-only 事实提取并在归一化前校验明确业务角色、页面、操作和数据范围；字段遗漏或空占位进入模型自动修复，不能要求用户重复已描述内容。没有业务候选时，只在已持久化的角色事实基础上澄清/确认初始系统管理员角色，不生成业务资源候选或行为字段。
- 每条明确候选都具备非空有效 `defaultGrantedRoleIds`；未说明角色时问题 ID 稳定，回答后重新生成并确认 RequirementSpec。
- 业务管理员合并、明确拆分独立系统管理员、多管理员选择三个场景均得到唯一 `isInitialAdminRole=true` 角色。
- `isInitialAdminRole=true && isSystemRole=false`、多个初始管理员、未知角色引用、重复/非法 `dataRuleKey` 均阻止确认。
- CamelCase、kebab-case 或重复的角色 ID、`pageId/actionId/dataRuleKey` 均在进入 TechnicalPlan 前阻止确认。
- 阶段 2 和阶段 3 的现有正向、负向、Markdown 编辑同步及确认门禁测试全部通过。

#### 4B. 限制模型输出并执行归一化前校验

- TechnicalPlan 模型只能为每条已确认数据规则输出 `entityIds`、`endpointIds`、`targetSchemaRef`、`requiredSubjectAttributes`；页面和操作绑定直接消费 ProductPlan 的稳定映射。
- 在任何确定性归一化、字段删除或默认补全之前校验原始模型输出；出现模型手写 `resourceKey/policyKey`、角色授权、点号前缀、角色型 `authentication` 或未声明字段时直接进入现有自动修复/失败路径，不能通过归一化掩盖漂移。
- `api_contracts.py` 是 API `authentication` 唯一规范，所有 API Contract 和 Endpoint 的认证字段都只能是 `{required: boolean}`；planner、service、Markdown 和测试不得维护第二套角色认证结构。
- 删除 `permission_model`、`authentication.roles` 和角色型 `permissionBindings`；操作资源与数据策略绑定只能进入 manifest 的 `bindings.endpoints`。

4B 验收：

- 原始模型输出 `authentication.roles`、字符串 authentication、手写资源键或未知授权字段时，在归一化前得到明确校验错误。
- 合法 `{required:boolean}` 不被自动修复改写；API Contract 与 Endpoint 使用同一验证器和错误文案来源。
- 代码搜索只存在 `api_contracts.py` 的认证字段定义，planner 中不存在旧角色认证示例。

#### 4C. 编译 `authorization-manifest.v2`

- RBAC 关闭时输出 `enabled=false`、空资源/绑定/默认授权；RBAC 开启时确定性注入唯一 `type=system` 的 `system_authorization_management`。
- 页面规则编译为 `resourceKey=pageId`，操作规则编译为 `resourceKey=actionId`，数据规则编译为 `resourceKey=dataRuleKey`、`policyKey=<dataRuleKey>_policy`；所有 key 必须为 `lower_snake_case`。
- 多条规则指向同一页面或 action 时复用资源并聚合去重 `sourceRuleIds`；同一 action 的全部后端能力绑定同一操作资源。
- `/roles` 与每个管理 Endpoint 统一绑定 `system_authorization_management`；状态接口和当前成员有效权限接口仅要求认证，不绑定管理资源。
- 确定性编译 `defaultRoleAuthorization.roles`、`roleResourceGrants` 和 `initialAdminRoleSeedKey`。角色 seed key 复用 `user_roles[].id`，业务授权只来自 `defaultGrantedRoleIds`，初始管理员角色额外且仅固定获得该唯一系统资源。
- fingerprint 覆盖规范化后的 resources、bindings 和 defaultRoleAuthorization；数组排序与去重规则固定，重复编译必须字节级稳定。
- PageImplementationContract 只投影已确认的 `{targetType,targetId,resourceKey}`；页面投影只使用 `pageId`，不保存 PageKey/pageKey。EndpointDetail 只消费已声明的数据策略。

4C 验收：

- 三类业务候选为空时只生成唯一系统资源和初始系统管理员的一项默认授权。
- 只存在一种业务候选时只生成对应资源类型；未提及业务目标没有资源、绑定或守卫。
- 相同确认输入重复编译得到相同 manifest 和 fingerprint；只改文案但保留稳定 key/目标时资源键不变，语义变更仍触发重新确认。
- 复用业务管理员时该角色获得唯一系统资源和明确业务资源；拆分角色时独立管理员默认只有该系统资源。
- 无来源、规则漏覆盖、未知目标、key 冲突、未知角色引用、多个初始管理员或模型越权字段均阻止 TechnicalPlan 确认。

#### 4D. TechnicalPlan 文档整合与阶段交付

- TechnicalPlan JSON 只新增根字段 `authorization_manifest`，结构严格使用本文 `authorization-manifest.v2`，不在 API Contract、页面或实体中复制另一份资源目录或角色矩阵。
- TechnicalPlan Markdown 分别展示页面资源、操作资源、数据资源、Endpoint 绑定和默认角色授权表；显示业务名称、稳定 key、目标和来源规则，禁止只输出 UUID 或难以理解的点号路径。
- RequirementSpec、ProductPlan、UiDesign、TechnicalPlan 的重新确认和下游失效遵守现有正式文档确认门禁；自动修复后的 TechnicalPlan 仍必须重新确认。
- 更新 `docs/CODEBASE_INDEX.md` 中发生变化的契约、服务和文档边界，并记录阶段 4 的实际测试与启动结果；全部验收通过后才能把本阶段状态改为“已实施”。

阶段 4 完整启动验收：

- 后端聚焦测试、修改文件 `py_compile`、前端 `pnpm build`、后端 `/health` 和桌面开发态均通过。
- 生成并确认至少五组 TechnicalPlan：无业务权限、仅页面、仅操作、仅数据、合并/拆分系统管理员的组合权限。
- TechnicalPlan JSON、Markdown、PageImplementationContract 和 API Contract 对同一资源/目标的引用一致，不存在 v1、`business.*`、`system.authorization.*`、PageKey 或行为字段残留。

### 5. EndpointDetail 细化数据权限

主要入口：page/entity detail services、detail documents、build context resolver 和 planning node。

- EndpointDecision 只接收 TechnicalPlan 已声明的数据资源、策略键和已确认 EntityDesign。
- 生成 collection filter、object check、执行点、字段/关系引用、失败模式和正反测试向量。
- 不允许新建资源、修改 policyKey、改变数据范围或 API Schema。
- 同一目标上的多个有效数据策略按 OR/范围并集组合；分页必须发生在权限过滤之后。
- 固定权限管理 API 属于平台运行时，不进入普通 EndpointDetail。

启动验收：

- 列表过滤位于分页后、对象修改发生在校验前或引用未知字段的方案不能确认。
- 没有数据规则的 endpoint 不因登录、实体或页面资源自动获得数据过滤。
- 受控数据目标没有有效数据资源时默认拒绝，而不是静默扩大为全部数据。

### 6. 工程初始化接入模板权限基础

本阶段拆为三个必须顺序执行、独立启动和独立由用户验收的工作包。前后端模板按权限开关成对选择分支：`authorization.enabled=false` 时均使用 `main`，`authorization.enabled=true` 时均使用 `auth`；不允许混用分支或由调用方指定任意分支。

#### 6A. 后端模板权限契约与可启动骨架

模板仓库：<https://github.com/Hupy2118/springboot-template.git>

分支边界：权限契约、Controller 骨架、OpenAPI、`CurrentSubjectProvider` 和未就绪实现只存在于 `auth` 分支；`main` 分支不得包含运行态权限 API、OpenAPI 或权限运行时骨架。

固定实现边界：

- 在 `src/main/java/com/cmbchina/backend/authorization/` 增加 Controller、DTO、应用端口、领域类型、异常映射、就绪状态和 `CurrentSubjectProvider`。
- 在 `src/main/resources/openapi/authorization-api.v1.yaml` 提交与本仓库 `contracts/authorization-api.v1.yaml` 字节一致的副本；本地契约文件才是唯一事实源。
- 后端权限骨架只识别 `system_authorization_management` 一个 `type=system` 资源：除状态与当前成员权限查询外，资源目录、角色、角色资源、成员和审计接口均由该资源守卫；不得恢复页面资源与管理操作资源的双资源模型。
- 若模板尚未接入 Spring Security，本工作包只增加由 Spring Boot BOM 管理的 `spring-security-core` 编译依赖，用于 `SecurityContextHolder`/`Authentication` 类型；不得引入会默认保护全站的 starter 或伪造认证 Filter。实际一号通认证和 Web Security 配置由阶段 7 接入。
- `auth` 分支不保留 `xcodeagent.authorization.enabled` 运行时开关；公开 `/api/authorization/status` 和管理接口骨架始终注册。默认就绪状态为 `ready=false`；状态接口返回 200，其他接口统一返回结构化 503 `authorization_not_ready`。
- `CurrentSubjectProvider` 固定读取 Spring Security `Authentication.getName()`，但本工作包不伪造身份、不信任前端 subject，也不复制一号通认证协议。
- 只交付稳定编译边界，不增加 Flyway DDL、MyBatis Mapper、测试身份、内存角色数据或假审计；这些只能在阶段 7 实现。
- 保持 Spring Boot 2.7 和 Java 8 兼容，所有新增或实质修改的方法按模板工程规范添加中文用途注释。

独立验收：

- 后端模板测试和 Maven 构建通过，应用可以独立启动。
- `main` 分支不存在状态及管理接口；`auth` 分支状态接口返回契约版本和 `ready=false`，其他权限接口返回 503，响应不泄露内部信息。
- OpenAPI 与 Controller/DTO 骨架通过契约测试，不存在未在 OpenAPI 声明的权限接口。

#### 6B. 前端模板完整权限管理能力

模板仓库：<https://github.com/ruyue1/frontend-template.git>

分支边界：`AuthorizationManagementPage`、`src/authorization/` 和权限 service 封装只存在于 `auth` 分支；`main` 分支不得注册 `/roles`、权限菜单、Provider 或权限网络请求。

固定实现边界：

- 用完整页面替换现有 `src/pages/System/Role/index.tsx` 占位实现，固定页面目录为 `src/pages/System/AuthorizationManagementPage/`。
- 增加 `src/authorization/`，包含由本地 OpenAPI 生成的 TypeScript 类型、`authorizationApi.ts`、PermissionProvider、`can(resourceKey)`、菜单/路由/操作守卫及统一错误状态处理；禁止生成独立 Axios 客户端。
- `authorizationApi.ts` 必须从模板现有 `src/apis/service.ts` 导入 `service`，按 OpenAPI operationId 封装所有权限请求；页面和 Provider 只能调用这些封装函数，不能直接调用 axios、fetch 或拼装第二套请求。

  ```ts
  export const getMyEffectivePermissions = () =>
    service.get<EffectivePermissions>(
      "/api/authorization/me/effective-permissions",
    );
  ```

- GET 列表参数统一通过 `{ params }` 传递；POST/PUT 直接传请求 DTO；带 `expectedRevision` 的 DELETE 使用 `service.delete(path, { data: request })`，不得改成查询参数。
- `PermissionProvider` 只以 `can("system_authorization_management")` 守卫 `/roles`、权限菜单及管理页面全部操作；前端不得为管理页面、角色读写或成员操作派生额外资源键。
- `auth` 分支始终将 `/roles` 注册为 layout 下的独立系统路由和系统菜单，不把它放入业务 `/page` 菜单树或业务页面生成逻辑；不增加前端权限开关配置。
- `PermissionProvider` 每次挂载时都直接通过 `authorizationApi.ts` 请求 `/api/authorization/me/effective-permissions`。请求完成前保持 loading 并按无权限处理；成功后仅将本次响应的 `resourceKeys` 放入当前 Provider 的 React 状态用于渲染，不写入 localStorage、sessionStorage、IndexedDB、Electron 存储、模块全局变量或 service 单例。页面刷新或 Provider 重新挂载必须重新请求接口；401 清空当前状态并走登录处理，503 直接展示权限运行时未就绪状态，其他错误进入统一错误态。
- 页面完整提供只读资源目录、角色创建/修改/启停/删除、角色资源全量替换、成员列表与预配置/移除、成员角色全量替换、有效权限和审计查询。
- `/roles` 路由、菜单和页面内所有管理请求统一使用 `system_authorization_management`；前端不得将入口隐藏视为后端鉴权的替代。
- 页面必须覆盖 loading、empty、error、401、403、409、503，以及明暗主题下的背景、文字、边框、hover/focus、弹层和禁用状态。
- 增加 `authorization:types` 脚本，使用 lockfile 固定版本的 `openapi-typescript` 只生成类型到 `src/authorization/generated/authorizationApiTypes.ts`。

独立验收：

- 前端测试和 `pnpm build` 通过，开发服务器可以启动。
- 使用 6A 后端骨架启动联调时，当前成员有效权限请求返回 503 后，`/roles` 正确显示“权限服务尚未就绪”，而不是额外查询状态接口或崩溃。
- `main` 分支路由、菜单和网络请求均不存在；`auth` 分支固定系统路由存在且不污染业务菜单，并通过当前成员有效权限接口获得资源点。
- 连续刷新页面时每次都会重新请求 `/api/authorization/me/effective-permissions`；两次请求之间修改当前成员角色或角色资源后，刷新必须立即反映最新资源点，不得命中任何前端缓存。
- 资源目录没有创建、编辑和删除入口；生成类型与本地 OpenAPI 一致，全部权限请求均经过 `authorizationApi.ts` 和模板既有 `service.ts`。

#### 6C. XCodeAgent 模板生命周期接入

主要入口：application lifecycle、template generation、Electron 模板下载、对应 AG-UI protocol 和 frontend service。

固定实现边界：

- 从已持久化的 `application.json.authorization.enabled` 确定唯一 `templateBranch`：关闭为 `main`，开启为 `auth`；前后端必须使用同一分支并通过 `git clone --branch <templateBranch> --single-branch --depth 1` 浅克隆到生成项目的 `frontend/` 和 `backend/`。
- 分支选择不得来自前端自由输入或请求参数。用户自定义模板仓库 URL 可以保留，但 RBAC 开启时两个自定义仓库都必须存在并遵守 `auth` 分支契约；缺失时初始化失败。
- 选择 `auth` 分支后，使用本仓库本地契约生成前端类型，并校验本地契约与后端 `auth` 分支 OpenAPI 副本 SHA-256 一致；不得从网络再次获取另一份契约，也不得生成独立 HTTP 客户端。
- `auth` 分支不写入前后端权限开关配置，`/roles` 与权限接口作为该分支固有能力存在；`main` 分支不包含权限能力。
- `auth` 分支的文件级 capability gate 必须检查后端 OpenAPI 副本及其本地契约 SHA-256、权限骨架、前端固定页面、Provider、生成类型、`authorizationApi.ts` 和既有 `service.ts` 复用关系；`main` 分支必须验证这些权限能力均不存在，不能只检查 `package.json`、`pom.xml` 或目录存在。
- 先初始化业务页面，再校验所有业务路由不得与 `/roles` 冲突；固定页面不读取 UiDesign，不创建普通 PageImplementationContract、EndpointDetail 或业务开发任务。
- `auth` 分支写入 `.xcodeagent/authorization/permission-resources.json` 和 `.xcodeagent/authorization/foundation-manifest.json`。阶段 6 只写唯一系统资源 manifest 和基础接入状态，不写 DDL 或声称运行时 ready；`main` 分支不创建该目录。
- 模板生成 manifest 记录前后端仓库 URL、所选 `templateBranch`、各自实际 commit SHA，以及 `authorizationBackendContract`、`authorizationFrontend`、`authorizationConfig` 和 `authorizationGate`。已有模板目录只有 manifest 中的来源 URL 和分支与当前选择相同、且前后端 commit SHA 均存在时才能复用；记录缺失或不匹配时 fail closed，不能覆盖或混用。
- XCodeAgent 的规划、配置持久化、模板生成进度、成功和失败仍通过 AG-UI 完整生命周期传递；不能为该流程新增普通 JSON/REST 产品接口。

阶段产物：

```text
auth 分支：
  frontend/src/pages/System/AuthorizationManagementPage/
  frontend/src/authorization/
  backend/src/main/java/com/cmbchina/backend/authorization/
  backend/src/main/resources/openapi/authorization-api.v1.yaml
  contracts/authorization-api.v1.yaml（XCodeAgent 本地唯一事实源）
  .xcodeagent/authorization/permission-resources.json
  .xcodeagent/authorization/foundation-manifest.json
main 分支：无上述运行态权限产物
```

独立验收：

- XCodeAgent 前后端和桌面开发态启动通过，并能分别创建 `auth` 分支的 RBAC 开启应用与 `main` 分支的关闭应用；前后端分支选择、仓库 URL 和 commit SHA 必须成对一致。
- 生成项目模板完成后前后端立即可启动；`main` 分支不存在权限接口和 `/roles`，`auth` 分支的 `/roles` 显示未就绪状态。
- 业务页面设计列表中不存在权限管理页；修改业务路由为 `/roles` 时 ProductPlan 路由校验阻止确认。
- `auth` 分支缺失、前后端分支混用、已有目录来源不匹配、删除任一必需权限模板文件、制造 OpenAPI/生成类型漂移或绕过 `service.ts` 时，模板 capability gate 稳定失败并指出具体缺失项。

### 7. Build DAG 和内置授权运行时

主要入口：task preparer、tasks node、build task planner/menu、Build/Testing subgraph 和 task documents。

固定注入权限任务链：

```text
authorization.storage
→ authorization.core
→ business.authorization
→ authorization.runtime-api
→ frontend.authorization.runtime
→ frontend.authorization.guards
→ authorization.verification
```

实现内容：

- 仅对 `auth` 分支注入权限任务链，并使用 Flyway 生成当前契约的资源投影、角色、关系、成员、审计和 revision 表，使用 MyBatis 实现 Mapper、查询和事务内关系替换；`main` 分支不生成权限 Build 任务、DDL 或守卫。
- 生成 JIT 成员、allow 并集、数据策略合并、缓存失效和管理服务。
- 所有管理写操作实现 `expectedRevision`、事务、审计和防锁死校验。
- 按 manifest 创建带只读 `isSystemRole/isInitialAdminRole` 元数据的初始角色种子、唯一系统资源关系和默认成员关系；重复执行必须幂等且不得覆盖运行态配置。
- 所有权限管理路由和 `/roles` 仅校验 `system_authorization_management`；状态与当前成员有效权限查询仅校验认证，运行时不得创建或解释第二个管理资源键。
- 接入既有一号通 Spring Security 认证；`CurrentSubjectProvider` 只使用经过验证的 `Authentication.getName()`，认证适配未完成时不得把权限服务标记为 ready。
- 完成 OpenAPI 中全部稳定端口，使 `/api/authorization/status` 仅在数据库、资源 manifest、认证适配和运行时服务均就绪后返回 `ready=true`。
- 前端只对已有页面/操作资源生成控制；后端只对已有绑定目标生成守卫。
- `/roles` 页面由模板提供，不创建可修改该页面或资源定义的 Build/SmallTask 任务。

启动验收：

- 没有业务资源时，不向业务页面、操作或 endpoint 注入 RBAC 守卫。
- 只存在部分业务资源时只保护精确目标，不扩大到同页其他操作或同实体其他 endpoint。
- 验证 401、403、页面/操作行为、多角色并集、数据范围并集、停用角色和未知资源拒绝。
- 验证角色创建/改名/启停/删除、角色资源全量替换、成员预配置/JIT/移除和成员角色全量替换。
- 验证 revision 冲突、审计原子性、自我移权、管理角色不可删除和最后管理员保护。
- 验证 Flyway migration、MyBatis Mapper、manifest fingerprint、幂等 bootstrap 和状态接口从未就绪切换为就绪。
- 验证前端伪造 subjectId、未验证 Cookie、空 SecurityContext 和匿名 Authentication 均不能获得权限。
- 代码搜索不存在资源写接口、成员直接授权、角色继承、显式 deny、角色名分支和外部授权 provider。

### 8. 失效、目录协调和完整回归

主要入口：planning revision、planning persistence、application lifecycle、planning workflow 和各阶段测试。

失效规则：

- 新建配置只影响首次 RequirementSpec 和首次管理员 bootstrap；正式需求确认后，业务权限以 RequirementSpec 为准。
- RequirementSpec 新增或删除规则使 TechnicalPlan 和相关下游失效。
- 只修改规则文案且保留 `ruleId`/目标时资源键保持稳定，但语义变更仍重新确认 TechnicalPlan。
- ProductPlan `pageId/actionId` 变化使对应资源绑定和下游失效。
- TechnicalPlan resourceKey、policyKey 或 manifest fingerprint 变化使相关实现契约、EndpointDetail、DAG 和测试失效。
- 部署协调器在事务中写入当前 manifest、删除已移除资源的角色关系并记录目录协调审计。
- 普通启动发现运行态资源投影与 manifest fingerprint 不一致时 fail closed，不自动猜测或兼容。
- 纯业务代码错误进入 SmallTask，不回退权限需求。

完整测试样例：

- 不启用权限的数据库应用。
- 启用权限但没有业务候选的数据库应用。
- 仅页面、仅操作和仅数据范围三种最小权限应用。
- 同时包含页面、操作和多个数据范围的应用。
- 动态扩展角色和运行态配置成员的应用。
- 认证开启但 RBAC 关闭的应用。
- 静态应用申请 RBAC 时的前置拒绝。
- 新建关闭权限、需求阶段确认开启并持久化配置的路径。
- UI 确认和 UI skip 两条模板生成路径。
- 规划修订新增、保留和删除固定资源的目录协调路径。
- 初始管理员为空或使用 `current-user` 占位符时拒绝保存，真实 subject 可以完成幂等 bootstrap。
- 权限关闭、权限开启未 Build、运行时就绪未认证、已认证无资源四种接口状态矩阵。
- 一号通认证只通过 Spring Security `Authentication.getName()` 提供当前 subject，前端伪造 subject 不生效。
- 后端 OpenAPI 与前端生成类型一致，权限 service 封装覆盖全部前端使用的 operationId；`getAuthorizationStatus` 保持后端运维接口且不由前端调用。人为制造契约漂移或直接调用 axios/fetch 时 capability gate 失败。
- 前后端 `main` 分支不包含权限能力；`auth` 分支提供唯一系统资源控制的 `/roles` 和未就绪/就绪状态，且前端生成类型与本地契约一致、两个 YAML 副本 SHA-256 一致。

每个代码工作包均执行：

- 后端相关单元测试和 `py_compile`。
- `cd Frontend && pnpm build`。
- `scripts/start-backend.sh` 后检查 `/health`。
- `pnpm dev` 验收新建、需求确认或工作台界面。
- 从模板阶段开始，通过 `/api/projects/launch` 启动生成应用验收。
- 阶段 6A 在后端模板执行 Maven 测试、构建和 Spring Boot 启动验收；阶段 6B 在前端模板执行测试、`pnpm build` 和开发服务器验收。
- 阶段 6C 必须用本地 OpenAPI 重新生成前端类型，验证生成结果无未提交漂移，并验证 `authorizationApi.ts` 通过既有 `service.ts` 覆盖全部前端使用的 operationId；前端代码搜索不得调用 `/api/authorization/status`。
- 涉及目录、API、存储格式或边界变化时，同一工作包更新 `docs/CODEBASE_INDEX.md`。

## 默认决策

- 只支持内置数据库 RBAC；授权领域不保留外部 provider。
- 权限控制必须依赖身份认证和数据库。
- 启用 RBAC 必然接入 `/roles`，不保存独立运行态页面开关。
- 未提及的业务功能不生成资源点或守卫，对已认证成员默认可见可用。
- RequirementSpec 不保存未授权行为字段；页面入口固定隐藏且直接访问返回 403，操作入口固定隐藏且后端 Endpoint 返回 403。
- `unauthenticated` 属于认证层，不属于 RBAC RequirementSpec。
- 系统资源和业务资源在运行态均为只读固定目录；业务资源由 TechnicalPlan 确定性编译。
- RequirementSpec/TechnicalPlan 只确定首次角色种子和默认授权；首次初始化后，角色、成员角色关系和角色资源关系在运行态动态配置。
- 授权采用 allow 并集，不支持角色继承、显式 deny 或成员直接授权；普通角色可逻辑删除，拥有系统管理资源的角色不可删除。
- 初始管理员是角色的系统元数据属性，不是独立的隐式权限类型；系统始终通过显式资源关系保留至少一个拥有 `system_authorization_management` 的活跃成员。
- 初始管理员必须填写真实、精确的 subject，不使用 `current-user` 或首次访问者占位符。
- 当前 subject 固定来自 Spring Security `Authentication.getName()`；权限模块不信任前端身份输入。
- 本仓库 `contracts/authorization-api.v1.yaml` 是运行态接口唯一事实源，后端 `auth` 分支副本和前端 TypeScript 类型由它同步或生成；前端请求统一复用模板现有 `src/apis/service.ts`。
- `main` 分支不存在权限接口；`auth` 分支运行时未就绪时只有公开状态接口返回 200，其他接口返回 503。
- 模板分支由已持久化的权限开关唯一确定：关闭使用 `main`，开启使用 `auth`；前后端必须成对使用同一分支，调用方不能自由指定分支。
- 后端模板只提供稳定权限骨架，权限 DDL、MyBatis Mapper、Flyway migration 和真实运行时只在阶段 7 Build 生成。
- 不增加用户可选 tag、SHA 或分支；模板生成 manifest 仅记录本次 `main`/`auth` 分支实际拉取的 commit SHA，用于来源核验和安全复用。
- ProductPlan 使用 `product-plan.v5`，不保存 `resourceKey`、`policyKey` 或角色字段。
- 不读取旧权限字段或旧 application schema，不实现兼容分支。
