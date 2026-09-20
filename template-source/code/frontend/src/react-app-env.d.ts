/// <reference types="vite/client" />

/**
 * svg 图片当做 component 引入
 */
declare module '*.svg' {
  import * as React from 'react';

  export const ReactComponent: React.FunctionComponent<React.SVGProps<SVGSVGElement> & { title?: string }>;

  const src: string;
  export default src;
}

declare module '*.svg?react' {
  import * as React from 'react';
  const ReactComponent: React.FunctionComponent<React.SVGProps<SVGSVGElement> & { title?: string }>;
  export default ReactComponent;
}

/**
 * CSS-Module 规范下 LESS 样式文件声明
 */
declare module '*.module.less' {
  const classes: { readonly [key: string]: string };
  export default classes;
}

/**
 * 通过 import 导入 .less 文件的类型声明
 */
declare module '*.less' {
  const content: string;
  export default content;
}

/**
 * 包声明
 */
declare module '@lf12.32/front-web-lib' {
  import cmbTrackLib from '@lf12.32/front-web-lib';
  export default cmbTrackLib;
}
