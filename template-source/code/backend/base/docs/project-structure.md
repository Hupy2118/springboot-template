# 后端项目结构与代码放置规则

本文档描述生成后的后端工程目录，供业务代码生成与维护使用。新增代码应先按职责归类，再通过既有应用服务、领域抽象和基础设施实现完成组合。

## Base 目录职责

```text
src/main/java/com/cmbchina/backend/
├── Application.java                  Spring Boot 启动入口
└── common/
    ├── config/                       跨业务的 Spring、CORS、MyBatis 和受管拦截器配置
    ├── exception/                    统一错误码、业务异常和全局异常处理
    ├── page/                         通用分页请求与返回对象
    └── response/                     统一 HTTP 响应包装

src/main/resources/
└── application.yml                   非敏感默认配置及环境变量占位

src/test/java/                        单元测试与集成测试
```

`common/` 只放可跨业务复用的稳定基础能力，不放某个业务域的 Controller、实体、DTO、Mapper 或 Repository。

## 业务 Capability 的分层

业务 Capability 可以在 `com.cmbchina.backend.<capability>/` 下建立以下层次；按实际需要使用，不要求每一层都创建空目录。

```text
<capability>/
├── adapter/web/                      Controller、请求/响应适配
├── application/                      用例编排、Application Service、DTO、Assembler
├── domain/                           实体、值对象、领域服务、错误码、Repository 接口
├── infrastructure/                   Repository 实现、Mapper、PO、配置与外部系统适配
└── bootstrap/                        明确登记的启动初始化逻辑
```

对应的 MyBatis XML 放在 `src/main/resources/mapper/<capability>/`，并与 Mapper 接口、PO 和基础设施 Repository 保持同一业务归属。

## 依赖方向

```text
Web Adapter / Bootstrap
            ↓
       Application
            ↓
          Domain
            ↑
     Infrastructure
```

- `adapter/web` 只处理 HTTP 协议、参数校验和结果映射，并调用 Application Service。
- `application` 编排用例，可依赖 Domain 抽象；不得依赖 Controller、Servlet API 或具体 Mapper/PO。
- `domain` 不依赖 Spring MVC、MyBatis、数据库或 Web 层；Repository 只在此处定义接口。
- `infrastructure` 实现 Domain 定义的 Repository，并承载 MyBatis Mapper、PO、XML 和外部适配。
- 业务错误使用 `BizException` 和错误码接口表达，由 `BaseExceptionHandler` 统一映射为 `ResponseEntity`。

## 受管扩展与配置

- `common/config/CapabilityWebMvcConfiguration.java` 是平台受管的 Capability 拦截器入口。只允许模板引擎写入受管块，不要手工调整其锚点或注册顺序。
- 跨域配置、MyBatis 分页和统一异常处理属于 Base 共享能力。业务 Capability 不应覆盖这些文件。
- 本地数据库连接使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量覆盖 `application.yml` 的默认值；不得在代码、测试或文档中存放真实凭据。

## 动态 Capability 文档

每个启用的 Capability 会与其代码一同生成 `docs/capabilities/<capability>.md`。该文档说明该能力实际加入的接口、配置、拦截器、数据资产和维护边界。Capability 被禁用时，对应文档不会存在；不要将它当作 Base 的固定目录或手工维护的功能索引。

当上述目录职责、依赖方向、统一响应/异常处理方式或受管扩展面发生变化时，必须同步更新本文档。
