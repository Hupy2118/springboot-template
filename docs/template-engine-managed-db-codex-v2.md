# Template Engine / Managed Database：独立 Bee Mock 与普通 JSON 三字段响应契约实施方案

> 适用仓库：`Hupy2118/springboot-template`；分支：`dev_manage`；分析基线：`39ce1a402804e34e37f93d4cf25c1c8dccf02939`（2026-11-13）。
>
> 目标：SSE 保持原 AG-UI 协议；所有**已定义的 Managed Database 普通 JSON API** 成功统一 `{returnCode:"SUC0000",errorMsg:"",body:...}`、HTTP 200；错误统一三字段并保留 4xx/5xx；本地 Mock 独立目录、原 `com.cmb.bee.*` import 不变，正式服务不带 Mock 类。

## 本轮补充的确定性决策（Codex 必须遵守）

- **保留**唯一启动类：`template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/EngineServiceApplication.java`，对应 `com.devagentstudio.template.engine.EngineServiceApplication`。
- **删除**：`template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/service/EngineServiceApplication.java`。不要改包、不要保留其注解成为第二个 Spring Boot 入口。
- `-Plocal-mock` 完成后，必须能用**同一个** `engine-service-1.0.0-SNAPSHOT.jar`、**同一个** `127.0.0.1:18080` 端口同时访问 `/v1/generate` 和 `/managed-database/**`，以实际 HTTP、数据库写入、状态轮询和 SSE 验收，而不只是 Maven 编译成功。
- 默认不带 Profile 构建的 Template Engine 必须继续独立启动，不依赖数据库、企业 ZA21/LT99.09 或 Mock。

## 0. 不可突破的边界

1. 只 Mock **ZA21 企业基础设施 API**；不要 Mock 注册、建表、Seed、查询、持久化、JDBC SQL 业务逻辑。真实数据库访问继续走 MySQL 连接池。
2. 新增 Mock 全部放入 `template-engine/devtools/mock-internal-deps/bee-compat`，不要把其 Java 文件复制到 `managed-database-core/src/main/java` 或 `engine-service/src/main/java`。
3. `managed-database-core` 业务层所有 `import com.cmb.bee.*` 不改包名、不整体替换；可针对异常 HTTP 状态及测试作局部必要修改。
4. `@BeeResponseBody` 普通 JSON Controller 正常响应必须包装为 `returnCode` / `errorMsg` / `body` **恰好三个顶层字段**；已是 `BeeResponseEntity` 的对象不得再包装。`body` 为 `null` 时仍保留字段。
5. `SseEmitter`（包括带 `@BeeResponseBody` 的 recovery Controller）必须原样返回 `text/event-stream`、不得套三字段 JSON；流内错误不能当成 HTTP 成功后的业务成功。
6. 正常同步 POST 和异步操作**受理成功**统一 HTTP 200；最终业务成功与否按 operation 状态和 SSE 事件判断。失败使用明确 HTTP 错误码。
7. 不改变模板引擎 `/v1/generate`、`/v1/update`、app-update 等接口的既有 JSON 契约。不要把该类返回包装成 Bee 三字段。
8. Mock 和正式 ZA21 Starter 绝不能同时在一个运行时 classpath；也不允许用假的 `ZA21:*` 坐标发布到 Maven 仓库。

## 1. 已核对的代码现状

- `template-engine/pom.xml` 只包含 `engine-core`、`engine-service`，缺 `managed-database-core`。
- `engine-service/pom.xml` 只依赖 `engine-core`；缺 `managed-database-core`。
- `managed-database-core/pom.xml` 已声明 `ZA21:bee-starter-{logging,tracer,metrics,healthcheck,web,id,microservice}`、`LT99.09:tdsql-mysql-connector-java8`，但其父 POM 为 template-engine，**不会自动继承** Bee parent 的依赖版本管理。
- 当前 `engine-service` 下同时存在 `com.devagentstudio.template.engine.EngineServiceApplication` 和 `com.devagentstudio.template.engine.service.EngineServiceApplication` 两个 `@SpringBootApplication`，有重复扫描/主类歧义风险；本方案已明确**保留外层，删除内层**。
- 已有直接 Bee API 引用：`com.cmb.bee.commons.errorcodes.IErrorCode`、`com.cmb.bee.commons.exceptions.BizException`、`com.cmb.bee.web.annotations.BeeResponseBody`、`com.cmb.bee.web.entity.BeeResponseEntity`；另需 Mock 响应包装器。
- 普通 JSON Controller：`DatasourceRegistrationController`、`DataOperationController`、`DatasourceRuntimeConnectionController`、`DatasourceTableMetadataController`、`TableSynchronizationController`、`HealthController`，均有 `@BeeResponseBody`。
- SSE Controller：`DatasourceTableDataController`、`ResourceCleanupController`、`TableOperationRecoveryController`；第三者虽标注 `@BeeResponseBody`，但返回 `SseEmitter`，必须被排除。
- `ServiceKeyFilter` 直接通过 `ObjectMapper` 写出 `BeeResponseEntity.failed(...)`，HTTP 401/503 无需 ResponseBodyAdvice 介入，但 Mock DTO 要序列化出三字段。
- `BaseExceptionHandler` 的 BizException 分支已有 HTTP 400/404/409/413/503/500 映射；参数异常、未知错误等分支仅返回 Bean DTO，没有显式 HTTP 状态，需要修正。
- 现有 `DataOperationControllerTest` 导入**不存在**的 `com.cmbchina.managedb.common.response.JsonResponseAdvice`，并将 Seed 提交期望设为 202；多项其他测试又直接校验 `$.operationId`、`$.code` 等非包装字段。需要统一。

## 2. 目标目录

```text
template-engine/
├── pom.xml                                    # 改：父 BOM + profile 模块
├── engine-core/                               # 原则不改
├── managed-database-core/
│   ├── pom.xml                                # 改：基础依赖、local-mock/managed-enterprise 依赖
│   └── src/main/java/com/cmbchina/managedb/
│       └── common/exception/BaseExceptionHandler.java  # 小改 HTTP status，不改 Bee import
├── engine-service/
│   ├── pom.xml                                # 改：profile 引入 managed-database-core，指定 mainClass
│   ├── config/
│   │   └── application-managed-local.yml      # 新增：真实本地管理库/挂载示例（不提交真实密码）
│   └── src/main/java/com/devagentstudio/template/engine/
│       ├── EngineServiceApplication.java      # 唯一保留的启动类（用户明确指定）
│       ├── ManagedDatabaseIntegrationConfiguration.java # 新增：条件扫描
│       └── service/
│           ├── ApiExceptionHandler.java       # 小改范围限定
│           └── (删除 EngineServiceApplication.java) # 原内层同名启动类必须删除
└── devtools/
    └── mock-internal-deps/
        ├── README.md
        └── bee-compat/
            ├── pom.xml
            └── src/main/
                ├── java/com/cmb/bee/
                │   ├── commons/errorcodes/IErrorCode.java
                │   ├── commons/exceptions/BizException.java
                │   ├── web/annotations/BeeResponseBody.java
                │   ├── web/entity/BeeResponseEntity.java
                │   └── web/support/
                │       ├── MockBeeResponseAdvice.java
                │       └── MockBeeAutoConfiguration.java
                └── resources/META-INF/spring.factories
```

**用户确定的唯一 Spring Boot 入口**：`template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/EngineServiceApplication.java`，对应 `com.devagentstudio.template.engine.EngineServiceApplication`。**删除** `template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/service/EngineServiceApplication.java`（内层同名启动类），不允许反过来保留内层类，也不允许同时保留两处 `@SpringBootApplication`。在保留的外层类上继续使用 `@EnableConfigurationProperties({TemplateEngineProperties.class, AppUpdateProperties.class})`，由 Maven Boot Plugin 明确 `mainClass`。

## 3. Maven Profile 隔离方案

建议使用**显式**两个互斥 Profile，避免默认 `mvn package` 需要下载企业包：

- **普通构建（不加 Profile）**：`engine-core` + `engine-service`，仍然只运行原 Template Engine。默认不解析管理数据库及企业内部依赖。
- **`-Plocal-mock`**：增加 `managed-database-core` + `devtools/mock-internal-deps/bee-compat` 模块；`managed-database-core` 依赖 Mock API、标准 MySQL Connector/J、公开 Spring Boot/MyBatis-Plus。
- **`-Pmanaged-enterprise`**：增加 `managed-database-core` 模块；其依赖原 ZA21 Starter 和 LT99.09 驱动，**不得**加入 `bee-compat`。企业包的具体版本/是否可导入内部 BOM 必须先经企业 Maven 仓验证，不能假设当前 template-engine 父 POM 自动继承 `bee-starter-parent`。

`template-engine/pom.xml` 中原有模块保持不变，在 `<profiles>` 中追加示意：

```xml
<profiles>
  <profile>
    <id>local-mock</id>
    <modules>
      <module>managed-database-core</module>
      <module>devtools/mock-internal-deps/bee-compat</module>
    </modules>
  </profile>
  <profile>
    <id>managed-enterprise</id>
    <modules><module>managed-database-core</module></modules>
  </profile>
</profiles>
```

在父 POM 用 `<dependencyManagement>` 导入统一 `spring-boot-dependencies` BOM（当前父版本为 Spring Boot 2.7.2；仓库 README 对 managed-database-core 又声称 2.7.10，须确定一个全工程版本，禁止混合），为 `mybatis-plus-boot-starter`、Lombok、MySQL 驱动等补齐受控版本，JDK 目标仍为 8。

在 `managed-database-core/pom.xml` 中：公共依赖使用 Spring Boot Web/Actuator/Validation、MyBatis-Plus、Lombok、Test；本地保证恢复 Spring Boot 默认日志实现，不再依赖 Bee logging；无真实代码依赖时将 Spring Cloud bootstrap 限定到企业模式或删除。把所有 `ZA21:*` 与 `LT99.09:*` 移到 `managed-enterprise` Profile，把下列依赖放到 `local-mock` Profile：

```xml
<dependency>
  <groupId>com.devagentstudio.template</groupId>
  <artifactId>bee-compat</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
  <version>8.0.33</version>
</dependency>
```

`bee-compat/pom.xml` 使用 template-engine 父 POM，`relativePath` 为 `../../../pom.xml`，`artifactId=bee-compat`，`packaging=jar`；依赖最小的 Spring MVC / Jackson 或 `spring-boot-starter-web` 供编译响应 Advice，不引入企业包。

`engine-service/pom.xml` 在 `local-mock`、`managed-enterprise` Profile 中均引入同一个：

```xml
<dependency>
  <groupId>com.cmbchina</groupId>
  <artifactId>managed-database-core</artifactId>
  <version>${project.version}</version>
</dependency>
```

正式模式 `-Pmanaged-enterprise` 还应使用可用的企业内部 `settings.xml` 或私有仓；禁止把无法解析的版本替换成猜测版本。

两个 Profile 不允许同时激活：增加 Maven Enforcer 或自定义校验避免一个 JAR 中出现 Bee 正式包与 Mock。可检查 `dependency:tree` 与 Boot JAR `BOOT-INF/lib` 列表。

### 特别注意：Spring Boot JDBC 自动配置

当 `local-mock` / `managed-enterprise` Profile 把 MyBatis、JDBC 包放进 `engine-service` classpath 后，即使 `managed-database.enabled=false`、不扫描 ManagedDb Bean，Spring Boot `DataSourceAutoConfiguration` 仍**可能因没有 spring.datasource.url 而启动失败**。因此：

1. 默认 Template Engine 产物**不包含** ManagedDb 依赖，保证原有无数据库启动链路不变。
2. 组合构建模式的 `application-managed-local.yml` 提供完整 `spring.datasource`、管理库、业务挂载等参数，并启用 `managed-database.enabled=true`。
3. 原 Engine Service 的 `@SpringBootTest` 在组合模式下测试时，使用测试专用 datasource（可用 Testcontainers/MySQL），或在该测试上下文显式排除 JDBC/MyBatis 自动配置；不能简单把 `managed-database.enabled=false` 当成自动配置隔离。

## 4. Mock 类实施范围与最小语义

### 4.1 `IErrorCode`

维持原全限定名。暴露现有代码所需的 `getErrorMessage()`、`getErrorMessage(Object...)`、`getErrorCodeStr()`；当前 `ResourceErrorCode` 是 enum，可用 enum name 生成稳定业务码；需从现有用法确认是否有更多方法。

```java
public interface IErrorCode {
    String getErrorMessage();
    default String getErrorMessage(Object... params) { return getErrorMessage(); }
    default String getErrorCodeStr() {
        return this instanceof Enum ? ((Enum<?>) this).name() : getClass().getSimpleName();
    }
}
```

### 4.2 `BizException`

维持 `com.cmb.bee.commons.exceptions.BizException`，继承 `RuntimeException`；至少支持 `BizException(IErrorCode)`、`BizException(IErrorCode,Object...)`、`getErrorCode()`、`getParams()`。异常文字仅输出脱敏业务消息。不能把 SQL、敏感凭据等内部 cause 直接放入 `errorMsg`。

### 4.3 `@BeeResponseBody`

维持原全限定名，设置 `@Target({TYPE,METHOD})`、`@Retention(RUNTIME)`、`@Documented`。不要把它定义成 `@RestController`；原 Controller 已具备 `@RestController`。

### 4.4 `BeeResponseEntity<T>`

维持 `com.cmb.bee.web.entity.BeeResponseEntity`，定义字段并确保 Jackson 仅输出：`returnCode`、`errorMsg`、`body`。空 `body` 应序列化为 JSON `null`。最少支持：

```java
public static <T> BeeResponseEntity<T> success(T body);
public static <T> BeeResponseEntity<T> failed(IErrorCode code, Object... params);
public static <T> BeeResponseEntity<T> failed(String code, String message);
```

`success` 固定 `SUC0000` + `""`；`failed` 使用已有业务码与脱敏信息、`body=null`。与当前 Filter 和 BaseExceptionHandler 的调用签名完全兼容。

### 4.5 `MockBeeResponseAdvice`：核心逻辑

本地使用 `ResponseBodyAdvice<Object>`，以 `@ControllerAdvice(basePackages="com.cmbchina.managedb")` 将作用域限定在 Managed Database。

- **仅**作用于 Jackson 普通 JSON 响应；拒绝影响 String、字节流、文件下载。
- `supports()` 通过 `MappingJackson2HttpMessageConverter` 和返回类型判断；排除 `SseEmitter`、`ResponseBodyEmitter`、`StreamingResponseBody` 等。
- `beforeBodyWrite()` 再次检查 `MediaType.TEXT_EVENT_STREAM` 兼容性，以及当前请求 URI 必须以 `/managed-database/` 开头。
- 若响应体是 `BeeResponseEntity`，直接返回，**避免套娃**；否则返回 `BeeResponseEntity.success(body)`。
- 不设置 HTTP status、不主动 flush/commit 响应；保持正常 200 或异常处理器提供的 4xx/5xx。
- `@BeeResponseBody` 用来保留现有 Controller 兼容性；即使 recovery SSE 类标注此注解，也必须因返回类型和 Content-Type 被排除。
- 不应全局启用此 Advice 处理 Template Engine Controller。

### 4.6 `MockBeeAutoConfiguration`

Spring Boot 2.7 使用：

```properties
# src/main/resources/META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.cmb.bee.web.support.MockBeeAutoConfiguration
```

AutoConfiguration 定义唯一 `MockBeeResponseAdvice` Bean，可加 `@ConditionalOnProperty(prefix="managed-database", name="enabled", havingValue="true")`，避免影响原引擎。Boot 企业模式不依赖该类，因而不会产生 classpath 冲突。

## 5. Engine Service 小范围改造

1. **唯一 Boot 主类（强制）**：保留 `com.devagentstudio.template.engine.EngineServiceApplication`（路径 `engine-service/src/main/java/com/devagentstudio/template/engine/EngineServiceApplication.java`），**删除** `engine-service/src/main/java/com/devagentstudio/template/engine/service/EngineServiceApplication.java`；在 `engine-service/pom.xml` 的 Spring Boot Maven Plugin 显式指定：

```xml
<configuration>
  <mainClass>com.devagentstudio.template.engine.EngineServiceApplication</mainClass>
</configuration>
```

2. 将唯一保留的外层 `EngineServiceApplication` 的 `@ComponentScan` 改为**仅扫描 Template Engine**（推荐删去该类当前的显式 `@ComponentScan`，由 `@SpringBootApplication` 默认扫描 `com.devagentstudio.template.engine` 子包）；不得在此类中无条件扫描 `com.cmbchina.managedb`。保留当前 `@EnableConfigurationProperties`。新建 `ManagedDatabaseIntegrationConfiguration` 于 `com.devagentstudio.template.engine` 包内：

```java
@Configuration
@ConditionalOnClass(name = "com.cmbchina.managedb.adapter.web.health.HealthController")
@ConditionalOnProperty(prefix = "managed-database", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = "com.cmbchina.managedb")
public class ManagedDatabaseIntegrationConfiguration {}
```

需由 Spring 扫描到此配置类，但仅当组合 JAR 包含 Managed Database 且 `managed-database.enabled=true` 时才扫描其 Controller、Service、Filter、Configuration。既有 `com.cmbchina.managedb.bootstrap.*` 配置仍创建仓储与 `@MapperScan`；默认 Template Engine 构建不含该模块且无需数据库即可启动。组合模式的 JDBC AutoConfiguration 另需由有效的 `spring.datasource` 配置满足，不能只依赖 `enabled=false` 隔离。

3. `ApiExceptionHandler` 的 `@RestControllerAdvice` 明确限定模板引擎 Controller 包，避免优先捕获 ManagedDb 业务异常；`BaseExceptionHandler` 继续只处理 `com.cmbchina.managedb`。

4. 增加 `config/application-managed-local.yml`，**不提交明文密钥**。启用 `managed-database.enabled=true`，配置 `spring.datasource.*` 管理库，`devagent-studio.list` 固定挂载，`managed-database.runtime.list` 调试账号、`managed-database.id.worker-id` / `datacenter-id`、`managed-database.service-key`。请遵循现有实体类的配置属性名。真实配置用环境变量注入。

## 6. Managed Database 原业务代码：允许的必要修改

- 不修改普通 Controller 的 `@BeeResponseBody`、return type、业务逻辑或 import。
- **必须局部修改** `BaseExceptionHandler`：已有 `ResponseEntity<BeeResponseEntity<?>>` 的 BizException HTTP 映射保持原样；`validationExceptionHandle()` 显式 HTTP 400、`methodNotAllowed()` 显式 HTTP 405、`unsupportedMediaType()` 显式 HTTP 415、`internalError()` 显式 HTTP 500，统一返回 `ResponseEntity<BeeResponseEntity<?>>`，避免 HTTP 200 + 非成功错误码；不得返回原始异常信息。
- `ServiceKeyFilter` 保留 401/503 + `BeeResponseEntity.failed(...)`；其直接写出 JSON，不走 ResponseBodyAdvice，测试确认也是三字段结构。
- 健康检查路径 `/managed-database/health` 当前仍被 `ServiceKeyFilter` 的 `/managed-database/` 前缀匹配；是否公开免鉴权按现有接口契约决定，若设计为免鉴权则只豁免 health，其他接口继续强制 Bearer。
- 不要调整已有业务错误码、表操作状态枚举、幂等行为和 DDL 流程。

### 未匹配路由/框架兜底 404

已定义 Controller 的业务异常由 `BaseExceptionHandler` 统一封装；未匹配任何 `/managed-database/**` Controller 的 404 可能被 Spring Boot `/error` 输出默认 JSON。若验收范围要求“所有 `/managed-database/**` 请求包括未知路径也必须三字段”，增加仅对 Managed Database 原始请求路径生效的 `ErrorAttributes`/错误路由适配，同时回归验证 `/v1/**` 默认错误结构不变；不得把全局默认错误输出都换成 Bee。该项可作为单独验收点以避免扩大 P0 主线。

## 7. 测试调整（不可跳过）

现有 MockMvc `standaloneSetup` 不会自动装配来自 `spring.factories` 的响应 Advice。涉及 JSON 响应契约的测试可在 `.setControllerAdvice(...)` 显式加入 `MockBeeResponseAdvice`，但**最终必须补充真正 SpringBoot Web 集成测试**，验证 AutoConfiguration + Advice 的实际装配与路径隔离。

- `DataOperationControllerTest`：去掉不存在的 `JsonResponseAdvice` 引用；使用 `MockBeeResponseAdvice` 或容器集成测试。按目标将 submit 正常 HTTP 202 预期改为 200；GET 与提交结果均校验 `$.returnCode`、`$.errorMsg`、`$.body`。保留 400/404/409/413 等错误断言。
- `DatasourceRegistrationControllerTest`：将 `$.datasourceKey`、`$.status` 等改成 `$.body.datasourceKey`、`$.body.status`；403/404 等无需凭空修改；401/503 断言改到 `$.returnCode`。
- `TableSynchronizationControllerTest`：正常响应断言 `$.body.operationId`、`$.body.status`、`$.body.ready`；失败断言 `$.returnCode`。
- `DatasourceRuntimeConnectionControllerTest`：正常响应 `$.body.datasourceKey` 等；保留 `Cache-Control: no-store`；确认密码不被日志打印。实际取密接口按原合同返回密码（安全测试只验证权限和不缓存），不要擅自删除合同字段。
- `DatasourceTableMetadataControllerTest`、`HealthController` 及其他 JSON 业务测试：统一 `.body.*` 路径。
- `SSE`：`DatasourceTableDataController`、`ResourceCleanupController`、`TableOperationRecoveryController` 应测正常返回 `text/event-stream`，事件包含 `RUN_STARTED` / `CUSTOM` / `RUN_FINISHED` 或 `RUN_ERROR`，且**整条响应不能变成 `{returnCode,...}`**。尤其验证 recovery Controller 带 `@BeeResponseBody` 时依然无包装。
- 重复包装：对预封装 `BeeResponseEntity` 的 Filter/ExceptionHandler 检查 `$.body.returnCode` 不存在。
- 隔离性：Template Engine `/v1/generate`、`/v1/update` 原成功与失败 JSON 契约测试必须通过。
- Mock API：检查接口恰好三字段、空 body 是否保留；额外检查 `Content-Type`、HTTP 状态、`returnCode` 的一致性。

测试矩阵：

| 验证 | HTTP | 最外层载荷 |
|---|---:|---|
| 普通 JSON 成功 | 200 | `SUC0000`, `""`, `body` |
| 正常建表/Seed 提交 | 200 | `SUC0000`, `""`, `body.operationId`；最终状态另查 |
| 参数错误 | 400 | `INVALID_ARGUMENT`, message, null |
| 幂等/占用冲突 | 409 | 对应业务错误码, message, null |
| 业务资源不存在 | 404 | `RESOURCE_NOT_FOUND`, message, null |
| 鉴权失败 | 401 | `UNAUTHENTICATED`, message, null |
| 服务未配置 | 503 | `RESOURCE_SERVICE_NOT_CONFIGURED`, message, null |
| SSE 成功建立 | 通常 200 | AG-UI 事件流，不是 Bee JSON |
| SSE 内业务错误 | HTTP 可能仍为 200 | `RUN_ERROR` / `CUSTOM` 错误事件 |
| 引擎 `/v1/**` | 保持原有 | 不得出现 `returnCode/errorMsg/body` |

## 8. Codex 必须实施：单 JAR 双服务启动与全链路验收

> **本节是本次补充的 P0 交付门禁。** 不是运行指导备注，Codex 必须将对应 Maven、配置、脚本、集成测试全部落地并提供执行证据。目标：同一个 Spring Boot 进程、同一个 JAR、同一 HTTP 端口 18080 同时提供 Template Engine 与 Managed Database。以下命令均在 `springboot-template` 仓库根目录执行。

### 8.1 构建产物与运行入口（必须一致）

- 构建使用：`mvn -f template-engine/pom.xml -Plocal-mock -pl engine-service -am clean verify`。
- Maven reactor 至少完成 `engine-core`、`managed-database-core`、`bee-compat`、`engine-service` 的构建，并使后两者作为依赖进入**同一个** `engine-service` 可运行 JAR；不得另起 `managed-database-core` 的 Web 进程。
- 输出位置：`template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar`。
- Spring Boot Maven Plugin `<mainClass>` 必须为 `com.devagentstudio.template.engine.EngineServiceApplication`，JAR `META-INF/MANIFEST.MF` 的 `Start-Class` 应一致；只允许该类存在有效 `@SpringBootApplication`。
- 本地 JAR 的 `BOOT-INF/lib/` 必须包含 managed-database-core 与 bee-compat 对应依赖，**不得包含 ZA21、LT99.09**；企业模式则相反（不得包含 bee-compat）。
- 依赖树/打包检查属于 P0，不允许用 `-DskipTests` 的结果代替最终验收。首次诊断可临时跳过测试，但交付时必须 `clean verify` 通过。

### 8.2 准备本地 MySQL（真实执行，不使用内存 Mock）

在本地 MySQL 8 的隔离实例或隔离 Schema 中准备：

```sql
CREATE DATABASE IF NOT EXISTS managed_meta
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS managed_business
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

本地管理数据库 `managed_meta` 手工运行仓库**现有**建表文件：

```bash
mysql -h 127.0.0.1 -P 3306 -u <管理库账号> -p managed_meta \
  < template-engine/managed-database-core/src/main/resources/db/manual/managed-database-schema.sql
```

需要核验 **4 张**管理表：`md_datasource_resource`、`md_datasource_table`、`md_database_operation`、`md_data_operation`。Schema 由用户/测试环境提前建立，应用启动不自动建库、建管理表。`managed_business` 是固定挂载的真实业务 Schema。须使用测试账号及独立的运行时调试账号；不要把 root、真实密码或服务密钥写入源码、测试日志和交付文档。

### 8.3 必须提交的两份 Spring 配置

1. 原 `template-engine/engine-service/config/application-local.yml` **保持**模板服务原有 `server.address=127.0.0.1`、`server.port=${TEMPLATE_ENGINE_PORT:18080}`、`devagentstudio.template-engine.source-root=${TEMPLATE_ENGINE_SOURCE_ROOT}` 等设置；不要覆盖模板 `source-root`。
2. 新增 `template-engine/engine-service/config/application-managed-local.yml`，仅组合启动时额外加载；提交带环境变量占位符的示例，不提交真实凭据。字段名必须与 `DatabaseMountProperties`、`RuntimeConnectionProperties`、`ServiceKeyFilter`、`DatasourceRegistrationConfiguration` 一致：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/managed_meta?useSSL=false&serverTimezone=UTC
    username: ${MANAGED_DB_ADMIN_USER}
    password: ${MANAGED_DB_ADMIN_PASSWORD}

mybatis-plus:
  mapper-locations: classpath*:mybatis/**/*.xml

managed-database:
  enabled: true
  service-key: ${MANAGED_DATABASE_SERVICE_KEY}
  id:
    worker-id: 1
    datacenter-id: 1
  mount:
    ssl-mode: DISABLED
  runtime:
    list:
      - datasource-id: 1
        username: ${MANAGED_DB_RUNTIME_USER}
        password: ${MANAGED_DB_RUNTIME_PASSWORD}
        ssl-mode: DISABLED

devagent-studio:
  list:
    - datasource-id: 1
      host: 127.0.0.1
      port: 3306
      db-name: managed_business
      username: ${MANAGED_DB_MOUNT_USER}
      password: ${MANAGED_DB_MOUNT_PASSWORD}
```

- **严格区分**管理库账号、业务挂载账号和专用调试账号；真实权限与凭据必须按职责分离，且账号不得随意拥有所有数据库的全局权限。
- MyBatis Mapper XML 必须可以从 managed-database-core JAR 通过 `classpath*:mybatis/**/*.xml` 加载，Mapper Bean、事务管理器和管理库 DataSource 均可用；需测试确认 `@MapperScan` 生效。
- 由于 `ManagedDatabaseIntegrationConfiguration` 条件扫描，只有组合构建且 `managed-database.enabled=true` 时才装配 Managed Database；默认 Template Engine 不应出现 DB Bean 依赖。
- 如果端口要供同机 XCodeAgent 调用，保持 `127.0.0.1`；若要跨机访问，必须显式设计监听地址、鉴权及网络限制，**不允许**验收脚本默认把服务暴露到 `0.0.0.0`。

### 8.4 构建、启动与依赖检查的准确命令

```bash
# 0. 默认模式仍可不依赖企业 Maven 包而构建（Template Engine only）
mvn -f template-engine/pom.xml -pl engine-service -am clean verify

# 1. 本地组合 Mock 模式：完整编译 + 测试 + 打包
mvn -f template-engine/pom.xml -Plocal-mock -pl engine-service -am clean verify

# 2. 检查本地依赖树（没有 ZA21 / LT99.09；需要检查 BOOT-INF/lib）
mvn -f template-engine/pom.xml -Plocal-mock -pl engine-service -am dependency:tree \
  '-Dincludes=ZA21:*,LT99.09:*'
unzip -p template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  META-INF/MANIFEST.MF | grep 'Start-Class'
unzip -l template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  | grep -E 'BOOT-INF/lib/(managed-database-core|bee-compat)'

# 3. 在仓库根目录设置本地变量，密码和密钥只在当前环境导入
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
export TEMPLATE_ENGINE_PORT=18080
export MANAGED_DATABASE_SERVICE_KEY='<本地服务测试密钥>'
export MANAGED_DB_ADMIN_USER='<管理库账号>'
export MANAGED_DB_ADMIN_PASSWORD='<管理库密码>'
export MANAGED_DB_MOUNT_USER='<业务挂载账号>'
export MANAGED_DB_MOUNT_PASSWORD='<业务挂载密码>'
export MANAGED_DB_RUNTIME_USER='<普通调试账号>'
export MANAGED_DB_RUNTIME_PASSWORD='<普通调试密码>'

# 4. 一个 JAR、一个进程、一个端口，同时加载两份配置
java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml,file:$(pwd)/template-engine/engine-service/config/application-managed-local.yml"
```

`-Pmanaged-enterprise` 正式环境验证另用企业仓库、真实 ZA21/LT99.09 及独立部署配置；禁止假定企业依赖已在本机可用。

### 8.5 端到端 HTTP 验收（两个服务缺一不可）

**A. Template Engine：同一个 18080 端口生成 ZIP**

```bash
curl --fail-with-body -sS -D /tmp/template-headers.txt \
  -o /tmp/generated-project.zip \
  -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/zip, application/json' \
  --data '{"requestedConfig":{"capabilities":{}}}'
# 校验 HTTP 200、Content-Type=application/zip，以及解压列表非空
unzip -t /tmp/generated-project.zip
```

不能只用模板引擎单独模式的测试充当组合模式验证；需确认**组合服务运行时**的 `/v1/generate` 和既有 `/v1/update` 契约无回归。

**B. Managed Database：健康检查（当前代码需 Bearer）**

```bash
curl --fail-with-body -sS -i \
  http://127.0.0.1:18080/managed-database/health \
  -H "Authorization: Bearer $MANAGED_DATABASE_SERVICE_KEY"
```

断言 HTTP 200、`returnCode=SUC0000`、`errorMsg=""`、`body.status=UP`；`GET /health` **只表示进程可用**，不证明管理库和固定挂载可用。当前 `ServiceKeyFilter` 对 `/managed-database/health` 也进行 Bearer 鉴权，本轮除非单独确定免鉴权契约，否则测试必须携带 Bearer。

**C. Managed Database：真实资源注册（每次成功 POST 都是创建新记录，不允许自动重试）**

```bash
curl --fail-with-body -sS -D /tmp/managed-headers.txt \
  -o /tmp/managed-register.json \
  -X POST http://127.0.0.1:18080/managed-database/datasources \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MANAGED_DATABASE_SERVICE_KEY" \
  --data '{"appKey":"codex-local-verification","appName":"Codex本地验证"}'
cat /tmp/managed-register.json
```

断言 HTTP 200、JSON 恰好具有 `returnCode/errorMsg/body` 三字段、`returnCode=SUC0000`、`body.datasourceKey` 非空，随后在 `managed_meta.md_datasource_resource` 中确认新增记录。**不得**自动重复提交该接口来排查网络超时；若不确定是否提交成功，应先核对服务端实际记录。

**D. Managed Database：真实建表、任务详情和元数据**

- 使用步骤 C 返回的真实 `datasourceKey`，从仓库现有 `TableSynchronizationControllerTest` / `MySqlCreateTableParserTest` 选取有效且不会污染其他数据的 SQL 测试样本，按 `POST /managed-database/datasources/{datasourceKey}/table-operations` 的实际 `OperationRequest` 合同提交 `{"mode":"CREATE","sql":"CREATE TABLE ...","allowDataLoss":false}`。测试数据必须限定在独立 `managed_business` Schema。
- 校验受理 HTTP 200 + Bee 三字段、`body.operationId` 非空；使用 `GET /managed-database/datasources/{datasourceKey}/table-operations/{operationId}` **轮询/读取**已完成的操作状态。`HTTP 200` 只证明受理成功，不证明物理建表成功。
- 进一步调用 `GET /managed-database/datasources/{datasourceKey}/tables` 和 `GET /managed-database/datasources/{datasourceKey}/tables/{tableName}/columns`，断言真实表与字段可查询；核对 `managed_meta` 管理记录与 `managed_business` 物理表相匹配。
- 再调用 `POST /managed-database/datasources/{datasourceKey}/tables/check` 验证结构检查协议，确保不返回裸 JSON。
- 测试完成后按既有资源回收/测试数据清理规则清理；不要把失败的、未知状态的建表操作视为可安全重放。

**E. Managed Database：权限 Seed / 取密 / SSE 与错误契约**

- 权限 Seed 通过 `POST /managed-database/datasources/{datasourceKey}/data-operations/batch` 验证：使用项目 `AuthorizationSeedRequest` 的严格 payload 契约及测试 Fixture，且先准备好相应权限表。受理返回 HTTP 200 + Bee 三字段，再按 operationId 或 idempotencyKey 读取最终状态。不要用随意拼造的 JSON 冒充 Seed 验收。
- `GET /managed-database/datasources/{datasourceKey}/runtime-connection` 断言 HTTP 200、Bee 三字段、`Cache-Control: no-store`；请求必须携带有效服务密钥，测试日志不能输出密码。
- SSE 至少覆盖：表数据 `.../tables/{tableName}/data/run`、资源清理 `.../cleanup/run`、恢复 `.../table-operations/{operationId}/recovery`。断言 `Content-Type: text/event-stream`，可解析 `RUN_STARTED`、`CUSTOM`/`STATE_SNAPSHOT`、`RUN_FINISHED` 或 `RUN_ERROR` 等事件。即使恢复 Controller 标有 `@BeeResponseBody`，也**不得**返回 Bee 三字段 JSON。
- 401/400/404/409/503 等错误均应使用 `returnCode/errorMsg/body` 三字段且 HTTP 保留错误状态。失败不能被伪装为 200 + `SUC0000`；`BeeResponseEntity` 不得双重嵌套。
- 完成 `/managed-database/**` 断言后，再验证 `/v1/**` 的原 JSON 错误结构（`code/message/details/traceId`）不受 Mock 影响。

### 8.6 必须交付自动化验收脚本与测试证据

Codex 在 `template-engine/scripts/ci/` 下新增 `verify-combined-local.sh`（如更符合项目目录组织，可同级放入现有 `scripts/ci/` 并说明路径），用以覆盖组合模式的：

1. 显式检测当前构建的 `Start-Class`、`BOOT-INF/lib` 依赖隔离，并断言一个 18080 服务同时响应 `/v1/generate` 和 `/managed-database/health`。
2. 验证普通 JSON 响应结构、HTTP 200/401/400/409/503、`Cache-Control`、SSE 非包装与模板 `/v1/**` 隔离。对于带风险的新增注册、建表、Seed 操作，只在测试环境通过显式的 `RUN_MUTATING_INTEGRATION_TESTS=true` 启用，不得默认在任何服务上写数据。
3. 外部脚本可使用 `curl`、`jq`、`mysql`、`unzip`；若本机缺少其中某工具，提供明确失败提示。切勿把服务密钥、数据库密码输出到命令回显或日志。
4. 输出测试结果摘要：端点、HTTP status、`returnCode`、是否具备三个顶层字段、SSE Content-Type、建表最终状态，以及未执行/失败的验收项。禁止报告没有实际执行的测试为通过。
5. 同时执行 **默认 Profile** 的无数据库启动回归，验证 Template Engine `/v1/generate` 正常且不装配 Managed Database；该模式不能因 Mock 或管理库配置缺失而失败。

## 9. 最终 Codex 验收与交付标准（全部 P0）

- [ ] **启动类选择**：仅保留 `template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/EngineServiceApplication.java`，已删除 `.../engine/service/EngineServiceApplication.java`，Boot JAR 的 `Start-Class` 与保留类一致。
- [ ] **单 JAR 双服务**：一条 `java -jar engine-service...jar` 命令、一个 `127.0.0.1:18080` 监听端口，同时可提供 `/v1/generate` 和 `/managed-database/datasources`，不启动第二个服务进程。
- [ ] **独立 Mock**：ZA21 最小兼容类全部位于 `devtools/mock-internal-deps/bee-compat`，所有 `com.cmb.bee.*` 原 import 未改变。
- [ ] **依赖隔离**：`-Plocal-mock` 产物只有 `bee-compat` + 标准 MySQL 驱动，不含 ZA21/LT99.09；正式模式产物反之。两 Profile 同时启用会明确构建失败。
- [ ] **普通 JSON 契约**：所有 Managed Database 普通 JSON 成功响应 HTTP 200、`SUC0000`、`""` 和恰好三个顶层字段；失败保留 HTTP 4xx/5xx、错误码与 `body:null`，无双重包装。
- [ ] **SSE 不改**：表数据、资源清理、操作恢复三个 AG-UI SSE 接口保持 `text/event-stream`，错误通过事件而非 HTTP 200 表达业务成功。
- [ ] **真实 MySQL**：已初始化 4 张管理表，业务挂载可连接，真实注册成功、建表任务完成、状态轮询和元数据查询匹配；权限 Seed 与专用运行时连接按既有契约验证。
- [ ] **原引擎不回归**：默认构建可独立运行、`/v1/generate` 与 `/v1/update` 合同不变，Template Engine 不被 Bee Advice 包装、不强制需要数据库。
- [ ] **自动化验收与证据**：提供 `verify-combined-local.sh`、SpringBoot 集成测试/MockMvc 测试、`mvn` 输出、HTTP 状态与脱敏响应、SSE 事件摘要，标识未运行项目。
- [ ] **边界保护**：无真实凭据进仓、无敏感信息打印、无数据库模拟仓储、无业务 Service / SQL / 幂等状态机改造，未自动重试不幂等的写操作。

> 注意：本文为依据仓库代码制定的 Codex 实施方案，并非已落地/已通过构建的代码。正式 Bee 包对异常包装的所有内部细节当前不可见，本地 Mock 以本方案约定的 HTTP+JSON 契约为准；若要证明与正式 Bee **二进制/行为完全一致**，必须在企业环境用真实包完成对照验收。
