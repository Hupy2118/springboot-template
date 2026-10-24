# Standalone 1009 契约归一实施与验收报告

日期：2026-10-24（Asia/Shanghai）。结论：代码与可自动执行的验收已完成；真实数据库验收 NOT_RUN，不能宣称完整数据库端到端通过。

## 已知事实与根因

正式 Login Manifest 贡献 `bee.auth.*` 与 `yht.authentication.*`。旧 Adapter 使用整文件 replace，改为 `standalone.mock.*` 并修改 dev 默认值；Engine 按 Extension 贡献下发真实 V4 配置片段。XCodeAgent 的 `config_surfaces.CONTRACT` 只允许正式叶子，因此旧片段触发 `TEMPLATE_PACKAGE_CONTRACT_INVALID`。本次真实包已通过原生 `validate_update_package_v4` 和 `validate_ensure_package`，没有调整调用方校验或构造假 metadata。

反向检验：该错误码也可能由错误 target/type、非新装 capabilityIds 或配置覆盖不全触发，不能只换字段就认定解决。本次同时校验 MAIN/DEV/ENV 仅使用 Ensure、新装能力闭包、实际 payload 叶子、工作副本合并、事务提交、参数投影及 StateSequence。

## 实施前基线与隔离

- 模板仓库：`1016`，HEAD `c0cca5c0344184e922202e0c2bef0d750e76d32e`。
- XCodeAgent：`dev_agent`，HEAD `e4ec47e450846191cf53222c1325f84a98f64c70`。
- 两仓库原有未提交改动均保留，完整状态见 `.standalone-build/reports/contract-alignment/baseline.json`。
- 正式来源 Revision `2026.10.23.2`；Standalone 独立 Revision `2026.10.24.1009`，未复用已有台账编号。
- 正式 Runtime Source、Engine、OpenAPI 未修改；XCodeAgent 状态与实施前一致。测试产生的跟踪 pyc 已还原为 HEAD 内容。

## 修改清单

| 文件（相对 Adapter） | 改动与原因 |
| --- | --- |
| `manifest.yaml` | Login replace 改精确 patch；保留源 SHA 与结果 SHA |
| `patches/login-manifest-runtime.patch` | 新增；仅清空企业注解与私有 Maven 贡献 |
| `overlays/backend/extensions/login/extension.yaml` | 删除旧整份 Manifest，消除双份配置事实源 |
| `overlays/backend/base/src/main/resources/application.yml` | 在 Base 添加五项 Mock 环境占位配置 |
| `scripts/adapter.py` | Prepare/Audit 共享严格契约 Gate 与关键本地运行替换检查 |
| `scripts/http_smoke.py` | 真实配置操作、三条安装链路、Native Update 与无 Mock 回归 |
| `release.yaml` | Standalone Revision 提升到 1009，正式 Revision 保持不变 |
| `tests/test_contract_parity.py` | 正向、注入 Mock、缺失 bee 字段、dev 值/依赖漂移、旧 SHA 失败与重复 Prepare |
| `tests/test_adapter.py` | 通用构造器 fixture 隔离生产 Manifest Gate，生产输入由新增测试覆盖 |
| `scripts/verify_contract_loader.py`、`tests/java/contract/ContractParity.java` | 使用真实 Engine Loader 比较解析后的结构和值，Java 8 编译目标 |
| `scripts/verify_xcodeagent_contract.py` | 新增真实 ZIP/Executor/事务/配置入口/生成后端验收工具 |
| `build.sh` | 新增 verify-xcodeagent、verify-contract-loader 分派 |
| `README.md`、本报告 | 执行方式、差异边界、报告与验收状态 |

## 契约对比

| 项目 | Native vs Standalone |
| --- | --- |
| Login id/requires | 相同：login / [] |
| Authorization id/requires | 相同：authorization / [login] |
| 两项 applicationYaml | 字节保留；生产 Loader 解析结构和值完全相等 |
| 两项 applicationProfileYaml | 字节保留；包括 dev 默认值，解析结构和值完全相等 |
| Login annotation/Maven | 唯一 Manifest 差异：Standalone 两项为空 |
| Authorization Manifest | 完整字节相等 |
| Mock 配置 | 仅 Standalone Base；新能力 Ensure 片段不含 Mock |

允许公共 Parent/依赖、兼容 Java、MySQL、Bootstrap 数据源解析、既有本地 Profile 的运行实现差异；State 的私有 Annotation/Maven 记录允许不同。没有要求两模式 State 或所有文件字节一致。私有依赖 FORBIDDEN 扫描未放宽；锁、staging、fingerprint 与 ledger 保留。

真实更新包的受管叶子如下（全部来自 XCodeAgent 原生 CONTRACT，并由原生校验器验证）：

| 能力 | target | 受管叶子 |
| --- | --- | --- |
| authorization | `backend/src/main/resources/application-dev.yml` | `yht.api.base-url`, `yht.api.view-id` |
| authorization | `backend/src/main/resources/application.yml` | `yht.api.user-query-path` |
| login | `backend/src/main/resources/application-dev.yml` | `yht.authentication.authorize-uri`, `yht.authentication.center-public-key`, `yht.authentication.center-uri`, `yht.authentication.client-id`, `yht.authentication.private-key`, `yht.authentication.public-key`, `yht.authentication.redirect-uri` |
| login | `backend/src/main/resources/application.yml` | `bee.auth.center-public-key`, `bee.auth.center-uri`, `bee.auth.client-id`, `bee.auth.login.authorize-uri`, `bee.auth.login.filter-patterns`, `bee.auth.login.redirect-uri`, `bee.auth.private-key`, `bee.auth.public-key` |
| login | `frontend/.env` | `PUBLIC_YHT_AUTHORIZE_URI`, `PUBLIC_YHT_CLIENT_ID`, `PUBLIC_YHT_END_URL`, `PUBLIC_YHT_LOGOUT_SHARE_JS` |

Base→Login：MAIN/DEV/ENV 的 capabilityIds=[login]。Base→Authorization：MAIN/DEV 为 login+authorization，ENV 为 login。Login→Authorization：仅 MAIN/DEV 的 Authorization 贡献；不重装 Login。格式分别为 yaml/yaml/dotenv，类型均为 ENSURE_CAPABILITY_CONFIG。

## 实际命令与结果

以下从仓库根执行，均已通过（真实数据库项目除外）：

| 命令 | 结果 |
| --- | --- |
| `mvn -f template-engine/pom.xml -pl engine-service -am package` | PASS |
| `mvn -f template-engine/pom.xml verify` | PASS：Core 9、Service 16 项测试 |
| `python3 scripts/ci/check-protected-surfaces.py` | PASS |
| `./scripts/ci/verify-code-template-release-revision.sh HEAD` | PASS，无正式 Runtime 变更 |
| `./devtools/standalone-adapter/build.sh test` | PASS：19 项，含反向漂移与重复构造 |
| `./devtools/standalone-adapter/build.sh prepare` | PASS，生成 1009，台账未删改 |
| `./devtools/standalone-adapter/build.sh audit` | PASS |
| `./devtools/standalone-adapter/build.sh verify-contract-loader` | PASS，真实 JAR Loader 语义对比 |
| `./devtools/standalone-adapter/build.sh verify-http-only --engine-url http://127.0.0.1:18080` | PASS，四种 Generate、V3/V4 真实包、重复 204、RECONCILE State 不变 |
| `./devtools/standalone-adapter/build.sh verify-full --engine-url http://127.0.0.1:18080 --manual-database` | PASS（限定范围）：四种后端公开 Maven 构建、默认启动/SSO、dev 覆盖、关闭 Mock、prod 排除；Bootstrap dry-run/缺文件/非法 URL 拒绝 |
| `python3 -B devtools/standalone-adapter/scripts/http_smoke.py --base-url http://127.0.0.1:18081 --expected-mode native --output-dir .standalone-build/reports/contract-alignment/native-http` | PASS，四种 Generate 无 Mock，Native V4 安装/重复/RECONCILE |
| `./devtools/standalone-adapter/build.sh verify-xcodeagent --xcodeagent-backend /Users/zhangrongrong/Documents/workspace/XCodeAgent/Backend --engine-url http://127.0.0.1:18080` | PASS_SCOPED，见下 |
| `git diff --check` | PASS |
| 真实 MySQL、数据库 RBAC、Bootstrap SQL/幂等/锁 | NOT_RUN：未提供隔离真实测试数据库 |
| 企业私仓编译、企业 SSO/TDSQL 运行 | NOT_RUN：本地适配验收不证明企业环境可用 |

真实 JAR 使用仓库 `application-local.yml`，Source Root 为所选模式的绝对 template-source 路径，绑定 127.0.0.1；Standalone 18080、Native 18081。Python 3.12.2、JDK 18.0.2.1、Maven 3.9.16、Pydantic 2.13.4、PyYAML 6.0.3。Java 产物保持 8 编译目标。

跨项目真实执行结果：

- base-login: real transaction rollback PASS
- base-login: ZIP/ensure/executor/working surfaces/transaction/state PASS
- login-authorization: ZIP/ensure/executor/working surfaces/transaction/state PASS
- base-authorization: ZIP/ensure/executor/working surfaces/transaction/state PASS
- application-login-authorization: real config orchestrator, parameter projection, local NO_CHANGE PASS
- application-base-authorization: real config orchestrator, parameter projection, local NO_CHANGE PASS
- executor-chain: public Maven/tests/JAR/Mock/anonymous RBAC/Bootstrap dry-run PASS; real DB NOT_RUN
- executor-direct: public Maven/tests/JAR/Mock/anonymous RBAC/Bootstrap dry-run PASS; real DB NOT_RUN

事务测试实际在第二个目标写入时注入一次失败，原生恢复逻辑恢复原文件与 State，再以同一真实工作副本成功提交。配置入口另用真实 ApplicationConfigOrchestrator：首次 Login、首次 Authorization 自动依赖 Login、参数投影、相同 operationId 幂等提交，以及只改已安装权限参数。仅改参数时禁止创建 TemplateEngineClient，返回本地 NO_CHANGE，State 字节不变；安装链 StateSequence 为 0→1→2 / 0→1。用户 YAML 注释、非受管节点、已有 Login 值、dotenv 原值通过原生 working-surfaces 校验并保持。

`verify-full` 自身仍标注 L3 未执行，其职责只做生成工程验收；L3 的实际通过证据来自独立 verify-xcodeagent 报告，不由 HTTP smoke 代替。未运行 verify-all，也未将其未完成项报告为全通过。

初次验收暴露的工具 fixture 问题已经修正：裸 Base Generate 不交付 .env，现改为带测试 applicationConfig 的真实 Generate；首次权限提交需显式提供 api.baseUrl/viewId。没有改动 XCodeAgent 来迎合测试。早期失败堆栈与包保留在各次隔离报告目录。沙箱初次阻止本地端口/HTTP，授权后重跑通过。

## 产物与复现

最终跨项目报告目录：`.standalone-build/reports/xcodeagent-contract-4uh2aegt`。

- `report.json`：环境、两仓库基线、Revision、全部 operation target/type/parameters 和结果。
- `executor-login-authorization-generate.zip`、`executor-base-authorization-generate.zip`：实际 Generate ZIP。
- `base-login.zip`、`login-authorization.zip`、`base-authorization.zip`：实际 V4 ZIP；从未直接解压覆盖工作区。
- `base-login-state.json`、`login-authorization-state.json`、`base-authorization-state.json`：原生事务提交的 State（sequence 1、2、1）。
- 对应 `*-diff.json`：变更路径与前后摘要；`executor-*`：实际 Apply 后的隔离工程与构建日志。
- `application-*`：真实应用配置 Orchestrator 完成后的工程、State 与配置操作记录（全部是测试值）。
- `.standalone-build/reports/xcodeagent-contract-latest.json`：最终结果索引。
- `.standalone-build/verify-pinsp7hh/`：四种生成组合及最新 HTTP 包；`.standalone-build/reports/verification-latest.md`：四种构建检查报告。
- `.standalone-build/reports/contract-alignment/`：基线、Engine package/verify 日志、Native HTTP 包与报告。

重现时先构建 Engine，停止同端口旧服务，显式开启 Standalone 并通过 run-engine.sh 启动，再执行上述 verify-xcodeagent 命令。测试工具每次新建隔离工作区，使用同一包 State，不连接用户数据库、不修改 XCodeAgent。

## 判断、限制与回退

合理判断：本次修复已消除被验证链路中由 Standalone 配置漂移引起的包校验失败；不能据此推断旧工作区的 State/application.json 一定一致，也不证明所有数据库权限流程通过。若旧工作区仍报同码，应检查 State 实际能力、模式/Revision、Desired 配置与旧 Engine 进程。

回退：停止 Standalone 引擎；`unset TEMPLATE_ENGINE_STANDALONE_ENABLED TEMPLATE_ENGINE_SOURCE_ROOT`，通过 run-engine.sh 重启 Native，在独立目录重新 Generate 并验证正式 Revision。不要手改 TemplateState 或将 Standalone State 发给 Native。保留 ledger/锁与旧产物供审计；锁仅在确认所有者进程结束后由工具回收。本次测试 Engine 已在验收后停止。
