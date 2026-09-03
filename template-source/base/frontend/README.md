# 低代码应用模板骨架

基于 React 18 + TypeScript + Ant Design 的低代码应用模板，使用 RSBuild (Rspack) 作为构建工具，提供完整的页面布局、路由管理、状态管理和 API 请求封装。


- **前端框架**: React 18.2
- **类型系统**: TypeScript 5.3
- **UI 组件库**: Ant Design ^5.22.6
- **路由管理**: React Router DOM 6.x
- **构建工具**: RSBuild (Rspack)
- **HTTP 客户端**: Axios 1.x
- **样式方案**: Less + CSS Modules + Tailwind CSS
- **代码规范**: ESLint + Prettier + Husky + lint-staged


- Node.js >= 16
- pnpm (推荐使用)


```bash
# 安装依赖
pnpm install

# 启动开发服务器
pnpm start

# 构建生产环境
pnpm build

# 构建开发环境
pnpm build:dev

# 构建预发环境
pnpm build:st

# 构建 UAT 环境
pnpm build:uat

# 代码质量检查
pnpm quality

# 代码格式化
pnpm prettier
```


```
├── .cmbjs.env.js              # CMB JS 环境配置文件
├── .cmbQuality.config.js      # 代码质量配置
├── .eslintcmb.js              # ESLint 自定义规则
├── .eslintrc.js               # ESLint 配置
├── .prettierrc.js             # Prettier 配置
├── Dockerfile                 # Docker 容器化部署配置
├── package.json               # 项目依赖与脚本配置
├── rsbuild.config.ts          # RSBuild 构建配置（核心）
├── tailwind.config.js         # Tailwind CSS 配置
├── theme.config.json          # Ant Design 主题配置
├── tsconfig.json              # TypeScript 配置
├── README.md                  # 项目说明文档
│
├── public/                    # 静态资源目录（不经过构建处理）
│   ├── favicon.ico            # 网站图标
│   ├── index.html             # HTML 入口模板
│   └── static/js/             # 第三方 JS 库（预构建）
│       ├── axios.min.js
│       ├── react-dom.production.min.js
│       ├── react-router-dom.production.min.js
│       ├── react-router.production.min.js
│       ├── react.production.min.js
│       └── router.umd.min.js
│
└── src/                       # 源代码目录
    ├── index.tsx              # 应用入口文件（挂载 React 根组件）
    ├── App.tsx                # 应用根组件（BrowserRouter + ErrorBoundary + Router）
    ├── react-app-env.d.ts     # React App 类型声明
    ├── setupProxy.js          # 开发代理配置
    ├── setupTests.ts          # 测试环境配置
    │
    ├── apis/                  # API 请求层
    │   ├── service.ts         # Axios 封装类（请求/响应拦截器、通用方法）
    │   ├── login.ts           # 登录相关 API
    │   └── index.ts           # API 统一导出
    │
    ├── assets/                # 静态资源（图片、SVG 等，经过构建处理）
    │   ├── xpro_app_icon_bg.svg
    │   └── appIconList/       # 应用图标集合（18 个 SVG 图标 + index 导出）
    │       ├── icon1.svg ~ icon21.svg
    │       └── index.tsx      # 图标统一导出
    │
    ├── components/            # 公共组件
    │   └── ErrorBoundary/     # 错误边界组件（捕获子组件渲染错误）
    │       └── index.tsx
    │
    ├── constants/             # 全局常量配置
    │   ├── common.ts          # 通用常量（前缀类名等）
    │   ├── index.ts           # 常量统一导出（PAGE_ROUTE 等）
    │   ├── routes.ts          # 路由常量（菜单列表 MENU_LIST）
    │   └── yst.ts             # YST 相关常量
    │
    ├── hooks/                 # 自定义 React Hooks
    │   └── useGuard.ts        # 权限守卫 Hook（鉴权逻辑）
    │
    ├── layout/                # 布局组件
    │   ├── index.tsx          # 主布局组件（LeftRightOne：左右布局）
    │   ├── index.less         # 布局样式
    │   ├── LeftRightOne.less  # 左右布局样式
    │   └── components/
    │       ├── header/        # 顶部导航栏
    │       │   ├── MobileAppHeader/   # 移动端头部
    │       │   └── PCAppHeader/       # PC 端头部
    │       │       ├── AppHeader.less
    │       │       ├── index.tsx
    │       │       └── AppIcon/       # 应用图标组件
    │       │           ├── AppIcon.less
    │       │           └── index.tsx
    │       ├── UserInfo/      # 用户信息组件
    │       │   ├── constants.ts
    │       │   ├── index.less
    │       │   ├── index.tsx
    │       │   ├── utils.ts
    │       │   └── UserAvatar/    # 用户头像组件
    │       │       ├── index.tsx
    │       │       └── UserAvatar.less
    │       └── menus/         # 菜单组件
    │           ├── FirstLevelMenu/   # 一级菜单
    │           │   ├── FirstLevelMenu.less
    │           │   └── index.tsx
    │           ├── FullSiderMenu/    # 完整侧边栏菜单
    │           │   └── index.tsx
    │           ├── HorizontalMenu/   # 水平菜单
    │           │   ├── HorizontalMenu.less
    │           │   └── index.tsx
    │           └── SiderMenu/        # 侧边栏菜单
    │               └── index.tsx
    │
    ├── pages/                 # 页面组件
    │   ├── index.tsx          # 主内容入口（MainContent，渲染布局 + 菜单）
    │   ├── DefaultPage/       # 默认页面
    │   │   └── index.tsx
    │   ├── Login/             # 登录页面
    │   │   └── index.tsx
    │   └── Logout/            # 登出页面
    │       └── index.tsx
    │
    ├── providers/             # Context Providers
    │   └── index.tsx          # 全局上下文提供者（用户信息 + 权限信息）
    │
    ├── routes/                # 路由配置
    │   └── index.tsx          # 路由定义（自动生成路由树，包裹 Suspense）
    │
    ├── styles/                # 全局样式
    │   ├── index.less         # 样式入口
    │   ├── theme.less         # 主题样式
    │   └── variable.less      # 样式变量
    │
    ├── typings/               # TypeScript 类型定义
    │   ├── index.ts           # 类型统一导出（IGlobalContext, IUserInfo, IAuthInfo 等）
    │   ├── routes.ts          # 路由类型定义
    │   └── workbench.ts       # 工作台类型定义（菜单类型、布局类型枚举等）
    │
    └── utils/                 # 工具函数
        └── workbench.ts       # 工作台相关工具函数
```



应用采用 React Router DOM 6.x 嵌套路由设计：

```
/                          → GlobalContextProvider（全局上下文）
├── /page                  → MainContent（主内容 + 布局）
│   ├── /page/default      → DefaultPage（默认页面）
├── /login                 → Login（登录页）
├── /logout                → Logout（登出页）
└── *                      → 404 页面
```


默认采用 **左右布局（LEFT_RIGHT_ONE）**：
- **左侧**：多级菜单导航（支持一级菜单、完整侧边栏、水平菜单等模式）
- **右侧**：顶部用户信息栏 + 主内容区域

布局类型通过 `PC_LAYOUT_TEMPLATE_TYPE` 枚举定义，支持多种布局模式切换。


使用 React Context 进行全局状态管理：
- `userInfo`: 用户信息（用户名、工号、用户ID）
- `authInfo`: 权限信息
- `useGuard` Hook 负责鉴权逻辑


`Service` 类封装了 Axios，提供：
- 统一的请求/响应拦截器
- GET / POST / PUT / DELETE 方法
- 请求头自动注入（BusinessId、TraceBaggage 等追踪信息）
- 支持动态设置 Authorization Token


RSBuild 配置了完整的路径别名，简化导入：

| 别名 | 映射路径 |
|------|----------|
| `@` | `./src` |
| `@routes` | `./src/routes` |
| `@pages` | `./src/pages` |
| `@providers` | `./src/providers` |
| `@components` | `./src/components` |
| `@apis` | `./src/apis` |
| `@utils` | `./src/utils` |
| `@styles` | `./src/styles` |
| `@typings` | `./src/typings` |
| `@constants` | `./src/constants` |
| `@assets` | `./src/assets` |
| `@hooks` | `./src/hooks` |
| `@store` | `./src/store` |
| `@metadata` | `./metadata` |


以下环境变量在构建时注入：

| 变量名 | 说明 |
|--------|------|
| `WORKS_ENV` | 运行环境标识 |
| `PUBLIC_URL` | 资源前缀 |
| `TRACKING_UPLOAD_ID` | 埋点上传 ID |
| `TRACKING_API_HOST` | 埋点 API 地址 |
| `BUSINESS_ID` | 业务 ID |
| `TRACE_BAGGAGE` | 链路追踪信息 |
| `TRACKING_API_TRACK_HOST` | 埋点追踪 API 地址 |
| `AAA` | 自定义环境变量 |
| `BBB` | 自定义环境变量 |


项目集成了完整的代码规范体系：

- **ESLint**: 代码质量检查
- **Prettier**: 代码格式化
- **Husky + lint-staged**: Git 提交前自动检查和格式化
- **cmb-quality**: 自定义代码质量工具


构建产物输出到 `build/` 目录。


通过 `theme.config.json` 文件可以自定义 Ant Design 主题变量，构建时通过 Less 加载器注入。


项目集成了 Tailwind CSS，可在组件中直接使用 Tailwind 工具类进行样式编写。