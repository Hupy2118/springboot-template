/**
 * 全局通用请求定义
 */

import service from './service';

export const getServerData = () => {
  return service.get('/api/welcome');
};

export default getServerData;
