# 生成前端的结构与服务边界

前端使用 React、Rsbuild 和现有页面路由投影。业务页面放 src/pages，API 访问层放 src/apis，复用既有 bootstrap、扩展注册表和身份映射；业务路由由 Route Projector 维护受管标记。

Java 应用默认 /api 代理到本地 8080。Runtime 应用由 Studio 注入仅供开发服务器使用的 AGENT_RUNTIME_PUBLIC_URL，代理 /api、/agents、/_runtime 和 /__runtime/health；浏览器仍同源访问，不保存模型/数据库密钥。AG-UI 调用使用 @ag-ui/client 和 @ag-ui/core。

登录/授权按已启用能力加载；当前 Runtime 不开放这些扩展。不要把未启用能力的代码预先写入页面。调试检查器和页面身份路径保持现有合同。
