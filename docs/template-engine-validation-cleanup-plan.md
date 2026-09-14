# `validation/` 目录收敛改造方案

## 1. 背景

当前仓库顶层存在：

```text
validation/
├── fixtures/
├── stage3/
├── verify-base-frontend.sh
├── verify-stage2.sh
├── verify-stage3-http.sh
└── verify-template-release-revision.sh
```

该目录最初主要服务于 Template Engine 重构阶段的 Stage2 / Stage3 验证，但随着 `template-engine` 的 Core、Service、Authoring 模块逐步稳定，`validation/` 已混合承载了多种不同职责：

- 测试 Fixture；
- 阶段性验证脚本；
- Service 启动配置；
- HTTP 冒烟测试；
- Template Release CI Gate；
- Base Frontend 构建验证。

这些职责本质上不属于同一层级，继续放在统一的 `validation/` 下会产生以下问题：

1. Stage2 / Stage3 属于历史实施阶段，而不是稳定架构概念；
2. 测试资源没有跟随各 Maven Module 管理；
3. Service 启动配置放在仓库级验证目录，职责不清；
4. 一部分 Shell 验证已与 JUnit 集成测试重复；
5. CI Governance 与临时验证脚本混在一起；
6. 后续开发者需要理解“validation/stage3”才能启动 Service，不利于工程长期维护。

因此建议：

> **删除仓库顶层 `validation/` 目录，将其中仍有长期价值的能力迁移到其真正所属的位置。**

---

# 第一章 目标架构

## 2. 改造目标

本次改造后，不再保留：

```text
validation/
```

新的职责划分：

```text
测试数据
    → 各 module/src/test/resources

模块测试
    → 各 module/src/test/java

Service 本地启动配置
    → engine-service/config 或正式 Spring 配置

Release CI Gate
    → scripts/ci 或 CI Workflow

Template Source 构建检查
    → scripts/ci 或 CI Workflow
```

目标目录：

```text
springboot-template/
├── template-engine/
│   ├── engine-core/
│   │   └── src/
│   │       ├── main/
│   │       └── test/
│   │           ├── java/
│   │           └── resources/
│   │
│   ├── capability-authoring/
│   │   └── src/
│   │       ├── main/
│   │       └── test/
│   │           ├── java/
│   │           └── resources/
│   │
│   └── engine-service/
│       ├── config/
│       │   └── application-local.yml
│       └── src/
│           ├── main/
│           │   └── resources/
│           │       └── openapi/
│           └── test/
│               ├── java/
│               └── resources/
│
├── scripts/
│   └── ci/
│       ├── verify-template-release-revision.sh
│       └── verify-base-frontend.sh
│
├── template-source/
└── docs/
```

---

# 第二章 文件处理方案

## 3. `validation/fixtures/`

当前内容：

```text
validation/fixtures/
├── authorization.yaml
├── template-state-v2-golden.json
└── template-state-v2-golden.sha256
```

### 3.1 `template-state-v2-golden.sha256`

处理：

```text
DELETE
```

原因：

本轮 Template Engine Digest 收敛会删除 `StateDigest`，因此 SHA Golden Fixture 不再有意义。

对应删除：

```text
StateDigestGoldenTest.java
```

---

### 3.2 `template-state-v2-golden.json`

优先处理：

```text
DELETE
```

如果 StateDigestGoldenTest 删除后，没有其他测试消费该 JSON，则直接删除。

如果仍有结构化 State Contract Test 需要，则迁移为：

```text
template-engine/engine-core/src/test/resources/
    template-state-v2-golden.json
```

禁止继续通过：

```java
repositoryRoot().resolve("validation/fixtures")
```

访问仓库级 fixture。

测试资源必须通过 classpath 加载。

---

### 3.3 `authorization.yaml`

优先判断是否仍被单测实际消费。

如果无引用：

```text
DELETE
```

如果仍是 Core / Authoring 测试输入：

```text
MOVE TO
template-engine/<owner-module>/src/test/resources/
```

原则：

> Fixture 必须跟随真正消费它的测试 Module，而不是放在仓库顶层。

---

# 第三章 Stage3 目录清理

## 4. `validation/stage3/requested-authorization.json`

处理：

```text
DELETE
```

原因：

该文件仅属于手工 Stage3 HTTP 测试输入。

目前 `EngineServiceIT` 已在 Java 测试代码中直接构造 requestedConfig，不依赖该文件。

---

## 5. `validation/stage3/requested-login.json`

处理：

```text
DELETE
```

原因同上。

Stage3 手工请求 Fixture 不应继续作为稳定工程资产。

---

## 6. `validation/stage3/application.yml`

该文件不能简单删除，因为它目前承担：

```text
engine-service 启动参数
source-root
principal/token-sha256
scopes
server.port
```

但它不应继续位于：

```text
validation/stage3/
```

### 6.1 拆分原则

首先区分两类配置：

#### Service 通用配置

例如：

```text
server.port
source-root
```

#### 环境敏感配置

例如：

```text
principal
token-sha256
scope
```

第二类不应提交真实生产 Secret。

---

## 7. 推荐的本地启动配置

新增：

```text
template-engine/engine-service/config/application-local.yml
```

建议结构：

```yaml
server:
  port: ${TEMPLATE_ENGINE_PORT:18080}

xcodeagent:
  template-engine:
    source-root: ${TEMPLATE_ENGINE_SOURCE_ROOT:../../../template-source}

    principals:
      - principal-id: local-full
        principal-type: XCODE_AGENT
        token-sha256: ${TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256}
        scopes:
          - template.plan
          - template.generate
          - template.update

      - principal-id: local-plan
        principal-type: XCODE_AGENT
        token-sha256: ${TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256}
        scopes:
          - template.plan
```

原则：

```text
配置结构可提交
真实 secret 不提交
```

开发者本地通过环境变量注入。

例如：

```bash
export TEMPLATE_ENGINE_SOURCE_ROOT=/absolute/path/template-source
export TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256=...
```

---

## 8. Service 启动方式

推荐统一为：

```bash
mvn -f template-engine/pom.xml -pl engine-service -am package

java -jar template-engine/engine-service/target/engine-service-*.jar \
  --spring.config.additional-location=file:template-engine/engine-service/config/application-local.yml
```

这样工程中不再出现：

```text
STAGE3_SOURCE_ROOT
validation/stage3
stage3-full
stage3-plan-only
```

等阶段性命名。

---

# 第四章 Shell 验证脚本收敛

## 9. `validation/verify-stage2.sh`

处理：

```text
DELETE
```

原因：

当前脚本本质是：

```text
mvn -Pstage2-verification verify
+
verify-base-frontend.sh
```

`Stage2` 已不应作为稳定架构概念存在。

同时检查：

```text
template-engine/pom.xml
```

中的：

```text
stage2-verification
```

Profile。

如果该 Profile 仅服务于历史 Stage2 流程，应一并删除。

最终标准测试入口统一为：

```bash
mvn test
```

或：

```bash
mvn verify
```

---

## 10. `validation/verify-stage3-http.sh`

处理：

```text
DELETE
```

原因：

当前脚本执行：

```text
package engine-service
→ java -jar
→ curl /v1/generate
→ curl /v1/update
→ 检查 ZIP
```

其中主要协议能力已经由：

```text
EngineServiceIT
```

覆盖：

- Authentication；
- Generate；
- Update；
- No Change；
- TemplateState；
- Package Contract；
- ZIP 内容；
- Determinism；
- Capability Combination。

因此继续保留 Shell HTTP Stage3 测试，会造成两套重复验证。

---

## 11. 是否保留 JAR Startup Smoke Test

`verify-stage3-http.sh` 唯一比 MockMvc / SpringBootTest 多验证的一点是：

```text
真正打包后的 executable JAR
可以通过 java -jar 启动
```

该能力如果认为有必要，可以在 CI 中单独保留一个极简 Startup Smoke Test。

例如：

```text
package
→ java -jar
→ wait for startup
→ health / lightweight endpoint
→ kill process
```

但它应该：

```text
属于 CI
```

而不是重新引入：

```text
validation/stage3
```

本次默认建议：

> 如果现有发布流水线已有启动验证，则不额外保留；否则新增一个独立 CI startup smoke job。

---

# 第五章 保留并迁移的 CI 能力

## 12. `verify-template-release-revision.sh`

该脚本不能删除。

它已经承担长期有效的 Template Release Governance：

```text
managed Template Source changed
AND
template-revision.txt unchanged
→ FAIL
```

因此迁移：

```text
FROM
validation/verify-template-release-revision.sh

TO
scripts/ci/verify-template-release-revision.sh
```

---

## 13. Revision CI Gate 后续加强

迁移后按 Template Engine Digest V3 方案继续加强。

### 13.1 Managed Scope

当前 Managed Scope 应明确维护为：

```text
template-source/base
template-source/capabilities
template-source/strategy-registry-v2.yaml
```

后续如果 Loader 增加新的 Release 输入，需要同步增加。

禁止简单写成：

```text
template-source/**
```

避免 README / docs 等非运行时内容变化强制升级 revision。

---

### 13.2 Revision 单调性

在原：

```text
managed input changed
→ revision must change
```

基础上增加：

```text
newRevision > baseRevision
```

对于：

```text
YYYY.MM.DD.N
```

版本，应禁止：

```text
newRevision == baseRevision
newRevision < baseRevision
历史 revision 重用
```

合法回滚方式：

```text
历史内容
+
新的更高 revision
```

---

# 第六章 Base Frontend 验证迁移

## 14. `verify-base-frontend.sh`

当前脚本验证：

```text
template-source/base/frontend
→ pnpm install --frozen-lockfile
→ pnpm run build
```

该能力有长期价值。

因此不建议删除验证本身，只迁移：

```text
FROM
validation/verify-base-frontend.sh

TO
scripts/ci/verify-base-frontend.sh
```

或者直接内联 CI Workflow。

推荐优先保留独立脚本，方便：

```text
本地运行
CI 复用
```

---

# 第七章 测试职责重新划分

## 15. Core Test

所有 Core 测试资源：

```text
template-engine/engine-core/src/test/resources
```

所有 Core 测试代码：

```text
template-engine/engine-core/src/test/java
```

不再允许 Core Test 从：

```text
repositoryRoot()/validation
```

读取资源。

---

## 16. Service Test

Service 测试：

```text
template-engine/engine-service/src/test/java
```

测试配置：

```text
template-engine/engine-service/src/test/resources
```

`EngineServiceIT` 继续负责：

```text
Authentication
Generate
Update
No Change
Release Refresh
Package Contract
Determinism
```

测试配置优先使用：

```text
@DynamicPropertySource
```

或：

```text
application-test.yml
```

不得依赖：

```text
validation/stage3/application.yml
```

---

# 第八章 与跨仓库边界的关系

## 17. Template Engine 单仓验收

本仓只负责：

```text
Core
Service
Package Contract
Template Release
Authoring
Authentication
```

不因为删除 `validation/` 而增加：

```text
Workspace Apply
Workspace abstraction
State Persist
XCodeAgent runtime
```

---

## 18. 跨仓库 E2E

以下仍由 XCodeAgent / 调用方负责：

```text
Template Engine Package
→ Consumer Parse
→ Workspace Apply
→ Consumer State Persist
→ 后续 Reconcile
```

该能力属于跨仓库发布门槛，不放回 `validation/`。

---

# 第九章 实施步骤

## 19. 步骤 1：建立新目录

新增：

```text
scripts/ci/
template-engine/engine-service/config/
```

按实际需要补：

```text
*/src/test/resources/
```

---

## 20. 步骤 2：迁移 Release CI Gate

移动：

```text
validation/verify-template-release-revision.sh
→
scripts/ci/verify-template-release-revision.sh
```

同步修改：

```text
CI workflow
docs/REFACTOR.md
README / developer commands
```

---

## 21. 步骤 3：迁移 Base Frontend CI

移动：

```text
validation/verify-base-frontend.sh
→
scripts/ci/verify-base-frontend.sh
```

同步所有调用路径。

---

## 22. 步骤 4：迁移启动配置

将：

```text
validation/stage3/application.yml
```

重构为：

```text
template-engine/engine-service/config/application-local.yml
```

删除所有：

```text
STAGE3_*
stage3-full
stage3-plan-only
```

阶段性名称。

真实 token digest 改为环境变量注入。

---

## 23. 步骤 5：删除 Stage3 Fixture

删除：

```text
validation/stage3/requested-authorization.json
validation/stage3/requested-login.json
```

---

## 24. 步骤 6：删除 Stage2 / Stage3 Shell 验证

删除：

```text
validation/verify-stage2.sh
validation/verify-stage3-http.sh
```

如 Stage2 Maven Profile 已无独立价值，同时删除：

```text
stage2-verification
```

---

## 25. 步骤 7：清理 Fixture

删除：

```text
template-state-v2-golden.sha256
```

结合 Digest 改造删除：

```text
StateDigestGoldenTest
```

检查：

```text
template-state-v2-golden.json
authorization.yaml
```

如果无引用直接删除；有引用则迁至对应 Module 的：

```text
src/test/resources
```

---

## 26. 步骤 8：删除 `validation/`

确认目录为空后：

```text
DELETE validation/
```

---

## 27. 步骤 9：清理所有历史引用

全仓搜索：

```bash
grep -Rni \
  --exclude-dir=.git \
  --exclude-dir=target \
  -E 'validation/|stage2|stage3|STAGE3_' .
```

逐项检查。

目标：

```text
无仍依赖旧 validation 路径的运行或测试逻辑
```

注意：

文档中如果只是描述历史阶段，也建议同步清理，避免误导。

---

# 第十章 验收标准

改造完成后必须满足：

1. 仓库顶层不存在 `validation/`；
2. `template-engine` 所有测试都可通过正常 Maven 生命周期执行；
3. 测试不再从仓库顶层路径读取 Fixture；
4. `EngineServiceIT` 不依赖 `validation/stage3/application.yml`；
5. Service 有明确、独立的本地启动配置；
6. 启动配置不提交真实 Token；
7. Template Release Revision CI Gate 仍然存在并正常执行；
8. Managed Scope 仍能检测受管 Template Source 改动；
9. Revision 单调性规则生效；
10. Base Frontend Build 验证仍保留；
11. `verify-stage2.sh`、`verify-stage3-http.sh` 被删除；
12. `stage2-verification` Profile 如无其他用途被删除；
13. 不新增 Workspace Apply / State Persist 到 Template Engine；
14. `mvn test` / `mvn verify` 成为 Template Engine 标准测试入口；
15. CI 不再依赖任何 `validation/...` 路径。

---

# 第十一章 Codex 实施约束

```text
1. 目标是删除 validation/ 这个阶段性聚合目录，不是删除所有验证能力。

2. 所有仍有长期价值的验证能力必须先迁移，再删除旧文件。

3. verify-template-release-revision.sh 必须保留能力并迁到 scripts/ci/。

4. verify-base-frontend.sh 必须保留能力并迁到 scripts/ci/ 或 CI Workflow。

5. verify-stage2.sh 删除。

6. verify-stage3-http.sh 删除；不得为了保留它继续维护 Stage3 概念。

7. EngineServiceIT 继续作为 Service 协议集成测试主入口。

8. executable JAR startup 如果仍需要验证，应作为独立 CI Smoke Test，
   不得重新建立 validation/stage3。

9. validation/stage3/application.yml 不能原样留下。
   必须重构成 engine-service 自己管理的本地启动配置。

10. 启动配置中的 token-sha256 真实值不得硬编码；
    使用环境变量 / Secret 注入。

11. fixture 必须迁到真正消费它的 module/src/test/resources。

12. StateDigest Golden Fixture 随 StateDigest 改造删除。

13. 删除 stage2-verification Maven Profile 前检查是否还有其他调用方。

14. 所有 CI、文档、脚本路径必须同步修改。

15. 最终全仓不得存在对 validation/ 的有效代码、测试或 CI 引用。

16. 本次不得新增 Workspace / State Persist 能力到 template-engine。
```

---

# 第十二章 最终结论

本次改造的目标不是：

```text
validation/ → 只剩 application.yml
```

而应该是：

```text
validation/
    → 完全删除
```

其原有职责分别回归：

```text
Fixture
→ module test resources

Unit / Integration Test
→ module test code

Service Local Config
→ engine-service/config

Release Governance
→ scripts/ci

Template Source Build Check
→ scripts/ci

Stage2 / Stage3 temporary validation
→ 删除
```

最终 Template Engine 工程只保留长期稳定的正式能力，不再暴露重构阶段的 Stage2 / Stage3 验证脚手架。
