# Route Projection 模板契约化改造方案

> 适用范围  
> - XCodeAgent：`YYYWYF/XCodeAgent`，`dev_agent` 分支  
> - Template：`Hupy2118/springboot-template`，`template_refactor` 分支  
> - 目标：移除 XCodeAgent 中对 `routes.tsx`、`PAGE_ROUTES`、marker、import、React Router 结构等模板实现细节的硬编码；Route Projection 作为一个确定性的 Platform Action 在 Build DAG 中可见，由模板工程负责具体路由投影实现。

---

# 1. 基本契约：XCodeAgent 与模板工程如何协作

## 1.1 当前问题

当前 XCodeAgent 的 `Backend/app/services/route_projection.py` 同时承担两类职责：

1. 从规划产物中识别有哪些业务页面；
2. 理解当前前端模板的具体实现，并直接写入：
   - `frontend/src/constants/routes.tsx`
   - `XCODEAGENT_BUSINESS_ROUTE_IMPORTS_START/END`
   - `XCODEAGENT_BUSINESS_ROUTES_START/END`
   - React Component import
   - `path`
   - `menu`
   - `element`
   - `resourceKey`

这导致 XCodeAgent 与模板实现强耦合。

当前模板已经采用“页面身份驱动”的路由机制：

```text
pageId
  ↓
pageDirectoryFromId(pageId)
  ↓
src/pages/<PageKey>/index.tsx

pageId
  ↓
pageRouteSegmentFromId(pageId)
  ↓
React Router route segment

PAGE_ROUTES
  ↓
createPageRoutes()
  ↓
Router

PAGE_ROUTES
  ↓
createLayoutMenus()
  ↓
Menu
```

因此 XCodeAgent 不应该继续自己理解并渲染 `PAGE_ROUTES` 的具体 TSX 结构。

---

## 1.2 改造目标

改造后职责固定为：

```text
ProductPlan / TechnicalPlan / authorization_manifest
                     │
                     │ 业务事实
                     ▼
                XCodeAgent
                     │
                     │ 判断本次是否需要 Route Projection
                     │ 并在 Build DAG 中显式生成 Platform Action
                     ▼
              Build DAG
                     │
                     ▼
          Template Route Projector
                     │
                     │ 模板实现知识
                     ▼
                  源代码
```

核心原则：

### XCodeAgent 负责 What

XCodeAgent 负责确定：

- 当前应用有哪些业务页面；
- 稳定 `pageId`；
- 页面名称等页面事实；
- TechnicalPlan 中页面与 Endpoint / Action 的技术绑定；
- 哪些页面需要权限；
- 页面对应的 `resourceKey`；
- 本次 Build 是否发生影响路由的事实变化；
- 是否在 DAG 中加入 `route_projection`；
- Route Projection 的执行依赖和执行时机。

### Template 负责 How

Template 负责决定：

- 页面目录名如何从 `pageId` 推导；
- route segment 如何从 `pageId` 推导；
- 是否使用 `PAGE_ROUTES`；
- `routes.tsx` 位于哪里；
- marker 是什么；
- route object 的字段结构；
- Menu 如何生成；
- React Router 如何生成；
- `resourceKey` 如何进入模板路由定义；
- 由模板现有 `import.meta.glob` 机制根据 `pageId` 动态发现页面组件。

当前模板已经采用 `import.meta.glob` 动态页面发现，因此 Route Projector **不得生成显式 Component import，也不得向路由对象注入 `component` 字段**。

一句话：

> XCodeAgent 决定“哪些页面需要被投影”，Template 决定“这些页面如何在当前模板版本中落成真实路由”。

---

## 1.3 不再维护第二份页面事实

本次改造明确删除以下模式：

```json
{
  "route_projection": {
    "pages": [
      {
        "pageId": "...",
        "path": "...",
        "pageKey": "...",
        "menu": true
      }
    ]
  }
}
```

Build DAG 不再保存一份独立的 `route_projection.pages`。

页面事实继续来自现有正式规划产物。

### 页面产品事实

以：

```text
ProductPlan.pages
```

为页面产品事实源，例如：

```json
{
  "pageId": "portal_home",
  "name": "门户首页",
  "path": "/portal-home",
  "module_id": "portal"
}
```

### 页面技术事实

TechnicalPlan 只保存本阶段新增的技术绑定，例如：

```json
{
  "pageId": "portal_home",
  "references": {
    "endpoint_dependencies": [
      {
        "endpoint_id": "portal_api.get_welcome",
        "usage": "page_load",
        "trigger": "访客打开门户欢迎页时加载静态欢迎内容",
        "required_for_initial_load": true
      }
    ],
    "action_implementations": []
  }
}
```

### 页面权限事实

权限资源继续来自：

```text
authorization_manifest
```

例如：

```text
pageId = asset_list
resourceKey = PAGE.ASSET_LIST
```

因此正式事实关系为：

```text
ProductPlan.pages
      │
      ├── 页面身份、名称等产品事实
      │
      ▼
TechnicalPlan.pages
      │
      └── endpoint/action implementation binding

authorization_manifest
      │
      └── pageId/resourceKey
```

不得再次复制成独立的 Route Projection 页面模型。

---

## 1.4 Route Projector 调用输入

XCodeAgent 调用模板 Projector 时允许临时构造运行时 DTO，但它不是新的事实源，也不落盘。

示例：

```json
{
  "protocol": "route-projector.v1",
  "pages": [
    {
      "...": "ProductPlan.pages 中的页面对象"
    }
  ],
  "technicalPages": [
    {
      "...": "TechnicalPlan.pages 中的页面对象"
    }
  ],
  "authorization": {
    "pageResources": [
      {
        "pageId": "asset_list",
        "resourceKey": "PAGE.ASSET_LIST"
      }
    ]
  }
}
```

必须满足：

1. 只在 Projector 执行时临时构造；
2. 不写入 Build DAG；
3. 不落盘为新的 Planning Artifact；
4. 不重新定义一套 Page Schema；
5. 不包含模板实现字段。

XCodeAgent 不允许产生：

```text
pageKey
derivedPath
componentImport
component
routeElement
menuObject
marker
routesFile
```

其中页面组件实现尤其不属于 Route Projector 输入。当前模板会通过 `pageId + import.meta.glob` 在运行时找到对应页面模块。

---

## 1.4.1 页面组件不是 Projector Input

这里需要明确一个关键边界。

ProductPlan 只能确定：

```text
pageId
name
```

页面的实际 React 组件是在 Build DAG 的 Page Task 阶段生成的，例如：

```text
pageId = portal_home
        ↓
Page Task
        ↓
frontend/src/pages/PortalHome/index.tsx
```

但当前模板已经通过：

```text
pageId
→ pageDirectoryFromId(pageId)
→ import.meta.glob
→ lazy(Component)
```

自动找到页面实现。

因此 Route Projector Input **不要增加**：

```text
component
componentName
componentPath
importPath
```

Route Projector 也不要生成：

```tsx
import PortalHome from '@/pages/PortalHome';
```

或：

```tsx
{
  pageId: 'portal_home',
  component: PortalHome
}
```

它只负责生成：

```tsx
{
  name: '门户首页',
  pageId: 'portal_home'
}
```

权限页面：

```tsx
{
  name: '资产管理',
  pageId: 'asset_list',
  resourceKey: 'PAGE.ASSET_LIST'
}
```

Route Projector 执行时可以根据 `pageId` 检查当前 Workspace 中对应页面入口是否已经存在；但组件发现和组件装载继续完全由模板 Runtime 负责。

完整链路为：

```text
ProductPlan.pages
        ↓
定义页面应该存在
        ↓
Build DAG Page Task
        ↓
生成页面 Component
        ↓
Workspace
        ↓
platform_route_projection
        ↓
只注册 pageId/name/resourceKey
        ↓
Template Runtime
        ↓
import.meta.glob 动态找到 Component
```

## 1.5 Route Projection 只有一个动作：apply

当前阶段不引入：

```text
validate
verify
```

也不新增 Route Projection 前置 Gate 或后置 EDD 节点。

模板只需要提供一个确定性的：

```text
apply
```

入口。

例如：

```bash
node frontend/scripts/xcodeagent/route-projector.mjs apply
```

职责：

> 根据当前确认的完整页面事实，对模板的业务路由受管区域进行一次全量 reconcile。

这里的“完整 Business Routes”是指完整的**页面注册事实**，而不是把组件实现写进路由定义。当前模板标准页面只需要注册：

```text
pageId
name
resourceKey（可选）
```

页面 Component 由模板现有 `import.meta.glob` 运行时动态发现。

它不是：

```text
“把本次新增页面 append 到 routes.tsx”
```

而是：

```text
当前 ProductPlan.pages
+
当前 authorization_manifest
        ↓
计算当前应该存在的完整路由集合
        ↓
整体更新模板受管区域
```

因此 Projector 必须幂等：

```text
同样输入执行一次
=
同样输入执行十次
```

第二次执行不应产生新的 Workspace Diff。

---

## 1.6 Template Projector 的发现契约

XCodeAgent 不能把：

```text
frontend/scripts/xcodeagent/route-projector.mjs
```

写死在 Python 中。

模板需要随生成工程携带一个机器可读的 Descriptor。

推荐 Template Source：

```text
template-source/base/
├── contracts/
│   └── route-projector.json
└── frontend/
    └── scripts/
        └── xcodeagent/
            └── route-projector.mjs
```

生成到 Workspace：

```text
.xcodeagent/template-contracts/route-projector.json
frontend/scripts/xcodeagent/route-projector.mjs
```

Descriptor V1：

```json
{
  "schemaVersion": "route-projector-contract.v1",
  "protocol": "route-projector.v1",
  "command": [
    "node",
    "frontend/scripts/xcodeagent/route-projector.mjs",
    "apply"
  ]
}
```

XCodeAgent 只知道统一发现入口：

```text
.xcodeagent/template-contracts/route-projector.json
```

至于：

```text
routes.tsx 在哪里
marker 是什么
PAGE_ROUTES 长什么样
脚本内部如何更新
```

全部属于 Template。

---

## 1.7 Route Projection 必须在 Build DAG 中可见

当前 Route Projection 不应继续隐藏在：

```text
所有 Build Task 完成
→ apply_platform_projections()
→ 内部再调用 route_projection
```

而应显式进入最终 Build DAG。

例如：

```json
{
  "task_id": "platform_route_projection",
  "task_type": "platform_action",
  "action": "route_projection",
  "name": "路由投影",
  "depends_on": [
    "frontend_page_asset_list"
  ]
}
```

普通业务 Task：

```text
task_type = agent_task
```

Route Projection：

```text
task_type = platform_action
```

Scheduler 根据 Task 类型选择执行器：

```python
if task["task_type"] == "platform_action":
    execute_platform_action(task)
else:
    execute_agent_task(task)
```

Route Projection 不由 LLM / Task Planner 自由生成。

必须由 DAG Assembly 确定性决定是否加入。

---

## 1.8 不是每次 Build 都生成 Route Projection

Route Projection 只有在：

> 当前版本与上一次成功 Build 相比，实际影响路由输出的事实发生变化

时才加入 DAG。

不通过 Task 文本猜测。

不使用：

```text
排序
canonical JSON
SHA256
route projection fingerprint
额外 snapshot 文件
```

直接比较两份结构化 Route Facts。

---

## 1.9 最简单的 Route Facts 比较

新增一个很轻量的：

```python
extract_route_facts(...)
```

它只抽取 Template Route Projector 真正消费的业务字段。

当前 V1 推荐：

```python
def extract_route_facts(product_plan, authorization_manifest):
    page_resources = get_page_resources(authorization_manifest)

    return {
        "pages": {
            page["pageId"]: {
                "name": page.get("name"),
                "resourceKey": page_resources.get(page["pageId"]),
            }
            for page in product_plan.get("pages", [])
        }
    }
```

因为使用 dict：

```text
页面数组顺序变化
```

不会触发误判。

如果后续 Template Projector 正式消费：

```text
path
module_id
navigation metadata
```

只需把对应字段加入：

```python
extract_route_facts()
```

例如：

```python
{
    "name": page.get("name"),
    "path": page.get("path"),
    "module_id": page.get("module_id"),
    "resourceKey": ...
}
```

原则是：

> Projector 会用到、且变化后会改变最终 Route/Menu 输出的业务字段，才进入 Route Facts。

不要把整个 ProductPlan.pages 拿来比较。

以下变化不应该触发 Route Projection：

```text
information_items
actions
acceptance_criteria
endpoint_dependencies
action_implementations
页面内部 UI
后端 API
数据库实现
```

---

## 1.10 如何决定是否把 Route Projection 加入 DAG

DAG Assembly 直接比较：

```text
上一成功 Build 对应的 Route Facts
vs
当前确认版本的 Route Facts
```

伪代码：

```python
def requires_route_projection(previous_build, current_product_plan, current_auth_manifest):
    if previous_build is None:
        return True

    previous_facts = extract_route_facts(
        previous_build.product_plan,
        previous_build.authorization_manifest,
    )

    current_facts = extract_route_facts(
        current_product_plan,
        current_auth_manifest,
    )

    return previous_facts != current_facts
```

首次 Build：

```text
previous_build = None
→ 需要 Route Projection
```

只修改 API：

```text
previous Route Facts == current Route Facts
→ 不生成 Route Projection
```

新增页面：

```text
previous Route Facts != current Route Facts
→ 生成 Route Projection
```

删除页面：

```text
previous Route Facts != current Route Facts
→ 生成 Route Projection
```

修改菜单名称：

```text
name 变化
→ 生成 Route Projection
```

新增 / 删除页面权限：

```text
resourceKey 变化
→ 生成 Route Projection
```

只修改页面 UI：

```text
Route Facts 不变
→ 不生成 Route Projection
```

---

## 1.11 Route Projection 的执行时机

如果本次需要 Route Projection：

```text
DAG Assembly
      ↓
正常业务 Tasks
      ↓
确定性追加 platform_route_projection
```

最终：

```text
page / api / backend / database tasks
                ↓
     platform_route_projection
```

Route Projection 默认依赖本次全部正常业务 Build Task。

当前阶段不额外优化到“只依赖 Page Task”，原因是：

- 更简单；
- 避免和 Agent 同时修改共享文件；
- 保留现有 Platform Projection 在业务任务结束后执行的安全边界；
- 后续如果有性能需求再缩小 depends_on。

如果本次 Route Facts 未变化：

```text
DAG 中完全没有 platform_route_projection
```

因此也不会发生重复路由注册。

---

## 1.12 Build DAG 不再保存 route_projection.pages

删除：

```json
{
  "route_projection": {
    "pages": [...]
  }
}
```

最终 DAG 只表达：

```text
这次要不要执行 Route Projection
```

而不保存页面内容。

有变化时：

```json
{
  "task_id": "platform_route_projection",
  "task_type": "platform_action",
  "action": "route_projection",
  "name": "路由投影",
  "depends_on": [
    "..."
  ]
}
```

无变化时：

```text
该节点不存在
```

执行节点时再从当前 Confirmed Planning Artifacts 读取页面事实。

这样形成明确边界：

```text
规划产物
→ 决定最终有哪些页面

DAG
→ 决定这次是否需要做 Route Projection

Template
→ 决定这些页面如何落到当前模板
```

---

## 1.13 关于 ProductPlan.path 的边界

当前模板对于带 `pageId` 的标准业务页面实际按：

```text
pageId
→ pageRouteSegmentFromId(pageId)
```

生成路由。

因此当前 V1：

### 标准业务页面

以：

```text
pageId
```

作为模板页面身份。

Projector 生成类似：

```tsx
{
  name: '门户首页',
  pageId: 'portal_home'
}
```

权限页面：

```tsx
{
  name: '资产管理',
  pageId: 'asset_list',
  resourceKey: 'PAGE.ASSET_LIST'
}
```

**不得生成：**

```tsx
import PortalHome from '@/pages/PortalHome';
import AssetList from '@/pages/AssetList';

{
  pageId: 'portal_home',
  component: PortalHome
}
```

因为当前模板的页面组件解析链已经是：

```text
pageId
→ pageDirectoryFromId(pageId)
→ import.meta.glob
→ lazy(Component)
→ React Router
```

Route Projector 只负责注册页面身份和权限事实，不负责注入组件实现。

同时也不由 XCodeAgent 自己把 ProductPlan.path 翻译成 React Router path。

### 特殊动态路由

如果未来需要：

```text
/orders/:orderId/history
```

等模板 Page Identity 无法表达的场景，再扩展 Template Route Projector 协议。

本次不扩大范围。

---

# 2. 模板工程改造步骤

仓库：

```text
Hupy2118/springboot-template
branch: template_refactor
```

## 2.1 新增 Route Projector Descriptor

新增：

```text
template-source/base/contracts/route-projector.json
```

内容：

```json
{
  "schemaVersion": "route-projector-contract.v1",
  "protocol": "route-projector.v1",
  "command": [
    "node",
    "frontend/scripts/xcodeagent/route-projector.mjs",
    "apply"
  ]
}
```

同时在：

```text
template-source/base/base.yaml
```

声明生成：

```text
.xcodeagent/template-contracts/route-projector.json
```

目的：

> Projector 入口属于 Template Source Contract，而不是 XCodeAgent 配置。

---

## 2.2 新增模板自带 Route Projector

新增：

```text
template-source/base/frontend/scripts/xcodeagent/route-projector.mjs
```

职责：

```text
stdin 读取 RouteProjectorInput
        ↓
读取 ProductPlan pages 语义
        ↓
读取 pageId/resourceKey
        ↓
确认 Page Task 已生成对应页面入口
        ↓
按照当前 Template 实现生成最小 PAGE_ROUTES 注册项
        ↓
更新受管区域
```

注意：

```text
Route Projector 不生成 Component import
Route Projector 不写 component 字段
Route Projector 不创建页面实现
```

组件发现继续由模板 Runtime 的 `import.meta.glob` 完成。

不调用 LLM。

不读取 XCodeAgent Python renderer。

不依赖 XCodeAgent 知道 TSX 结构。

---

## 2.3 Projector 输入校验

虽然当前阶段不单独提供 validate 动作，但 `apply` 自身仍必须对输入做基本 fail-fast 校验。

至少检查：

### ProductPlan.pages

- 必须为数组；
- `pageId` 非空；
- `pageId` 唯一；
- `pageId` 满足当前模板页面身份规则。

### TechnicalPlan.pages

- TechnicalPlan 中引用的 `pageId` 必须存在于 ProductPlan；
- Endpoint / Action references 不用于生成 Route。

### authorization.pageResources

- `pageId` 必须存在于 ProductPlan；
- `resourceKey` 非空；
- 同一 `pageId` 只能有一个页面资源；
- 没有权限资源的页面默认正常访问。

失败直接使：

```text
platform_route_projection
```

Task 失败。

无需额外增加前置校验节点。

---

## 2.4 标准页面使用当前模板 Page Identity

当前模板标准页面生成形状：

```tsx
{
  name: '门户首页',
  pageId: 'portal_home',
}
```

受控页面：

```tsx
{
  name: '资产管理',
  pageId: 'asset_list',
  resourceKey: 'PAGE.ASSET_LIST',
}
```

标准页面不再生成：

```tsx
import AssetList from '@/pages/AssetList';

{
  path: '/page/assets',
  menu: {...},
  element: <AssetList />
}
```

也不生成：

```tsx
{
  name: '资产管理',
  pageId: 'asset_list',
  component: AssetList,
}
```

页面组件完全由当前模板已有动态页面发现机制处理：

```text
pageId
→ pageDirectoryFromId(pageId)
→ import.meta.glob
→ lazy(Component)
```

因此 Route Projector 的职责只到“页面注册事实”这一层。

---

## 2.5 routes.tsx marker 完全归模板管理

当前模板可以继续使用：

```text
XCODEAGENT_BUSINESS_ROUTE_IMPORTS_START/END
XCODEAGENT_BUSINESS_ROUTES_START/END
```

但这些字符串只允许出现在 Template Repository。

XCodeAgent 不允许再：

```text
引用 marker
定位 marker
替换 marker 文本
测试 marker 结构
```

未来模板删除 marker 或更换实现时，只修改 Template Projector。

---

## 2.6 Projector 必须是全量 reconcile

Projector 不允许 append：

错误：

```text
本次新增 asset_list
→ 在已有 routes.tsx 后追加 asset_list
```

正确：

```text
当前完整 ProductPlan.pages
        ↓
生成当前完整 expected business routes
        ↓
替换模板受管区域
```

例如上一次：

```text
portal_home
asset_list
```

当前 ProductPlan 变成：

```text
portal_home
```

Projector 执行后：

```text
asset_list
```

必须自然从受管区域移除。

因此同一输入重复执行也不会产生重复路由。

---

## 2.7 页面入口检查

Route Projector 虽然不注入 Component，但它执行时位于 Page Task 之后，因此可以利用当前 Workspace 做一个最基本的实现完整性检查。

Projector 根据模板自己的：

```text
pageDirectoryFromId(pageId)
```

规则确认：

```text
frontend/src/pages/<PageDirectory>/index.tsx
```

已经由 Page Task 生成。

例如：

```text
portal_home
→ PortalHome
→ frontend/src/pages/PortalHome/index.tsx
```

如果页面入口缺失：

```text
platform_route_projection
→ failed
```

但这个检查只用于确认“页面实现已经存在”，**不会把该路径、组件名或 import 语句写入 Projector Input，也不会把 Component 注入路由定义**。

因此职责边界为：

```text
ProductPlan
→ 定义页面应该存在

Page Task
→ 生成页面实际实现

Route Projector
→ 检查页面实现已存在，并注册 pageId/name/resourceKey

Template Runtime
→ 通过 import.meta.glob 动态解析 Component
```

Projector 不创建 placeholder 页面。

---

## 2.8 模板侧测试

模板仓库负责测试所有模板实现规则。

至少覆盖：

### 标准页面

```text
pageId = asset_list
name = 资产管理
```

生成正确的最小 PageRouteDefinition：

```tsx
{
  name: '资产管理',
  pageId: 'asset_list'
}
```

并明确验证：

```text
不生成显式 import
不生成 component 字段
```

### 权限页面

```text
asset_list
resourceKey = PAGE.ASSET_LIST
```

Projector 输出正确 `resourceKey`。

### 普通页面

未声明权限：

```text
不产生 resourceKey
```

### 删除页面

上一次存在：

```text
asset_list
```

当前输入不存在：

```text
Projector apply 后路由中不再存在 asset_list
```

### 幂等

连续 apply 两次：

```text
第二次 Workspace Diff = 0
```

### 页面入口缺失

Projector apply 失败。

### Runtime 动态组件发现

验证：

```text
pageId
→ import.meta.glob
→ 页面模块
```

仍由模板 Runtime 完成，Route Projector 不注入组件实现。

### 非法 pageId

Projector apply 失败。

---

## 2.9 更新模板指引

修改：

```text
template-source/base/frontend/AGENTS.md
template-source/base/frontend/docs/project-structure.md
```

增加：

- 业务页面共享路由注册由 Route Projector 管理；
- Page Agent 不直接维护共享路由表；
- 标准业务页面使用稳定 `pageId`；
- Page Task 只生成页面代码；
- Route Projection Platform Action 负责统一路由注册；
- 路由、菜单、权限继续通过模板统一 PageRouteDefinition 链路消费。

---

## 2.10 base.yaml 文件声明

新增：

```text
contracts/route-projector.json
frontend/scripts/xcodeagent/route-projector.mjs
相关测试脚本
```

必须全部进入：

```text
template-source/base/base.yaml
```

保证 Template Source 唯一托管。

---

# 3. XCodeAgent 改造步骤

仓库：

```text
YYYWYF/XCodeAgent
branch: dev_agent
```

## 3.1 删除 route_projection.py 中的模板 renderer

当前：

```text
Backend/app/services/route_projection.py
```

逐步删除：

```text
ROUTES_RELATIVE_PATH
IMPORT_START
IMPORT_END
ROUTES_START
ROUTES_END

_render_imports()
_render_routes()
_managed_bounds()
_replace_managed()
_write_text_atomically()
模板 PageKey / path 推导
```

最终不再由 XCodeAgent 直接写：

```text
frontend/src/constants/routes.tsx
```

推荐新增：

```text
Backend/app/services/template_route_projector.py
```

只承担 Template Projector 调用适配。

---

## 3.2 新增 Template Route Projector Adapter

建议：

```text
Backend/app/services/template_route_projector.py
```

职责：

```text
1. 定位 Projector Descriptor
2. 验证 Descriptor 基本结构
3. 组装临时 Projector Input
4. 调用模板 Projector
5. 解析执行结果
6. 把失败映射为 Build Task failure
```

禁止：

```text
理解 routes.tsx
理解 PAGE_ROUTES
生成 TSX
生成 Component import
生成 component 字段
推导页面组件路径
理解 marker
```

---

## 3.3 Projector Descriptor Loader

固定读取：

```text
<workspace>/.xcodeagent/template-contracts/route-projector.json
```

例如：

```python
load_route_projector_contract(workspace)
```

只校验：

```text
schemaVersion
protocol
command
```

安全要求：

- command 必须为数组；
- 禁止 `shell=True`；
- cwd 固定 Workspace；
- stdin 传 JSON；
- stdout 解析结果；
- timeout 有上限。

Descriptor 缺失：

```text
platform_route_projection
→ failed
```

不回退旧 renderer。

---

## 3.4 运行时组装 Projector Input

新增：

```python
build_route_projector_input(...)
```

输入来源：

```text
Confirmed ProductPlan
Confirmed TechnicalPlan
authorization_manifest
```

其中：

```text
pages = ProductPlan.pages
technicalPages = TechnicalPlan.pages
```

权限只提取：

```text
pageId
resourceKey
```

该对象只存在于内存 / stdin。

不得：

```text
write route-projector-input.json
write route_projection.json
写入 Build DAG pages
```

---

## 3.5 新增 extract_route_facts()

这是决定 DAG 中是否生成 Route Projection 节点的唯一比较入口。

建议：

```python
def extract_route_facts(product_plan, authorization_manifest):
    page_resources = extract_page_resource_map(authorization_manifest)

    return {
        "pages": {
            page["pageId"]: {
                "name": page.get("name"),
                "resourceKey": page_resources.get(page["pageId"]),
            }
            for page in product_plan.get("pages", [])
        }
    }
```

V1 不做：

```text
排序
JSON 序列化
SHA256
fingerprint
snapshot
```

直接比较 Python dict。

以后如果 Template Projector 开始消费：

```text
path
module_id
```

就在这里加字段。

该函数的字段集合必须和 Route Projector 实际消费的业务语义保持一致。

---

## 3.6 找到“上一次成功 Build”对应的规划输入

DAG Assembly 需要拿到：

```text
previous successful build ProductPlan
previous successful build authorization_manifest
```

当前版本拿：

```text
current confirmed ProductPlan
current authorization_manifest
```

不新增：

```text
last-route-projection.json
route-fingerprint.json
route-snapshot.json
```

优先复用现有 Build Run / Planning Run / confirmed artifact 绑定能力定位上一成功版本。

如果没有 previous successful build：

```text
首次 Build
→ requires_route_projection = True
```

---

## 3.7 DAG Assembly 直接比较 Route Facts

新增：

```python
requires_route_projection(...)
```

例如：

```python
def requires_route_projection(previous_build, current_product_plan, current_auth_manifest):
    if previous_build is None:
        return True

    previous_facts = extract_route_facts(
        previous_build.product_plan,
        previous_build.authorization_manifest,
    )

    current_facts = extract_route_facts(
        current_product_plan,
        current_auth_manifest,
    )

    return previous_facts != current_facts
```

DAG Assembly：

```python
tasks = build_normal_tasks(...)

if requires_route_projection(...):
    tasks.append(
        {
            "task_id": "platform_route_projection",
            "task_type": "platform_action",
            "action": "route_projection",
            "name": "路由投影",
            "depends_on": [task["task_id"] for task in tasks],
        }
    )
```

Route Projection 节点不是模型生成，而是 DAG Assembly 确定性追加。

---

## 3.8 删除 route_projection.pages 生产逻辑

如果当前：

```text
scope_assembly.py
```

或其他 DAG Assembly 逻辑中存在：

```python
assembled["route_projection"] = compile_route_projection(...)
```

删除。

Build DAG 不再持久化：

```text
route_projection.pages
```

同时删除：

```text
Build DAG 缺少 route_projection
```

这种根字段 Gate。

因为无路由变化时 DAG 合法地可以完全没有 Route Projection 节点。

---

## 3.9 Scheduler 支持 platform_action

当前业务 Task 执行器保持不变。

增加最小分支：

```python
if task.get("task_type") == "platform_action":
    execute_platform_action(...)
else:
    execute_agent_task(...)
```

当前 V1 只支持：

```text
action = route_projection
```

例如：

```python
def execute_platform_action(task, context):
    action = task["action"]

    if action == "route_projection":
        return apply_template_route_projection(
            workspace=context.workspace,
            product_plan=context.confirmed_product_plan,
            technical_plan=context.confirmed_technical_plan,
            authorization_manifest=context.authorization_manifest,
        )

    raise UnsupportedPlatformAction(action)
```

后续其他平台动作是否进入 DAG，另行设计。

本次不扩大范围。

---

## 3.10 改造 authorization_platform_projection.py

当前该模块直接：

```python
apply_route_projection(...)
```

需要移除 Route Projection 分支。

Route Projection 改由 DAG 中：

```text
platform_route_projection
```

节点执行。

如果：

```text
authorization_platform_projection.py
```

还负责：

```text
authorization_frontend_projection
authorization_constants_projection
```

当前阶段可以继续保留。

也就是说先从：

```text
apply_platform_projections()
  ├─ routes
  ├─ frontend
  └─ authConstants
```

变成：

```text
DAG:
  └─ platform_route_projection

apply_platform_projections():
  ├─ frontend
  └─ authConstants
```

不要一次把所有 Platform Projection 都重构进 DAG。

---

## 3.11 收缩 authorization_frontend_projection

如果当前：

```text
routeDecorations
```

唯一目的就是供旧 `route_projection.py` 渲染 routes.tsx，则删除这层模板代码 decoration。

保留真正的语义事实：

```text
pageId -> resourceKey
```

权威来源仍然是：

```text
authorization_manifest
```

Template Projector 在执行时接收：

```text
pageId/resourceKey
```

并决定如何进入模板路由。

---

## 3.12 authorization_edd.py 当前阶段不新增 Route Verify

当前阶段目标是简化生命周期。

因此删除：

```python
verify_route_projection(...)
```

这种 XCodeAgent 自己理解模板源码的 EDD。

但不新增：

```text
template.route.verify
```

节点。

现有 EDD 中其他检查继续保留，例如：

```text
authorization constants
Controller ANY-OF
禁止页面直接 HTTP
重复 AuthProvider
```

模板自身的 Route Projector 正确性通过模板仓库单元测试/集成测试保障。

以后如果确有必要再增加 Route Runtime Verify，本次不做。

---

## 3.13 Build 调用使用 Confirmed Inputs

Route Projection 节点执行时必须读取当前 Build Run 绑定的确认输入：

```text
Confirmed ProductPlan
Confirmed TechnicalPlan
Confirmed authorization_manifest
Frozen Template Revision / Workspace
```

不能：

```text
DAG 判断用 confirmed
apply 时却读取已经被后续流程改写的 mutable planning state
```

本次不需要额外创建 Route Snapshot。

继续复用现有 Build Run 与 Planning Artifact 绑定即可。

---

## 3.14 XCodeAgent 测试调整

### 删除旧 renderer 测试

删除：

```text
_render_routes()
_render_imports()
marker replacement
routes.tsx exact expected text
```

这些测试迁移到 Template Repository。

### 新增 `test_route_projection_decision.py`

重点验证：

```text
首次 Build → True
页面新增 → True
页面删除 → True
页面 name 变化 → True
resourceKey 新增 → True
resourceKey 删除 → True
resourceKey 变化 → True

只改 information_items → False
只改 actions → False
只改 endpoint_dependencies → False
只改 action_implementations → False
只改后端 API → False
```

### 新增 `test_template_route_projector.py`

验证：

```text
descriptor load
stdin input
command execution
non-zero exit
malformed descriptor
timeout
```

### Scheduler 测试

验证：

```text
route facts unchanged
→ DAG 不含 platform_route_projection

route facts changed
→ DAG 包含 platform_route_projection

platform_route_projection
→ 在普通 Build Task 后执行

Projector failed
→ Build failed
```

---

# 4. 两个仓库修改文件总览

## 4.1 Template Repository

预计新增：

```text
template-source/base/contracts/route-projector.json
template-source/base/frontend/scripts/xcodeagent/route-projector.mjs
```

预计修改：

```text
template-source/base/base.yaml
template-source/base/frontend/AGENTS.md
template-source/base/frontend/docs/project-structure.md
```

测试根据现有工程测试框架增加。

原则：

> 所有 React/Vite/Route/Menu 具体规则只存在 Template Repository。

---

## 4.2 XCodeAgent Repository

预计新增：

```text
Backend/app/services/template_route_projector.py
```

可根据现有代码结构新增或放入已有 DAG Assembly 模块：

```text
extract_route_facts()
requires_route_projection()
```

预计修改：

```text
Backend/app/services/route_projection.py
Backend/app/services/authorization_platform_projection.py
Backend/app/services/authorization_frontend_projection.py
Backend/app/services/authorization_edd.py
Backend/app/services/scope_assembly.py
Backend/app/services/build_task_plan_lifecycle.py
Backend/app/graph/subgraphs/build.py
```

实际文件以当前代码调用链为准。

最终目标：

```text
Backend/app/services/route_projection.py
```

不再包含 Template renderer；如果没有剩余职责则删除。

---

# 5. 实施顺序

## Stage 1：Template 先提供 Projector

实现：

```text
1. route-projector.json
2. route-projector.mjs apply
3. 全量 reconcile
4. 幂等
5. 页面入口检查
6. Template tests
7. base.yaml 声明
```

此阶段 XCodeAgent 暂不切换。

---

## Stage 2：XCodeAgent 增加 Projector Adapter

实现：

```text
1. descriptor loader
2. runtime input builder
3. projector command runner
4. adapter tests
```

---

## Stage 3：增加 Route Facts 判断

实现：

```text
extract_route_facts()
requires_route_projection()
```

直接比较：

```text
previous successful build route facts
vs
current route facts
```

不增加 hash。

不增加 snapshot。

---

## Stage 4：让 Route Projection 进入 DAG

DAG Assembly：

```text
route facts unchanged
→ 不生成节点

route facts changed
→ 追加 platform_route_projection
```

Scheduler 增加：

```text
platform_action
```

执行器。

---

## Stage 5：移除 DAG 外旧 Route Projection

从：

```text
apply_platform_projections()
```

中移除旧 Route Projection 调用。

删除：

```text
route_projection.pages
root route_projection gate
old renderer
old route source EDD
```

最终 Route Projection 只有一个执行入口：

```text
Build DAG.platform_route_projection
```

---

# 6. 最终验收标准

1. XCodeAgent 中不存在 `frontend/src/constants/routes.tsx` 硬编码路径。
2. XCodeAgent 中不存在业务路由 marker 常量。
3. XCodeAgent 中不存在 `PAGE_ROUTES` TSX renderer。
4. XCodeAgent 不再生成 Component import，也不生成或传递 `component` 字段。
5. Build DAG 不再保存 `route_projection.pages`。
6. 页面清单只由现有 ProductPlan / TechnicalPlan 正式产物维护。
7. Route Projector Input 仅为运行时 DTO。
8. Route Projection 作为 `platform_action` 在 Build DAG 中可见。
9. Route Projection 节点由 DAG Assembly 确定性生成，不由 LLM 生成。
10. 首次 Build 会生成 Route Projection 节点。
11. 页面新增会生成 Route Projection 节点。
12. 页面删除会生成 Route Projection 节点。
13. 页面名称等 Projector 消费事实变化会生成 Route Projection 节点。
14. 页面 `resourceKey` 变化会生成 Route Projection 节点。
15. 只修改 API、页面内部 UI 或 endpoint binding 时不会生成 Route Projection 节点。
16. Route Facts 判断直接比较结构化 dict，不使用排序、canonical JSON 或 SHA256。
17. 不新增 route fingerprint / snapshot 文件。
18. Route Projector 对完整当前页面事实做 reconcile，不做 append。
19. Route Projector 重复执行必须幂等。
20. 删除页面后 Projector 能从模板受管路由区域删除对应页面。
21. Route Projector 只在所有正常业务 Build Task 成功后执行。
22. Projector 失败会使 `platform_route_projection` Task 失败。
23. Projector 产生的 Workspace Change 继续记录为平台动作，不混入 Agent Task Change Set。
24. Descriptor 缺失时失败，不回退旧 XCodeAgent renderer。
25. Template Revision 改变路由实现时，只修改 Template Projector，不修改 XCodeAgent Route renderer。
26. 当前模板的页面 Component 解析继续完全依赖 `import.meta.glob`，Route Projector 不注入组件实现。
27. Route Projector 只在执行时检查页面入口是否已由 Page Task 生成，不把组件路径写入协议。

---

# 7. Codex 实施硬约束

```text
1. 不新增第二份持久化页面清单。
2. 不新增 route fingerprint、SHA256 或 route snapshot。
3. 不从 Task 自然语言描述猜测是否需要 Route Projection。
4. 只比较上一成功 Build 与当前版本的 Route Facts。
5. Route Facts 只包含 Template Projector 真正消费且会影响 Route/Menu 输出的业务字段。
6. Route Projection 节点由 DAG Assembly 确定性追加。
7. 不让 LLM 生成 route_projection platform task。
8. 不把旧 route_projection renderer 搬到另一个 XCodeAgent Python 文件。
9. 不把旧 renderer 简单搬成 XCodeAgent YAML 模板。
10. Template Projector 必须确定性执行，禁止调用 LLM。
11. XCodeAgent 不解析 routes.tsx 来重新理解模板业务路由结构。
12. Template Projector 不修改 ProductPlan / TechnicalPlan。
13. Template Projector 不生成业务页面 placeholder。
14. Descriptor 缺失必须失败，不允许 silent fallback。
15. Template Projector 必须做全量 reconcile，不允许 append-only。
16. 当前阶段不新增 Route validate / verify 生命周期节点。
17. 当前阶段不把其他 Platform Projection 一并重构进 DAG。
18. XCodeAgent 测试只验证 Route Facts 判断、DAG 生命周期和 Projector 调用，不验证 PAGE_ROUTES 具体文本。
19. 禁止为了“路由完整”向 Projector Input 增加 `componentPath`、`componentName` 或显式 import 信息。
20. 页面组件发现必须继续由 Template Runtime 的 `import.meta.glob` 完成。
```

---

# 8. 最终架构

```text
          ┌────────────────────┐
          │    ProductPlan     │
          │ pages / pageId     │
          └─────────┬──────────┘
                    │
          ┌─────────▼──────────┐
          │   TechnicalPlan    │
          │ endpoint/action ref│
          └─────────┬──────────┘
                    │
          ┌─────────▼────────────┐
          │authorization_manifest│
          │ pageId/resourceKey   │
          └─────────┬────────────┘
                    │
                    ▼
        ┌──────────────────────────────┐
        │         XCodeAgent           │
        │                              │
        │ extract current route facts  │
        │            │                 │
        │ compare previous successful  │
        │            │                 │
        │       changed ?              │
        └────────────┬─────────────────┘
                     │
             ┌───────┴────────┐
             │                │
            No               Yes
             │                │
             │       DAG 追加固定节点
             │       platform_route_projection
             │                │
             └────────┬───────┘
                      │
                      ▼
                Normal Build Tasks
                      │
                      ▼
       ┌────────────────────────────┐
       │ Template Route Projector   │
       │                            │
       │ full reconcile             │
       │ pageId → route definition  │
       │ resourceKey → route guard  │
       │ check page entry exists    │
       └─────────────┬──────────────┘
                     │
                     ▼
        Template Runtime (import.meta.glob)
                     │
                     ▼
             Generated Application
```

最终职责一句话：

> **XCodeAgent 通过“上一成功版本与当前版本的 Route Facts 直接比较”决定这次是否需要 Route Projection，并把该动作显式放入 Build DAG；Template Route Projector 只根据当前完整页面事实幂等地注册 `pageId / name / resourceKey`，页面 Component 始终由模板现有 `import.meta.glob` Runtime 动态发现。**

这样既解决当前 `route_projection` 时机隐藏、DAG 不可见的问题，也避免每次 Build 都重复执行路由注册，同时不引入 hash、额外快照、前后校验节点或新的页面事实结构。
