# Dependency map

Upstream: `e56e7676b300d45e01093cd7697186e449046feb` (`1016`), source revision `2026.10.23.2`.

| Upstream dependency / provider | Local replacement | Verification |
| --- | --- | --- |
| ZA21:bee-starter-parent:6.0.7; implicit dependency versions | overlays/backend/base/pom.xml, Spring Boot 2.7.2 + explicit MyBatis/MapStruct/Lombok processors | Public settings, isolated empty Maven cache, generated Base package/start |
| ZA21:bee-starter-core:parent-managed; MVC, exceptions, JDBC defaults | Standard web/validation/JDBC starters; limited Bee response/error/exception classes | JSON/exception tests, Spring MVC and JDBC integration |
| LT99.09:tdsql-mysql-connector-java8:1.5.0 | com.mysql:mysql-connector-j:8.2.0; standard datasource YAML | Actual local MySQL connection; Bootstrap remains a later gate |
| ZA21:bee-starter-auth:parent-managed; EnableAuthClient and token context | Login manifest removes internal contributions; Mock identity implementation is a later P1 gate | Login/RBAC not yet accepted |
| TokenContextHolder.get / LocalToken.getSapId | P1 local identity compatibility classes | Thread cleanup / authentication / RBAC tests required |
| Bee ds1 / TDSQL Bootstrap loader and URL validation | MySQL BootstrapDataSource overlay + exact URL check patch | Manifest-v3 logic and SQL unchanged; real Bootstrap acceptance required |
| MyBatis mapper / pagination / transaction defaults | Public MyBatis-Plus starter; existing MybatisPlusConfiguration and @Mapper annotations retained | Mapper XML + Spring transaction/JDBC tests |

`LT54.06:xcode-agent-backend` is the generated application's own coordinate, not a downloadable private dependency. `com.cmbchina.backend` is its legitimate application package.

The template_refactor POM confirms the public Boot 2.7.2 / MyBatis-Plus 3.5.2 / MySQL 8.2.0 combination. Its changed application coordinates, transmittable-thread-local and older auth architecture are not copied. Its JWT service is a design reference only. Parent-managed enterprise dependency versions cannot be resolved without the private parent; no invented versions are recorded.

## Exact runtime references

- `backend/base/pom.xml:13` — `<groupId>ZA21</groupId>`
- `backend/base/pom.xml:14` — `<artifactId>bee-starter-parent</artifactId>`
- `backend/base/pom.xml:32` — `<groupId>ZA21</groupId>`
- `backend/base/pom.xml:33` — `<artifactId>bee-starter-core</artifactId>`
- `backend/base/pom.xml:51` — `<groupId>LT99.09</groupId>`
- `backend/base/src/main/java/com/cmbchina/backend/common/exception/WebGlobalExceptionHandler.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/base/src/main/java/com/cmbchina/backend/common/exception/WebGlobalExceptionHandler.java:4` — `import com.cmb.bee.commons.exceptions.SysException;`
- `backend/base/src/main/java/com/cmbchina/backend/common/exception/WebGlobalExceptionHandler.java:5` — `import com.cmb.bee.web.entity.BeeResponseEntity;`
- `backend/base/src/main/java/com/cmbchina/backend/common/exception/error/code/BusinessErrorCode.java:3` — `import com.cmb.bee.commons.errorcodes.IBizErrorCode;`
- `backend/base/src/main/java/com/cmbchina/backend/common/exception/error/code/SystemErrorCode.java:3` — `import com.cmb.bee.commons.errorcodes.ISysErrorCode;`
- `backend/base/src/main/resources/application.yml:10` — `driver-class-name: com.tencentcloud.tdsql.mysql.cj.jdbc.Driver`
- `backend/base/src/main/resources/application.yml:11` — `url: jdbc:tdsql-mysql://${datasource.ds1.url}:${datasource.ds1.port}/${datasource.ds1.schema}?passwordCharacterEncoding=latin1&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useUnicode=true&useSSL=false&connectTimeout=10000&socketTimeout=600000&tdsqlSendClientInfoEnable=true`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/adapter/web/MemberController.java:3` — `import com.cmb.bee.web.entity.BeeResponseEntity;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/adapter/web/ResourceController.java:3` — `import com.cmb.bee.web.entity.BeeResponseEntity;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/adapter/web/RoleController.java:3` — `import com.cmb.bee.web.entity.BeeResponseEntity;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/MemberApplicationService.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/ResourceApplicationService.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/ResourceApplicationService.java:4` — `import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/ResourceApplicationService.java:5` — `import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/RoleApplicationService.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/RoleApplicationService.java:4` — `import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/application/service/RoleApplicationService.java:5` — `import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java:55` — `if (!dbUrl.startsWith("jdbc:tdsql-mysql://") \|\| dbUser.trim().isEmpty())`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java:38` — `if (!"com.tencentcloud.tdsql.mysql.cj.jdbc.Driver".equals(driver) \|\| !url.startsWith("jdbc:tdsql-mysql://")`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/common/interceptor/ResourcePermissionInterceptor.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/common/interceptor/ResourcePermissionInterceptor.java:4` — `import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/common/interceptor/ResourcePermissionInterceptor.java:5` — `import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;`
- `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/domain/exception/AuthErrorCode.java:4` — `import com.cmb.bee.commons.errorcodes.IBizErrorCode;`
- `backend/extensions/authorization/src/test/java/com/cmbchina/backend/auth/common/interceptor/AuthorizationMvcTest.java:3` — `import com.cmb.bee.commons.exceptions.BizException;`
- `backend/extensions/authorization/src/test/java/com/cmbchina/backend/auth/common/interceptor/AuthorizationMvcTest.java:4` — `import com.cmb.oa.one.account.oauth.client.login.context.TokenContextHolder;`
- `backend/extensions/authorization/src/test/java/com/cmbchina/backend/auth/common/interceptor/AuthorizationMvcTest.java:5` — `import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;`
- `backend/extensions/login/docs/login.md:7` — `- 引擎向 Application 注入 `@EnableAuthClient`，并向 Maven POM 注入 `ZA21:bee-starter-auth`。`
- `backend/extensions/login/extension.yaml:8` — `artifactId: bee-starter-auth`

## Maintenance boundary

backend/assembly uses public SnakeYAML 1.31 / Jackson 2.13.4.2 and is maintenance-only. Workspace, profiles and assembly are not copied by the adapter.
