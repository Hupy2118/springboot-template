# 前端模板开发链路断点修复方案

## 1. 改造目标

当前 `template-source/code/frontend` 已具备：

```text
base/src
+
extensions/*
+
assembly
↓
src
```

的正向 Assembly 能力，但开发者在 `pnpm dev:<profile>` 后实际修改的是根目录 `src/**`，这些修改不会自动持久化回：

```text
base/src/**
extensions/<id>/src/**
```

导致：

```text
src 修改
→ 当前开发环境有效
→ 下一次 Assembly 被覆盖
→ 修改无法复用
```

本次改造只解决 `template-source/code/frontend` 内部开发闭环，不涉及 Template Engine 正式发布链路。

目标是形成：

```text
Authoring Source
↓
Assembly
↓
src
↓
开发者修改
↓
自动同步回 Authoring Source
↓
下次 Assembly 可完整恢复
```

---

# 2. 核心设计

不同 Profile 采用不同的同步策略。

## 2.1 Base：Mirror Sync

执行：

```bash
pnpm dev:base
```

后，根目录 `src/**` 可以视为 Base 的完整开发工作区。

规则：

```text
src 中所有非 Assembly Managed 文件
↓
直接同步到
base/src/**
```

包括：

* 修改文件；
* 新增文件；
* 删除文件。

因此 Base 开发形成：

```text
base/src
↓
Assembly
↓
src
↓
开发
↓
Mirror Sync
↓
base/src
```

开发者无需直接维护 `base/src`。

---

## 2.2 Login / Authorization：Source-aware Delta Sync

组合 Profile 不能整体覆盖 Extension，因为：

```text
dev:login
=
Base + Login

dev:authorization
=
Base + Login + Authorization
```

因此已有文件必须根据原始 Source 归属进行同步。

例如：

```text
src/layout/index.tsx
↓
base/src/layout/index.tsx
```

```text
src/pages/Login/index.tsx
↓
extensions/login/src/pages/Login/index.tsx
```

```text
src/pages/AuthorizationManagement/index.tsx
↓
extensions/authorization/src/pages/AuthorizationManagement/index.tsx
```

已有文件的 Owner 不需要额外维护 Ownership Manifest，可直接根据 Source Tree 判断。

Assembly 已禁止 Base 与 Extension 文件路径碰撞，因此已有文件的 Owner 是唯一的。

---

# 3. 新增文件归属规则

新增文件不存在原始 Owner，需要根据当前 Profile 指定默认写入目标。

调整：

```text
assembly/profiles.json
```

建议从：

```json
{
  "base": [],
  "login": ["login"],
  "authorization": ["authorization"],
  "full": ["login", "authorization"]
}
```

调整为：

```json
{
  "base": {
    "extensions": [],
    "writeOwner": "base"
  },
  "login": {
    "extensions": ["login"],
    "writeOwner": "login"
  },
  "authorization": {
    "extensions": ["authorization"],
    "writeOwner": "authorization"
  },
  "full": {
    "extensions": ["login", "authorization"],
    "writeOwner": null
  }
}
```

规则：

```text
dev:base
新增文件 → base/src
```

```text
dev:login
新增文件 → extensions/login/src
```

```text
dev:authorization
新增文件 → extensions/authorization/src
```

`dev:full` 暂不自动判断新增文件归属。

新增文件时直接报：

```text
NEW_FILE_OWNER_REQUIRED
```

`full` 定位为集成验证 Profile，而不是具体 Extension 的主要开发入口。

---

# 4. Assembly Managed 文件保护

以下文件由 Compiler 生成：

```text
providers/AppProviders.tsx
routes/rootRoutes.tsx
routes/systemPageRoutes.ts
bootstrap/appInitializers.ts
observability/errorReporter.ts
```

这些文件不得从 `src` 反向同步。

开发者修改这些文件时，应报：

```text
ASSEMBLY_MANAGED_FILE
```

并提示通过：

```text
extension.yaml
assembly Contract
Compiler
```

进行修改。

防止插件化架构重新退化为直接修改生成代码。

---

# 5. 新增 Workspace Sync 组件

新增：

```text
assembly/workspace-sync.mjs
```

职责：

```text
src/**
↓
判断文件类型和 Owner
↓
同步 Authoring Source
```

核心逻辑：

```text
如果是 Assembly Managed File
→ 禁止同步

否则如果 profile = base
→ 同步到 base/src

否则如果 Source 中已存在该文件
→ 回写原 Owner

否则如果当前 Profile 有 writeOwner
→ 写入 writeOwner

否则
→ NEW_FILE_OWNER_REQUIRED
```

建议同时支持：

```text
CREATE
UPDATE
DELETE
```

---

# 6. 修改 dev.mjs

当前：

```text
watch base/src
watch extensions
watch assembly
↓
重新 Assembly
```

改造后增加：

```text
watch src/**
↓
workspace-sync
↓
持久化 Source
```

完整流程：

```text
pnpm dev:<profile>
↓
Assembly 当前 Profile
↓
生成 src
↓
启动 Vite
↓
监听 src
↓
开发者修改
↓
workspace-sync
↓
写回 Source
```

同时需要避免：

```text
src
→ Source
→ Source Watch
→ Assembly
→ src
→ Source
```

循环触发。

推荐规则：

### 普通源码开发

以：

```text
src → Source
```

为主。

### Manifest / Assembly 结构变化

以下目录变化时才重新 Assembly：

```text
extensions/*/extension.yaml
assembly/**
```

必要时也可以保留 Source Watch，但 Assembly 写 `src` 时必须增加同步抑制标记，防止触发反向同步。

第一阶段建议优先采用更简单的：

```text
普通源码：src → Source
结构配置：Source → Assembly → src
```

---

# 7. Base Profile 特殊优化

Base 不需要做复杂 Owner 判断。

增加：

```text
syncBaseWorkspace()
```

规则：

```text
src/**
-
Assembly Managed Files
=
base/src/**
```

采用镜像方式同步：

```text
新增 → 新增
修改 → 覆盖
删除 → 删除
```

这样 `dev:base` 的开发体验就是一个普通 React 工程。

---

# 8. Extension Profile 同步规则

以 `dev:authorization` 为例。

当前组合：

```text
Base
+
Login
+
Authorization
```

### 修改 Base 文件

```text
src/layout/index.tsx
↓
base/src/layout/index.tsx
```

### 修改 Login 文件

```text
src/pages/Login/index.tsx
↓
extensions/login/src/pages/Login/index.tsx
```

### 修改 Authorization 文件

```text
src/hooks/usePermission.ts
↓
extensions/authorization/src/hooks/usePermission.ts
```

### 新增文件

```text
src/components/PermissionTree.tsx
↓
extensions/authorization/src/components/PermissionTree.tsx
```

因为：

```text
writeOwner = authorization
```

---

# 9. 删除规则

删除操作与修改保持相同 Owner 规则。

例如：

```text
dev:login
```

删除：

```text
src/pages/Login/index.tsx
```

同步删除：

```text
extensions/login/src/pages/Login/index.tsx
```

删除：

```text
src/utils/a.ts
```

如果原 Owner 是 Base，则删除：

```text
base/src/utils/a.ts
```

同步日志应明确输出：

```text
DELETE base/src/utils/a.ts
via profile login
```

方便开发者通过 Git Diff 回检影响。

---

# 10. Rename 边界

第一阶段不实现跨 Owner Rename Detection。

例如：

```text
base/src/utils/a.ts
```

在 `dev:login` 中被改名为：

```text
src/utils/b.ts
```

系统按：

```text
DELETE base/utils/a.ts
+
CREATE login/utils/b.ts
```

处理。

即：

> Rename 第一阶段按 DELETE + CREATE 处理。

如果需要改变文件 Owner，由开发者显式调整维护源码。

暂不为此引入复杂 Rename 推断。

---

# 11. 修改 build-profile.mjs

`build:<profile>` 不再只是：

```text
Assembly
→ tsc
→ vite build
```

应增加 Workspace 持久化和重建验证。

推荐流程：

```text
① workspace-sync
↓
确保 src 修改已经进入 Source

② 保存当前 Profile

③ 从 Source 重新 Assembly 到临时目录

④ 比较：
当前 src
vs
重新 Assembly 输出

⑤ 不一致
→ WORKSPACE_SOURCE_DRIFT

⑥ 一致
→ tsc

⑦ vite build

⑧ 恢复原 Profile
```

不要再固定：

```text
build 后恢复 full
```

例如：

```text
进入前：base
执行 build:base
结束后：base
```

避免 Build 打断开发状态。

---

# 12. 建议新增命令

`package.json` 增加：

```json
{
  "scripts": {
    "sync:workspace": "node assembly/workspace-sync.mjs",
    "sync:base": "node assembly/workspace-sync.mjs --profile=base"
  }
}
```

正常开发无需人工执行。

用途主要是：

```text
调试
CI
异常恢复
```

正常链路：

```text
dev 自动 Sync
build 自动 Sync
```

---

# 13. 实施步骤

## Step 1：调整 Profile Contract

修改：

```text
assembly/profiles.json
assembly/profiles.mjs
```

支持：

```text
extensions
writeOwner
```

验收：

```text
base.writeOwner = base
login.writeOwner = login
authorization.writeOwner = authorization
full.writeOwner = null
```

---

## Step 2：抽取 Assembly Managed Files

当前 `assemble.mjs` 内已有：

```text
assemblyFiles
```

抽取为共享模块，例如：

```text
assembly/assembly-contract.mjs
```

由：

```text
assemble.mjs
workspace-sync.mjs
```

共同引用。

禁止维护两套文件列表。

---

## Step 3：实现 Base Mirror Sync

新增：

```text
assembly/workspace-sync.mjs
```

优先仅支持：

```text
profile=base
```

完成：

```text
CREATE
UPDATE
DELETE
```

同步。

验收：

```text
src/layout/index.tsx
→ base/src/layout/index.tsx
```

以及新增、删除文件均一致。

---

## Step 4：接入 dev:base

修改：

```text
assembly/dev.mjs
```

实现：

```text
启动时 Assembly Base
↓
监听 src
↓
自动 Sync Base
```

验收：

修改：

```text
src/layout/index.tsx
```

不执行任何额外命令，检查：

```text
base/src/layout/index.tsx
```

已经同步。

---

## Step 5：修复 build:base

修改：

```text
assembly/build-profile.mjs
```

流程改成：

```text
sync
→ re-assemble
→ compare
→ build
```

Build 完成后仍保持 Base Profile。

---

## Step 6：扩展 Existing File Owner 判断

支持：

```text
dev:login
dev:authorization
```

通过 Source Tree 判断原始 Owner。

规则：

```text
base 存在 → base

selected extension 存在
→ 对应 extension
```

若出现多个 Owner：

```text
SOURCE_OWNER_CONFLICT
```

应直接失败。

理论上现有 `FILE_COLLISION` 已保证不会正常出现。

---

## Step 7：实现 writeOwner

支持组合 Profile 中新增文件。

```text
login
→ login

authorization
→ authorization
```

Full 新增文件失败：

```text
NEW_FILE_OWNER_REQUIRED
```

---

## Step 8：完善测试

增加 Workspace Sync 测试：

```text
Base Update
Base Create
Base Delete

Login 修改 Base 文件
Login 修改 Login 文件
Login 新增文件

Authorization 修改 Base
Authorization 修改 Login
Authorization 修改 Authorization
Authorization 新增文件

Assembly Managed File 修改失败

Full 新增文件失败
```

---

# 14. 最终验收标准

必须至少通过以下场景。

### Base 持久化

执行：

```bash
pnpm dev:base
```

修改：

```text
src/layout/index.tsx
```

要求：

```text
base/src/layout/index.tsx
```

自动同步。

---

### Base 新增

新增：

```text
src/components/Test/index.tsx
```

要求自动创建：

```text
base/src/components/Test/index.tsx
```

---

### Base 删除

删除：

```text
src/components/Test/index.tsx
```

要求同步删除 Base Source。

---

### Extension Existing File

执行：

```bash
pnpm dev:login
```

修改：

```text
src/pages/Login/index.tsx
```

要求更新：

```text
extensions/login/src/pages/Login/index.tsx
```

修改：

```text
src/layout/index.tsx
```

要求更新：

```text
base/src/layout/index.tsx
```

---

### Extension New File

执行：

```bash
pnpm dev:authorization
```

新增：

```text
src/components/PermissionTree.tsx
```

要求创建：

```text
extensions/authorization/src/components/PermissionTree.tsx
```

---

### Generated File

修改：

```text
src/providers/AppProviders.tsx
```

必须拒绝：

```text
ASSEMBLY_MANAGED_FILE
```

---

### 可恢复性

这是最关键验收。

完成开发后：

```bash
rm -rf src
pnpm assemble -- --profile=base
```

重新生成的 `src` 必须完整包含刚才所有修改。

即：

```text
开发 src
↓
持久化 Source
↓
删除 src
↓
重新 Assembly
↓
代码完全恢复
```

只有这个场景通过，模板开发链路才真正闭环。

---

# 15. 最终开发模型

完成改造后，对模板开发者暴露的流程统一为：

```bash
pnpm dev:base
```

开发 Base。

```bash
pnpm dev:login
```

开发 Login。

```bash
pnpm dev:authorization
```

开发 Authorization。

开发者统一只修改：

```text
frontend/src/**
```

底层平台负责：

```text
Base
→ Mirror Sync

Extension
→ Source-aware Delta Sync

New File
→ Profile writeOwner

Generated File
→ 禁止直接修改
```

最终实现：

```text
上一版 Source
↓
dev:<profile>
↓
src 正常开发
↓
自动持久化
↓
build 验证
↓
下一次继续基于最新版本开发
```

本阶段不修改 Template Engine，不处理正式发布链路，只确保 `template-source/code/frontend` 内部开发生命周期完整闭环。
