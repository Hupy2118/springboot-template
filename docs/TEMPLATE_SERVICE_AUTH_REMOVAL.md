# Template Service 静态 Token 认证移除与企业认证演进方案

## 1. 核心思路

### 1.1 背景

当前 Template Service 使用静态 Bearer Token 进行认证：

```text
DevAgent Studio
  │
  │ Authorization: Bearer ${DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN}
  ▼
Template Service
  │
  │ TokenAuthenticator
  │ SHA-256(token) + Principal + Scope
  ▼
/v1/generate
/v1/update
```

现阶段 `DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN` 使用固定值，例如：

```env
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN=stage3-demo-token
```

Template Service 再通过预配置的 SHA-256 摘要识别该固定 Token。

这套机制存在以下问题：

1. Token 是固定开发配置，不具备真正的身份区分、动态签发、轮换和吊销能力。
2. 原始 Token 可以从配置示例或部署配置中获取，SHA-256 仅避免服务端保存明文，并不能建立有效的身份认证边界。
3. 为一个固定 Token 引入了 `Principal / Scope / TokenAuthenticator / token-sha256 / 401 / 403` 等额外复杂度。
4. 现有认证逻辑与未来企业统一认证体系没有直接复用价值；保留兼容开关反而容易形成长期历史包袱。

因此，本次改造不对现有静态 Token 体系做增强，而是**彻底移除现有 Template Service 应用层认证逻辑**。

---

### 1.2 目标架构

当前阶段明确 Template Service 的定位：

> Template Service 是 DevAgent Studio 的本地辅助服务，不是独立对外开放的公共服务。

本地部署模式调整为：

```text
┌────────────────────────────── 本机 ──────────────────────────────┐
│                                                                  │
│  DevAgent Studio Backend                                              │
│        │                                                         │
│        │ HTTP                                                    │
│        │ http://127.0.0.1:18080                                  │
│        ▼                                                         │
│  Template Service                                                │
│        │                                                         │
│        ├── /v1/generate                                          │
│        └── /v1/update                                            │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

当前阶段：

- 不做应用层身份认证；
- 不发送 Bearer Token；
- Template Service 仅监听 Loopback；
- IPv4 固定绑定 `127.0.0.1`；
- 不允许默认监听 `0.0.0.0`；
- 不在 Engine Core 中引入任何身份或权限概念。

安全边界由：

```text
固定 Bearer Token
```

收敛为：

```text
Local Process
    +
Loopback-only Network Reachability
```

需要强调：

> Loopback 不是“认证机制”，而是当前本地部署模式下的网络暴露边界。只有本机进程能够通过 `127.0.0.1` 访问 Template Service。

---

### 1.3 不保留认证兼容开关

本次不设计：

```yaml
authentication:
  enabled: false
```

也不保留：

```java
if (authenticationEnabled) {
    auth.require(...);
}
```

应直接删除现有认证实现，包括：

```text
TokenAuthenticator
Principal
Scope
tokenSha256
Authorization Bearer Header
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256
TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256
```

原因是未来企业认证与当前静态 Token 模型不是同一个认证体系。

保留旧实现只会形成：

```text
旧静态 Token
        +
未来企业认证
```

两套安全模型并存。

正确的演进方式是：

```text
当前
Local Template Service
Loopback only
无应用层认证

        ↓

未来
Enterprise Template Service
企业网络部署
+
独立企业认证接入层
```

---

### 1.4 企业认证的预留原则

本次不实现企业认证，但必须保证未来企业认证可以独立接入。

核心原则是：

> 认证属于 Template Service 的 HTTP 接入层，不属于 Template Engine Core，也不属于 Generate / Update 的业务协议。

未来企业认证应位于：

```text
Client
   │
   ▼
┌──────────────────────────────┐
│ Enterprise Authentication    │
│                              │
│ Gateway / Spring Security /  │
│ Service Identity / JWT /     │
│ mTLS 等                      │
└──────────────┬───────────────┘
               │ 已认证请求
               ▼
        EngineController
               │
               ▼
      Reconcile / Generator
```

而不是：

```text
EngineController
    ↓
业务代码手工解析 token
    ↓
Core 接收 principal / scope
```

因此未来企业认证应满足：

1. 认证发生在 Controller 之前。
2. `EngineController` 不手工解析企业 Token。
3. `/v1/generate`、`/v1/update` 的业务 Request Body 不增加认证字段。
4. `engine-core` 不感知认证体系。
5. 企业身份、JWT、mTLS、网关 Header 等不写入 Template State。
6. 授权策略如确有需要，由 HTTP Security Layer 根据 Endpoint 做映射，不重新引入当前 `template.generate/template.update` 静态 Scope 模型。

未来可以选择：

```text
方案 A：企业 API Gateway 统一认证
方案 B：Spring Security Resource Server
方案 C：服务间 mTLS / Service Identity
```

具体采用哪种方案，应由企业统一安全体系决定，而不是在本次改造中预埋一套假实现。

---

### 1.5 改造边界

本次改动范围：

```text
springboot-template
└── template-engine/engine-service
    ├── Controller
    ├── Service Configuration
    ├── Service Properties
    ├── Local Configuration
    ├── OpenAPI
    ├── Integration Tests
    └── README / 启动脚本

DevAgent Studio
└── Backend
    ├── Settings
    ├── TemplateEngineClient
    ├── WorkspaceBootstrapService
    ├── TemplateReconcileService
    ├── Tests
    └── .env.example
```

不改：

```text
engine-core
ReconcileDecisionEngine
V2ProjectGenerator
TemplateStateV2
Capability
Strategy
Template Source
Template Package
Workspace Apply
```

认证移除不能改变 Template Engine 的业务行为。

---

## 2. 实施步骤

### 2.1 阶段一：移除 Template Service 服务端认证

#### 2.1.1 修改 `EngineController`

当前：

```java
private final TokenAuthenticator auth;

public ResponseEntity<byte[]> generate(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
        String authorization,
        @RequestBody Map<String, Object> request) {

    auth.require(authorization, "template.generate");
    ...
}
```

`update` 同样执行：

```java
auth.require(authorization, "template.update");
```

改造后：

```java
public ResponseEntity<byte[]> generate(
        @RequestBody Map<String, Object> request) {

    requireOnly(request, "requestedConfig");
    ...
}
```

```java
public ResponseEntity<byte[]> update(
        @RequestBody Map<String, Object> request) {

    requireOnly(
        request,
        "protocolVersion",
        "currentTemplateState",
        "requestedConfig",
        "mode"
    );
    ...
}
```

同时删除：

```java
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
```

以及：

```java
private final TokenAuthenticator auth;
```

Controller 构造函数中删除 `TokenAuthenticator auth` 参数。

#### 验收

以下请求必须能够直接执行：

```bash
curl -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Content-Type: application/json' \
  --data '{
    "requestedConfig": {
      "capabilities": {}
    }
  }'
```

不得要求：

```http
Authorization: Bearer xxx
```

---

### 2.2 删除 `TokenAuthenticator`

删除：

```text
template-engine/engine-service/
src/main/java/com/devagentstudio/template/engine/service/
TokenAuthenticator.java
```

同时彻底移除其中：

```text
template.plan
template.generate
template.update

Principal
principal-id
principal-type
token-sha256
scopes

Bearer Header 解析
SHA-256 Token 比较
401 UNAUTHORIZED
403 FORBIDDEN
```

本次不创建：

```text
NoOpAuthenticator
LocalAuthenticator
AuthenticationProvider
AuthEnabled=false
```

因为当前阶段不存在应用层认证需求。

---

### 2.3 修改 `ServiceConfiguration`

删除：

```java
@Bean
TokenAuthenticator tokenAuthenticator(
        TemplateEngineProperties properties) {
    return new TokenAuthenticator(properties.getPrincipals());
}
```

最终 Service Configuration 只负责 Template Engine 业务组件：

```text
TemplateRelease
ReconcileDecisionEngine
V2ProjectGenerator
```

认证不再是 Service 启动的前置条件。

---

### 2.4 收缩 `TemplateEngineProperties`

删除：

```text
principals
Principal
principalId
principalType
tokenSha256
scopes
```

保留 Template Service 本身真正需要的配置，例如：

```java
@ConfigurationProperties(
    prefix = "devagentstudio.template-engine"
)
public class TemplateEngineProperties {

    private String sourceRoot;

    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }
}
```

目标是使：

```text
TemplateEngineProperties
```

只表达 Template Engine 配置，不承担认证数据库的职责。

---

### 2.5 修改本地网络边界

`application-local.yml` 应明确配置：

```yaml
server:
  address: 127.0.0.1
  port: ${TEMPLATE_ENGINE_PORT:18080}

devagentstudio:
  template-engine:
    source-root: ${TEMPLATE_ENGINE_SOURCE_ROOT}
```

删除：

```text
principals
token-sha256
scopes
principal-id
principal-type
```

以及：

```text
TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256
TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256
```

#### 约束

当前 Local Profile 不建议写成：

```yaml
server:
  address: ${TEMPLATE_ENGINE_HOST:127.0.0.1}
```

因为这会让使用者可以无意中配置：

```text
TEMPLATE_ENGINE_HOST=0.0.0.0
```

从而绕开本地安全边界。

本地 Profile 应明确：

```yaml
server:
  address: 127.0.0.1
```

如未来需要远程部署，应新增独立 Enterprise Deployment Profile，而不是修改 Local Profile。

---

### 2.6 修改 OpenAPI Contract

删除 OpenAPI 中：

```yaml
security:
  - bearerAuth: []
```

删除：

```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
```

删除仅由旧认证体系产生的：

```text
401 Unauthorized
403 Forbidden
```

保留业务协议错误：

```text
400 Bad Request
409 Conflict
500 Internal Server Error
```

OpenAPI 的职责是描述当前真实服务契约，不提前声明尚未实现的企业认证。

未来企业认证落地时，再由对应 Enterprise Profile / Gateway Contract 增加安全声明。

---

### 2.7 修改 Template Service 集成测试

删除测试初始化中的：

```text
principal
tokenSha256
scope
```

删除 Request 中：

```http
Authorization: Bearer ...
```

删除旧认证测试：

```text
missing token -> 401
invalid token -> 401
insufficient scope -> 403
```

保留并继续强化：

```text
generate 正常返回 ZIP
update 正常返回 Strategy Package
update NO_CHANGE 返回 204
request contract 校验
Template State 校验
Golden Test
幂等测试
ZIP 内容验证
错误响应规范
```

增加一个 Local Profile 边界测试，至少验证：

```text
server.address = 127.0.0.1
```

也可以在 CI 中增加配置静态检查，防止 Local Profile 后续被误改为：

```text
0.0.0.0
```

---

## 3. 阶段二：移除 DevAgent Studio 中的 Template Engine Token

### 3.1 删除 Settings 字段

当前 DevAgent Studio Backend：

```python
template_engine_base_url: str = ""
template_engine_token: str = ""
```

并从：

```text
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
```

读取 Token。

改为：

```python
template_engine_base_url: str = ""
```

删除：

```text
template_engine_token
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
```

保留：

```text
DEVAGENTSTUDIO_TEMPLATE_ENGINE_BASE_URL
DEVAGENTSTUDIO_TEMPLATE_ENGINE_CONNECT_TIMEOUT_SECONDS
DEVAGENTSTUDIO_TEMPLATE_ENGINE_READ_TIMEOUT_SECONDS
DEVAGENTSTUDIO_TEMPLATE_PACKAGE_MAX_BYTES
```

---

### 3.2 修改 `TemplateEngineClient`

当前构造函数：

```python
TemplateEngineClient(
    base_url=...,
    token=...,
    connect_timeout=...,
    read_timeout=...,
    max_package_bytes=...,
)
```

改为：

```python
TemplateEngineClient(
    base_url=...,
    connect_timeout=...,
    read_timeout=...,
    max_package_bytes=...,
)
```

删除：

```python
self._token = token
```

当前：

```python
if not self._base_url or not self._token:
    raise TemplateEngineError(
        "Template Engine 地址或凭据未配置。"
    )
```

改为：

```python
if not self._base_url:
    raise TemplateEngineError(
        "Template Engine 地址未配置。"
    )
```

---

### 3.3 删除 HTTP Authorization Header

`generate()` 当前类似：

```python
headers={
    "Authorization": f"Bearer {self._token}",
    "Accept": "application/zip",
}
```

改为：

```python
headers={
    "Accept": "application/zip",
}
```

`update()` 同样删除 Authorization。

最终 DevAgent Studio 与 Template Service 的传输协议只包含：

```text
HTTP Endpoint
Content-Type
Accept
Request Body
Response Body
```

不再包含静态认证凭据。

---

### 3.4 修改调用 `TemplateEngineClient` 的 Service

重点检查：

```text
Backend/app/services/workspace_bootstrap/service.py
Backend/app/services/template_reconcile/service.py
```

将：

```python
TemplateEngineClient(
    base_url=self._settings.template_engine_base_url,
    token=self._settings.template_engine_token,
    ...
)
```

改为：

```python
TemplateEngineClient(
    base_url=self._settings.template_engine_base_url,
    ...
)
```

业务流程不做其他改变。

---

### 3.5 修改 `.env.example`

删除：

```env
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN=stage3-demo-token
```

最终保留类似：

```env
# Template Engine local service
DEVAGENTSTUDIO_TEMPLATE_ENGINE_BASE_URL=http://127.0.0.1:18080
DEVAGENTSTUDIO_TEMPLATE_ENGINE_CONNECT_TIMEOUT_SECONDS=10
DEVAGENTSTUDIO_TEMPLATE_ENGINE_READ_TIMEOUT_SECONDS=120
DEVAGENTSTUDIO_TEMPLATE_PACKAGE_MAX_BYTES=104857600
```

同时删除所有“Renderer 不持有此凭据”“Engine token”等已经失效的说明。

---

### 3.6 修改 DevAgent Studio 测试

全局搜索：

```text
template_engine_token
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
Bearer
stage3-demo-token
test-token
```

重点修改：

```text
test_workspace_bootstrap_template_engine_client.py
test_workspace_bootstrap_service.py
test_template_reconcile_v2_e2e.py
```

测试 Settings 不再构造：

```python
template_engine_token="token"
```

Mock HTTP Client 不再断言：

```text
Authorization
```

同时保留：

```text
请求地址
请求 Body
超时
ZIP 流式下载
下载大小限制
SHA-256 Package 校验
204 NO_CHANGE
Engine 错误透传
```

说明：

> Package SHA-256 属于内容完整性校验，与本次删除的认证 Token SHA-256 无关，应继续保留。

---

## 4. 迁移顺序

为了避免 Template Service 与 DevAgent Studio 必须同时升级，采用服务端优先迁移。

### Step 1：先升级 Template Service

先完成：

```text
删除 TokenAuthenticator
删除 Controller auth.require
删除 Principal / Scope
删除 Token 配置
固定 127.0.0.1
```

此时新的 Template Service：

```text
不要求 Authorization Header
```

旧 DevAgent Studio 仍然会发送：

```http
Authorization: Bearer stage3-demo-token
```

但 Spring Controller 不再读取该 Header，请求仍然可以正常执行。

因此这一阶段具备向后兼容性：

```text
旧 DevAgent Studio
   ↓ 带无效但无害的 Authorization Header
新 Template Service
   ↓
正常工作
```

---

### Step 2：验证 Template Service

独立验证：

```text
无 Authorization Header 调 generate -> 成功
无 Authorization Header 调 update -> 成功
Template Service 仅监听 127.0.0.1
Engine Core 回归测试全部通过
```

建议检查：

```bash
lsof -nP -iTCP:18080 -sTCP:LISTEN
```

期望监听地址为：

```text
127.0.0.1:18080
```

而不是：

```text
*:18080
0.0.0.0:18080
```

---

### Step 3：再升级 DevAgent Studio

删除：

```text
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
template_engine_token
TemplateEngineClient.token
Authorization Bearer Header
```

此时：

```text
新 DevAgent Studio
    ↓ 无 Authorization
新 Template Service
    ↓
正常工作
```

---

### Step 4：清理文档、脚本和 CI

两个仓库统一搜索并清理：

```bash
rg -n \
  "DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN|\
TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256|\
TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256|\
TokenAuthenticator|\
tokenSha256|\
template\.generate|\
template\.update|\
template\.plan|\
stage3-demo-token"
```

应区分：

```text
认证 Token SHA-256
```

和：

```text
Template Package / 文件内容 SHA-256
```

后者不能删除。

---

## 5. 企业认证演进边界

本次改造后，不提前创建具体企业认证代码，但需要固化以下架构约束。

### 5.1 Local Mode

```text
DevAgent Studio
   ↓
127.0.0.1
   ↓
Template Service
```

特点：

```text
Loopback only
无应用层认证
不需要 Credential
```

---

### 5.2 Enterprise Mode

未来如果 Template Service 需要共享部署：

```text
DevAgent Studio A ─┐
DevAgent Studio B ─┼──── Enterprise Auth Layer
DevAgent Studio C ─┘              │
                              ▼
                       Template Service
```

此时必须重新引入正式企业认证，例如：

```text
企业 API Gateway
JWT / OAuth2
Spring Security
Service Identity
mTLS
```

但认证必须与 Template Engine Core 解耦。

推荐边界：

```text
HTTP Request
     │
     ▼
Enterprise Auth Layer
     │
     │ Authentication / Authorization
     ▼
EngineController
     │
     ▼
Template Engine Core
```

禁止重新演化成：

```text
EngineController
    ↓
手工读取 Bearer Token
    ↓
手工计算 SHA-256
    ↓
自己维护 Principal / Scope
```

---

### 5.3 企业认证未来接入时的扩展点

未来认证接入应优先选择以下扩展点之一：

```text
API Gateway
Spring Security Filter Chain
Service Mesh / mTLS
```

而不是修改：

```text
EngineController.generate(...)
EngineController.update(...)
ReconcileDecisionEngine
V2ProjectGenerator
TemplateStateV2
```

如果未来需要访问调用方身份，应通过标准 Security Context 获取，不改变 Generate / Update Request Schema。

---

## 6. 最终代码状态

完成本次改造后，代码中应不存在：

```text
TokenAuthenticator
TemplateEngineProperties.Principal

DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN

TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256
TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256

template_engine_token

Authorization: Bearer ...
template.plan
template.generate
template.update
```

注意：

`template.generate`、`template.update` 如果仅作为旧认证 Scope 使用，应删除；如果其他地方存在同名业务常量，需要逐处确认后再删除。

应继续存在：

```text
DEVAGENTSTUDIO_TEMPLATE_ENGINE_BASE_URL
TEMPLATE_ENGINE_SOURCE_ROOT

Template Package SHA-256
文件 contentHash
ZIP 完整性校验
Template State
Capability State
```

---

## 7. 验收标准

### 7.1 Template Service

必须满足：

- Template Service 不配置任何 Token 即可启动。
- `/v1/generate` 不带 Authorization 可以成功调用。
- `/v1/update` 不带 Authorization 可以成功调用。
- 不存在 401/403 的静态 Token 认证路径。
- Local Profile 固定监听 `127.0.0.1`。
- `engine-core` 无认证相关改动。
- Generate / Update 的业务行为与改造前一致。
- Golden / Reconcile / Idempotency 测试通过。

### 7.2 DevAgent Studio

必须满足：

- 不再读取 `DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN`。
- `Settings` 不再包含 `template_engine_token`。
- `TemplateEngineClient` 不再接收 token 参数。
- Generate / Update 请求不发送 Authorization Header。
- Workspace Bootstrap 正常生成工程。
- Template Reconcile 正常更新工程。
- ZIP 大小、SHA-256、错误处理等传输保护保持不变。

### 7.3 配置和代码清理

执行全仓搜索后：

```text
DEVAGENTSTUDIO_TEMPLATE_ENGINE_TOKEN
TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256
TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256
TokenAuthenticator
stage3-demo-token
```

应无有效运行时代码引用。

### 7.4 网络边界

Template Service 启动后：

```bash
lsof -nP -iTCP:18080 -sTCP:LISTEN
```

必须显示监听：

```text
127.0.0.1:18080
```

不得监听：

```text
*:18080
0.0.0.0:18080
```

---

## 8. 实施完成后的最终架构

```text
                    当前 Local Mode

┌──────────────────────────────────────────────────────┐
│ Local Machine                                        │
│                                                      │
│  DevAgent Studio Backend                                  │
│      │                                               │
│      │ HTTP / no credential                          │
│      ▼                                               │
│  127.0.0.1:18080                                     │
│      │                                               │
│      ▼                                               │
│  Template Service                                    │
│      │                                               │
│      ├── Generate                                    │
│      ├── Update                                      │
│      └── Reconcile                                   │
│              │                                       │
│              ▼                                       │
│       Template Engine Core                           │
│                                                      │
└──────────────────────────────────────────────────────┘


                    Future Enterprise Mode

DevAgent Studio
    │
    ▼
Enterprise Authentication Layer
    │
    │ AuthN / AuthZ
    ▼
Template Service
    │
    ▼
Template Engine Core
```

最终原则：

> **当前删除无实际安全价值的固定 Token 认证，通过 Loopback 限制 Local Template Service 的网络暴露；未来需要共享或远程部署时，在 HTTP 接入层重新接入真正的企业认证体系，而不是保留或复用当前静态 Token 机制。**
