# 工程协作指引

## 工程目标与边界

本仓库实现的是 Template Engine V1。它根据固定 Template Source 和完整 RequestedConfig，确定性地产生 TemplateState、文件 Operation、首次工程 ZIP 或更新 ZIP。

Engine Service 是无状态计算入口，不是 Project Registry、Workspace Manager 或 ChangeSet 状态机。不得在 Service 中增加数据库、JDBC/JPA/MyBatis、Flyway、Redis、Repository/DAO、Project ID、服务端 TemplateState 或幂等记录。

实际生成工程中的 Workspace Apply、构建/测试、失败恢复和 State 推进属于外部 XcodeAgent/调用方，不在本仓库 Service 的职责中。

## 目录职责

- `template-source/`：唯一受管模板源。修改 Base 或 Capability 文件时，必须同步其 `base.yaml` / `capability.yaml` 的 files、migrations、extensions 等声明。
- `template-engine/engine-core/`：纯 Java Core；不得引入 Spring MVC、HTTP、持久化或 ZIP 打包依赖。
- `template-engine/engine-service/`：Spring HTTP、认证、Core 映射、错误映射和 Package Builder。不得实现业务 Capability 逻辑。
- `scripts/ci/`：可重复使用的 CI Gate 与 Template Source 构建检查；脚本必须自行定位仓库根目录。
- `docs/REFACTOR.md`：V1 设计与验收协议。任何新增 State 字段、Operation、Package 字段、错误码、HTTP 参数或 Capability 协议，必须先更新本文、Schema/OpenAPI、Fixture 与测试。

## 修改规则

1. `template-source` 不接受未声明的模板文件、二进制文件或 symlink；文件所有权必须唯一。
2. Capability 的 `config.schema.json`、依赖、Extension Contribution 和 Migration 都属于 Template Source 契约，不要在 Service 中按 Capability ID 写条件分支。
3. `RequestedConfig` 是完整目标配置；省略 Capability 等同禁用。`TemplateState` 由调用方持有和提交，Service 不保存它。
4. HTTP 契约以 `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml` 为唯一权威。Controller 不得私自增加字段或改变 Content-Type/状态码。
5. Authorization 固定为 `Authorization: Bearer <token>`，Service 比对 UTF-8 Token 的 SHA-256。日志中不得记录 Token、Token digest、完整 Request Body 或完整受管文件内容。
6. ZIP entry 必须使用相对安全路径、固定排序和固定 timestamp；Update Package 仅为 ADD_FILE / UPDATE_FILE 创建 payload，DELETE 不得生成伪 payload。
7. 保持 Java 8 编译兼容；Spring Boot 2.7 运行依赖与 Jackson YAML/SnakeYAML 版本必须保持兼容，不要单独升级其中一个。

## 验证要求

完成 Engine Service 相关改动后至少执行：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
git diff --check
```

涉及 Template Source 或 Core 行为时，同时执行：

```sh
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-base-frontend.sh
```

涉及 HTTP、认证、ZIP 或启动配置时，使用 `template-engine/engine-service/config/application-local.yml` 进行真实 JAR 启动验收。该配置要求显式传入绝对 `TEMPLATE_ENGINE_SOURCE_ROOT` 及本地 Token digest；详情见 `README.md` 和 `docs/REFACTOR.md`。
