# 初始管理员工号/姓名改造：本项目实施记录

## 范围与实际文件

仅实施 springboot-template 的 PR2；未操作 XCodeAgent 仓库，未声称完成 PR1/PR3。

| 文件 | 归属与改动 |
| --- | --- |
| `template-source/code/backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java` | Runtime：集中解析 AdminMember、冲突检测、分列 JDBC 绑定与 dry-run 数量 |
| `template-source/code/template-revision.txt` | Runtime Revision 提升至 `2026.11.07.1` |
| `scripts/ci/authorization-bootstrap/src/test/java/com/cmbchina/backend/auth/bootstrap/InitialAdministratorsTest.java` | 新增解析向量、Unicode、SQL 绑定与已有成员跳过测试 |
| `scripts/ci/authorization-bootstrap/src/test/java/com/cmbchina/backend/auth/bootstrap/BootstrapContractTest.java` | 新增复合值 dry-run；夹具显式使用 Bootstrap 的 ds1 配置 |
| `devtools/standalone-adapter/manifest.yaml` | 审核原 JDBC URL 局部补丁不变，更新上游和结果 SHA |
| `devtools/standalone-adapter/release.yaml` | sourceRevision 同步，standaloneRevision 提升至 `2026.11.07.1001` |
| `docs/DEVELOPMENT_GUIDE.md` | 记录解析语义、调用方写入边界和成员修复限制 |
| 本文件 | 实施与验收记录 |

未修改 SaaS、DDL、RoleMember/PO/Mapper、登录判权、HTTP/ZIP Schema、Bootstrap 脚本参数或事务流程。用户提供的实施方案原文件未修改。

## 已知事实：Java 语义与测试证据

Java 读取已保存的 `string[]`，合法新格式要求 8 位半角数字、一个半角斜线、姓名 1..32 Unicode 码点；去除整项首尾空白，不改变姓名内部字符。姓名拒绝首尾空白、控制字符、斜线、半角/全角逗号和 Unicode 行/段分隔符。最多接受 100 项，按 memberId 排序；同身份同姓名去重，不同姓名或新旧身份碰撞拒绝。

历史值回退为原值/原值，不裁剪或重写原值；两列仍各受 32 码点 SQL 长度限制。空白、非字符串和 current-user 占位符拒绝。错误仅包含分类与索引，不包含管理员身份。

SQL 测试通过 JDBC 代理调用实际 ensureInitialAdministrators：

- `00000001/李四` INSERT 绑定为 `SYSTEM_ADMIN, 00000001, 李四`，SQL 固定 `created_by='SYSTEM'`。
- `ops@example.com` INSERT 仍绑定原值/原值。
- 数据中已有 `00123456 → 00123456` 时，配置 `00123456/张三` 不执行 INSERT，姓名保持 `00123456`；再次执行所有成员均跳过。
- 代理只接受原 SELECT 和 INSERT，出现 UPDATE、DELETE 或其他语句即失败。
- 新格式 dry-run 成功并按去重后的身份计数，不连接数据库，不输出管理员身份。

## 跨端合同与反向检验

| 层 | 本次状态 | 应有职责 |
| --- | --- | --- |
| Java Bootstrap | 已实现并测试 | 已保存配置的初始化解析，兼容历史值 |
| Python 配置写入与 Builtin Seed | 本仓库不存在，未验证 | before/after 基线严格校验；新值分列投影；保留已有 Seed 操作证据 |
| TS 共用表单 | 本仓库不存在，未验证 | 与 Python 共用语义及测试向量，透传真实已保存基线 |

合理判断：JDBC 初始化的分列与 skip 行为已由测试支持。但 Bootstrap 没有保存前基线，无法证明某个非合规字符串有历史保留资格；不能将其兼容回退用作新增输入的校验。新增非法值能否被所有写入路径拒绝，以及历史 Seed 不产生额外 POST，必须由 XCodeAgent 实施和验收，本项目测试不能证明。

## 验证结果（2026-11-07）

| 命令 | 结果 |
| --- | --- |
| `LC_ALL=en_US.UTF-8 mvn -f scripts/ci/authorization-bootstrap/pom.xml test` | 13 项，12 通过、1 跳过（真实 TDSQL 未配置） |
| `mvn -f template-engine/pom.xml verify` | Core 9 项、Service 32 项通过，包含打包 |
| `python3 scripts/ci/check-protected-surfaces.py` | 通过 |
| `PYTHONDONTWRITEBYTECODE=1 ./scripts/ci/verify-code-template-release-revision.sh HEAD` | 通过 |
| `python3 -B -m unittest scripts/ci/test_authorization_scripts.py scripts/ci/test_code_template_release_inputs.py` | 9 项，8 通过、1 跳过（PowerShell） |
| `./devtools/standalone-adapter/build.sh test` | 19 项通过，包含临时目录 prepare/audit 与当前补丁校验 |
| `./devtools/standalone-adapter/build.sh prepare` | 当前工作目录被活动进程 PID 88266 的锁阻止；未停止进程、删锁或替换 Source |
| `./devtools/standalone-adapter/build.sh audit` | 当前工作目录旧构造产物与新输入不一致；未报告通过 |
| `git diff --check` | 通过 |

首次 Bootstrap 测试暴露了现存 Base 数据源名 xcode-agent 与 Bootstrap loader 要求 ds1 不一致。测试夹具显式映射到 ds1，仅隔离本次解析验收；生产 loader/Base 行为未改。默认生成工程的真实数据库 Bootstrap 因此仍有现存配置限制，不能依据 dry-run 夹具通过声称其已验收。

尚未验证真实数据库、完整生成工程的私有 Bee 构建、运行中 Standalone 的新版验收和两仓库端到端联调。当前改动不含 Builtin 编排代码，不能单凭本次变更证明其不会重播 POST。
