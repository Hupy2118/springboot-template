# 1016 模板服务 Standalone Adapter 改造实施方案（Codex · V3 完整版）

> 仓库：`https://github.com/Hupy2118/springboot-template.git`  
> 实施基线：`1016` 分支**执行时的最新提交**；提交实施报告时记录 SHA。  
> 参考：`template_refactor` 后端的公开 Spring Boot 依赖、Mock JWT 和 MySQL 配置，仅作为可行性参考，**不得覆盖 `1016` 引擎/业务主线**。  
> 范围：新增隔离的本地适配工具及测试；仅允许必要的通用运行时模板根目录配置，禁止改动 V3/V4 算法。  
> 原则：先保住 `1016` 正式源码，再实现本地构建运行，最后验证完整 Generate/Update。
> **V3 收敛：** 一个显式环境开关（默认原生）、Adapter 独立版本清单、基于 1016 实际接口的请求样例、服务端操作包与工作区 Apply 分层验证，以及失败保护与回退标准。
> **注意：** 本文是 Codex 实施规范，不代表改造已经提交或端到端验证已经通过；所有新增命令均标记为“拟新增”。

## 1. 目标与非目标

### 1.1 目标

1. **唯一代码主线为 `1016`**。V3/V4 Generate、Update、配置投影、Release Loader、Assembly/Sync、Authorization Bootstrap 的正式实现不回退、不改写为 `template_refactor`。
2. 将内部 Maven Parent、Bee/TDSQL/企业认证所需的**本地替代文件**集中放到 `devtools/standalone-adapter/`，正式 `template-source/code/backend/{base,extensions,assembly}` 保持原样。
3. Adapter 在临时目录生成**标准形态**的 Runtime Source；模板服务从临时 Source 加载，仍使用原来的 V3/V4 实现。**必须显式打开环境开关；默认使用正式模板源，完全跳过适配。**
4. 生成后的 `Base`、`Login`、`Authorization`、`Full` 工程在公开依赖环境中**可以构建、启动，并验证所启用的能力**。对真实企业 SSO/TDSQL 不承诺本地复现。
5. 在迁移行内时删除整个 Adapter 和临时产物，不影响正式发布路径；本地与企业版模板 Release 必须可区分，禁止隐式跨模式更新。

### 1.2 非目标／硬约束

- 不引入新的业务功能，不调整 `1016` 的角色/资源/成员/权限规则、API 路由、前端响应契约。
- 不改动 `template-engine` 的 V3/V4 生成更新算法与 HTTP 请求/响应结构；不向 XCodeAgent 增加 `standalone` 字段。
- 不在正式源码中编写 `if (standalone)`、`@Profile("standalone")`、Fake Bee 依赖、手动屏蔽权限等分支。
- 不通过 `mvn -DskipTests`、排除缺失 Jar、`systemPath`、创建假 Maven Parent 等方式掩盖无法解析的依赖。
- 不以“能 package”为“能运行”；依赖校验、启动测试、登录/RBAC/Bootstrap 测试缺一不可。
- 不将 `.standalone-build/`、Mock 身份凭据、临时数据库密码、适配代码写回 `base` 或 `extensions` 正式目录。

## 2. 基线事实及须首先核验的文件

Codex 开始时执行：

```bash
git fetch origin
git switch 1016
git pull --ff-only origin 1016
git rev-parse HEAD
# 为实施建立独立分支，不直接提交到 1016
git switch -c feat/standalone-adapter-1016
```

若工作区有未提交改动，**不得自动覆盖或清理**；保留用户改动并选择安全方式创建实施分支。

核验并以最新源码为准（不应直接复制本文推测的完整实现）：

| 位置 | 1016 当前已确认情况 | Codex 需处理 |
| --- | --- | --- |
| `backend/base/pom.xml` | Parent `ZA21:bee-starter-parent:6.0.7`、`bee-starter-core`、`LT99.09:tdsql-mysql-connector-java8` | 独立 POM 全量替换 |
| `backend/base/src/main/resources/application.yml` | `bee.persistence.ds1`、TDSQL Driver | 替换标准 Spring Datasource/MySQL |
| `backend/base/src/main/resources/application-dev.yml` | `datasource.ds1.*` | 替换为普通开发配置，保持文件存在 |
| `backend/base/.../WebGlobalExceptionHandler.java` | 使用 `BeeResponseEntity`、`BizException`、`SysException` | 提供受限本地兼容类 |
| `backend/base/.../BusinessErrorCode.java`、`SystemErrorCode.java` | 使用 `IBizErrorCode`、`ISysErrorCode` | 提供受限接口兼容类 |
| `backend/extensions/login/extension.yaml` | 注入 `EnableAuthClient` 和 `ZA21:bee-starter-auth`，另含 `applicationYaml`/`applicationProfileYaml` | 完整覆盖 Manifest：取消内部 Java/Maven 注入，保留配置投影必要结构 |
| `backend/extensions/authorization/.../ResourcePermissionInterceptor.java` | 直接调用 `TokenContextHolder.get()` 和 `LocalToken.getSapId()` | 本地模拟登录上下文必须满足同一调用契约 |
| `backend/extensions/authorization/.../ResourceApplicationService.java`、`RoleApplicationService.java` | 同样依赖企业 Token Context | 优先兼容类替换，减少业务源码修改 |
| `backend/extensions/authorization/.../AuthorizationBootstrapCommand.java` | 限定 `jdbc:tdsql-mysql://`；校验 Manifest v3 | 仅修改数据库适配相关片段，其他校验保留 |
| `backend/extensions/authorization/.../BootstrapDataSource.java` | 读取 Bee ds1/TDSQL | Standalone 文件级替换 |
| `backend/extensions/authorization/scripts/bootstrap-authorization.sh` | 已存在，调用 Maven 运行 Bootstrap | 应保留并验证可执行及正确打包 |
| `backend/extensions/authorization/sql/authorization/schema.sql` | 已存在 | 原样保留；不得回退到旧 `docs/auth/sql/ddl.sql` |
| `frontend/base/src/apis/service.ts` | 1016 按 `returnCode/errorMsg/body` 解包，成功码 `SUC0000` | 前端不回退；兼容类应输出一致响应 |
| `CodeTemplateReleaseLoader` | 从 `backend/base`、`backend/extensions` 固定目录加载 Release | 临时 Source 必须保持相同目录结构 |
| `ApplicationConfigProjector` | Backend 配置投影到 `backend/src/main/resources/application-dev.yml` | 保留目标路径及必需 YAML 标量节点 |

先以 `rg '^(import|.*groupId|.*artifactId)|com\.cmb\.|com\.tencentcloud\.|jdbc:tdsql-mysql|bee\.' template-source/code/backend/` 为起点完成**全量引用扫描**，但最终以实际 Maven 编译和 JVM 启动错误为准，不把上表视为穷尽清单。

## 2.1 环境开关、模板源选择与服务启动（本次新增，必须实施）

**已核对 1016 实现**：

- `template-engine/engine-service/config/application-local.yml` 已包含 `devagentstudio.template-engine.source-root: ${TEMPLATE_ENGINE_SOURCE_ROOT}`。
- `TemplateEngineProperties` 已支持 `sourceRoot`，官方启动示例将 `TEMPLATE_ENGINE_SOURCE_ROOT` 设为仓库的 **`template-source/` 绝对路径**。
- `CodeTemplateReleaseLoader` 实际加载其下 `code/` 的 Base/Extension、Contracts、Revision。**设置 `TEMPLATE_ENGINE_SOURCE_ROOT` 时不要再加 `/code`**。
- 现有服务入口为 `EngineServiceApplication`，不需要新增 Engine Controller、Loader、Materializer 或 V3/V4 Update 算法。

### 2.1.1 环境变量与默认行为

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `TEMPLATE_ENGINE_STANDALONE_ENABLED` | `false` | `false`：原生模板源，不执行适配；`true`：先生成 Standalone 临时模板源，再启动同一引擎 |
| `TEMPLATE_ENGINE_SOURCE_ROOT` | 由启动脚本确定 | `false` → `<repo>/template-source`；`true` → `<repo>/.standalone-build/template-source` |
| `TEMPLATE_ENGINE_PORT` | `18080` | 沿用 `application-local.yml`，仅绑定 `127.0.0.1` |

在 **`devtools/standalone-adapter/run-engine.sh`** 中实现模式开关（新增文件）。**所有调用 Adapter 的测试与本地启动统一使用该脚本；原生 `java -jar` 不解析新开关。**

1. 从脚本位置确定仓库根目录，解析开关；空值按 `false`，仅接受 `true`/`false`，其他值报错。模式为**进程启动时**选定，不允许 API 按请求切换。
2. `false`：直接设置绝对 `TEMPLATE_ENGINE_SOURCE_ROOT=<repo>/template-source`，按 1016 原有命令启动 JAR；**不得执行 prepare/audit，不得读取 Adapter Manifest，不得清理或创建 `.standalone-build/`**。
3. `true`：从 `devtools/standalone-adapter/release.yaml` 读取 Standalone Revision，先执行 `build.sh prepare` 和 `build.sh audit`；通过后设置 `TEMPLATE_ENGINE_SOURCE_ROOT=<repo>/.standalone-build/template-source`，再启动同一个 JAR。路径必须存在并且包含 `code/template-revision.txt`、`code/backend/base`、`code/frontend/base`。不得复用正式 Revision。
4. `true` 下任何准备失败，启动脚本都应**退出并返回非零码，不能回退至正式模板**。若用户预设了与开关不符的 `TEMPLATE_ENGINE_SOURCE_ROOT`，应明确拒绝冲突，而非静默混用。
5. 服务启动时记录有效模式、绝对 Source 路径、Revision、上游 SHA、适配 Manifest 摘要；不打印密钥。避免运行中覆盖其 Release：先在 staging 构建和校验，**要求旧进程已停止**，在互斥锁下提交临时源；失败时保留上一份有效源并退出，不能热替换正在服务的 Release。切换模式或 Revision 必须重新启动。
6. **正式的 `template-engine/`、`template-source/code/` 不写任何 `if (standalone)`**。行内可继续使用原有 `java -jar` 启动方式而完全不安装 Adapter。

> 重要限制：`TEMPLATE_ENGINE_STANDALONE_ENABLED` 由**新增启动脚本**解析。若绕过脚本直接执行 1016 原本的 `java -jar`，单独设置此变量不会启用 Adapter；必须同时显式配置现有 `TEMPLATE_ENGINE_SOURCE_ROOT` 才能选择临时源。这样才能满足“正式主线零适配依赖、迁移时直接删除适配目录”。

### 2.1.2 服务启动实例

**Standalone 模式，终端 A，在仓库根目录：**

```bash
# 引擎本身正常编译，不依赖企业 Backend POM
mvn -f template-engine/pom.xml -pl engine-service -am package -DskipTests

export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
# Release Revision 默认由 devtools/standalone-adapter/release.yaml 管理
# 本次需要 Codex 新增的命令
./devtools/standalone-adapter/run-engine.sh
```

**正式模式，终端 A，仍可使用 1016 原生方式：**

```bash
unset TEMPLATE_ENGINE_STANDALONE_ENABLED
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

新增 `run-engine.sh` 在 `false` 下应达到与此相同的效果。生产构建/发布不需要增加新的 Maven Profile、Controller 请求字段或持久化开关。

### 2.1.3 模式切换验收

| 场景 | 预期 |
| --- | --- |
| 未设置开关，使用新增启动脚本 | 原生模式，未执行 prepare、未修改临时目录，生成工程 POM 仍为 `ZA21` Parent |
| 设置 `false` | 同上，Adapter 缺失也不影响原生命令启动 |
| 设置 `true` 且 `release.yaml` 有效 | 自动 prepare + audit，加载临时模板源，生成工程 POM 使用公开 Spring Boot Parent |
| 设置 `true` 但 `release.yaml` 缺失、修订非法或与正式 Revision 相同 | 启动前失败、端口不监听 |
| 设置 `true` 且 Overlay/检查失败 | 启动前失败，绝不以企业原生模板回退启动 |
| 开关拼写错误或冲突 Source Root | 明确失败，避免选错模式 |
| 从 `true` 改回 `false` 并重启 | 同一个 Generate 请求恢复原生 `1016` 依赖与输出 |
| 仅在请求体传 `standalone: true` | 按既有 HTTP 严格合同返回 `400 BAD_REQUEST`；不允许请求切换 Release |



### 2.1.4 版本清单的确定性规则（Codex 必须实现）

新增 `devtools/standalone-adapter/release.yaml`，由**本地开发者维护**，而不是生产 Release 的一部分：

```yaml
schemaVersion: 1
sourceRevision: '2026.10.23.2'       # 示例：以实施时实际 code/template-revision.txt 为准
standaloneRevision: '2026.10.23.1002' # 示例：本地专用，不能与 sourceRevision 相等
```

实施约束：

1. `sourceRevision` 必须与正式 `template-source/code/template-revision.txt` 一致；不一致时拒绝启动并提示更新清单；示例 `2026.10.23.2` 仅对应当前已核查基线，Codex 应读取执行时真实版本。
2. `standaloneRevision` 必须满足 `RevisionVersion` 的现有格式，且不得等于 `sourceRevision`。两个版本均不在正式源码中回写。正常启用只需要设置 `TEMPLATE_ENGINE_STANDALONE_ENABLED=true`；不要求用户再提供第二个修订号环境变量。
3. 同一 Release 应建立独立 `build-fingerprint.json`（原始 Git SHA、源文件 Hash、Manifest Hash、Overlay Hash）；如上游或适配内容发生变化而 Revision 未变化，拒绝覆盖此前已发布的本地 Release。**Git SHA 本身变化但 Runtime Source 输入未变化，可允许复用，并记录原因**。
4. 本地专用版本应占用明确约定的非生产编号区间；这只降低碰撞风险，**不能代替跨模式兼容性校验**。
5. **不要借 `release.yaml` 在正式版本中添加 Variant 字段**。正式 TemplateState Schema 保持不变；独立来源记录只存在于 Adapter 生成的构建报告或本地 Workspace 侧车元数据中。

### 2.1.5 启动时序、进程与并发边界

- `false` 时直接按原生路径启动，启动脚本不得触发 Python Overlay、不得删除 `.standalone-build`，且不应依赖 Python、公开 MySQL 或 Adapter 的 `release.yaml`。
- `true` 时执行 `preflight → prepare(staging) → audit → commit → java -jar`；所有步骤成功后才可启动。使用 `exec java -jar` 正确传播信号与退出码。
- `prepare` 不得清空整个 `.standalone-build`（该目录还保存 HTTP 验证包、日志和隔离 Maven 缓存），只管理自身临时 Runtime Source 与 staging 子目录。
- 使用可跨 macOS/Linux 的互斥策略。构建成功后才替换有效 Release；不得把尚未通过校验的 staging 路径传给引擎。旧进程仍在使用 Release 时拒绝覆盖；不得承诺非空目录可通过一次 `mv` 原子覆盖。
- **服务没有已证实的 `/health` 接口**。启动就绪由 HTTP `/v1/generate` 返回 200 ZIP 验证；进程启动日志只能作为辅助。
- 适配模式仅绑定 `127.0.0.1`，不向公网开放，Mock 身份与测试密钥只能用于开发。

## 3. 新增目录与文件边界

```text
devtools/standalone-adapter/
├── README.md                    # 构建、依赖替换、运行、删除说明
├── manifest.yaml                # 精确 replace/add/remove/patch 操作
├── release.yaml                 # 独立、可审核的 Standalone Revision 与基线 Revision
├── build.sh                     # 适配 prepare/audit/verify 入口；可用 Python 3 标准库实现
├── run-engine.sh                # 新增：按开关启动原生/Standalone 同一引擎
├── scripts/
│   ├── prepare.py               # 复制 + 校验 + 应用 Overlay
│   ├── audit.py                 # 原始路径保护、Maven/Java 残留扫描、差异报告
│   ├── verify.sh                # 构建和功能验证
│   └── http-smoke.sh            # 新增：真实 HTTP Generate/Update 验证
├── overlays/
│   └── backend/
│       ├── base/
│       │   ├── pom.xml
│       │   └── src/main/resources/{application.yml,application-dev.yml}
│       └── extensions/
│           ├── login/extension.yaml
│           └── authorization/src/main/java/.../BootstrapDataSource.java
├── sources/
│   ├── base/src/main/java/...    # Bee 响应/异常/错误码兼容类
│   └── login/src/main/java/...   # 模拟登录、LocalToken、TokenContextHolder、Filter
├── patches/
│   └── bootstrap-datasource.patch  # 只改原类数据库 URL 校验，不重写主体
└── tests/
    ├── fixture/                # Manifest v3、application.json、数据库测试数据
    └── ...                     # Adapter 幂等、构建、HTTP/RBAC、Bootstrap 回归

.standalone-build/              # 完全忽略，不提交
├── template-source/code/       # 标准 Runtime Source（由 1016 复制并适配）
├── generated/                  # Generate 产出工程
├── http-verification/          # 新增：请求/响应/ZIP/测试报告
├── reports/                    # 差异/依赖/测试报告
└── m2/                         # 隔离 Maven 缓存（可选）
```

仅允许向仓库根 `.gitignore` 增加 `.standalone-build/`，不得忽略正式 `backend/base` 或 `backend/extensions`。除新增 Adapter/测试外不直接编辑正式模板代码。工具实现应同时支持 macOS/Linux；若在 Windows 运行，使用 Git Bash 或在 README 明确前置条件。

## 4. Overlay 引擎实施规范

### 4.1 输入/输出

输入：仓库 `template-source/code/`、Adapter `manifest.yaml`；输出：`.standalone-build/template-source/code/`。**对源目录只读**。

严格流程：

1. 确认 Git 工作目录；校验适配列表和所有目标均为安全相对路径，拒绝 `..`、绝对路径及越界符号链接。
2. 清理**仅属于 Adapter 的临时目录**；复制整个 `template-source/code/`，保留前端、契约、revision、Base/Extension。排除用户工作区缓存和非 Runtime Source 临时文件。
3. 按 Manifest 执行 `replace`、`remove`、`add`、`patch`，每条操作严格校验前置条件；**每个覆盖/补丁目标必须记录并核对上游 SHA256**，源文件发生未预期变化必须失败。
4. `replace` 必须目标已存在；`add` 必须不存在；`remove` 必须存在；`patch` 必须预校验并严格应用，禁止静默忽略、模糊匹配和 `sed` 全局替换。
5. 生成 Manifest 完成报告：上游 Git SHA、精确文件清单、变更类型、前后 SHA256、目标 Revision。报告不包含密钥。
6. 扫描临时模板 POM/Extension YAML 中禁用的 Maven GroupId/ArtifactId、企业注解和 TDSQL Driver；不允许任何会参与本地编译的企业依赖残留。
7. Adapter 校验成功后才允许调用 `1016` 的 Release Loader、Generate 或 Update。

建议 Manifest 结构（示例路径、前置 SHA 须按实际源码填写）：

```yaml
schemaVersion: 1
operations:
  - type: replace
    target: backend/base/pom.xml
    source: overlays/backend/base/pom.xml
  - type: replace
    target: backend/extensions/login/extension.yaml
    source: overlays/backend/extensions/login/extension.yaml
  - type: add
    target: backend/base/src/main/java/com/cmb/bee/web/entity/BeeResponseEntity.java
    source: sources/base/src/main/java/com/cmb/bee/web/entity/BeeResponseEntity.java
  - type: patch
    target: backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java
    source: patches/bootstrap-datasource.patch
```

**注意**：Manifest 的路径相对 `template-source/code/`，添加到 Base 的文件由 `CodeTemplateReleaseLoader` 归入 `backendBase`；登录模拟实现须添加到 Login Extension，避免 Base 无 Login 时出现无关接口。

### 4.2 Revision 和 Update 安全

- `1016` 版本格式固定为 `YYYY.MM.DD.N`；不得生成 `-standalone` 后缀。
- 默认从 Adapter 的 `release.yaml` 读取合法、可复现且与企业版不同的 `standaloneRevision`，只写入**临时** `code/template-revision.txt`；不要在每次运行时自动生成随机 Revision。用于调试的环境变量覆盖须严格校验并记录，不应依赖它完成正常启动。
- 同一 Standalone Release 反复构建产物哈希一致；`sourceRevision`、受管上游文件 SHA 或 Overlay 内容改变时必须要求人工审核并提升 `standaloneRevision`，不能静默保留旧版本号。
- 由于现有 TemplateState 没有独立 Variant 字段，**禁止跨 Enterprise/Standalone 执行 Update**；测试中只对当前 Standalone Release 的 TemplateState 做 V3/V4 Update。
- 生成包、Update payload 和 Report 中不得残留任何真实企业内部凭据；模拟密钥只能是本地测试值且不得提交生产密钥。



### 4.3 精确操作语义及回滚（不要只写一个文件复制脚本）

Codex 必须实现以下**可测试的不变量**：

| 操作 | 前置检查 | 后置检查 |
| --- | --- | --- |
| `replace` | 目标存在、类型为普通文件、原始 SHA 与 manifest 匹配 | 文件 hash 等于 Overlay 源，未改变非目标路径 |
| `add` | 目标不存在、父路径未越界 | 新文件完整写入，仅出现一次 |
| `remove` | 目标存在、原始 SHA 匹配 | 目标不存在，不影响邻接文件 |
| `patch` | 目标 SHA 匹配、补丁精确适用 | 目标文件结果 hash 等于预期，其他区块不变 |

- `manifest.yaml` 的每项必须写 `reason`、`target`、`source`（remove 除外）、`expectedSourceSha256`（每个被覆盖/删除/补丁的源目标）。Codex 实现时把实际 SHA 填入清单，不允许留占位字符串。
- 同一 `target` 不允许同时出现两条操作；对 Extension Manifest 的全量覆盖与 Java 源文件添加允许采用两个独立目标，不允许叠加字符串替换。
- 禁止遍历或复制 `workspace/`、`node_modules/`、`target/`、`.env`、实际数据库密码等非 Runtime Source 输入。脚本需要根据 `CodeTemplateReleaseLoader` 的输入边界明确 copy allowlist。
- 构建失败时不改动生效源、原始工作树；报告错误路径与预期 hash。连续两次成功构建应验证文件清单及字节哈希稳定。
- 对全部待输出文件做 symlink、`..`、绝对路径和大小写碰撞检查，以免不同系统行为不一致。

### 4.4 为什么不能做“仅替换 Maven 包名”

必须为每个企业依赖编制 `dependency-map.md`，至少记录：`依赖坐标/源码 import/由谁提供 Bean 或 Filter/适配方式/对应测试`。例如：

| 企业能力 | 允许的本地适配方式 | 必须证明 |
| --- | --- | --- |
| Bee Parent 和核心 Starter | 一整份公开 Parent POM + 受限同名响应/异常类型 | Maven 离线私仓不依赖、接口响应形状一致 |
| Bee 默认 MVC/数据库配置 | 标准 Spring/MyBatis 配置 | Spring 容器启动、Mapper/分页/事务正确 |
| 企业 YHT 登录上下文 | Mock JWT + 请求上下文/Filter，仅本地 | 未登录 401/业务错误、无权限拒绝、合法身份能通过 |
| TDSQL JDBC | 标准 MySQL 配置与 BootstrapDataSource | JDBC 连接、Schema/初始化幂等、两套 profile 一致 |
| Bee 认证注解/Maven 贡献 | Standalone Login Manifest 清除注解和依赖 | V4 Update 操作绝不再次注入被删除的声明 |

未经完整 Maven **公开仓库 + 空缓存**解析和生成工程启动验证，不得将任何项标记“已替代完成”。

## 5. P0：依赖与 Base 可运行

### P0-1：全量 POM 替换

制作 Standalone `backend/base/pom.xml`：

- Parent：`org.springframework.boot:spring-boot-starter-parent:2.7.2`（参考 `template_refactor` 已用组合，Java 8）；明确 `java.version=8`。
- 保留应用 Maven 坐标、入口 `com.cmbchina.backend.Application`、`spring-boot-maven-plugin` 可执行 JAR。
- 明确加入可用公开依赖：`spring-boot-starter-web`、`spring-boot-starter-validation`、`spring-boot-starter-jdbc`、`mybatis-plus-boot-starter`（已知可用参考 `3.5.2`）、`mysql-connector-j`（已知参考 `8.2.0`）、`lombok`、`mapstruct`、`mapstruct-processor`、`lombok-mapstruct-binding`、`commons-lang3`、`jetbrains-annotations`、`spring-boot-starter-test`。具体版本以 Java 8 和实际公开仓库兼容性验证为准，不能遗留 Bee Parent 负责版本管理的无版本项。
- `annotationProcessorPaths` 明确配置 Lombok/MapStruct 及 binding 所需版本，验证当前 JDK 上可编译。
- 禁止 `ZA21`、`LT99.09`、`bee-starter-*`、TDSQL Driver；不使用 `systemPath`、伪造 Jar。
- 明确版本、镜像与依赖解析报告；测试用公开仓库 settings + 独立 Maven 本地仓库，防止内部缓存误判成功。

### P0-2：Bean、错误码及响应兼容

在 `sources/base` 新增受限兼容类，**仅含 1016 实际引用的方法**：

- `com.cmb.bee.web.entity.BeeResponseEntity<T>`：`success()` / `success(T)` / `failed(...)`，字段 `returnCode`、`errorMsg`、`body`，成功码 `SUC0000`，可被 Jackson 正确序列化。
- `com.cmb.bee.commons.errorcodes.IBizErrorCode`、`ISysErrorCode`：仅保留使用到的 code/message API 和格式化方法。
- `com.cmb.bee.commons.exceptions.BizException`、`SysException`：只实现真实构造器及 `getErrorCode()`、`getParams()` 等被使用的 API；异常码和格式必须与前端契约一致。
- 避免复制企业 SDK 整包，也不要让兼容异常自动吞掉错误。编写 API 响应和异常处理单元测试。

### P0-3：标准数据源 + MyBatis

- 适配 `application.yml`：`spring.datasource`、普通 MySQL Driver、`mybatis-plus.mapper-locations: classpath*:mapper/**/*.xml` 等。
- `application-dev.yml` 必须存在且 YAML 结构合法，不能保留无关 Bee/TDSQL 字段。
- 采用环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，不在仓库写真实数据库账号或可用密码。
- 验证 `@Mapper` 扫描、MyBatis-Plus 分页拦截器、Mapper XML 资源和 Spring transaction manager；缺失时在 Adapter Base 中新增标准配置类。
- 完整 Base 生成工程 Maven `clean package` 并在可用测试数据库上启动；不能只验证 `backend/assembly` 维护工具 Maven 构建。

**P0 完成标准：** `Base` 生成工程只依赖公开 Maven 资产，JAR 可启动，无缺失 Bean、JDBC Driver、Mapper 或 YAML 解析错误，基本 HTTP/健康探测可运行。

## 6. P1：Login、Authorization、Bootstrap

### P1-1：覆盖 Login Extension Manifest

完整覆盖临时 `backend/extensions/login/extension.yaml`：

- `id: login`、`requires` 保持 1016 语义；`contributes.applicationAnnotations: []`，`contributes.mavenDependencies: []`，禁止注入 `EnableAuthClient` 和 `bee-starter-auth`。
- **保留 `applicationYaml`、`applicationProfileYaml` 中 `1016 ApplicationConfigProjector` 需要的 YAML 标量路径和默认值**，避免 `CONFIG_TARGET_CONFLICT`；移除/调整 Bee-only 配置时务必测试后端 YAML 投影与 V4 `ENSURE_CAPABILITY_CONFIG`。
- 不复制 `template_refactor` 的旧 Extension Manifest 覆盖 `1016` 的新 YAML 投影规则。

### P1-2：本地认证兼容实现

在 `sources/login` 提供本地开发实现：

- 优先实现与现有调用方兼容的 `com.cmb.oa.one.account.oauth.client.login.model.LocalToken`、`...login.context.TokenContextHolder`，具有 `get()`、`getSapId()` 和请求结束清理机制；**这些是临时兼容实现，不是企业 SDK**。
- 基于 `template_refactor` 的 Mock JWT 思路添加开发态登录控制器/过滤器；签名 Key 来自环境变量；Cookie 设置 HttpOnly、SameSite，按本地 HTTP/HTTPS 场景配置 Secure。
- 通过 JWT/Session 认证身份，在每次请求开始时设置当前登录人，请求完成（含异常）时清理 ThreadLocal，不允许跨请求/线程身份泄漏。
- 模拟登录只允许显式 `dev/local` + 环回地址测试环境；Standalone 生成后端默认仅监听 `127.0.0.1`（例如在开发配置加入 `server.address`），正式配置默认禁用，禁止匿名用户通过参数冒充管理员。
- 不能通过空的 `TokenContextHolder`、固定 `SYSTEM_ADMIN`、无条件放行实现“可运行”；权限规则由 `1016` RBAC 代码继续执行。
- 检查并适配 `1016` 前端 Login API、`YST` 本地分支，不改变前端统一响应协议；若本地登录需特殊入口，只在 Adapter 内说明并提供测试方式。
- Standalone **不宣称真实 YHT/SSO 有效**。投影可保留配置结构，但本地认证路径明确是模拟能力。

### P1-3：Authorization 保留业务实现

- 默认保持 `1016` 的 `RoleController`、`ResourceController`、`MemberController`、`RoleApplicationService`、`ResourceApplicationService`、`ResourcePermissionInterceptor`、Repository、Mapper、DTO/PO、SQL 不变。
- 优先通过上面的兼容类满足 `BizException`、`BeeResponseEntity`、`TokenContextHolder` 等 API；如果实际签名不符，仅将**确实无法兼容**的 Java 文件加入 Adapter `replace` 清单，并在报告中说明最小修改点。
- 严禁使用固定 `getMyResources()`、跳过 `@RequireAnyResource` 等方式使测试虚假通过。
- 对不携带身份、携带无权限身份和系统管理员分别进行真实 RBAC HTTP 测试。

### P1-4：Bootstrap MySQL 适配

- 保留 1016 的 `AuthorizationBootstrapCommand` Manifest v3、fingerprint、默认角色授权、唯一约束、幂等初始化、SQL 文件验证及事务/锁规则。
- 在 Adapter 里覆盖 `BootstrapDataSource.java`：从实际项目的 `application.yml` + `application-dev.yml` 或显式参数解析标准 `spring.datasource`，准确处理 profile/环境变量占位符。
- 对 `AuthorizationBootstrapCommand` 的 TDSQL URL 前置校验应用最小补丁，仅允许预期的 `jdbc:mysql://`，保留空用户名和非法参数拦截；严禁整份 671 行文件复制并自由重写。
- 验证原有 `backend/scripts/bootstrap-authorization.sh`（以及 PowerShell 如适用）会随生成工程交付，脚本入口、参数含空格场景与退出码正确。
- 固定 DDL 位置 `backend/sql/authorization/schema.sql`；不要创建/校验旧 `backend/docs/auth/sql/ddl.sql`。若 XCodeAgent 另有错误路径契约，**只记录跨项目阻断，不在本次修改 XCodeAgent**。
- 真实 MySQL 集成测试验证首次初始化、重复执行幂等、锁竞争、缺少 SQL/Manifest 拒绝启动；仅 `--dry-run` 不视作 Bootstrap 成功。

**P1 完成标准：** `Login`、`Authorization` 和 `Full` 工程可构建启动，认证上下文有效；无权限访问确实被拒绝；Bootstrap 真正完成 Schema 与管理员/系统资源初始化。



### 6.1 本地 Mock 登录的外部接口与前端对接验证

**先查 `1016` 前端登录 Extension 的现有 API 调用、Cookie/Token 格式与路由，再决定本地后端 Mock 入口。**不得凭空假定 `/api/login` 或 `/api/auth/login` 已经存在。Codex 的步骤：

1. 输出 `frontend/extensions/login` 实际登录请求、回调、登出、当前身份读取路径及字段清单，形成 `reports/login-contract.md`。
2. 如果当前前端只有 YHT 重定向登录，Adapter 应提供**仅开发模式可用**的 Mock 登录入口，并通过本地测试专用客户端验证；需要临时模拟浏览器回调时，仅在 Adapter overlay 中添加所需文件，不改正式前端。
3. Mock 用户标识必须映射到 `TokenContextHolder` 中的 `sapId`，与 RBAC 的 `role_member.member_id` 相同；Bootstrap 管理员初始主体必须使用可登录的同一标识。否则即使 SQL 初始化成功，管理员仍会被权限拦截器拒绝。
4. 未登录、无权限、合法管理员三个测试身份分别走真实 HTTP；不得绕过 `RequireAnyResource`。拒绝码以 1016 已有错误码及对应 HTTP 实现为准，不提前假定一定是 401/403。
5. 模拟身份不能从任意请求 Header（如 `X-User-Id`）无校验提取；固定测试私钥/Session 仅限本地且不打印到报告。

## 7. P2：与 1016 Engine / V3 / V4 贯通

1. 临时树应保留原有 `contracts/`、`frontend/base`、`frontend/extensions`、`backend/base`、`backend/extensions`、`template-revision.txt`。 `CodeTemplateReleaseLoader` 能直接读取 `.standalone-build/template-source/code/`。
2. **已确认 1016 原生支持 `TEMPLATE_ENGINE_SOURCE_ROOT`**。通过新增 `run-engine.sh` 选择 `template-source/`（默认）或 `.standalone-build/template-source/`（显式启用）。不得新增 Java Bean/Loader/Controller 模式字段，也不得改 V3/V4 算法。
3. 用现有 `/v1/generate`（Generate 不区分 V3/V4 接口路径）和 V4 Update 链路验证，不得只运行 `./backend-template base`。分别生成 `Base`、`Login`、`Authorization`、`Full`（如 `Full` 与 Authorization 能力集合相同，也要保留现有语义验证）。
4. **必须先生成 Base，再使用同一 Standalone Release 通过 V4 安装 Login、Authorization**：确保 Update 贡献操作不会重新写回 `bee-starter-auth`、`@EnableAuthClient` 或 TDSQL 配置；Maven 编译、权限接口、Bootstrap 再测试一遍。
5. 走 V3 支持的更新模式和变更场景回归，但不要将 V3 不支持的旧配置刷新伪装成已实现；使用 V4 能力增量注入验证。
6. 直接核查输出工程中的 `pom.xml`、`Application.java`、`application*.yml`、`scripts/bootstrap-authorization.sh`、`sql/authorization/schema.sql`、前后端 API 响应以及所有 Release State 与 Update Operation。
7. 仅对 `1016` 当前支持的可用 API 进行调用。具体请求/响应请参照仓库现有 OpenAPI、V3/V4 schema、EngineServiceIT，不虚构 Endpoint。

## 7.1 服务启动后的真实 HTTP Generate/Update 验证（本次新增，必须实施）

**1016 权威合同已核对**：`EngineController` 暴露 `POST /v1/generate` 和 `POST /v1/update`；`OpenAPI` 的 Generate 输入仅有 `requestedConfig`，Update 输入固定为 `protocolVersion/currentTemplateState/requestedConfig/mode`。Generate 响应是完整工程 ZIP；Update 响应是**操作包 ZIP**，无变化时 HTTP `204`。本节命令可以直接放到 Postman 或终端运行。以下示例要求先按 **2.1.2** 在 Standalone 模式启动服务。

### 7.1.1 准备测试目录

**终端 B**，进入仓库根目录后：

```bash
export ENGINE_URL=http://127.0.0.1:18080
export OUT=.standalone-build/http-verification
mkdir -p "$OUT"
```

不要虚构 `GET /health`：目前 OpenAPI 没有该接口。以 `POST /v1/generate` 的 `200 + application/zip` 作为真实连通性验证。

### 7.1.2 Generate Base（无能力）

```bash
cat > "$OUT/generate-base.json" <<'JSON_BASE_001'
{
  "requestedConfig": {
    "capabilities": {}
  }
}
JSON_BASE_001

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/zip' \
  --data-binary @"$OUT/generate-base.json" \
  "$ENGINE_URL/v1/generate" \
  -o "$OUT/base.zip"

unzip -p "$OUT/base.zip" backend/pom.xml | head -35
unzip -p "$OUT/base.zip" .devagentstudio/template-state.json | python3 -m json.tool
```

验收：`base.zip` 包含 `frontend/`、`backend/pom.xml` 和 `.devagentstudio/template-state.json`；Standalone 版 POM 的 Parent 必须是 `org.springframework.boot:spring-boot-starter-parent`，不是 `ZA21`。`templateRevision` 必须与 Adapter `release.yaml` 的 `standaloneRevision` 一致。

### 7.1.3 Generate Login

```bash
cat > "$OUT/generate-login.json" <<'JSON_LOGIN_001'
{
  "requestedConfig": {
    "capabilities": {
      "login": {"enabled": true, "config": {}}
    }
  }
}
JSON_LOGIN_001

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' -H 'Accept: application/zip' \
  --data-binary @"$OUT/generate-login.json" \
  "$ENGINE_URL/v1/generate" -o "$OUT/login.zip"
```

验收：登录 Extension 已安装，本地认证实现随生成工程交付；Backend POM/Java Annotation 中没有 `bee-starter-auth`、`EnableAuthClient`，且能使用公开依赖编译。

### 7.1.4 Generate Authorization（自动安装 Login 依赖）

```bash
cat > "$OUT/generate-authorization.json" <<'JSON_AUTH_001'
{
  "requestedConfig": {
    "capabilities": {
      "authorization": {"enabled": true, "config": {}}
    }
  }
}
JSON_AUTH_001

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' -H 'Accept: application/zip' \
  --data-binary @"$OUT/generate-authorization.json" \
  "$ENGINE_URL/v1/generate" -o "$OUT/authorization.zip"

unzip -l "$OUT/authorization.zip" | grep -E 'bootstrap-authorization|sql/authorization/schema.sql'
unzip -p "$OUT/authorization.zip" .devagentstudio/template-state.json | python3 -m json.tool
```

验收：`requested` 包含 Authorization，`effective` 同时包含 Login 和 Authorization；生成包必须含 `backend/scripts/bootstrap-authorization.sh` 和 `backend/sql/authorization/schema.sql`，而不是历史路径 `backend/docs/auth/sql/ddl.sql`；权限 Manifest v3/Bootstrap 的正式校验与授权规则保持不变。

### 7.1.5 检查并编译三个生成工程

```bash
mkdir -p "$OUT/base-project" "$OUT/login-project" "$OUT/authorization-project"
unzip -q "$OUT/base.zip" -d "$OUT/base-project"
unzip -q "$OUT/login.zip" -d "$OUT/login-project"
unzip -q "$OUT/authorization.zip" -d "$OUT/authorization-project"

# 仅作快速人工检查；Codex 应将以下残留检查做成严格返回码的自动断言
python3 - <<'PY_AUDIT_001'
from pathlib import Path
import re, os
out = Path(os.environ['OUT'])
pat = re.compile(r'ZA21|LT99\.09|bee-starter-|EnableAuthClient|jdbc:tdsql-mysql|com\.tencentcloud\.tdsql')
for item in ('base-project', 'login-project', 'authorization-project'):
    root = out / item / 'backend'
    targets = [root / 'pom.xml'] + list((root/'src').rglob('*.java')) + list((root/'src').rglob('*.yml'))
    for p in targets:
        if p.exists() and pat.search(p.read_text(encoding='utf-8')):
            raise SystemExit(f'Private dependency remains: {p}')
print('PASS: no forbidden enterprise dependencies in generated backend')
PY_AUDIT_001

mvn -f "$OUT/base-project/backend/pom.xml" clean package -DskipTests
mvn -f "$OUT/login-project/backend/pom.xml" clean package -DskipTests
mvn -f "$OUT/authorization-project/backend/pom.xml" clean package -DskipTests
```

编译成功还不代表能够运行。需要额外使用测试 MySQL 启动生成的 Backend JAR，检查 Spring Bean、MyBatis Mapper、HTTP 登录、RBAC、Bootstrap。依赖校验应再以**独立 Maven 本地仓库 + 仅公开仓库的 settings.xml**执行一次，避免本机企业依赖缓存制造假通过。严禁将 Mock 登录的无条件放行当作成功。

### 7.1.6 V4 Update：Base → Login → Authorization

**① 从刚才 Base ZIP 中提取真实 TemplateState 并生成 Login Update 请求：**

```bash
python3 - <<'PY_LOGIN_UPDATE_001'
import json, zipfile, os
from pathlib import Path
out = Path(os.environ['OUT'])
with zipfile.ZipFile(out / 'base.zip') as z:
    state = json.loads(z.read('.devagentstudio/template-state.json'))
request = {
    'protocolVersion': '4',
    'currentTemplateState': state,
    'requestedConfig': {
        'capabilities': {'login': {'enabled': True, 'config': {}}}
    },
    'mode': 'APPLY'
}
(out / 'update-login.json').write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
PY_LOGIN_UPDATE_001

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' -H 'Accept: application/zip' \
  --data-binary @"$OUT/update-login.json" \
  "$ENGINE_URL/v1/update" -o "$OUT/update-login.zip"

unzip -p "$OUT/update-login.zip" extension-update-package.json | python3 -m json.tool
```

Login 更新包中的 `nextTemplateState.effective` 应包含 `login`，`stateSequence` 从 0 变为 1；更新操作不得注入 `ZA21:bee-starter-auth`、`EnableAuthClient` 或 TDSQL 配置。

**② 基于 Login 更新包的 `nextTemplateState` 生成 Authorization Update 请求：**

```bash
python3 - <<'PY_AUTH_UPDATE_001'
import json, zipfile, os
from pathlib import Path
out = Path(os.environ['OUT'])
with zipfile.ZipFile(out / 'update-login.zip') as z:
    state = json.loads(z.read('extension-update-package.json'))['nextTemplateState']
request = {
    'protocolVersion': '4',
    'currentTemplateState': state,
    'requestedConfig': {
        'capabilities': {
            'login': {'enabled': True, 'config': {}},
            'authorization': {'enabled': True, 'config': {}}
        }
    },
    'mode': 'APPLY'
}
(out / 'update-authorization.json').write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
PY_AUTH_UPDATE_001

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' -H 'Accept: application/zip' \
  --data-binary @"$OUT/update-authorization.json" \
  "$ENGINE_URL/v1/update" -o "$OUT/update-authorization.zip"

unzip -p "$OUT/update-authorization.zip" extension-update-package.json | python3 -m json.tool
```

验收：`extension-update-package.json.protocolVersion=4`；`nextTemplateState` 同时包含 `login` 与 `authorization`；`stateSequence` 从 1 变为 2；`operations` 有预期资源/脚本/SQL 的 ADD_FILE 和受管配置操作，所有 `payloadRef` 能在 ZIP 中找到；没有任何私有 Maven/Annotation/驱动注入。

**③ 无变化重试，必须返回 HTTP 204：**

```bash
python3 - <<'PY_NO_CHANGE_001'
import json, zipfile, os
from pathlib import Path
out = Path(os.environ['OUT'])
with zipfile.ZipFile(out / 'update-authorization.zip') as z:
    state = json.loads(z.read('extension-update-package.json'))['nextTemplateState']
request = {
    'protocolVersion': '4',
    'currentTemplateState': state,
    'requestedConfig': {'capabilities': {
        'login': {'enabled': True, 'config': {}},
        'authorization': {'enabled': True, 'config': {}}
    }},
    'mode': 'APPLY'
}
(out / 'update-no-change.json').write_text(json.dumps(request, ensure_ascii=False), encoding='utf-8')
PY_NO_CHANGE_001

STATUS=$(curl --silent --show-error -o "$OUT/no-change.body" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data-binary @"$OUT/update-no-change.json" "$ENGINE_URL/v1/update")
[ "$STATUS" = 204 ] && [ ! -s "$OUT/no-change.body" ] || {
  echo "Expected HTTP 204 empty body, got $STATUS" >&2
  exit 1
}
```

> **关键区别**：上述 Update 测试只验证**服务端规划了正确的更新包**。更新包不是完整工程，**不可直接解压覆盖 Base 工程**。要验证更新后项目可运行，必须使用现有 XCodeAgent Workspace Operation Executor 根据 `operations` 和 `payloadRef` 应用到 Base 工作区、原子提交 `nextTemplateState`，再对结果执行 Maven 构建、启动、Login/RBAC/Bootstrap 测试。若当前执行环境无法连接 XCodeAgent Executor，验收报告应把“Generate 完整工程可运行”“Update 规划正确”“Update 实际 Apply 未验证”分别列明，绝不虚报通过。

### 7.1.7 切回正式模式做对照

停止原 Standalone 进程后，按 **2.1.2** 的正式模式命令重启。重新发送**同一份** `generate-base.json`，保存为 `base-enterprise.zip`；检查 `backend/pom.xml` 使用 `ZA21:bee-starter-parent`，而不是 Standalone Spring Boot Parent。无需在没有企业仓库的环境编译正式工程；此步只验证默认路径不执行 Adapter。

### 7.1.8 Codex 必须补充可重复执行的自动脚本

新增 `devtools/standalone-adapter/scripts/http-smoke.sh`（可以用 Python 3 标准库实现 HTTP/ZIP/JSON 核验），支持：

```bash
./devtools/standalone-adapter/scripts/http-smoke.sh \
  --base-url http://127.0.0.1:18080 \
  --expected-mode standalone \
  --output-dir .standalone-build/http-verification
```

自动脚本须有明确的 HTTP 状态码、`Content-Type`、ZIP 魔数与路径安全性、POM Parent、Revision、State、Operation、`payloadRef` 与私有依赖残留断言，并写入 `requests/*.json`、ZIP、`metadata/*.json`、`verification.md` 等报告；失败返回非零退出码。`verification.md` 区分未执行/通过/失败，避免把编译测试、功能测试和 Update 实际落盘测试混为一谈。禁止把真实 `applicationConfig` 中的密钥打印到日志。

**Revision 隔离提醒**：当前 1016 的 TemplateState 没有独立 Variant 字段，单靠 Revision 高低不能严格拒绝所有跨模式 Update。测试必须使用相同 Standalone Release，真实 XCodeAgent 对接须增加外部来源隔离/拦截；不能声称现有引擎已经防止全部跨模式误更新。



### 7.1.9 V3/V4 请求体边界与 Postman 设置

本节提供一个最小、可重复的 Postman 与 curl 测试规范。**运行环境是 Engine Service，不是生成的 Backend**：

| 请求 | Method / URL | Body | 成功响应 |
| --- | --- | --- | --- |
| Base Generate | `POST http://127.0.0.1:18080/v1/generate` | `{"requestedConfig":{"capabilities":{}}}` | `200 application/zip`，完整工程 |
| Login Generate | 同上 | `{"requestedConfig":{"capabilities":{"login":{"enabled":true,"config":{}}}}}` | `200 application/zip` |
| Authorization Generate | 同上 | `{"requestedConfig":{"capabilities":{"authorization":{"enabled":true,"config":{}}}}}` | `200 application/zip`，`effective` 含 Login |
| V4 Update | `POST http://127.0.0.1:18080/v1/update` | `protocolVersion:"4"` + **来自 Base ZIP 的完整** `currentTemplateState` + `requestedConfig` + `mode:"APPLY"` | `200 application/zip` 操作包或 `204` 无变化 |

Postman：Body→raw→JSON；请求头 `Content-Type: application/json`；响应 `Send and Download` 保存为 `.zip`，不能把二进制 ZIP 当 JSON 解析。`/v1/generate` 顶层只允许 `requestedConfig`，`/v1/update` 顶层只允许四字段；**不要添加 `standalone`、`templateMode`、`profile`**。模式只能在服务启动时选择。

`Generate` 必须包含 `.devagentstudio/template-state.json`、`frontend/`、`backend/`。`Update` 必须包含 `extension-update-package.json` 和被引用的 `payload/`，不是完整工程。`Postman` 和脚本都应检验 Status、Content-Type 和 ZIP 内部条目。

### 7.1.10 服务级/生成工程级/增量落盘级必须分开报告

| 级别 | 证明什么 | 不证明什么 |
| --- | --- | --- |
| L1 HTTP & ZIP | 引擎启动正确、加载指定 Runtime Source、Generate/Update 合同合法 | 生成后端可编译、数据库功能可用 |
| L2 Generated Backend | 在公开 Maven 仓库上可编译、真实 MySQL 启动、Mock Login/RBAC/Bootstrap 通过 | 已从 V4 操作包正确 Apply |
| L3 Incremental Apply | Base 经 Login、Authorization 两轮 V4 Update **真实执行操作并落盘**后编译运行 | 企业 SSO/TDSQL 在行内可用 |

P0/P1 验收必须达到 L2；P2 完整验收必须达到 L3。若无法使用现有 XCodeAgent Workspace Operation Executor，**必须明确在报告中标出 L3 未执行**，不能将两份操作包的内容检查冒充真实 Apply。不得临时编写一个简单 `unzip -o` 冒充 Executor。

### 7.1.11 HTTP 自动校验必须实现的断言（Codex 直接编码）

1. 每个 Generate 响应是 `HTTP 200` 且 `Content-Type` 包含 `application/zip`；ZIP 能安全打开、无重复路径、绝对路径、`../`、符号链接或异常超大条目。
2. Standalone 的 `backend/pom.xml` 包含 Spring Boot Parent、无 ZA21/LT99.09；Base 无 Login/Authorization 代码，Authorization 包含 Login/Authorization 两 Extension 文件且保留真实 SQL/Bootstrap 资源。
3. Standalone 的 `.devagentstudio/template-state.json.templateRevision` 等于 Adapter Release 修订，原生环境则等于正式 `code/template-revision.txt`，`effective/requested` 符合依赖闭包。
4. 生成后端所有 POM/Java/YAML/Mapper XML/脚本都经过私有依赖扫描；但扫描排除合法业务包名 `com.cmbchina.backend`，避免把工程自己的包误删。
5. V4 更新包 `protocolVersion=4`，`sourceRevision` 与请求 State 的 Revision 一致，`expectedStateSequence` 和 `nextTemplateState.stateSequence` 逐次为 `0→1→2`；`ADD_FILE`/`ENSURE_CAPABILITY_CONFIG`/其他贡献操作不产生 Bee 认证依赖或 Annotation。
6. 每个**存在且非空的** `operations[*].payloadRef` 必须对应 ZIP 中恰好一个文件（有些 Contribution 操作并无 payloadRef）；目标路径安全且操作编号连续；权限 SQL、两个 Bootstrap 启动脚本以及 `BootstrapDataSource.java` 的 Update 资产可回查。
7. 对第二轮后的相同能力集合发送 V4 APPLY，严格断言 `HTTP 204`、空 Body。再以 `RECONCILE` 验证现有合同允许的受管修复操作，但不得修改用户配置。
8. 同一请求连续调用两次 Generate 的 ZIP 字节相同（若现有打包规则保证确定性），且源根目录与 Git 正式代码 hash 不变。
9. 原生模式重启后相同 Generate 请求恢复 1016 的企业 POM/Manifest；Standalone 模式的原生适配产物不得作为企业模式的 currentTemplateState 测试输入。

### 7.1.12 缺少 Java 私仓时的测试环境准备

- 预备 Java 8 或与 1016 Maven/MapStruct/Lombok 兼容的受支持 JDK、Maven、Python 3、curl、unzip；MySQL 使用临时本地库或一次性容器，不使用生产/共享数据库。
- `mvn` 不允许依赖公司镜像地址或 `~/.m2` 私仓缓存。提供 Adapter 测试专用 Maven `settings-public.xml` 与独立 `-Dmaven.repo.local=<repo>/.standalone-build/m2`，确保 Parent/Starter/Connector 真正可以下载。
- MySQL 测试库名称须以 `_standalone_test` 结尾。任何 Bootstrap 写库前必须验证目标为明确允许的本地测试数据库；禁止删除用户业务表。测试结束仅清理本轮创建的测试库/容器。
- 把 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 以临时环境变量注入生成后端；所有敏感字段在日志、`verification.md` 中做遮蔽。测试 `application-dev.yml` 不得再保留正式代码里开发凭据的字面值。
- 测试启动后的真正后端 API 时使用**后端监听端口**（如 `8080`），不要误用引擎端口 `18080`。两种服务需要分别启动与记录 PID/退出码。



### 7.1.13 真实应用配置投影验证（防止只测空配置）

还必须增加一次包含 `applicationConfig` 的 Generate，用**纯测试值**检查 `1016` 保留的投影规则（该接口只有 Generate 允许随初始请求携带应用参数）：

```json
{
  "requestedConfig": {
    "capabilities": {
      "authorization": {"enabled": true, "config": {}}
    },
    "applicationConfig": {
      "appName": "Standalone 测试工程",
      "appIcon": "AppstoreOutlined",
      "theme": {"primaryColor": "#3366CC"},
      "auth": {
        "enable": true,
        "authnSource": "yst",
        "yht": {
          "clientId": "standalone-test-client",
          "privateKey": "test-value-only",
          "publicKey": "test-value-only",
          "centerPublicKey": "test-value-only"
        }
      }
    }
  }
}
```

验证 `frontend/.env` 已按 1016 既有方式投影应用名与客户端公开参数，`backend/src/main/resources/application-dev.yml` 保留必要的 `yht.authentication.*` 字段，`backend/src/main/resources/application.yml` 不泄漏实际密钥，最终生成工程仍能以 Standalone Mock 认证启动。上述示例内的密钥字段只是**测试占位字符串**，不是真实企业密钥。

**重要**：若 Standalone Mock 登录不使用 YHT，Adapter 必须明确哪些 YHT 参数仅用于兼容原有投影契约，不能以参数存在推断真实企业登录可用。V4 `requestedConfig` 不允许 `applicationConfig`；后续配置参数修改仍由调用方遵循 1016 既有工作区规则完成，不能在本任务自创接口字段。

## 8. P3：自动化校验、隔离与迁移验证

### 8.1 最少命令（由 Codex 实际实现）

```bash
# 入口命令均为本次拟新增，非仓库既有命令
./devtools/standalone-adapter/build.sh prepare
./devtools/standalone-adapter/build.sh audit
./devtools/standalone-adapter/build.sh verify-base
./devtools/standalone-adapter/build.sh verify-full
./devtools/standalone-adapter/build.sh verify-engine
./devtools/standalone-adapter/scripts/http-smoke.sh --base-url http://127.0.0.1:18080 --expected-mode standalone --output-dir .standalone-build/http-verification
./devtools/standalone-adapter/build.sh verify-all
```

`verify-all` 应串行汇总报告并在任一关键步骤失败时返回非零码；可按环境提供 `--skip-integration` 做快速开发检查，但不能把跳过集成测试称为验收通过。

### 8.2 必测用例

| ID | 用例 | 通过标准 |
| --- | --- | --- |
| A01 | 运行 Adapter 前后比较 Git 工作树 | 正式 `template-engine/`、`template-source/code/` 原始文件无变化 |
| A02 | Adapter 连续两次相同输入构建 | 结果哈希一致、幂等；无重复注入文件 |
| A03 | Manifest 路径不存在/前置 hash 不一致 | 明确失败，无部分适配被当作成功 |
| A04 | 公开仓库 + 独立 Maven 缓存 | 生成 `Base`、`Login`、`Authorization/Full` 工程均成功 resolve 和 package，无内部依赖 |
| A05 | Base 与各能力工程启动 | Spring、MyBatis、Mapper、Datasource 与主要 API 无 Bean/配置错误 |
| A06 | 本地身份测试 | 未登录拒绝受保护资源；合法身份可访问允许接口；请求结束上下文清除 |
| A07 | RBAC 权限测试 | 只根据资源/角色授权决定允许或拒绝；不出现固定放行 |
| A08 | Bootstrap 首次 + 重复 | 真实 MySQL 建表/数据初始化正常、可重复执行且无重复脏数据 |
| A09 | Bootstrap 缺 Manifest、SQL、数据库信息 | 有明确非零退出码，不把失败静默视作成功 |
| A10 | V4 增量安装 `Base → Login → Authorization` | 操作与 TemplateState 合法、目标文件齐全、无私有依赖回流 |
| A11 | V3/V4 配置投影 | `application-dev.yml` 合法，必要标量默认节点存在，不发生 `CONFIG_TARGET_CONFLICT` |
| A12 | 企业主线回归 | 删除 Adapter 后，1016 原有 Release Loader、EngineServiceIT/CI 行为无变化 |
| A13 | 跨模式 Update | 验证 Adapter 测试/外部执行器的来源检查；记录原生引擎缺少 Variant 字段的已知边界，不谎称 HTTP 接口天然阻断 |
| A14 | Bootstrap 脚本与 SQL 资产 | 生成包存在 `backend/scripts/bootstrap-authorization.sh` 和 `backend/sql/authorization/schema.sql` |
| A15 | 默认开关关闭 | 原生生成，Adapter 不执行、不影响正式服务 |
| A16 | Standalone 开关开启 | 启动前 prepare/audit，Source Root 指向临时树，生成公开 POM |
| A17 | 开关非法/准备失败 | fail-fast，不启动新进程、不回退正式模式；如果端口已被旧进程占用，报告要识别旧进程而非误判为新服务就绪 |
| A18 | 真实 HTTP Generate | Base/Login/Authorization ZIP、Revision、能力闭包和 SQL/脚本资产符合预期 |
| A19 | 真实 HTTP V4 Update | Base→Login→Authorization 返回操作 ZIP，随后重复请求 204；实际 Workspace Apply 独立记录 |
| A20 | 重启切回原生 | 同一个 Generate 请求恢复 ZA21 原生 POM；不得通过 HTTP 字段切换模式 |

### 8.3 安全及移交标准

- 新增文件必须全部在 `devtools/standalone-adapter/`；例外仅 `.gitignore`（临时目录）以及确有必要的通用 Source 根目录配置。
- Mock JWT、本地身份兼容包、临时 POM、覆盖后的 YML 不得出现在正式 `template-source/code/`；CI 验证防回写。
- 所有临时结果含生成工程均归属于 `.standalone-build/`，可安全一次性删除。
- README 明确行内迁移步骤：删除 `devtools/standalone-adapter/`、`.standalone-build/`，使用 `1016` 原生 Source/发布配置；验证原有 Release 生成/更新行为不变。



### 8.4 验证脚本最终接口与拒绝条件

Codex 应提供一个无需手动拼接 JSON/下载包的“一键端到端校验”入口（新增）：

```bash
# 终端 A：仅设置一个开关即可启用 Adapter；release.yaml 维护本地 Revision
export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
./devtools/standalone-adapter/run-engine.sh

# 终端 B：对已经启动的服务执行 HTTP/ZIP/状态/构建校验
./devtools/standalone-adapter/build.sh verify-all \
  --engine-url http://127.0.0.1:18080 \
  --mysql-test-url 'jdbc:mysql://127.0.0.1:3306/xcode_standalone_test'
```

第二条是**待 Codex 实现的目标 CLI**。如果参数缺少可连接 MySQL，不得把真实 Bootstrap、RBAC 运行测试算作通过；可以输出 L1 通过、L2/L3 未执行并返回非零或专用的不完整验收状态。另支持 `verify-http-only` 供无数据库环境快速检查，不能等同于完整验收。

必须包括故障注入：`manifest.yaml` 的目标不存在、SHA 不匹配、私有依赖未移净、Adapter `release.yaml` 与正式修订冲突、生成的 ZIP 内有重复/越界路径、更新包 stateSequence 不正确、无数据库认证等；每一项不得误报成功。

### 8.5 回退/删除演练

1. 停止 standalone 服务；删除 `.standalone-build/template-source`（测试报告可另留存）；用默认关闭状态启动原生引擎。
2. 原生 `POST /v1/generate` 输出的 `backend/pom.xml` 仍应含 `ZA21:bee-starter-parent`，Login Extension 仍声明企业版 `bee-starter-auth` / `EnableAuthClient`；原生 V3/V4 单元测试继续通过。
3. 在单独测试分支删除整个 `devtools/standalone-adapter/` 后，原生 `template-engine` 的构建、启动、Generate/Update 不依赖此目录。
4. 不得删除正式 `template-source/code/` 中原有的 Base、Extension、`scripts/bootstrap-authorization.sh`、`sql/authorization/schema.sql`。
5. 若未能在本地私仓缺失情况下编译原生 Backend，只需验证正式 Release Loader 与生成输出保持不变，不得称“企业工程运行已验证”。

## 9. Codex 执行顺序与交付内容

严格按以下顺序实施，每一步运行相应测试，再开始下一步；无需每步询问确认，但失败必须说明并停止扩大范围：

1. **扫描建档：** 对比 1016 与 `template_refactor` 可借鉴内容；生成全量私有 Maven 坐标和 `com.cmb.*` 引用清单、上游 SHA、操作清单。特别检查企业 Parent 管理的隐式版本、MyBatis 自动配置和配置投影默认路径。
2. **建立 Adapter 基础设施与启动开关：** `manifest.yaml`、`release.yaml`、prepare/audit、精确 replace/add/remove/patch、`run-engine.sh`；默认 `TEMPLATE_ENGINE_STANDALONE_ENABLED=false`，正常启用只需一个环境开关；与既有 `TEMPLATE_ENGINE_SOURCE_ROOT` 关联，校验路径/hash、失败保护及正式源码零污染。
3. **完成 P0：** 独立 POM、Spring YAML、Bee 受限兼容类、MyBatis 配置、Base 构建启动。
4. **完成 P1：** 登录 Manifest、Mock JWT 与 TokenContext 兼容、Bootstrap DataSource 与最小补丁、真实 RBAC/Bootstrap 功能测试。
5. **完成 P2：** 复用已存在的 Source Root 装载逻辑，实现真实 HTTP Generate 三场景、V4 `Base → Login → Authorization` 两次增量规划和 204 幂等测试；使用现有 Workspace Executor 验证实际 Apply（不能使用时明确未验证）。
6. **完成 P3：** 公开仓库隔离依赖验证、全量自动化、反向保护、运行说明及迁移验证。

提交时输出以下交付物：

- `devtools/standalone-adapter/` 完整代码、`release.yaml`、测试及 README，含 `run-engine.sh`、`http-smoke.sh` 和完整接口请求样例。
- `reports/dependency-map.md`：`1016` 内部坐标/引用点 → 替代文件 → 测试覆盖；示例内容复制到 Adapter 文档目录，运行报告存在临时 reports。
- `reports/overlay-diff.md`：所有 `replace/add/remove/patch` 文件列表、理由、前置 SHA；禁止笼统“替换所有依赖”。
- `reports/verification.md`：每个用例的命令、实际通过/失败/未执行状态、日志位置；额外包含开关两模式、HTTP Generate 三场景、V4 Update 两次 + 204，以及实际 Workspace Apply 的独立结论。
- 最终 `git diff --stat` 及变更范围；如触碰正式代码，逐文件解释通用配置修改的必要性。

## 10. 交付成功判定

**只有同时满足下面全部条件，才认定完成：**

1. `1016` 正式 Base、Extension、Assembly 和 V3/V4 核心处理逻辑未被本地适配修改。
2. Adapter 构建出的 Standalone 模板真正不依赖内部 Maven 仓库，四类能力组合均构建、启动及执行必要功能。
3. `Base → Login → Authorization` 真实 V4 增量安装不会重新引入 Bee/TDSQL/内部认证依赖。
4. 权限 Bootstrap 保留 1016 Manifest v3 业务逻辑，使用实际 MySQL 完成初始化，脚本和 SQL 资源完整交付。
5. 临时与正式模板 Revision 不混淆，自动测试/外部执行器禁止跨模式误更新；**现有 V3/V4 原生 API 缺少 Variant 身份字段，无法仅通过独立 Revision 从服务端严格拒绝所有跨模式输入，该能力不在本次范围内，不得误报已具备。**
6. 默认 `TEMPLATE_ENGINE_STANDALONE_ENABLED=false` 时完全不走 Adapter；设置为 `true` 且通过 Adapter `run-engine.sh` 启动后，通过真实 `/v1/generate` 生成适配项目、通过 `/v1/update` 规划 V4 增量；切换模式必须重启服务。
7. 整个 Adapter 可整体删除，正式 1016 在行内环境继续按原方式构建、生成、更新。

> 若企业版 `1016` 本身有与本方案无关的既存失败（如内部仓库不可访问），必须记录“环境限制/既存问题”，不要把这些失败归咎于 Adapter，也不要悄悄改动企业正式逻辑。
