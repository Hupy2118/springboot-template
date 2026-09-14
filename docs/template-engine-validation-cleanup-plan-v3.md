# `validation/` 目录收敛改造方案（V3）

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
    source-root: ${TEMPLATE_ENGINE_SOURCE_ROOT}

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

`TEMPLATE_ENGINE_SOURCE_ROOT` 必须显式设置为绝对路径，不提供类似 `../../../template-source` 的默认值。原因是 Java/Spring 对相对文件路径的解析依赖进程启动目录（`user.dir`），而不是配置文件所在目录，在仓库根目录、IDE、CI 或 `engine-service/` 下启动时可能解析到不同位置。

例如：

```bash
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
export TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256=...
```

如果未设置 `TEMPLATE_ENGINE_SOURCE_ROOT`，Service 应直接启动失败并提示配置缺失，而不是静默使用相对路径。

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

当前 `engine-service/pom.xml` 的 Surefire 已显式包含：

```xml
<include>**/*Test.java</include>
<include>**/*IT.java</include>
```

因此 `EngineServiceIT` 已经进入正常 Maven 测试生命周期，不依赖 `stage2-verification` Profile。

所以该 Profile 的处理应明确为：

> 在确认 CI、外部脚本和开发文档不存在 `-Pstage2-verification` 调用后，直接删除。

删除前执行：

```bash
grep -Rni   --exclude-dir=.git   --exclude-dir=target   'stage2-verification' .
```

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

### 13.1 Managed Scope：不得再使用目录级 Glob

原有写法：

```text
template-source/base
template-source/capabilities
template-source/strategy-registry-v2.yaml
```

仍然过宽，因为：

```text
git diff -- template-source/base
```

会把该目录下所有 README、docs、开发辅助文件都视为 Release 输入。

同时，也不能简单通过：

```text
:(exclude)**/README.md
:(exclude)**/docs/**
```

全局排除文档文件，因为是否属于 Release 输入不能由文件扩展名或目录名决定，而应该由 **Runtime 实际消费关系** 决定。

当前代码的事实是：

```text
V2ProjectGenerator
    → 读取 template-source/base/base.yaml
    → 只 materialize base.yaml.files[].source

StrategyRegistryLoader
    → 读取 strategy-registry-v2.yaml
    → 读取其中 targets 指向的 Base 文件

CapabilityV2Loader
    → 读取 capabilities/*/capability-v2.yaml
    → 读取 additions[].source
    → 读取 migrations[].source
    → 读取 migration consumer 校验所需文件
```

因此 Managed Scope 应定义为“实际 Release 输入集合”，而不是目录。

#### A. 固定入口文件

始终纳入：

```text
template-source/base/base.yaml
template-source/strategy-registry-v2.yaml
template-source/template-revision.txt   # 仅用于 revision 比较，不作为 managed change 本身
```

`release-digests.yaml` 不再属于 Runtime Release Identity，因此不纳入 Managed Scope。

#### B. Base Runtime 输入

纳入：

```text
base/base.yaml -> files[].source
```

引用的所有文件。

注意：

当前 `base.yaml` 明确包含：

```text
backend/README.md
backend/docs/project-structure.md
frontend/README.md
frontend/docs/project-structure.md
```

这些文件目前会进入 `/v1/generate` 的最终项目，因此在**不修改 template-source/base/base.yaml 的前提下，它们当前必须视为 Release 输入**。

也就是说：

> “README/docs 默认不触发 revision”这一目标在当前 Template Source Contract 下并不成立。

如果未来明确希望文档变化不触发 revision，必须先改变 Template Source Contract，例如：

```text
从 base.yaml.files 中移除对应 README/docs
```

使它们不再进入生成项目；之后 CI 才可以自然忽略，而不是单独写排除规则。

#### C. Capability Runtime 输入

对每个：

```text
template-source/capabilities/<capabilityId>/capability-v2.yaml
```

纳入：

```text
capability-v2.yaml 本身
additions[].source
migrations[].source
```

以及 Loader 明确读取的其他校验输入。

当前如果存在类似：

```text
AuthorizationBootstrapCommand.java
```

这类由 Loader 为 migration consumer contract 显式读取的文件，也必须纳入。

#### D. Strategy Runtime 输入

纳入：

```text
template-source/strategy-registry-v2.yaml
```

以及 registry 中：

```text
targets[].path
```

所引用的 Base 文件。

这些文件通常已经包含于 `base.yaml.files`，但 CI 规则不得依赖“碰巧重合”；应按 Runtime Contract 构造集合后去重。

---

### 13.2 Managed Scope 的推荐实现

不建议继续维护一长串 Shell glob。

推荐新增一个轻量脚本：

```text
scripts/ci/list-template-release-inputs.py
```

职责：

```text
读取 base/base.yaml
读取 strategy-registry-v2.yaml
遍历 capabilities/*/capability-v2.yaml
解析其中 source / target / migration contract
输出排序后的仓库相对路径
```

输出示例：

```text
template-source/base/base.yaml
template-source/base/backend/pom.xml
template-source/base/backend/src/main/...
template-source/base/frontend/package.json
template-source/capabilities/authorization/capability-v2.yaml
template-source/capabilities/authorization/backend/...
template-source/strategy-registry-v2.yaml
...
```

然后 Revision Gate 使用：

```text
git diff --name-only <base>...HEAD
        ↓
与 list-template-release-inputs.py 输出集合求交集
        ↓
managed_changes
```

只有：

```text
managed_changes != empty
```

时才要求 revision bump。

这样可以同时解决两个问题：

1. 不会因为目录内无关文件变化误触发；
2. 新增 Runtime 引用时，只需要让 Manifest / Registry 成为事实来源，不再手工维护第二套 CI 路径规则。

#### 新文件的特殊处理

如果新增：

```text
capabilities/<new-id>/capability-v2.yaml
```

或修改 Manifest 使其引用新的 source 文件，新文件本身也必须被识别为 Managed Input。

因此脚本计算集合时应基于：

```text
HEAD
```

的 Template Source Contract。

对于删除的 Runtime 输入，还应同时基于：

```text
base ref
```

计算一次 Managed Input 集合。

最终比较集合使用：

```text
managed_inputs(base) ∪ managed_inputs(HEAD)
```

否则“删除一个原先受管的文件/引用”可能漏检。

---

### 13.3 Revision 单调性：落成可执行规则

Revision 格式固定为：

```text
YYYY.MM.DD.N
```

例如：

```text
2026.09.14.1
2026.09.14.2
2026.09.15.1
```

#### 版本格式规则

必须同时满足：

```text
YYYY = 4 位十进制数字
MM   = 2 位十进制数字，01-12
DD   = 2 位十进制数字，并且是 YYYY-MM 下合法日期
N    = 十进制正整数，>= 1，不允许前导 +
```

非法示例：

```text
2026.9.14.1
2026.09.31.1
2026.09.14.0
2026.09.14.-1
v2026.09.14.1
2026.09.14.01   # 建议禁止，避免同值多表示
```

#### 比较规则

解析成四元组：

```text
(year, month, day, sequence)
```

按数值字典序比较：

```text
new > base
```

即：

```text
year 大于 → new 较新
year 相同，month 大于 → new 较新
年月相同，day 大于 → new 较新
年月日相同，sequence 大于 → new 较新
其他情况 → 非递增
```

禁止：

```text
new == base
new < base
历史 revision 重用
```

合法回滚必须：

```text
恢复历史 Template Source 内容
+
发布新的更高 revision
```

例如：

```text
当前 2026.09.14.3
需要回滚到 2026.09.13.2 的内容
→ 发布为 2026.09.14.4 或后续更高版本
```

---

### 13.4 Revision Gate 的可执行实现

建议把 revision 解析与比较从复杂 Shell 字符串判断中独立出来。

推荐新增：

```text
scripts/ci/verify-template-release-revision.py
```

Shell 文件如果需要兼容现有 CI，可以仅作为薄 wrapper。

伪代码：

```python
REVISION_RE = r"^(\d{4})\.(\d{2})\.(\d{2})\.([1-9]\d*)$"

def parse_revision(value):
    match = fullmatch(REVISION_RE, value.strip())
    if not match:
        fail("invalid template revision format")

    year, month, day, sequence = map(int, match.groups())

    # 同时校验真实日历日期，例如拒绝 2026.02.30
    datetime.date(year, month, day)

    return year, month, day, sequence
```

Revision 来源：

```text
baseRevision =
    git show <base-ref>:template-source/template-revision.txt

headRevision =
    当前工作树 / HEAD 的 template-source/template-revision.txt
```

执行逻辑固定为：

```text
1. parse(baseRevision)
2. parse(headRevision)
3. 计算 managed_changes
4. 如果 managed_changes 为空：
       不强制 revision 变化
5. 如果 managed_changes 非空：
       要求 headRevision > baseRevision
6. 否则 CI FAIL
```

注意：

```text
managed_changes 非空 + revision 只是“不相等”
```

不再足够。

必须是：

```text
headRevision > baseRevision
```

#### 是否在“无 Managed Change”时禁止 revision 单独增长

默认建议：

```text
允许
```

即允许只 bump revision 的提交。

原因：

- 可能用于重新发布；
- 可能用于版本治理修复；
- 不需要 CI 猜测发布意图。

如果团队希望禁止空 bump，可额外增加规则，但不属于本次必须项。

#### 跨日 Sequence 规则

不要求：

```text
新日期时 N 必须重新从 1 开始
```

只要求四元组严格递增。

例如：

```text
2026.09.14.8
→ 2026.09.15.3
```

合法。

这样避免把 CI 变成“版本号生成器”，只负责验证单调性。

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

迁移后同样必须把仓库根目录解析调整为：

```sh
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
```

脚本内部所有仓库路径都必须基于 `$root` 计算，不能依赖调用方当前工作目录。

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

移动后必须同步修改脚本中的仓库根目录解析。原脚本位于 `validation/` 时使用 `..` 可以回到仓库根；迁移到 `scripts/ci/` 后必须改成：

```sh
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
```

否则 `root` 只会指向 `<repo>/scripts`。

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

并固定：

```sh
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
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

其中：

```yaml
source-root: ${TEMPLATE_ENGINE_SOURCE_ROOT}
```

必须由环境变量显式注入绝对路径，例如：

```bash
export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
```

禁止继续使用 `../../../template-source` 一类依赖 Java 当前工作目录的默认相对路径。

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

由于 `EngineServiceIT` 已由 Surefire 的 `**/*IT.java` include 纳入正常 Maven 生命周期，`stage2-verification` Profile 不再承担独立测试入口职责。

删除前仅需确认：

```text
CI workflow
外部脚本
开发文档
```

没有继续调用：

```text
-Pstage2-verification
```

确认无调用后，直接删除该 Profile。

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
8. Managed Scope 由 Runtime Manifest / Registry 推导，不再使用 `template-source/base`、`template-source/capabilities` 目录级 glob；
9. Base Managed Inputs 以 `base/base.yaml -> files[].source` 为准；
10. Capability Managed Inputs 以 `capability-v2.yaml` 及其实际 source 引用为准；
11. 删除 Runtime Input 也能通过 base/head Managed Input 并集被识别；
12. Revision 严格按 `YYYY.MM.DD.N` 解析，并校验真实日历日期；
13. Managed Input 发生变化时必须满足 `headRevision > baseRevision`；
10. Base Frontend Build 验证仍保留；
11. `verify-stage2.sh`、`verify-stage3-http.sh` 被删除；
12. `stage2-verification` Profile 如无其他用途被删除；
13. 不新增 Workspace Apply / State Persist 到 Template Engine；
14. `mvn test` / `mvn verify` 成为 Template Engine 标准测试入口；
15. CI 不再依赖任何 `validation/...` 路径；
16. 所有迁移到 `scripts/ci/` 的脚本从任意当前工作目录执行都能正确定位仓库根；
17. `application-local.yml` 不包含 `../../../template-source` 等相对 `source-root` 默认值；
18. `TEMPLATE_ENGINE_SOURCE_ROOT` 必须使用显式绝对路径；
19. 全仓确认不存在 `-Pstage2-verification` 外部调用后删除该 Profile；
20. `EngineServiceIT` 仍通过正常 `mvn test` / `mvn verify` 执行。

---


## 28.1 实施前必须确认的两个契约事实

### README / docs 是否应触发 Revision

当前不能把它们机械排除。

因为 `base/base.yaml` 当前明确把部分 README/docs 列为生成文件，所以它们属于 `/v1/generate` 的输出组成部分。

因此本次规则是：

```text
是否触发 revision
=
是否属于 Runtime Managed Input
```

而不是：

```text
是不是 README/docs
```

如果后续产品决策希望 README/docs 完全不影响 Template Release，则应另行修改：

```text
template-source/base/base.yaml
```

把这些文件移出生成契约；之后 CI 自动不再把它们视为 Managed Input。

### Managed Scope 的唯一事实源

不允许同时维护：

```text
Loader/Generator 一套路径
+
CI 手工白名单另一套路径
```

CI 必须尽可能从：

```text
base.yaml
strategy-registry-v2.yaml
capability-v2.yaml
```

推导 Runtime 输入，避免长期漂移。

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

17. 所有迁移到 `scripts/ci/` 的 Shell 脚本必须使用 `../..` 回到仓库根，不能沿用原 `validation/` 下的 `..`。

18. CI 脚本必须与调用方当前工作目录无关，所有仓库内路径都基于脚本计算出的 `$root`。

19. `TEMPLATE_ENGINE_SOURCE_ROOT` 必须显式传入绝对路径；禁止使用 `../../../template-source` 一类默认值。

20. `stage2-verification` Profile 删除前只检查 CI / 外部脚本 / 文档引用；确认无引用后直接删除，因为 `EngineServiceIT` 已由 Surefire 正常执行。

21. Revision Gate 不允许只判断“revision 是否变化”；Managed Input 有变化时必须严格满足 `headRevision > baseRevision`。

22. Revision 格式固定为 `YYYY.MM.DD.N`，必须校验格式、真实日历日期和 N >= 1。

23. Managed Scope 不允许继续使用 `template-source/base` 或 `template-source/capabilities` 整目录 glob。

24. Managed Scope 必须从 Runtime Contract 推导：`base.yaml`、`strategy-registry-v2.yaml`、`capability-v2.yaml` 及其实际 source 引用。

25. 计算 Managed Scope 时必须取 base ref 与 HEAD 两侧输入集合的并集，避免删除受管文件时漏检。

26. 不允许仅按文件名排除所有 README/docs；当前 `base.yaml` 中被列入 files 的 README/docs 仍属于 Release 输入。

27. 如果产品明确要求 README/docs 不触发 revision，必须先把它们从 Template Source 的生成契约移除，再调整 CI；不得只改 CI 绕过 Runtime Contract。
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


---

# 附录：Revision Gate 最终判定矩阵

| Managed Runtime Input | Revision | 结果 |
|---|---|---|
| 无变化 | 不变 | PASS |
| 无变化 | 增大且格式合法 | PASS |
| 有变化 | 不变 | FAIL |
| 有变化 | 变小 | FAIL |
| 有变化 | 格式非法 | FAIL |
| 有变化 | 增大 | PASS |

核心规则最终收敛为：

```text
Runtime Contract
    ↓
Managed Input Set(base ∪ HEAD)
    ↓
git diff
    ↓
managed_changes ?

NO  → revision 可保持或合法增大

YES → parse YYYY.MM.DD.N
       ↓
       headRevision > baseRevision
       ↓
       PASS
```
