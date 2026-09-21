/** 错误上报接口；当前模板未配置外部上报服务。 */
export function reportError(_error: Error, _info?: unknown) {
  // 将来由模板编译阶段静态接入具体上报实现。
}
