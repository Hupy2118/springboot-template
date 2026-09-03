import React from 'react';
import * as Icons from '@ant-design/icons';

export const renderIcon = (icon?: any, className?: string) => {
  if (!icon) return null;

  // 如果传入的是字符串，尝试解析为antd图标或者资源中心的图标
  if (typeof icon === 'string') {
    const antIcons: { [key: string]: any } = Icons;
    
    if (antIcons?.[icon]) {
      // 1. 渲染antd的图标
      return React.createElement(antIcons?.[icon], { className });
    } else {
      // 2. 渲染资源中心、用户上传的图标
      return (
        <img
          alt={icon}
          src={icon}
          style={{ width: '1em', height: '1em', verticalAlign: 'text-bottom' }}
          className={className}
        />
      );
    }
  }
};

export function openNewPage(
  url: string,
  target: string,
  callback?: (element: HTMLAnchorElement) => void,
) {
  if (!url) {
    return false;
  }
  const anchorElement: HTMLAnchorElement = document.createElement('a');
  anchorElement.href = url;
  anchorElement.target = target ?? '_self';
  typeof callback === 'function' && callback(anchorElement);
  document.body.appendChild(anchorElement);
  anchorElement.click();
  document.body.removeChild(anchorElement);
}
