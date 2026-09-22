那就不再动 Extension、Ownership、Assembly 等已完成部分，只针对 **Base 开发链路**做最小改造。核心目标是：**Base 也采用和 Extension 一致的 `dev → 修改 src → sync → build` 开发体验，不再引入 Base 专用 watcher 或直接编辑 `base/src` 的第二套流程。**

# Base 模板开发流程改造方案

## 1. 改造范围

仅处理：

```text
template-source/code/frontend
```

中的 Base 开发流程。

假设以下能力已经完成，本次不要重复改造：

```text
Extension Assembly
Source Ownership
editTarget
workspace-sync
Extension 依赖解析
Generated Registry
FILE_COLLISION
Login / Authorization 开发流程
```

本次只保证 Base 能按照统一模板开发模式正常开发、同步和验证。

---

# 2. 最终 Base 开发流程

模板开发者使用：

```bash
pnpm dev:base
```

Assembly：

```text
base/src
+
generated/extensions/*
        ↓
src
```

开发者只修改：

```text
template-source/code/frontend/src/**
```

完成后：

```bash
pnpm sync:base
pnpm build:base
```

最终流程：

```text
pnpm dev:base
      ↓
Assembly 生成 Base Workspace
      ↓
修改 src/**
      ↓
pnpm sync:base
      ↓
覆盖更新 base/src/**
      ↓
pnpm build:base
```

Base 与 Extension 的开发心智保持一致。

---

# 3. Base Profile 配置

确认：

```text
assembly/profiles.json
```

Base Profile 为：

```json
{
  "base": {
    "extensions": [],
    "editTarget": "base"
  }
}
```

含义：

```text
Source Owners = [base]

已有文件
→ base

新文件
→ editTarget = base

删除文件
→ 从 base 删除
```

Base 不需要任何特殊 Ownership 规则。

---

# 4. 保留 `dev:base`

`package.json` 中继续保留：

```json
"dev:base": "node assembly/dev.mjs --profile=base"
```

不要：

```text
删除 dev:base
新增 dev-base.mjs
增加 base/src watcher
增加 source mirror
```

`dev:base` 与 Extension 共用现有 `dev.mjs`。

启动时：

```text
assemble --profile=base
→ src
→ Vite
```

开发者直接修改组装后的 `src`。

---

# 5. 保留并修正 `sync:base`

继续提供：

```json
"sync:base": "node assembly/workspace-sync.mjs --profile=base"
```

Base Sync 应复用现有统一 Workspace Sync。

不要保留单独的：

```text
syncBaseWorkspace()
```

Base 应和其他 Profile 一样调用：

```text
syncWorkspace("base")
```

Base Profile 下：

```text
owners = [base]
editTarget = base
```

因此同步自然完成：

```text
UPDATE src/foo.ts
→ base/src/foo.ts

CREATE src/bar.ts
→ base/src/bar.ts

DELETE src/baz.ts
→ DELETE base/src/baz.ts
```

---

# 6. Assembly Managed 文件必须排除

Base Sync 中必须继续排除：

```text
generated/extensions/providers.ts
generated/extensions/rootRoutes.tsx
generated/extensions/systemPageRoutes.ts
generated/extensions/initializers.ts
generated/extensions/errorReporters.ts

.devagentstudio-template-generated.json
```

这些内容不能进入：

```text
base/src/generated/**
```

Base 只保存宿主逻辑。

Registry 始终由 Assembly 生成。

---

# 7. Base 中宿主文件必须允许同步

以下文件已经属于真正的 Base Source：

```text
providers/AppProviders.tsx
routes/rootRoutes.tsx
routes/systemPageRoutes.ts
bootstrap/appInitializers.ts
observability/errorReporter.ts
```

这些文件：

```text
不能被 ASSEMBLY_MANAGED_FILES 排除
```

必须允许：

```text
src
→ sync:base
→ base/src
```

也就是说：

```text
Assembly Managed
```

只能是：

```text
generated/extensions/*
```

不能再把完整运行时代码文件视为 Generated。

---

# 8. Base 新文件处理

这是 Base 开发必须重点验证的场景。

执行：

```bash
pnpm dev:base
```

开发者新增：

```text
src/components/Foo/index.tsx
```

由于所有 Source Root 中不存在：

```text
components/Foo/index.tsx
```

Sync 应使用：

```text
editTarget = base
```

最终：

```text
base/src/components/Foo/index.tsx
```

不能报：

```text
NEW_FILE_OWNER_REQUIRED
```

也不能忽略。

---

# 9. Base 删除文件处理

开发者在：

```text
src/**
```

删除一个原 Base 文件。

例如：

```text
src/utils/old.ts
```

而：

```text
base/src/utils/old.ts
```

仍存在。

执行：

```bash
pnpm sync:base
```

必须删除：

```text
base/src/utils/old.ts
```

并清理空目录。

---

# 10. Base Sync 只处理真实变化

执行：

```bash
pnpm sync:base
```

不应把整个 Base 都输出成：

```text
UPDATE
```

比较 Workspace 与 Source 内容：

```text
内容相同
→ NO-OP

内容不同
→ UPDATE
```

最终输出类似：

```text
UPDATE base/src/App.tsx
CREATE base/src/components/Foo/index.tsx
DELETE base/src/utils/old.ts
```

方便模板开发者确认本次实际修改。

---

# 11. `build:base` 行为

继续使用：

```bash
pnpm build:base
```

要求：

```text
Source
↓
assemble base profile
↓
tsc
↓
vite build
```

重点保证下面这一正常流程可以执行：

```bash
pnpm dev:base

# 修改 src

pnpm sync:base

pnpm build:base
```

`sync:base` 后：

```text
src
```

与重新 Assembly 的 Base 应完全一致，因此不能出现：

```text
WORKSPACE_SOURCE_DRIFT
```

---

# 12. 增加/调整 Base Sync 测试

重点修改：

```text
assembly/test-workspace-sync.mjs
```

如果其他 Extension 测试已经完成，只补 Base Case。

至少覆盖以下场景。

### Case 1：已有 Base 文件更新

Source：

```text
base/src/layout/index.tsx = old
```

Workspace：

```text
src/layout/index.tsx = new
```

执行：

```text
syncWorkspace("base")
```

断言：

```text
base/src/layout/index.tsx = new
```

---

### Case 2：Base 新建文件

Workspace 新增：

```text
src/components/New/index.tsx
```

断言生成：

```text
base/src/components/New/index.tsx
```

---

### Case 3：Base 删除文件

Source 存在：

```text
base/src/utils/obsolete.ts
```

Workspace 不存在。

Sync 后 Source 文件必须删除。

---

### Case 4：Base 宿主文件可以修改

重点测试：

```text
src/providers/AppProviders.tsx
```

修改后：

```text
base/src/providers/AppProviders.tsx
```

必须同步更新。

这用于防止之前的 Assembly Managed 问题重新出现。

---

### Case 5：Generated 文件不回写

Workspace：

```text
src/generated/extensions/providers.ts
```

即使存在或变化，也不能生成：

```text
base/src/generated/extensions/providers.ts
```

---

### Case 6：无变化不产生 UPDATE

Workspace 与 Base 内容一致：

```text
changes = []
```

或至少对应文件不能出现在变更列表。

---

# 13. 更新 Base 开发文档

只修改 Base 对应章节。

最终文档统一为：

```text
开发 Base：

pnpm dev:base

修改：

src/**

保存修改：

pnpm sync:base

验证：

pnpm build:base
```

完整流程：

```bash
pnpm dev:base

# 修改 src/**

pnpm sync:base
pnpm build:base
```

并明确：

```text
base/src/**
```

是 Base 的持久化源码。

正常模板开发不需要直接编辑它。

---

# 14. Codex 实施步骤

按以下顺序执行：

```text
Step 1
确认 profiles.json 中：
base.editTarget = "base"

Step 2
确认 workspace-sync.mjs 支持：
profile=base
owners=[base]
editTarget=base

Step 3
删除任何 Base 专用旧同步逻辑，
统一使用 syncWorkspace("base")

Step 4
确认 ASSEMBLY_MANAGED_FILES
只包含 generated/extensions/*

Step 5
确保 AppProviders、rootRoutes、
systemPageRoutes、appInitializers、
errorReporter 可以正常回写 Base

Step 6
补充 Base Workspace Sync 测试

Step 7
验证 dev:base → sync:base → build:base

Step 8
更新 TEMPLATE_DEVELOPMENT.md 中 Base 部分
```

---

# 15. 最终验收

必须手工完成以下完整测试：

```bash
pnpm dev:base
```

然后在 `src`：

```text
1. 修改一个已有 Base 文件
2. 修改 AppProviders.tsx
3. 新增一个普通文件
4. 删除一个普通 Base 文件
```

执行：

```bash
pnpm sync:base
```

检查：

```text
已有文件正确更新到 base/src

AppProviders 正确更新到 base/src

新增文件进入 base/src

删除文件从 base/src 删除

generated/extensions/* 没有进入 base/src
```

然后：

```bash
pnpm build:base
```

必须成功。

最后再次：

```bash
pnpm reset:base
```

重新生成的：

```text
src
```

必须完整保留刚才的 Base 修改。

这证明：

```text
src
→ sync:base
→ base/src
→ assemble
→ src
```

已经形成可持续的 Base 开发闭环。

这样改造量会很小：**不再动已经完成的 Extension 体系，只把 Base 接入当前已经形成的统一 Workspace 开发模型，并重点补齐 `sync:base` 的新增、修改、删除和宿主文件回写测试。**
