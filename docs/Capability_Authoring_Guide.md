# Capability Authoring 指引：向模板注入能力代码

本文面向需要向模板工程新增前端页面、后端接口、菜单注册或初始化脚本的开发者。它只说明如何将业务代码编译为 Template Capability；不涉及 Template Runtime 的修改。

## 核心边界

Capability Compiler 是离线 Template Source 生产工具。正式产物永远是 `template-source/`；Engine Core 与 Engine Service 不读取 `.workbench/`，也不保存 Authoring 状态。

开发者只写业务代码，不要手工维护：

```text
capability-v2.yaml
strategy-registry-v2.yaml
additionId、strategyId、managedMarker
```

这些由 Compiler 从受限的代码差异自动生成。不能解释的差异会失败，不存在 raw patch 回退。

## 日常流程

在仓库根目录执行：

```bash
# 创建基础工作区
./capability init excel-export
# 创建工作区；该能力依赖 login
./capability init excel-export --requires login

# 仅编辑 project
cd .workbench/excel-export/project
# 编写 React / Spring Boot 业务代码
cd ../../..

# 可选：查看差异如何被理解
./capability status excel-export

# 编译、验证并发布 Draft
./capability build excel-export

# 审阅受管结果后提交
git diff -- template-source
git add template-source
git commit -m "feat: add excel-export capability"
```

主流程是：

```text
init → 写代码 → status（可选）→ build → git diff → commit
```

`capture`、`compile`、`verify` 仅供维护者排查问题，不是普通开发流程。

## Workbench

`init` 创建：

```text
.workbench/<capabilityId>/
├── baseline/             # Base + requires，只读
├── project/              # 开发者唯一可编辑目录
├── authoring.yaml        # requires 和显式 migration 声明
└── compile-state.yaml    # 首次成功 build 后生成，本地所有权记录
```

`baseline` 是 `Base + requires` 的完整生成结果；`project` 是其可编辑副本。不要编辑 `baseline`，也不要提交 `.workbench/`。

ID 必须匹配：

```text
[a-z0-9][a-z0-9-]*
```

有效示例：`excel-export`、`audit-log-v2`。依赖必须已经存在且不得构成循环：

```bash
./capability init excel-export --requires login,authorization
```

## 可被 Compiler 识别的改动

V1 只允许三类差异：

| 代码改动 | Compiler 产物 | 典型用途 |
| --- | --- | --- |
| 新增文件 | Addition，`NO_OP` | 页面、组件、Controller、Service、DTO |
| 在注册 Surface 新增 import | `ENSURE_IMPORT` | 引入页面、Provider、Bean |
| 在注册 Anchor 前插入连续代码块 | `TEXT_ANCHOR_INSERT` | 路由、菜单、Provider、拦截器注册 |

Anchor 行是不可变的稳定边界。Compiler 会忽略新增块紧邻 Anchor 的空行和空白格式差异，并保留新增代码块自身的格式；Anchor 本身、其后的 Base 内容，以及 Anchor 前既有的非空白 Base 内容仍必须保持不变。

### 新增文件

直接在 `project/` 下新增，例如：

```text
.workbench/excel-export/project/frontend/src/pages/ExcelExport/index.tsx
```

该文件会被编译为 Capability Addition。

### 注册页面或服务

新增文件通常还需要在已注册的 Surface 中添加 import，并在对应 Anchor 前加入注册代码。可用 Surface 和 Anchor 由 `template-source/strategy-registry-v2.yaml` 定义。

例如：

```tsx
import ExcelExport from '@/pages/ExcelExport';

{
  path: '/excel-export',
  element: <ExcelExport />,
},
// xcodeagent:page-routes
```

运行 `status` 后应出现一个 Import 和一个 Anchor insert。不要自行书写或改动 `managedMarker`；它由 Compiler 根据 Capability、Target、Anchor 自动生成。

## 不支持的改动

以下差异会使 `status` 输出 `BLOCKED`，并使 `build` 失败：

```text
删除 Base 文件
修改未注册为 Surface 的 Base 文件
删除或改写既有 import
删除或改写既有 Anchor
重命名或移动既有文件
无法归并为单个 Anchor insertion block 的交错修改
```

这不是可绕过的限制，而是保证模板可重复生成的契约。若确实缺少扩展点，应先由模板维护者修改 Base 与 Registry，建立稳定 Target/Anchor，再开发 Capability。不要在 Service 中按 Capability ID 加分支，也不要使用 raw patch。

## status：先理解差异

```bash
./capability status excel-export
```

成功示例：

```text
Capability: excel-export

Detected:
  Additions            3
  Imports              1
  Anchor inserts       1

Unsupported:
  0

Status: READY
```

失败时会给出路径与原因：

```text
Unsupported:
  frontend/src/utils/common.ts
  Reason: MODIFY_NON_SURFACE

Status: BLOCKED
```

`status` 是只读操作：不会修改 `template-source/`、Workbench 或 Registry。

## build：事务性发布 Draft

```bash
./capability build excel-export
```

固定过程：

```text
Diff → Analyze → Unsupported Gate → Contract Plan
→ Compile 到 staging Template Source
→ Draft Validation → Round-trip Verify
→ 原子替换正式 template-source
```

任何一步失败，staging 会被丢弃，正式 `template-source/` 保持 build 前状态。成功后必须审阅：

```bash
git diff -- template-source
```

## build 后：生成 ZIP 的端到端验收

`build` 验证的是 Capability Draft 能否被编译并发布到 `template-source/`；它不替代
Engine Service 的加载、HTTP 响应和 ZIP 打包验收。提交前应以刚刚 build 的
`template-source/` 启动本地 Service，并请求一次 `/v1/generate`。以下以
`excel-export` 为例；其他 Capability 只需替换请求中的 ID 和后续断言的文件、代码片段。

先确保 Service JAR 已构建，然后在终端一启动它：

```bash
mvn -f template-engine/pom.xml -pl engine-service -am package

export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"

java -jar template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```

保持该进程运行。在终端二执行下面的请求；临时目录仅保存本次验收产物，可在验收后手工删除：

```bash
validation_dir="$(mktemp -d)"

curl --fail --silent --show-error \
  --dump-header "$validation_dir/headers.txt" \
  --output "$validation_dir/project.zip" \
  --header 'Content-Type: application/json' \
  --request POST http://127.0.0.1:18080/v1/generate \
  --data @- <<'JSON'
{
  "requestedConfig": {
    "capabilities": {
      "excel-export": {
        "enabled": true,
        "config": {}
      }
    }
  }
}
JSON
```

预期响应为 HTTP `200` 且 `Content-Type` 为 `application/zip`。检查响应、ZIP 完整性及
Capability 生成结果：

```bash
rg -i '^HTTP/.* 200|^content-type: application/zip' "$validation_dir/headers.txt"
unzip -t "$validation_dir/project.zip"

# 新增文件、注册 Surface 与调用方持有的 State 都必须存在。
unzip -Z1 "$validation_dir/project.zip" | rg -x \
  'frontend/src/pages/Home/index.tsx|frontend/src/capability-extensions/routes.tsx|\.xcodeagent/template-state\.json'
unzip -p "$validation_dir/project.zip" frontend/src/capability-extensions/routes.tsx | \
  rg -F "import Home from '@/pages/Home';"
unzip -p "$validation_dir/project.zip" frontend/src/capability-extensions/routes.tsx | \
  rg -F "pageId: 'home'"
unzip -p "$validation_dir/project.zip" .xcodeagent/template-state.json | \
  rg -F '"excel-export"'
```

上述断言验证 Service 实际加载了新 Release，并将 Addition、Anchor 注入和 State 打入 ZIP；
它们不是对生成工程可编译性的充分证明。若要整体确认最终代码可用，解压后在生成工程内执行
前后端构建（依赖安装可能访问本地缓存或依赖仓库）：

```bash
unzip -q "$validation_dir/project.zip" -d "$validation_dir/project"
(cd "$validation_dir/project/frontend" && pnpm install --frozen-lockfile && pnpm build)
(cd "$validation_dir/project/backend" && mvn test)
```

任何一步失败时，不应将其视为 Capability 已验收：先保留 `$validation_dir` 中的 ZIP、响应头和
构建输出，分别判断是 Draft 内容、Template Release、Service 配置还是生成工程依赖的问题。

## 反复 build 与所有权

首次成功 build 后，Workbench 的 `compile-state.yaml` 会记录其生成的 capability、strategy 与 validator ID。后续 build 仅替换这批可验证属于当前 Workbench 的 Draft 产物。

下列情况会以 `CAPABILITY_OWNERSHIP_CONFLICT` fail-closed：

- 已存在同名 Capability，但没有当前 Workbench 的 state；
- state 的 Capability ID 不匹配；
- state 记录的 strategy / validator 缺失、重复或与 Registry 不一致；
- 现有 Capability 不属于当前 Workbench。

不要手工删除 Registry 条目或伪造 state。已发布 Capability 不允许被本地 Draft 静默覆盖。

## Migration

Migration 不能根据文件名自动推断；必须在 `authoring.yaml` 显式声明：

```yaml
migrations:
  - id: schema
    source: db/schema.sql
    bootstrapConsumerPath: backend/db/schema.sql
    executionTrigger: AUTHORIZATION_BOOTSTRAP_DDL
```

规则：

- `source` 必须是 `project/` 下存在的普通文件；
- 如填写 `target`，它必须与 `bootstrapConsumerPath` 相同；
- Compiler 只生成 Migration Contract，**不会执行 SQL**；
- 违反规则时返回 `MIGRATION_CONTRACT_INVALID`。

## 常见错误

| 错误 | 应对方式 |
| --- | --- |
| `CAPABILITY_CAPTURE_UNSUPPORTED` | 先执行 `status`，根据首个 path/reason 将改动改为 Addition、Import 或单一 Anchor Insert。 |
| `ROUND_TRIP_MISMATCH` | 根据首个不一致文件检查遗漏的新文件、错误 Surface 或不正确的 Anchor 位置。 |
| `CAPABILITY_OWNERSHIP_CONFLICT` | 停止修改正式模板，使用原 Workbench 或由发布维护者处理所有权。 |
| `CAPABILITY_ALREADY_EXISTS` | 多见于高级 `compile`；日常流程请使用 `build`。 |

## 提交前检查

```bash
./capability status <capabilityId>
./capability build <capabilityId>
git diff -- template-source
mvn -f template-engine/pom.xml -pl capability-authoring -am test
mvn -f template-engine/pom.xml verify
./scripts/ci/verify-base-frontend.sh
git diff --check
```

提交受管内容通常是：

```text
template-source/capabilities/<capabilityId>/**
template-source/strategy-registry-v2.yaml
该能力确实需要的 Base / Surface 契约变化
```

不得提交 `.workbench/`、`compile-state.yaml` 或本机依赖目录。

## 高级命令

```bash
./capability capture <capabilityId>  # 仅 Diff + Analyze
./capability compile <capabilityId>  # 仅编译
./capability verify <capabilityId>   # 单独 Draft + Round-trip 验证
```

它们用于定位框架或模板契约问题，不能替代 `build`。
