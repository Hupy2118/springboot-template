# 实施与验收记录（首轮 1005 与后续补充）

- 日期：2026-10-23（Asia/Shanghai）。
- `1016` / `origin/1016` 基线：`e56e7676b300d45e01093cd7697186e449046feb`；fetch 后两者一致。
- 正式 Revision：`2026.10.23.2`；首轮已激活 Standalone Revision：`2026.10.23.1005`。
- 独立实施分支的 Git 写操作未获批准，因此未切分支、未提交；当前修改位于 `1016` 工作区。用户原有未跟踪的方案文档保留。
- 正式 `template-engine/`、`template-source/code/` 无代码差异。唯一既有文件变更是根 `.gitignore` 加入 `.standalone-build/`；其余新增文件全部在 `devtools/standalone-adapter/`。
- 本次用户明确要求真实数据库由其手工验证，后续自动化不连接/写入真实 MySQL。最初连接尝试返回 MySQL 1045，未执行建库或 SQL；报告不保存该连接凭据。

## 实际执行

| 命令 / 检查 | 实际结果 | 临时日志/产物 |
| --- | --- | --- |
| `build.sh test` | PASS，13 个 Python 测试；覆盖精确四操作、SHA/路径/重复目标、symlink/大小写/二进制、失败保留旧源、Revision 冲突、进程锁、默认模式不依赖 Python/清单、ZIP 越界/重复/link、V4 错误 sequence/payloadRef/跨 Release | `.standalone-build/reports/adapter-tests.log` |
| `build.sh prepare` 连续两次 + `audit` | PASS，完整 source/fingerprint 文件字节哈希一致；已停止服务的锁可恢复 | `.standalone-build/reports/reproducibility.log`、`build-fingerprint.json` |
| 活动真实服务期间 prepare | 预期失败，明确 Release in use，旧源哈希未变 | `.standalone-build/reports/live-release-rejection.log` |
| `mvn -f template-engine/pom.xml -pl engine-service -am package` | PASS | `.standalone-build/reports/engine-package.log` |
| `build.sh verify-engine` → Maven verify + protected surfaces + revision gate | PASS，原生单元/集成回归全部通过 | `.standalone-build/reports/engine-verify.log`、`protected-surfaces.log`、`release-revision.log` |
| Standalone `run-engine.sh` + `http-smoke.sh` | PASS，127.0.0.1:18081，真实 JAR + application-local.yml | `.standalone-build/reports/engine-standalone.log`、`http-verification/` |
| 默认关闭 `run-engine.sh` + native smoke | PASS，127.0.0.1:18083，同样 Generate 请求恢复 ZA21 Parent 和正式 Revision | `.standalone-build/reports/engine-native.log`、`http-native/` |
| `build.sh verify-full --engine-url http://127.0.0.1:18081 --manual-database` | PASS 于明确执行的非数据库范围；四工程独立 package、依赖树、真实 JAR 启停、Mock HTTP | `.standalone-build/reports/verify-full-driver.log`、各工程 package/start/dependencies 日志 |
| `build.sh verify-all --engine-url http://127.0.0.1:18081 --manual-database` | 返回 **3（验收不完整）**；所有自动检查 PASS，真实 DB 与 Executor 未执行 | `.standalone-build/reports/verify-all-driver.log`、`verification-latest.md` |
| `git diff --check`；正式目录 diff；初始/结束 Runtime Source 清单比较 | PASS | 正式源无修改 |

公开 Maven 校验从隔离缓存开始，user/global settings 均指向 `settings-public.xml`，镜像仅 Maven Central，不用企业 Parent、本机私仓缓存、假 Jar 或 systemPath。四工程最终单元测试分别为 Base 2、Login 4、Authorization 7、Full 7，零失败/跳过；Authorization/Full 包括未经改写的原始拦截器测试。编译字节码目标为 Java 8，实际 Maven/JAR 使用 JDK 18.0.2.1。

所有自动启动的数据源使用关闭的环回端口和占位账号，不接触真实 MySQL。Base 通过 Spring 启动及真实 HTTP 404 探测；Login/Authorization/Full 通过无身份 401、错误密钥 401、合法签名 Cookie 身份 200、登出后 401；Authorization/Full 另验证原 RBAC 对匿名角色接口的 `XCD1B11` 拒绝。启动/Mapper Bean 成功不等同于 JDBC 查询和真实数据权限已验收。

## 分层结果

| 层级 | 结果 |
| --- | --- |
| L1 HTTP/ZIP | **PASS**：Base/Login/Authorization/Full 的 200 ZIP、POM、Revision、能力闭包、重复 ZIP 字节一致；真实 applicationConfig 投影；禁止 HTTP standalone 字段；V4 Login 0→1、Authorization 1→2、payload/SQL/双平台脚本、204 空响应、RECONCILE；V3 规划回归；正式源哈希不变 |
| L2 Generated Backend | **部分验证**：四类公开构建、单元测试、JAR 启动及 Mock HTTP 通过。Bootstrap local/dev dry-run、缺 Manifest/SQL、非法 JDBC URL 非零保护通过。真实数据库、Mapper SQL、真实成员/角色授权、Bootstrap 首次/重复/锁竞争由用户手工验收，未记为通过 |
| L3 Incremental Apply | **未执行**：无已连接的现有 XCodeAgent Workspace Operation Executor。仅检查了服务端两份操作包，未将它们当作完整工程解压，也未编写简化 Executor 冒充 Apply |

## A01–A20

| ID | 状态与证据边界 |
| --- | --- |
| A01 | PASS：正式目录 Git diff 与 Runtime Source 哈希无变 |
| A02 | PASS：真实重复 prepare 完整字节清单相同 |
| A03 | PASS：缺目标、上游 SHA、补丁结果 SHA、越界等故障测试 |
| A04 | PASS：四类公开 Maven package / dependency tree |
| A05 | 部分：四类 JAR/Bean/Mapper 配置启动与 HTTP 通过，真实 JDBC/SQL 待手工 |
| A06 | PASS：真实 Mock HTTP 及签名/上下文清理测试，默认/条件注册有明确配置边界 |
| A07 | 部分：原始拦截器单元测试验证拒绝/允许，匿名真实 HTTP 拒绝；真实 DB 角色数据待手工 |
| A08 | 未自动执行：真实 Bootstrap 首次、重复与锁竞争由用户手工验证 |
| A09 | 部分：真实入口 dry-run、缺 Manifest/SQL/非法 URL 非零；真实 DB 认证错误验收未自动执行 |
| A10 | 部分：两轮 V4 规划与资源完整性通过；实际增量 Apply 未验证 |
| A11 | PASS：V3/V4 规划与真实 applicationConfig 投影，无 CONFIG_TARGET_CONFLICT |
| A12 | PASS 于原生 Engine/Release Loader 回归与生成输出；企业 Backend 私仓运行未验证 |
| A13 | 部分：自动客户端仅本轮同模式 State，错误 Release 拒绝测试；外部 Executor 拦截未验证，原生 API 不具备完整 Variant 隔离 |
| A14 | PASS：Generate 与 Update 内两平台脚本和 schema.sql 逐字节回查 |
| A15 | PASS：默认关闭真实原生生成；缺 Python/清单的 native 单元测试 |
| A16 | PASS：开启自动 staging/audit/commit，真实指定 Source Root |
| A17 | PASS：非法开关、冲突 Source、准备失败不回退、活动进程锁拒绝；真实服务就绪以 Generate 为准 |
| A18 | PASS：四类真实 HTTP Generate |
| A19 | 部分：两轮 V4 + 204 PASS；实际 Workspace Apply 未执行 |
| A20 | PASS：默认原生真实启动，同一请求恢复企业 Parent/Revision |

## 已发现的限制与未执行项

1. 原生 API 没有 Variant 字段；高位 Revision 不是跨模式安全边界。原生服务不会严格拦截所有跨模式 State，仍需调用方来源校验。
2. 原生错误请求若 `Accept` 仅为 `application/zip`，Spring 错误 JSON 可能无法协商并产生 500。脚本/Postman 使用 `application/zip, application/json`，非法字段按合同得到 400；未改服务端。
3. 正式 frontend Login 页面是 YHT callback，非本地 Mock 登录页。Logout 页面引用的 logoutApi 在该基线模块未导出，属于原有前端不一致；本任务保留前端字节，不宣称修复或完成前端浏览器联调。
4. 企业 YHT 参数只保留投影路径，不代表企业 SSO 有效；企业 SSO/TDSQL/私仓后端运行未验证。
5. 未在独立分支删除整个 Adapter 做删除演练（分支创建未获批准）；已证明原生代码无 Adapter 引用、原生构建/Generate 通过，native 脚本不依赖 Python/清单。迁移删除步骤见 README。
6. 所有本轮测试服务已关闭；用户可按 README 自行启动。手工数据库验收入口不会被自动验证调用。

反向检验：四工程可编译并启动、规划包内容正确，是“适配链路可用”的证据；但关闭端口的占位数据源无法证明真实 Mapper 查询、Bootstrap 幂等和管理员实际权限，服务端规划也不能证明工作区 Apply。这些结论保持未验证，没有用构建成功替代功能验收。

## 变更规模

`git diff --stat`（已跟踪文件）为根 `.gitignore` 新增 2 行；另有未暂存的新 Adapter 目录，共 52 个文本文件、2428 行（独立 no-index diff 统计，含报告）。用户原有方案文档不计入本次新增。完整表保存 `.standalone-build/reports/adapter-diff-stat.txt`。未 stage、未 commit。


## 2026-10-23：前端既有 SSO 接口补齐（1006）

- Adapter 新增 `GET /api/sso/login?code=...` 兼容方法，复用签名 JWT 与原 Cookie Filter，返回 SUC0000 + `{userId,userName,avatar?}`，响应 Set-Cookie 和 Cache-Control:no-store。
- 非空 code 仅是模拟回调占位参数，不校验真实企业授权、不按 code 选择用户。固定用户由 STANDALONE_MOCK_SSO_USER_ID 指定，默认配置白名单第一位。用户配置兼容旧字符串及新 userName/avatar 对象。
- 原 POST 模拟登录接口继续校验共享密钥；dev/local、显式开启 Mock 和 loopback 限制保持。
- 已同步到用户指定的 `response_111/backend`：两个 Java 文件、新增 SsoLoginMvcTest、更新 docs/login.md。原文件已备份到 `.standalone-build/response111-sso-backup-1ao7wgwe`，未修改该工程前端、数据源配置或 TemplateState。
- 生成工程实测 `mvn package` PASS：6 个测试全部通过（新增 SSO 3 + 原始权限 3）；日志 `.standalone-build/reports/response111-sso-package.log`。Adapter 临时 Authorization 工程 10 个测试 PASS，日志 `sso-login-package.log`。
- 真实临时 JAR HTTP PASS：SSO body/avatar、Set-Cookie、携带该 Cookie 访问 me、登出、旧登录接口、匿名 RBAC 拒绝；数据源仍指向关闭端口，未连接真实 MySQL。
- 原生 `mvn -f template-engine/pom.xml verify`、保护面与 Revision Gate、Adapter 13 个故障/基础测试、git diff --check 均 PASS；日志 `sso-engine-verify.log`、`sso-adapter-tests.log`。
- 新清单 `2026.10.23.1006` 的精确 Overlay/上游 SHA/私有依赖检查 PASS。发现用户已有引擎 PID 47129 持有临时 Release，未停止该进程、未热替换源；新版本要在用户停止并重新运行 run-engine.sh 后激活。当前已运行后端也需要重启才加载 Java 修改。
- 未新增数据库或浏览器验收；不能据此声称数据库 RBAC、真实 YHT 或完整浏览器登录流程已验证。

## 2026-10-23：Standalone 默认可用的模拟登录（1007）

- Login 配置贡献改为 `${STANDALONE_MOCK_ENABLED:true}`。缺省白名单包含 member-001 / Local Admin 和 member-002 / Local Member，SSO 回调默认选择第一位；仍允许覆盖身份和用户配置。
- 缺失或空字符串的签名/POST 登录密钥分别在后端启动时生成 32 字节 SecureRandom 随机值，只保留在进程内存，不写入生成文件或日志。重启后缺省签名 Cookie 失效。非空短密钥、非法非空用户配置继续拒绝启动；旧 POST 登录需显式配置登录密钥。
- 保留 dev/local、loopback、Cookie、ThreadLocal 清理及真实 RBAC 约束；显式 false 与仅 prod Profile 不注册 Mock。Base 不增加 Login 代码。数据库配置和真实 Bootstrap 初始化仍由调用方准备。
- 现有引擎 PID 55324 持有 1006 模板源，本次未停止或覆盖该进程/源。验收在 `.standalone-build/mock-defaults-3s6kkpyl/source-repo/` 构造独立 1007 Runtime Source，两次 prepare 字节清单一致且 audit 通过。当前源须在停止旧引擎后，通过 run-engine.sh 重启激活。
- 真实独立 Engine JAR 使用权威 application-local.yml 与绝对 Source Root 启动；Generate 重复 ZIP 字节一致、配置投影、V3/V4 规划及 V4 Login 默认启用贡献检查通过。未执行工作区增量 Apply。
- Base / Login / Authorization / Full 公开 Maven package 通过，测试数分别为 2 / 12 / 15 / 15，零失败和跳过。新增测试覆盖无配置身份与随机签名隔离、显式密钥稳定性、非法配置、条件注册及非环回绑定失败。
- 四类真实 JAR 在清除全部 STANDALONE_MOCK_* 和 SPRING_PROFILES_ACTIVE 后启动通过；含 Login 的工程完成默认 SSO、Cookie 身份与登出。额外验证 dev 下密钥/用户/SSO 身份覆盖、旧 POST 登录、显式关闭和 prod 不注册。Authorization / Full 的匿名 RBAC 拒绝及 Bootstrap local/dev dry-run、缺 Manifest/SQL、非法 JDBC URL 检查通过。
- 所有验收数据源指向关闭端口，未连接或写入真实 MySQL。真实 DB 权限、Bootstrap 初始化、前端浏览器完整流程与 Executor Apply 均未验证。独立验收进程已停止。
- Adapter 13 个测试、原生 Engine `mvn verify`、`mvn -f template-engine/pom.xml -pl engine-service -am package`、保护面检查、Revision Gate（HEAD）及 whitespace 检查通过。原生 Runtime Source 哈希保持不变。
- 完整分项报告：`.standalone-build/mock-defaults-3s6kkpyl/reports/verification.md`；HTTP/ZIP 证据在同目录的 `../http-verification/`；原生回归日志为 `.standalone-build/reports/mock-defaults-engine-verify.log`。

## 2026-10-23：Mapper 资源复制与前端登出修复（1008）

- 在 response_222 的保留时间戳副本复现了 Maven resources:3.2.0 的 NoSuchFileException：ZIP 解压资源 mtime 为 epoch 0，复制实现认为源与不存在的目标一样旧而跳过复制，随后设置目标权限失败。改为正数固定时间后，同一资源内容的 resources 及 clean package 通过。此前验收助手写入文件时使用当前时间，掩盖了这个缺陷；现已改为固定归档时间。
- Engine PackageBuilder 将所有 Generate/Update ZIP entry 固定为 DOS 本地时间 1980-01-02 00:00:00；仍按路径排序。新增回归测试验证正数时间、无额外时间字段及 UTC/上海/洛杉矶下 ZIP 字节一致。HTTP 验收也检查真实 ZIP 元数据。
- Standalone 新增两个前端精确 Overlay：补齐 logoutApi，调用已有 POST /api/standalone/logout；Logout 页面等待服务端成功后清除身份、用户缓存和 Authorization，返回本地首页。企业前端源码、路由受管面与 TemplateState 未改写。
- response_222 已同步两个前端文件，并只修正 backend/src/main/resources 下六个 epoch-zero 文件的 mtime；逐文件内容摘要保持不变。原前端备份及可审阅补丁位于 `.standalone-build/response222-fix-6zml6r57/original-frontend/` 和 `response222-frontend.patch`。
- 修复副本的 TypeScript + Rsbuild 生产构建通过；登出行为测试通过，验证在服务端返回前不清身份，返回后清理凭据并导航首页。既有 ts-jest 27 与 TypeScript 5 不兼容，测试使用 tests/frontend 的独立编译器配置；未改业务工程依赖或宣称默认 Jest 配置通过。
- 原工程副本的 clean package 与 3 个原有权限测试通过。由于 mockito-inline 在沙箱内无法动态附加，使用已安装的 Byte Buddy 启动代理完成测试，未跳过测试或改变项目依赖。
- 使用独立 1008 Source Root 和权威 application-local.yml 启动真实 Engine JAR；Generate/V3/V4、重复 ZIP、默认 Mock 和新增前端 API 的 V4 payload 检查通过。新 Authorization ZIP 经系统 unzip 保留原始时间解压，clean package 自动复制三个 Mapper，字节比对通过；无手工复制。
- 新生成 JAR 和 response_222 修复副本 JAR 均通过默认 SSO、Cookie 身份、登出与匿名 RBAC 拒绝检查。数据源通过 Spring 配置明确覆盖到关闭端口，未连接或写入真实 MySQL，未执行真实 Bootstrap 或 Workspace Apply。
- Engine package/verify、保护面、Revision Gate（HEAD）、git diff --check 通过。运行中的旧模板服务未重启；后续新 Generate 需重启加载新版 JAR 和 1008 Source。
- 完整验收：`.standalone-build/response222-fix-6zml6r57/reports/verification.md`；资源复现前后日志、前端构建/测试和引擎回归在 `.standalone-build/reports/response222-*.log`。
