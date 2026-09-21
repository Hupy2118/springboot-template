# 前端模板开发指引

本文用于说明如何开发、更新和发布 `template-source/code/frontend` 下的 Base、Login、Authorization 模板。

## 1. 基本原则

开发模板时统一遵循以下流程：

```text id="4esvd9"
选择开发对象
↓
启动开发环境
↓
修改 src/**
↓
同步模板源码
↓
构建验证
↓
提交发布
```

开发过程中只需要修改：

```text id="39jgrt"
template-source/code/frontend/src/**
```

不要直接修改：

```text id="8k4euc"
base/src/**
extensions/*/src/**
```

模板源码由同步命令自动更新。

---

## 2. 开发 Base

启动：

```bash id="iyfpqc"
pnpm dev:base
```

然后正常修改：

```text id="43pwb4"
src/**
```

开发确认后同步：

```bash id="dzc3wa"
pnpm sync:base
```

验证：

```bash id="xx0sc8"
pnpm build:base
```

完整流程：

```bash id="w26p92"
pnpm dev:base

# 修改 src/**

pnpm sync:base
pnpm build:base
```

Base 是公共模板，修改后建议同时验证：

```bash id="ndy6qx"
pnpm build:login
pnpm build:authorization
pnpm build:full
```

---

## 3. 开发 Login

启动：

```bash id="dhsdrt"
pnpm dev:login
```

修改：

```text id="9c957g"
src/**
```

开发确认后：

```bash id="1v1xkg"
pnpm sync:login
pnpm build:login
```

完整流程：

```bash id="ms5fz4"
pnpm dev:login

# 修改 src/**

pnpm sync:login
pnpm build:login
```

由于 Authorization 依赖 Login，Login 修改后建议继续验证：

```bash id="p0fu2f"
pnpm build:authorization
pnpm build:full
```

---

## 4. 开发 Authorization

启动：

```bash id="b7n0dz"
pnpm dev:authorization
```

修改：

```text id="1gc2cw"
src/**
```

开发确认后：

```bash id="69rf8g"
pnpm sync:authorization
pnpm build:authorization
```

完整流程：

```bash id="a0kkbg"
pnpm dev:authorization

# 修改 src/**

pnpm sync:authorization
pnpm build:authorization
pnpm build:full
```

---

## 5. Full 模式

Full 用于完整模板联调：

```bash id="ax7046"
pnpm dev:full
```

主要用于检查：

```text id="9r392u"
Base
Login
Authorization
```

组合后的整体运行效果。

不建议在 Full 模式下开发新的模板文件。

---

## 6. 放弃本次修改

如果当前 `src` 中的修改不需要保留，可以执行：

```bash id="ox0132"
pnpm reset:base
```

或者：

```bash id="kbbauf"
pnpm reset:login
pnpm reset:authorization
```

`reset` 会重新根据模板源码生成 `src`，未同步的修改会被丢弃。

---

## 7. 修改 Extension 配置

如果只是修改普通页面、组件、Hook、API 等代码：

```text id="qb6xqw"
直接修改 src/**
```

如果需要调整：

```text id="bcwbop"
Provider
路由
初始化逻辑
Extension 依赖
```

则修改对应：

```text id="w1y84v"
extensions/<extension>/extension.yaml
```

不要直接修改 Assembly 自动生成文件。

---

## 8. 发布前检查

完成开发后执行对应构建。

### Base

```bash id="bj06ge"
pnpm build:base
pnpm build:login
pnpm build:authorization
pnpm build:full
```

### Login

```bash id="56y2bz"
pnpm build:login
pnpm build:authorization
pnpm build:full
```

### Authorization

```bash id="r6ifvp"
pnpm build:authorization
pnpm build:full
```

然后检查：

```bash id="3hd60a"
git status
git diff
git diff --check
```

确认修改符合预期后提交代码。

---

## 9. 常用命令

| 操作   | Base              | Login              | Authorization              |
| ---- | ----------------- | ------------------ | -------------------------- |
| 开发   | `pnpm dev:base`   | `pnpm dev:login`   | `pnpm dev:authorization`   |
| 更新模板 | `pnpm sync:base`  | `pnpm sync:login`  | `pnpm sync:authorization`  |
| 构建验证 | `pnpm build:base` | `pnpm build:login` | `pnpm build:authorization` |
| 放弃修改 | `pnpm reset:base` | `pnpm reset:login` | `pnpm reset:authorization` |

日常开发只需要记住：

```text id="fcx3cx"
dev
→ 修改 src
→ sync
→ build
→ commit
```
