# 应用配置统一投影改造方案（Codex 实施文档）

> **适用仓库**：`https://github.com/Hupy2118/springboot-template.git`  
> **基准分支**：`template_refactor`  
> **核查提交**：`e474e5dd6120b71b679eed819a1a91f4d810e648`（2026-10-19）  
> **前端构建工具**：Rsbuild `1.0.14` + `@rsbuild/plugin-react` `1.0.4`  
> **实施对象**：模板服务 V3、前端 Base、Login Extension 运行代码、XCodeAgent 请求/Update Executor 对接  
> **状态**：设计与实施指引，尚未执行改造或测试。

## 1. 目标与范围

将 XCodeAgent `.devagentstudio/application.json` 中的**应用基础配置与 YHT 登录配置**统一交由模板服务投影，XCodeAgent 只传业务配置，不再自行生成代码文件或按前/后端拆分配置。

**最终工程产物：**

1. `frontend/.env`：集中写入 `appName`、`appIcon`、`theme.primaryColor` 和启用 YHT 时的 `auth.yht.clientId`。
2. `backend/src/main/resources/application.yml`：在 **`auth:` 根节点**下投影 `client-id`、`private-key`、`public-key`、`center-public-key`。
3. `frontend/src/config/appConfig.ts`：作为静态、类型化的 `.env` 读取适配层，**不再生成/引用 `app.generated.ts`**。

**不修改**前后端 Login `extension.yaml`；这两个文件仍仅用于 Extension 声明。**不将某个应用的配置写回**模板仓库中的 Base 或 Extension 源码。应用实例配置只在 Generate / Update 阶段投影到生成工程。

本期不处理 `track`、`apiTrack`、通用 `env`、Layout 结构、多环境单包、运行时配置中心或 Capability 卸载。

## 2. 基于最新代码的事实与风险

| 位置 | 已核查行为 | 本次调整 |
| --- | --- | --- |
| `frontend/base/src/config/app.generated.ts` | 保存默认名称、图标、主色；注释表明由 XCodeAgent 投影 | **新生成工程删除该文件**，不再由模板服务生成 TS 配置 |
| `frontend/base/src/config/appConfig.ts` | `import { generatedConfig } from './app.generated'` | 改为从 `import.meta.env.PUBLIC_*` 构造 `AppConfig` |
| `frontend/base/src/config/types.ts` | `AppConfig.app.name/icon`、`theme.primaryColor` 均为字符串 | **保留**原类型 |
| `frontend/base/src/layout/index.tsx` | 消费 `appConfig.app.name/icon` | **无需改动** |
| `frontend/base/src/layout/components/AppIcon/index.tsx` | 通过字符串名称匹配 Ant Design Icon/SVG，未知图标回退 | **无需改动** |
| `frontend/base/src/App.tsx` | 以 `appConfig.theme.primaryColor` 设置 Ant Design Token | **无需改动** |
| `frontend/base/src/config/applyTheme.ts` | 在 React 渲染前写 `--app-primary-color` | **无需改动**，主题 CSS 逻辑保持不变 |
| `frontend/base/src/index.tsx` | 调用 `applyTheme()` 后再渲染 | **无需改动** |
| `frontend/base/rsbuild.config.ts` | 固定 `process.env.WORKS_ENV=local`，另有 `source.define` | 允许构建环境覆盖；不重复定义新 `PUBLIC_*` 变量 |
| `frontend/extensions/login/src/constants/yst.ts` | 非本地环境硬编码 Client ID | 改读 `PUBLIC_YHT_CLIENT_ID`，其余认证环境地址不变 |
| `frontend/base/src/rsbuild-env.d.ts` | 尚未声明四个新公开变量 | 补齐声明，保留既有 Rsbuild 类型 |
| `backend/base/src/main/resources/application.yml` | 原有 `server/spring/mybatis-plus` | 仅对**最终工程**受控合并 `auth:` |
| `EngineMapper.requestedConfig()` | 严格仅接收 `capabilities`，返回 Capability Map | 新增独立 `applicationConfig` 解析，不能传给 `ExtensionResolver` |
| `V3ProjectMaterializer` | Base/Extension + Registries/注解/Maven | 增加统一应用配置投影阶段 |
| `V3UpdatePlanner` | 当前仅跟踪模板 Revision、Capability、Extension Artifact、Contributions | 纳入**实际有效配置投影**差异，避免仅改主题色返回 204 |
| `Operation` / 测试 Executor | `REPLACE_MANAGED_FILE` 仅允许五个 Registry | 设计受管 `.env` / YAML 更新，另设计**既有 Base 适配层迁移** |
| `TemplateState` / `StateDigest` | 严格校验状态字段 | 可选新增状态，不改变旧状态的原始摘要 |

**结论：** 三个基础配置字段目前仅通过 `AppConfig` 静态对象被消费，均为简单字符串；把它们转入 `.env` 是可行的。但新工程改动容易，**已生成工程的 `appConfig.ts` 迁移和 V3 Update 才是关键兼容成本**。

## 3. 配置输入与字段映射

### 3.1 XCodeAgent 应用配置保持业务语义

源头仍是 `.devagentstudio/application.json`，不引入 `environmentConfig.frontend/backend`：

```json
{
  "schemaVersion": 6,
  "appName": "人员管理",
  "appIcon": "AppstoreOutlined",
  "theme": { "primaryColor": "#6b3cf0" },
  "auth": {
    "enable": true,
    "authnSource": "yst",
    "yht": {
      "clientId": "example-client-id",
      "privateKey": { "source": "env", "name": "YHT_PRIVATE_KEY" },
      "publicKey": "<应用公钥>",
      "centerPublicKey": "<认证中心公钥>"
    }
  }
}
```

这只是与当前投影相关的字段子集；完整 `application.json` 的 `senario/repoUrl/branchName/menus/apis/authorization/...` 及其所有已有字段不变。

- `privateKey` 为环境变量引用 `SecretRef`，**不是私钥原文**；第一期仅允许 `source=env`。
- 私钥实际值由部署环境注入，不放入 XCodeAgent 持久化配置、HTTP 请求、仓库、ZIP、TemplateState、日志或前端。
- 当前使用字符串私钥的项目必须先迁移或安全清理；不得将原始明文当作此新字段直接发送。

### 3.2 映射表（最终确定）

| application.json 来源 | 最终工程目标 | 写入键/属性 |
| --- | --- | --- |
| `appName` | `frontend/.env` | `PUBLIC_APP_NAME` |
| `appIcon` | `frontend/.env` | `PUBLIC_APP_ICON` |
| `theme.primaryColor` | `frontend/.env` | `PUBLIC_THEME_PRIMARY_COLOR` |
| `auth.yht.clientId` | `frontend/.env` | `PUBLIC_YHT_CLIENT_ID` |
| `auth.yht.clientId` | `backend/src/main/resources/application.yml` | `auth.client-id` |
| `auth.yht.privateKey` | 后端同上 | `auth.private-key`（Spring 占位符） |
| `auth.yht.publicKey` | 后端同上 | `auth.public-key` |
| `auth.yht.centerPublicKey` | 后端同上 | `auth.center-public-key` |

**重要边界：** 四个 `PUBLIC_*` 值均进入浏览器构建产物，不能承载秘密。`appIcon` 仍是既有字符串图标标识，而不是文件引用或 React 组件。

### 3.3 扩展 V3 请求，不新增 HTTP 接口

```json
{
  "requestedConfig": {
    "capabilities": {
      "login": { "enabled": true, "config": {} }
    },
    "applicationConfig": {
      "appName": "人员管理",
      "appIcon": "AppstoreOutlined",
      "theme": { "primaryColor": "#6b3cf0" },
      "auth": {
        "enable": true,
        "authnSource": "yst",
        "yht": {
          "clientId": "example-client-id",
          "privateKey": { "source": "env", "name": "YHT_PRIVATE_KEY" },
          "publicKey": "<应用公钥>",
          "centerPublicKey": "<认证中心公钥>"
        }
      }
    }
  }
}
```

协议规则：

1. `capabilities` 按当前逻辑处理；`applicationConfig` 为**可选**独立对象，不进入 `ExtensionResolver`。
2. 新版 XCodeAgent 传递 `appName`、`appIcon`、`theme` 和 `auth` 的完整业务快照；不传 `schemaVersion`、`configRevision` 等与文件投影无关的元数据。
3. 旧客户端若完全不传 `applicationConfig`，Generate 继续成功并使用前端适配层默认值；Update 省略该字段表示**保留已记录配置**，不能重置。
4. 传入 `applicationConfig` 时，基础三个字段必须齐全且类型正确；`auth` 可以省略，明确启用 YHT 时四项齐全且 Login Capability 启用。
5. `auth.enable=true/authnSource=yst` 与 `capabilities.login.enabled` 不一致，或 Login 启用且显式 `auth.enable=false`，返回配置冲突；旧请求（没有 `applicationConfig`）沿用历史兼容行为。
6. `authnSource` 非 YHT 不启用 YHT 投影；本期不支持其他认证源的配置生成。
7. `configRevision` 不是 Template Revision；**Update 比较归一化后的有效配置，不依赖 `configRevision` 的数字大小**。

## 4. 最终工程产物与前端代码改造

### 4.1 统一 `frontend/.env`

```dotenv
# BEGIN DEVAGENTSTUDIO MANAGED: app-frontend
PUBLIC_APP_NAME="人员管理"
PUBLIC_APP_ICON="AppstoreOutlined"
PUBLIC_THEME_PRIMARY_COLOR="#6b3cf0"
PUBLIC_YHT_CLIENT_ID="example-client-id"
# END DEVAGENTSTUDIO MANAGED: app-frontend
```

- 无 YHT/无 Login 时**不写入** `PUBLIC_YHT_CLIENT_ID`，其余三个基础字段仍可生成。
- 不包含 `privateKey`、`publicKey`、`centerPublicKey`。
- 使用安全 dotenv 编码（引号、``、`#`、反斜杠、换行等），对不允许的多行值直接拒绝，确保不可借字段值注入其他配置行。
- **一个受管区块 `app-frontend`**，同一文件内管理四个公共字段，避免前后两个 Renderer 分别覆盖 `.env`。
- 若已有用户维护的 `.env`，保留区块外所有内容；发现区块外已有同名受管 Key 时返回冲突，不产生两个不同来源的同名键。
- 该文件在 Rsbuild `1.0.14` **构建期**被加载；更新 `.env` 后须重新构建前端，不能声称浏览器可热更新已发布静态包。
- `.env` 只包含非敏感客户端值；不得将私钥移入其中。保持项目已有 `.gitignore` 策略，对真正的本地秘密另用未跟踪的环境注入。

### 4.2 前端 `appConfig.ts` 改为纯环境读取适配层

修改 `template-source/code/frontend/base/src/config/appConfig.ts`：

```ts
import type { AppConfig } from './types';

export const appConfig: AppConfig = {
  app: {
    name: import.meta.env.PUBLIC_APP_NAME || '示例应用',
    icon: import.meta.env.PUBLIC_APP_ICON || 'AppstoreOutlined',
  },
  theme: {
    primaryColor:
      import.meta.env.PUBLIC_THEME_PRIMARY_COLOR || '#6b3cf0',
  },
};
```

- 保留 `types.ts`、`applyTheme.ts`、`App.tsx`、`layout/index.tsx`、`AppIcon/index.tsx` 的现有消费结构。
- 删除模板 Base 的 `src/config/app.generated.ts`；新 Generate 不应再含该文件。
- 不引入新的 JSON/TS 配置投影，也不改变 Layout 结构及图标查找逻辑。
- 不修改 Ant Design `ConfigProvider` 的主题绑定或原有 CSS `--app-primary-color` 链路。
- 可以给 `PUBLIC_THEME_PRIMARY_COLOR` 增加 `#[0-9A-Fa-f]{6}` 等颜色格式校验，避免无效 CSS；`appName` 与 `appIcon` 要求非空、长度受限。

### 4.3 Rsbuild 类型与 YHT 适配

修改 `frontend/base/src/rsbuild-env.d.ts`，在**原有** `ImportMetaEnv` 接口中追加：

```ts
interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  readonly MODE: string;
  readonly PUBLIC_APP_NAME: string;
  readonly PUBLIC_APP_ICON: string;
  readonly PUBLIC_THEME_PRIMARY_COLOR: string;
  readonly PUBLIC_YHT_CLIENT_ID: string;
}
```

修改 `frontend/extensions/login/src/constants/yst.ts`：仅将 `prd/biz/test` 分支的 `CLIENT_ID` 改为 `import.meta.env.PUBLIC_YHT_CLIENT_ID || ''`；`local` 的模拟/免真实 SSO 行为保持不变；不修改已有认证中心地址、登出/跳转策略。

修改 `frontend/base/rsbuild.config.ts`：使 `WORKS_ENV` 支持外部构建变量覆盖、默认 `local`，如 `process.env.WORKS_ENV ?? 'local'`。`PUBLIC_*` 使用 Rsbuild 的 `.env` 读取机制，不再另设 `source.define` 同名键，不将应用名称、主题色写死在构建配置中。

**补充**：当前 `frontend/index.html` 浏览器标签仍是固定的 `CMB React App`，favicon 与 `appIcon` 不联动。本次只改变**应用 UI 中的名称/图标/主题**，不扩大为网页标题与 favicon 的自定义。

### 4.4 后端 `application.yml`

最终工程目标：`backend/src/main/resources/application.yml`。

```yaml
# BEGIN DEVAGENTSTUDIO MANAGED: login-auth
auth:
  client-id: example-client-id
  private-key: ${YHT_PRIVATE_KEY}
  public-key: "<应用公钥>"
  center-public-key: "<认证中心公钥>"
# END DEVAGENTSTUDIO MANAGED: login-auth
```

- **必须是 `auth:`**，不得写成 `yht:` 或 `auth.yht:`。
- 保留 `server`、`spring.datasource`、`mybatis-plus` 和用户其他 YAML 内容。
- 若原工程存在非受管 `auth` 根节点，禁止直接追加重复根节点；返回 `409 CONFIG_TARGET_CONFLICT` 或执行有证据的人工迁移。
- 使用安全 YAML 序列化/值转义；`${YHT_PRIVATE_KEY}` 由服务端运行环境提供。
- 仓库中的 `backend/base/src/main/resources/application.yml` 只作为默认输入，不保存应用实例值。
- 通过认证 SDK 集成测试检验实际读取 `auth.*` 的行为。

## 5. 模板服务具体修改

### 5.1 请求解析与配置模型

修改：

```text
template-engine/engine-service/src/main/java/.../EngineMapper.java
template-engine/engine-service/src/main/java/.../EngineController.java
template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml
```

建议新增：

```text
template-engine/engine-core/src/main/java/.../core/v3/config/
├── RequestedApplicationConfig.java
├── ResolvedApplicationConfig.java
├── ApplicationConfigResolver.java
├── ApplicationConfigProjector.java
├── FrontendEnvRenderer.java
└── BackendYamlRenderer.java
```

Codex 实施动作：

1. 增加 `RequestedConfigV3`（或等价不可变模型），分别保存 Capability Map 与可选 `applicationConfig`。
2. `EngineMapper` 严格校验新字段与 Legacy 请求，`ExtensionResolver.resolve()` 仍只接收 Capability Map。
3. `ApplicationConfigResolver` 校验三项基础字段、YHT 启用条件、四项认证配置、SecretRef、允许值与冲突；归一化为不可变 `ResolvedApplicationConfig`。
4. `ApplicationConfigProjector` **统一拥有业务映射**：生成前端公共环境区块与后端登录 YAML 区块；Generate/Update 共用，不能各写一套名称映射。
5. 更新 OpenAPI Schema 和错误码定义；服务 API 路径及 JSON 顶层字段不变。

### 5.2 Generate 投影

修改 `V3ProjectMaterializer.java`，组装流程固定为：

```text
Capability 解析 → Base/Extension 文件组装
                → Registry / Java Annotation / Maven 投影
                → ApplicationConfigProjector
                     ├── frontend/.env（应用三字段 + YHT Client ID）
                     └── backend/src/main/resources/application.yml（auth 四字段）
                → TemplateState + ZIP
```

- 只修改本次请求的内存文件 Map，不回写 Base、Extension 或模板仓库。
- `frontend/.env` 原不存在则新增；后端 YAML 对 Base 原文做受控插入/合并。
- 上述两个目标文件以固定安全白名单允许投影，其他路径冲突检测保持不变。
- 旧请求省略 `applicationConfig`：不强行创建 `.env`，`appConfig.ts` 使用默认值。
- 有基础配置但未启用 YHT：只生成三个应用级环境变量，后端不注入 `auth` 区块。
- 新工程不得包含 `frontend/src/config/app.generated.ts`。

### 5.3 Update：配置状态、受管区块与操作

修改：

```text
template-engine/engine-core/src/main/java/.../core/v3/V3UpdatePlanner.java
template-engine/engine-service/src/main/java/.../service/EngineMapper.java
template-engine/engine-service/src/main/resources/contracts/v3/template-state.schema.json
template-engine/engine-service/src/main/resources/contracts/v3/operation.schema.json
template-engine/engine-service/src/main/resources/contracts/v3/extension-update-package.schema.json
template-engine/engine-service/src/test/java/.../V3PackageExecutorContractIT.java
XCodeAgent 实际执行 Update Package 的模块（需在对应仓库定位）
```

**推荐新增通用操作：** `UPSERT_MANAGED_CONFIG_BLOCK`，只允许两个明确目标：`frontend/.env` 的 `app-frontend` 区块、`backend/src/main/resources/application.yml` 的 `login-auth` 区块。Executor 只执行通用文件区块替换，不解释业务配置字段。

新增状态建议为可选 `managedConfiguration`，包含：

- 规范化后的**非秘密**应用配置快照（名称、图标、主色、Client ID、公钥、私钥引用标识等）；**不得**含私钥实际值。
- 受管目标、区块 ID、预期内容摘要。
- 可选的配置投影 Schema 版本，用于判定旧工程迁移。

**更新语义：**

1. 只有三项应用字段改变时：只更新 `.env`，后端 YAML 不变。
2. Client ID 改变时：更新 `.env` 和后端 `auth.client-id`。
3. 公钥或私钥引用改变时：只更新后端 YAML 受管区块。
4. `applicationConfig` 在 Update 中**省略**时：维持旧状态已记录的配置；不删除区块。
5. 配置和模板均未改变时返回 `204`；JSON 字段顺序及无关 `application.json` 字段不引起变更。
6. `APPLY` 生成所需受管区块更新操作；`RECONCILE` 只用当前 State 所记录的配置修复区块，不更改业务意图。
7. 检查现有受管区块摘要；被人工更改、重复键或路径越界时全部拒绝并回滚。
8. **旧 TemplateState 没有新字段时不能补空对象再计算摘要**；维持老摘要兼容，直到被显式升级。
9. 严格扩展 Operation Schema、payloadManifest、签名前提、事务回滚和测试 Executor；不能沿用只允许五个 Registry 的 `REPLACE_MANAGED_FILE` 绕过路径限制。
10. 明确关闭已安装 Login 仍受 V3 当前 `CAPABILITY_REMOVAL_UNSUPPORTED` 限制；本期不实现卸载。

### 5.4 已有工程的 `app.generated.ts` 迁移（必须单独处理）

**原因**：目前已有工程的 `appConfig.ts` 仍引用 `app.generated.ts`。V3 当前不主动更新普通 Base 源文件。仅修改最新 Base 并生成新 `.env`，**旧工程依然继续读取旧 TS 文件，应用名称和主色不会生效**。

实施策略：

1. 以 `application.json` 的 `appName/appIcon/theme` 作为迁移输入，不从历史 `app.generated.ts` 反向猜测数据；历史生成文件可能已被 XCodeAgent 修改。
2. 新 Generate 删除 `app.generated.ts`；历史工程迁移后**允许暂时保留旧文件但不再引用**，避免扩展通用危险删除操作。
3. 为更新包新增**单目标、受保护的 Base 适配层迁移操作**（例如 `REPLACE_KNOWN_BASE_FILE`），目标仅允许 `frontend/src/config/appConfig.ts`，并要求 `expectedFileSha256` 与已知旧模板该文件字节匹配。
4. Update Executor 必须先验证真实文件内容；与预期不符（人工修改、历史版本未知）返回 `409 APP_CONFIG_MIGRATION_CONFLICT`，绝不静默覆盖。
5. 迁移成功后，`appConfig.ts` 才能从 `.env` 读取，TemplateState 记录迁移版本；重复 Update 不重复修改。
6. 已知旧 Release 的校验基线必须明确：根据实际旧版 `appConfig.ts` 内容/模板版本构建有限、可审计的哈希白名单；不得凭字符串包含关系或 Revision 大小盲目替换。
7. 所有迁移操作与 `.env` 更新须在同一事务完成，失败整体回滚；新生成工程不需要此迁移操作。
8. 对无法安全自动迁移的项目返回明确诊断和人工迁移方案；**禁止误报更新成功**。

> 如果 XCodeAgent 同期负责旧工程的 Spec/文件保护，仍由模板服务决定该迁移的目标内容与先决条件，XCodeAgent Executor 只执行通用可校验的受限操作。

### 5.5 XCodeAgent 对接（独立仓库任务）

1. 从 `.devagentstudio/application.json` 读取 `appName`、`appIcon`、`theme`、`auth`，原样按业务语义放入 `requestedConfig.applicationConfig`；`capabilities` 继续沿用现有选择。
2. 不自行决定 `.env` Key、Spring YAML 属性，也不再拼接 `app.generated.ts`。
3. 调用 Generate / Update；安全落地 `.env` 与后端 YAML 的受管区块、迁移操作。
4. 对旧项目实施**有前置哈希校验**的 `appConfig.ts` 迁移；不为实际业务映射写本地副本。
5. 适配 SecretRef，避免日志与状态落盘明文私钥。
6. 在模板服务 Update 与 Executor 端到端验证前，不提前移除当前正在使用的旧更新链路。

## 6. 分阶段实施与验收

| 阶段 | Codex 交付 | 关键验收 |
| --- | --- | --- |
| P0-1 | 配置模型、V3 请求协议、SecretRef | 旧请求兼容；新入参校验；无明文私钥持久化 |
| P0-2 | Base `appConfig.ts`、删除 `app.generated.ts`、类型声明 | 无 `.env` 时 Base 默认值正常；Layout/主题行为不变 |
| P0-3 | 统一 `.env` Renderer、后端 YAML Renderer、YST/Rsbuild 适配 | 新 Generate 两个目标正确；四个 Profile 能构建 |
| P1-1 | TemplateState、配置受管操作、Update Planner/Executor | 单独修改名称/图标/主色/Client ID 可更新；幂等、冲突、回滚通过 |
| P1-2 | 旧工程 `appConfig.ts` 安全迁移 | 已知旧版自动迁移；改动过的旧版拒绝覆盖；配置更新生效 |
| P1-3 | XCodeAgent 对接、旧生成逻辑清理、Release/文档 | 端到端一致；旧 V3 组装与更新不回退 |

### 必测场景

- **基础配置 Generate**：名称/图标/主色均按 `application.json` 生成 `.env`，最终 UI 名称、图标、Ant Design 主题和 CSS 主色正确。
- **Login Generate**：额外生成 Client ID；后端 `auth` 四项正确；前端无私钥/公钥文本。
- **Base/无 Login**：只生成基础配置；旧请求无 `applicationConfig` 也能构建并显示默认值。
- **主色格式**：`#6b3cf0` 保持完整值；引号/转义不会导致 dotenv 截断。
- **更新差异**：只修改名称/图标/主色 ⇒ 仅 `.env` 更新；只修改公钥 ⇒ 仅后端更新；改 Client ID ⇒ 前后端同时更新。
- **用户自定义 `.env`**：保留非受管字段，重复受管 Key 及受管区块被改动触发冲突。
- **用户自定义 YAML**：保留 `server/spring/mybatis-plus`；已有非受管 `auth` 时拒绝重复根节点。
- **旧工程迁移**：旧版 `appConfig.ts` 经过哈希验证后改为环境读取；`app.generated.ts` 留存也不被引用；人工修改拒绝替换，整个更新包回滚。
- **State/协议兼容**：旧 State 原始摘要不变；更新后配置有效状态可复原；重复请求可返回 204。
- **安全**：私钥明文不进入 `.env`、YAML、TemplateState、ZIP、日志及代码库；真实密钥仍由运行时注入。
- **构建/生效**：Rsbuild 1.0.14 构建通过；变更 `.env` 后重新构建生效；测试/生产 `WORKS_ENV` 不再被强制 local。
- **既有能力**：五个 Registry、Extension 依赖、Java Annotation、Maven Contribution 与 Release 流程不变。

### 建议验证命令

```bash
# 模板工程根目录
mvn -f template-engine/pom.xml -pl engine-service -am test

cd template-source/code/frontend
pnpm test:assembly
pnpm test:workspace-sync
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
pnpm verify:release
```

> 上述命令**尚未执行**。如存在私有 Maven/pnpm 依赖导致测试受阻，Codex 必须列明失败命令、环境限制与未验证范围，不得报告为已通过。

## 7. Codex 实施约束与交付要求

1. 先核对目标分支 HEAD；若与本文不同，记录文件差异并按实际签名调整。
2. **不新增前后端分栏 `environmentConfig`；不修改 Login 两份 `extension.yaml`；不新增 Manifest `configBindings` 协议。**
3. 不让单个应用的值污染模板源码；只在最终生成工程投影配置。
4. **前端基础配置统一 `.env`；`appConfig.ts` 保留作为类型化读取层；新工程不再出现 `app.generated.ts`。**
5. 前端使用 `PUBLIC_APP_NAME/PUBLIC_APP_ICON/PUBLIC_THEME_PRIMARY_COLOR/PUBLIC_YHT_CLIENT_ID`；私钥只通过 Spring `${YHT_PRIVATE_KEY}` 运行时引用。
6. 后端只能输出 `auth.client-id/private-key/public-key/center-public-key`，不可改成 `yht.*`。
7. 文件受管区块、状态差异、冲突与迁移前置条件均由模板服务定义；Executor 仅执行受白名单保护的通用文件操作。
8. 绝不修改现有 V3 五个 Registry、路由动态加载、源码定位 Loader、Extension 依赖与 Maven 注解处理的行为。
9. 每阶段提供：修改文件清单、实际测试结果、Generate ZIP 文件检查、Update 操作清单及回滚报告、剩余风险。

## 8. 不纳入本次改造

- `track`、`apiTrack`、通用 `env` 配置及前端埋点/日志系统。
- 网页 `<title>`、favicon 与 `appName/appIcon` 同步（当前仅业务界面的名称及图标）。
- 多环境运行时配置切换、同一前端构建产物跨环境改名/换主题。
- Layout 改造、更多 Extension Point、Capability 卸载。

**最终完成判据：** XCodeAgent 只发送现有应用基础配置和 `auth` 业务数据；模板服务在 Generate/Update 阶段统一生成/更新最终工程的 `frontend/.env` 与 `backend/src/main/resources/application.yml`；`appConfig.ts` 直接读取环境变量，旧工程可受保护迁移；用户自定义配置与私钥安全边界保持不变。

---

**参考：**

- [springboot-template / template_refactor](https://github.com/Hupy2118/springboot-template/tree/template_refactor)
- [Rsbuild v1 环境变量](https://v1.rsbuild.dev/zh/guide/advanced/env-vars)
