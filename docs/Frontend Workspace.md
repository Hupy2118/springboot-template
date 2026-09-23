# Frontend Workspace 与 Base 边界修复方案

## 一、最终目标

将当前：

```text
template-source/code/frontend
├── base/src/
├── extensions/
├── assembly/
├── src/                    # Assembly Workspace
├── package.json
├── index.html
├── vite.config.ts
├── public/
├── scripts/
└── ...
```

调整为：

```text
template-source/code/frontend
├── base/
│   ├── src/
│   ├── public/
│   ├── scripts/
│   ├── index.html
│   ├── Dockerfile
│   ├── package.json
│   ├── pnpm-lock.yaml
│   ├── pnpm-workspace.yaml
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── tsconfig.test.json
│   ├── jest.config.cjs
│   ├── postcss.config.js
│   ├── tailwind.config.js
│   ├── rsbuild_copy.config.ts
│   ├── README.md
│   ├── AGENTS.md
│   └── ...
│
├── extensions/
│   ├── login/
│   └── authorization/
│
├── assembly/
│
└── workspace/
    └── .gitkeep
```

核心模型变成：

```text
Base 完整工程
+
Selected Extensions
+
Assembly Generated Contributions
            ↓
        Workspace
```

这与 Backend 完全一致。

---

# 二、Base 的定义

Frontend Base 不再只是：

```text
base/src/**
```

而是：

> 一个不启用任何 Extension 时，能够独立安装、启动、测试和构建的完整前端工程。

因此以下文件都属于 Base：

```text
base/
├── src/**
├── public/**
├── scripts/**
├── index.html
├── Dockerfile
├── package.json
├── pnpm-lock.yaml
├── pnpm-workspace.yaml
├── vite.config.ts
├── tsconfig.json
├── tsconfig.test.json
├── jest.config.cjs
├── postcss.config.js
├── tailwind.config.js
├── rsbuild_copy.config.ts
├── README.md
└── AGENTS.md
```

判断标准非常简单：

> 如果没有 Login、Authorization 等 Extension，这个文件是否仍然是正常运行该前端工程所必需的？

如果是，就属于 Base。

---

# 三、Extension 只拥有增量能力

Extension 继续保持：

```text
extensions/login/
├── extension.yaml
└── src/**

extensions/authorization/
├── extension.yaml
└── src/**
```

如果未来某个 Extension 确实需要提供非 `src` 文件，例如：

```text
public/**
scripts/**
```

则可以后续扩展 Assembly 的 Source Tree Merge 范围。

当前 Login / Authorization 没有该需求，不要提前设计复杂机制。

---

# 四、Step 1：把前端根级运行文件迁入 Base

将当前位于：

```text
template-source/code/frontend/
```

的应用运行文件移动到：

```text
template-source/code/frontend/base/
```

至少包括：

```text
.gitignore
AGENTS.md
Dockerfile
README.md
index.html
jest.config.cjs
package.json
pnpm-lock.yaml
pnpm-workspace.yaml
postcss.config.js
public/**
rsbuild_copy.config.ts
scripts/**
tailwind.config.js
tsconfig.json
tsconfig.test.json
vite.config.ts
```

注意：

以下文件不属于 Base：

```text
TEMPLATE_DEVELOPMENT.md
base.md
extension.md
assembly/**
extensions/**
workspace/**
```

它们属于模板维护体系。

---

# 五、Step 2：将 Base 从 `base/src` 提升为完整 `base/`

当前 Assembly：

```text
copy base/src
    ↓
src
```

改为：

```text
copy base/**
    ↓
workspace/**
```

但必须排除任何 Base 内部维护元数据，如果后续存在。

Assembler 的基础逻辑改为：

```text
workspace = copyTree(base)
```

而不是：

```text
workspace/src = copyTree(base/src)
```

这样 Workspace 天然就是完整工程：

```text
workspace/
├── package.json
├── vite.config.ts
├── public/
├── scripts/
└── src/
```

---

# 六、Step 3：Extension 继续按相对路径合并

当前 Extension 的：

```text
extensions/<id>/src/**
```

直接按相对路径合并到：

```text
workspace/src/**
```

即：

```text
extensions/login/src/pages/Login/index.tsx
        ↓
workspace/src/pages/Login/index.tsx
```

未来若支持 Extension 根级文件，仍遵循：

```text
Extension 内相对路径
        ↓
Workspace 同相对路径
```

不需要重新定义 Target Mapping。

---

# 七、Step 4：Assembly Generated 文件写入 Workspace

继续生成：

```text
workspace/src/extensions/providers.ts
workspace/src/extensions/rootRoutes.tsx
workspace/src/extensions/systemPageRoutes.ts
workspace/src/extensions/initializers.ts
workspace/src/extensions/errorReporters.ts
```

以及：

```text
workspace/.devagentstudio-template-generated.json
workspace/.devagentstudio-template-workspace.json
```

这些是 Assembly 产物，不属于 Base 或 Extension。

---

# 八、Step 5：将开发 Workspace 从根 `src` 改为 `workspace`

删除现有：

```text
template-source/code/frontend/src/
```

所有 Assembly / Dev / Build / Sync 操作统一针对：

```text
template-source/code/frontend/workspace/
```

开发链路统一为：

```text
Base + Extensions
        ↓
Assembly
        ↓
workspace
        ↓
开发 / Build / Test
        ↓
Sync
        ↓
Base / Extension
```

与 Backend：

```text
base + extensions
        ↓
workspace
```

保持一致。

---

# 九、Step 6：调整 Source Ownership

当前 Ownership 主要针对：

```text
src/**
```

需要改为：

```text
Base
= base/**

Extension
= extensions/<id>/**
```

第一阶段 Extension 实际仍只有 `src/**`。

因此：

```text
workspace/package.json
    → owner: base

workspace/vite.config.ts
    → owner: base

workspace/public/favicon.svg
    → owner: base

workspace/src/App.tsx
    → owner: base

workspace/src/pages/Login/index.tsx
    → owner: login
```

这样用户在 Workspace 中修改：

```text
vite.config.ts
```

执行 Sync 后，会自然回写：

```text
base/vite.config.ts
```

这比单独设计 Runtime Root Source 清晰很多。

---

# 十、Step 7：修改 Workspace Sync

Sync 从：

```text
frontend/src/**
```

调整为：

```text
frontend/workspace/**
```

规则统一为：

```text
已有文件
    ↓
查询 Source Owner
    ↓
回写对应 Base / Extension

新文件
    ↓
当前 Profile editTarget

删除文件
    ↓
删除对应 Owner 文件
```

例如：

```text
Base Profile 新增：

workspace/scripts/foo.mjs

        ↓

base/scripts/foo.mjs
```

Login Profile 新增：

```text
workspace/src/pages/Login/helper.ts

        ↓

extensions/login/src/pages/Login/helper.ts
```

---

# 十一、Step 8：Generated 文件必须继续隔离

以下文件不得 Sync：

```text
workspace/src/extensions/**
workspace/.devagentstudio-template-generated.json
workspace/.devagentstudio-template-workspace.json
```

它们由 Assembly 管理。

不要允许 Workspace Sync 把这些文件写入：

```text
base/
extensions/
```

---

# 十二、Step 9：根 package.json 改为模板维护入口

由于真正应用的：

```text
package.json
```

现在迁入：

```text
base/package.json
```

建议 `frontend/` 根目录保留一个非常薄的：

```text
package.json
```

仅用于模板维护 CLI。

例如职责只包括：

```text
dev:base
dev:login
dev:authorization
dev:full

build:base
build:login
build:authorization
build:full

sync:*
reset:*
verify:update:*
test:assembly
```

不要再把这个根 `package.json` 当用户应用 package.json。

也就是说：

```text
frontend/package.json
    = Template Maintainer Tooling

frontend/base/package.json
    = User Application Runtime
```

这两个概念必须明确区分。

---

# 十三、Step 10：Dev / Build 必须在 Workspace 中执行

例如：

```text
pnpm dev:authorization
```

内部：

```text
assemble authorization
        ↓
workspace
        ↓
在 workspace 执行 pnpm dev
```

而：

```text
pnpm build:authorization
```

内部：

```text
assemble authorization
        ↓
workspace
        ↓
pnpm build
```

不再依赖：

```text
frontend 根目录 vite.config
frontend 根目录 src
```

---

# 十四、Step 11：删除 `base.yaml`

删除：

```text
template-source/code/frontend/base.yaml
```

因为 Base 现在已经是一个真实完整目录：

```text
base/**
```

不需要额外 Manifest 再枚举：

```text
source → target
```

Base 的发布边界天然就是：

```text
base directory tree
```

加上：

```text
selected Extension tree
+
Assembly generated files
```

---

# 十五、不再需要 `release-boundary.json`

前一版方案中提出：

```text
release-boundary.json
```

是因为 Runtime 文件仍散落在 Frontend 根目录。

现在既然所有用户工程运行文件都移动到：

```text
base/**
```

那么这个 Contract 就没有必要了。

最终 Release 本身就是：

```text
assemble(profile)
        ↓
clean workspace
```

也就是说：

> Workspace 去掉维护 Metadata 后，本身就是 Release。

这反而比增加 Release Boundary Contract 更简单。

---

# 十六、Workspace 与 Release 的关系

改造后：

```text
Base
+
Extensions
+
Assembly
        ↓
Workspace
```

Workspace 是：

```text
完整、可运行、可构建的用户工程
```

只比最终 Release 多：

```text
.devagentstudio-template-workspace.json
.devagentstudio-template-generated.json
```

因此正式 Release 只需要：

```text
Workspace
    ↓
去掉维护 Metadata
    ↓
Release
```

不需要再重新复制一次整个 Source Model。

---

# 十七、Step 12：调整 `verify-generated.mjs`

原逻辑：

```text
root src
VS
临时 Assembly
+
base.yaml 检查
```

改为：

```text
workspace
VS
重新执行相同 Profile Assembly
```

验证：

```text
Workspace 非开发变更
==
Base + Extension + Generated Contributions
```

删除所有：

```text
base.yaml
```

相关检查。

---

# 十八、Step 13：新增 Release 验证

可以保留：

```text
verify-release.mjs
```

但职责非常简单。

对临时 Assembly Workspace：

```text
删除两个维护 Metadata
```

然后检查：

```text
不存在：
base/
extensions/
assembly/
workspace/
模板维护 package.json
模板开发文档
```

因为这些文件本来就在 Workspace 外，所以正常情况下天然满足。

---

# 十九、最终目录模型

最终：

```text
template-source/code/frontend/
│
├── base/                           # 完整 Base 前端工程
│   ├── package.json
│   ├── pnpm-lock.yaml
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   ├── public/
│   ├── scripts/
│   └── src/
│
├── extensions/
│   ├── login/
│   │   ├── extension.yaml
│   │   └── src/
│   │
│   └── authorization/
│       ├── extension.yaml
│       └── src/
│
├── assembly/                       # Template Compiler / Sync
│
├── workspace/                      # 完整组装工程
│   └── .gitkeep
│
├── package.json                    # 仅模板维护 CLI
├── TEMPLATE_DEVELOPMENT.md
├── base.md
└── extension.md
```

这个结构和 Backend 对齐：

```text
frontend/base
backend/base
        ↓
完整基础工程

frontend/extensions
backend/extensions
        ↓
增量能力

frontend/workspace
backend/workspace
        ↓
完整组装工程
```

---

# 二十、Codex 实施顺序

按以下顺序实施，避免中间状态破坏开发命令：

1. 将根级应用运行文件移动到 `base/`。
2. 新建根级 Template Maintenance `package.json`。
3. 修改 Assembly：`base/** → workspace/**`。
4. 修改 Extension Merge：`extensions/*/src → workspace/src`。
5. 修改 Generated Contribution 输出路径到 `workspace/src/generated/**`。
6. 修改 Workspace State 路径。
7. 修改 Source Ownership 支持 Base 全目录。
8. 修改 Sync 输入为 `workspace/**`。
9. 修改 Dev / Build 在 `workspace/` 内执行。
10. 删除旧根 `src/`。
11. 删除旧 `base.yaml`。
12. 删除所有 `base.yaml` 依赖。
13. 修改 `verify-generated.mjs`。
14. 补充 Release 验证。
15. 更新 `TEMPLATE_DEVELOPMENT.md`。
16. 执行所有 Base / Login / Authorization / Full 验证。

---

# 二十一、必须覆盖的测试

至少验证：

1. Base Assembly 输出完整 `workspace/package.json`。
2. Base Assembly 输出 `workspace/index.html`。
3. Base Assembly 输出 `workspace/vite.config.ts`。
4. Base Assembly 输出 `workspace/public/**`。
5. Base Assembly 输出 `workspace/scripts/**`。
6. Base Assembly 输出 `workspace/src/**`。
7. Login Assembly 在 Base 上增加 Login 文件。
8. Authorization 自动包含 Login。
9. Workspace `pnpm install/build` 可运行。
10. Workspace 不依赖 Frontend 根应用文件。
11. 修改 `workspace/vite.config.ts` 后 Sync 回 `base/vite.config.ts`。
12. 修改 `workspace/src/App.tsx` 后 Sync 回 Base。
13. 修改 Login 文件后 Sync 回 Login Extension。
14. 新增 Base 根级文件可正确 Sync。
15. Generated Registry 不进入 Base。
16. Workspace State 不进入 Base。
17. `frontend/src` 不再存在。
18. `base.yaml` 不再存在。
19. Base 单独可以运行和构建。
20. Full 可以运行和构建。

---

# 二十二、最终设计原则

不要再区分：

```text
Base Source
+
Runtime Root Source
```

它们本来就是一个东西。

正确模型就是：

```text
Base
= 完整基础应用工程

Extension
= 可选增量源码 / Contribution

Assembly
= Base + Extension 的组合器

Workspace
= 完整组装结果
```

这样 Frontend 和 Backend 的模板开发模型才能真正一致。
