# 本地 Bee 兼容模块

`bee-compat` 提供本地运行所需的 Bee 错误码、异常、注解、JSON 响应和 Spring Boot 自动装配。Managed Database 的注册、DDL、Seed、查询和回收使用真实 MySQL。

在仓库根目录构建组合 JAR。

```sh
mvn -f template-engine/pom.xml -pl engine-service -am clean verify
```

用 [管理库初始化脚本](../../engine-service/src/main/resources/db/manual/managed-database-schema.sql) 初始化隔离 MySQL 8 实例的 `managed_meta`，并创建 `managed_business`。按 [本地配置](../../engine-service/src/main/resources/application-dev.yml) 注入三个独立账号及服务密钥。管理库账号需要管理表读写权限，挂载账号需要业务库 DDL 和 Seed 写权限，调试账号需要业务数据读取权限。示例配置要求 MySQL 启用 TLS。

[验收脚本](../../../scripts/ci/verify-combined-local.sh) 启动刚构建的 JAR，默认监听 `127.0.0.1:18080`。占用时可通过 `TEMPLATE_ENGINE_PORT` 选择空闲端口。默认检查打包、生成、更新、鉴权和 JSON 错误。完整检查使用一次性测试实例并显式设置 `RUN_MUTATING_INTEGRATION_TESTS=true`。脚本成功时通过清理 API 回收测试资源。如果写操作中断，应先检查该操作的记录再清理测试实例。

```sh
RUN_MUTATING_INTEGRATION_TESTS=true scripts/ci/verify-combined-local.sh
```

设置 `RUN_COMBINED_LOCAL_IT=true`、服务密钥和指向仓库 `template-source` 的绝对 `TEMPLATE_ENGINE_SOURCE_ROOT` 后再执行 Maven 命令，可启用真实 Spring Boot Web 集成测试。引擎构建直接依赖 `bee-compat`，数据库管理随服务启动。也可用 `scripts/ci/verify-combined-local.sh` 验证 JAR。
