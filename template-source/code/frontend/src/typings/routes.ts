/** 页面、目录与外链共用的唯一配置模型。 */
export type PageRouteDefinition = {
  /** 内部节点使用小写 snake_case 路径段；HTTP(S) URL 自动识别为外链。 */
  path: string;
  label: string;
  icon?: string;
  resourceKey?: string;
  /** false 时仍注册内部页面，但不展示在菜单或默认入口中。 */
  visible?: boolean;
  /** 仅外链使用，控制链接打开方式。 */
  target?: string;
  children?: PageRouteDefinition[];
};
