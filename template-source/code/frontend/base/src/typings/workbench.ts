// 导航布局类型
export enum LayoutTypeEnum {
  TOP = 'top', // 顶部菜单布局
  SIDE = 'side', // 侧边菜单布局
  MIX = 'mix', // 混合菜单布局（水平一级菜单，垂直子菜单）
}

export type MenuDataItem = {
  /** @name 子菜单 */
  children?: MenuDataItem[];
  routes?: undefined;
  /** @name 在菜单中隐藏子节点 */
  hideChildrenInMenu?: boolean;
  /** @name 在菜单中隐藏自己和子节点 */
  hideInMenu?: boolean;
  /** @name 菜单的icon */
  icon?: React.ReactNode;
  /** @name 自定义菜单的国际化 key */
  locale?: string | false;
  /** @name 菜单的名字 */
  name?: string;
  /** @name 用于标定选中的值，默认是 path */
  /** 如果渲染的是特定页面，key必须存在，且与src/page下面的page的引用地址保持一致 */
  key?: string;
  /** @name disable 菜单选项 */
  disabled?: boolean;
  /** @name disable menu 的 tooltip 菜单选项 */
  disabledTooltip?: boolean;
  /** @name 路径,可以设定为网页链接 */
  path?: string;
  /**
   * 当此节点被选中的时候也会选中 parentKeys 的节点
   *
   * @name 自定义父节点
   */
  parentKeys?: string[];
  /** @name 隐藏自己，并且将子节点提升到与自己平级 */
  flatMenu?: boolean;
  /** @name 指定外链打开形式，同a标签 */
  target?: string;
  /**
   * menuItem 的 tooltip 显示的路径
   */
  tooltip?: string;
  [key: string]: any;
};

export type Route = Omit<MenuDataItem, 'routes'> & {
  children?: Route[];
};
