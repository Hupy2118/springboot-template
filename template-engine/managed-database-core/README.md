# Managed Database Service

DevAgent Studio 的内置数据库资源服务，为本地生成项目提供统一的真实数据库资源。本模块是 `template-engine` 下的 library 子模块，由 `engine-service` 的
`EngineServiceApplication` 统一启动，不单独打包为可执行 JAR。采用 DDD 包分层，技术基线为 Java 8、Spring Boot 2.7.2、MyBatis-Plus 3.5.2。

## 交接文件

| 文件                                                                                      | 内容                       |
|-----------------------------------------------------------------------------------------|--------------------------|
| [managed-database-schema.sql](src/main/resources/db/manual/managed-database-schema.sql) | 当前四张管理表的完整建表脚本           |
| README.md                                                                               | 项目结构、部署配置与启动说明           |
| [managed-database-design.md](docs/managed-database-design.md)                           | 资源模型、Studio 交互、接口清单和执行规则 |

## 当前功能

已提供资源注册、建表与重建、结构检查、执行详情与历史、表及字段元数据、分页查看调试数据、运行时连接下发，以及单表删除和整份资源回收接口。当前查看数据不支持非空查询条件；资源回收尚未接入
Studio 应用删除流程。

Studio 保存实体设计并生成完整 SQL。SaaS 分配固定挂载，维护精确表归属和操作记录。调试启动时，Studio 获取专用连接，通过环境变量注入本地 Java
子进程，效果与用户提供数据库环境变量配置一致。

## 项目结构

```text
src/main/java/com/cmbchina/managedb/
├── domain/                聚合、值对象、仓储及数据库能力契约
├── application/           注册、表操作和查询用例
├── infrastructure/        持久化、连接池和 SQL 适配
├── adapter/web/           HTTP、AG-UI、鉴权和健康入口
├── bootstrap/             配置及依赖装配（由 engine-service 扫描装配）
└── common/                通用异常与响应
```

本模块不提供独立启动入口，由 `engine-service` 的 `EngineServiceApplication` 在组合 JAR 中根据 `managed-database.enabled=true` 装配。

## 部署配置

本模块不单独维护运行配置，相关配置项由 `engine-service` 统一提供（见 [组合本地配置](../engine-service/src/main/resources/application-dev.yml) 及部署环境变量）。以下配置项由
engine-service 启动时加载，供本模块的 `bootstrap` 装配使用：

| 配置项                                           | 用途                                                       |
|-----------------------------------------------|----------------------------------------------------------|
| spring.datasource.*                           | 管理库地址、账号和密码                                              |
| devagent-studio.list                          | 业务挂载列表：datasource-id、host、port、db-name、username、password |
| managed-database.runtime.list                 | 挂载对应的专用调试账号、密码和 ssl-mode                                 |
| MANAGED_DATABASE_SERVICE_KEY                  | SaaS 与 Studio 后端使用相同的 Bearer 服务密钥                        |
| managed-database.id.worker-id / datacenter-id | BIGINT 主键节点编号，各为 0～31；多实例使用不同组合                          |

监听地址与端口由 engine-service 统一配置（`server.address` / `server.port`），本模块不单独监听。

管理库和业务挂载库分别准备。初始化时，在管理库手工执行 [建表脚本](src/main/resources/db/manual/managed-database-schema.sql)，创建四张管理表。应用不自动创建
Schema 或初始化管理表。

datasourceId 是固定挂载编号，四张管理表统一使用 TINYINT UNSIGNED，部署使用 1～255；Java 保留 long/Long。编号上线后不能改指向其他数据库，配置列表下标也不等于挂载编号。主键和操作
ID 继续使用 BIGINT。

调试账号独立于建表账号，不允许 root 或回退使用管理账号。数据库地址需能从用户本地项目访问，部署侧授予调试所需的业务表读写权限。真实密码、服务密钥和运行时连接响应不得写入交接材料或日志。

## 构建与启动

本模块不单独启动，由 `engine-service` 统一打包和运行。在 `template-engine` 目录下构建全部模块：

```sh
mvn -B -ntp -Plocal-mock -pl engine-service -am clean verify
```

构建产物为 library JAR，由 `engine-service` 通过 Maven 依赖引入并由 `EngineServiceApplication` 统一启动。启动前通过 engine-service 提供完整部署配置和数据库授权。
`GET /managed-database/health` 仅表示进程可用，不代替数据库挂载、权限或表结构检查。资源接口使用 `Authorization: Bearer <服务密钥>`。

## 交互约定

注册接口每次成功 POST 都创建新资源，调用方不能自动重试。建表、重建和清理的受理结果只表示任务已提交，需要按 operationId 查看最终状态。UNKNOWN
保留占用，先人工核对，不能直接清空占用或重放 SQL。

服务接口和执行规则见 [整体设计](docs/managed-database-design.md)。
