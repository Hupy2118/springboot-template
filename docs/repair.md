# Template Capability 双项目修复实施计划

## 一、共同前提：冻结双端接口协议

本次修复过程中，XCodeAgent 与 springboot-template 必须共同遵守以下协议，**不得自行增加、删除或重命名字段**。

### 1. `/v1/generate`

请求固定为：

```json
{
  "requestedConfig": {
    "capabilities": {}
  }
}
```

本次不调整接口结构。

---

### 2. `/v1/update`

请求固定为：

```json
{
  "protocolVersion": "2",
  "currentTemplateState": {},
  "requestedConfig": {},
  "mode": "APPLY"
}
```

其中：

```text
mode = APPLY | RECONCILE
```

本次只修正 mode 的选择逻辑，不修改字段。

---

### 3. TemplateStateV2

固定为：

```text
schemaVersion
templateRevision
requested
effective
appliedAdditions
```

`appliedAdditions` 单项固定为：

```text
capabilityId
target
installedRevision
```

不得重新加入 `releaseDigest`、`managedFiles` 等字段。

---

### 4. Strategy Update Package

顶层字段固定为：

```text
protocolVersion
packageId
mode
sourceRevision
currentStateDigest
nextStateDigest
strategies
validationPlan
payloadManifest
nextTemplateState
diagnostics
```

本次不得修改这些顶层字段。

---

### 5. astSelector

双端统一冻结为：

```json
{
  "nodeType": "xxx",
  "position": "before | after | beforeEnd",
  "name": "optional"
}
```

其中：

* `nodeType`：必填
* `position`：必填
* `name`：可选

以 XCodeAgent 当前 Executor 能力为标准，springboot-template 向其对齐。

---

### 6. JSON_STRUCTURE_CHECK

双端统一冻结为：

```json
{
  "type": "JSON_STRUCTURE_CHECK",
  "path": "xxx",
  "pointer": "/xxx",
  "expected": "xxx"
}
```

执行语义固定为：

```text
pointer 存在
且
pointer 对应值 == expected
```

`expected` 作为正式协议字段处理。

---


# 三、springboot-template 项目修复任务

## 任务 1：补齐 Release Publication Gate

### 目的

保证非法 Capability Release 不进入可服务状态，避免用户调用 `/generate` 或 `/update` 时才发现元数据错误。

### 方案

Release 加载阶段统一检查：

```text
Capability dependency 是否存在
dependency 是否循环
Strategy 引用是否存在
Validator 引用是否存在
路径是否合法
Base target 是否存在
anchor 是否唯一
selector 是否唯一
source 是否存在
```

任何问题直接阻止 Release 加载。

### 接口约束

纯内部校验，不修改 HTTP 或 Package 字段。

---

## 任务 2：补齐全部 Strategy 参数门禁

### 目的

保证所有已声明的 Strategy Type 在 Release 加载阶段就完成参数合法性校验。

### 方案

全部 Strategy Type 建立唯一 Contract：

```text
ADD_FILE
TEXT_ANCHOR_INSERT
ENSURE_IMPORT
ENSURE_NPM_DEPENDENCY
ENSURE_MAVEN_DEPENDENCY
ENSURE_REACT_PROVIDER
ENSURE_ROUTE
ENSURE_MENU_ITEM
ENSURE_SPRING_BEAN
ENSURE_INTERCEPTOR
```

每种 Strategy：

* 明确必填字段。
* 明确可选字段。
* 拒绝未知字段。
* 校验 enum。
* 校验 target / marker / selector 等语义。

Loader 与 WireStrategyCompiler 使用相同协议规则，避免一层接受、一层拒绝。

---

## 任务 3：将 astSelector 对齐 XCodeAgent

### 目的

消除当前 Template 与 XCodeAgent 的 selector 参数差异。

### 方案

Template 统一接受：

```text
nodeType：required
position：before / after / beforeEnd
name：optional
```

修改：

```text
Capability Loader
WireStrategyCompiler
相关 Contract Test
```

不得继续限制为：

```text
nodeType + position
position 仅 before / after
```

### 接口约束

严格以共同冻结协议为准，不增加其他 selector DSL。

---

## 任务 4：补齐 JSON_STRUCTURE_CHECK.expected

### 目的

使 Template 生产的 Validation Plan 与 XCodeAgent 实际执行逻辑一致。

### 方案

正式 Contract 调整为：

```text
path
pointer
expected
```

同步修改：

```text
Capability Metadata Loader
ValidatorCompiler
CAPABILITY_POSTCONDITION checks
Wire Package 输出
Contract Test
```

禁止继续生成：

```text
path + pointer
```

但没有 `expected` 的 JSON_STRUCTURE_CHECK。

### 接口约束

这是本次唯一明确增加的 Validation 子字段。

上线时必须与 XCodeAgent 对应版本配套。

---

## 任务 5：增加 Generate Strategy 支持门禁

### 目的

避免 Registry 使用了 Update 支持、但 Generate 尚未支持的 Strategy。

### 方案

Release 加载时检查：

```text
Registry 实际使用的 Strategy Type
        ↓
Generate Materializer 是否支持
```

不支持则 Release 加载失败。

新增 Strategy 必须遵守：

```text
冻结协议
→ Generate 实现
→ Update Executor 实现
→ Contract Test
→ Registry 才允许使用
```

### 接口约束

不修改 Wire Contract。

---

## 任务 6：完善 Migration Gate

### 目的

避免 Migration 只有元数据声明，但实际没有明确执行入口。

### 方案

加载时校验：

```text
source 必须存在
target == bootstrapConsumerPath
migration id 唯一
executionTrigger 必须是已注册 Trigger
Trigger 必须存在明确消费者
```

例如：

```text
AUTHORIZATION_BOOTSTRAP_DDL
```

必须作为正式 Trigger 注册，而不是任意字符串。

### 接口约束

属于 Template 内部 Release Contract，不影响 XCodeAgent HTTP 请求。

---

## 任务 7：增加 templateRevision 不可复用门禁

### 目的

保证相同 `templateRevision` 永远对应相同 Release 内容。

### 方案

发布阶段计算：

```text
release manifest digest
```

维护：

```text
templateRevision
→ release manifest digest
```

如果：

```text
revision 相同 + digest 相同
→ 同一 Release

revision 相同 + digest 不同
→ 拒绝发布
```

### 接口约束

release digest 仅用于发布治理。

**禁止重新加入 TemplateStateV2 或 Update API。**

---

## 任务 8：统一 TEXT_ANCHOR_INSERT Generate 语义

### 目的

保证新应用 Generate 与旧应用增量 Update 最终文件内容一致。

### 方案

Strategy `content` 自身定义完整插入内容。

Java Generate 与 Python Update：

```text
均直接使用 content
```

不允许：

```text
Java 自动补换行
Python 不补换行
```

等双端隐式差异。

并验证：

```text
Pristine Base + Generate
=
Pristine Base + Update
```

### 接口约束

不修改 Strategy 字段，只统一 `content` 的字节语义。

---

## 任务 9：补齐 OpenAPI Error 状态声明

### 目的

使 OpenAPI 与实际 Service 行为一致。

### 方案

除现有：

```text
400
401
403
```

外，补充：

```text
409
500
```

全部使用相同 Error Schema：

```text
code
message
details
traceId
```

### 接口约束

不是新增字段，仅补正文档中的 HTTP 状态定义。

---

# 四、实施顺序

为避免两个项目出现短暂协议不兼容，建议按照以下顺序。

### 第一批：不改变协议的修复

XCodeAgent：

```text
1. APPLY / RECONCILE
2. TEXT_ANCHOR_INSERT
3. Error 透传
```

springboot-template：

```text
1. Release Gate
2. Strategy 参数 Gate
3. Generate 支持 Gate
4. Migration Gate
5. revision 不可复用
```

这些可以相对独立实施。

---

### 第二批：双端协议同步

先冻结：

```text
astSelector
JSON_STRUCTURE_CHECK
```

然后：

```text
springboot-template
    ↓
按冻结协议生成 Package

XCodeAgent
    ↓
按同一协议解析和执行

共同 Contract Fixture
    ↓
验证通过
```

其中 `astSelector` 主要由 Template 向 XCodeAgent 对齐。

`JSON_STRUCTURE_CHECK` 两端共同使用：

```text
path + pointer + expected
```

---

### 第三批：Parity 校验

最后完成：

```text
Generate / Update byte parity
跨项目 Contract Test
Golden Fixture
```

最终形成明确边界：

```text
springboot-template：
负责协议生产、Release 合法性、Generate

XCodeAgent：
负责协议消费、Workspace 增量执行、Validation

双端：
只共享冻结 Wire Contract
不得各自扩展字段
```
