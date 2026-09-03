import axios, { AxiosInstance, AxiosError, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { AUTHORIZATION_KEY } from '@/constants';
export type DataType = string | number | Object;
export type ReqFulfilledType = (config: InternalAxiosRequestConfig) => InternalAxiosRequestConfig;
export type ResFulfilledType = (response: AxiosResponse) => AxiosResponse['data'];
export type ResRejectedType = (error: AxiosError<{ message: string }>) => void;

// 请求体处理
const defaultReqFulfilled: ReqFulfilledType = (config) => {
  try {
    // 每次请求前接口携带最新的Authorization字段到请求头
    const latestAuthorization = sessionStorage.getItem(AUTHORIZATION_KEY);
    if(latestAuthorization) {
      config.headers[AUTHORIZATION_KEY] = latestAuthorization;
    }
    return config;
  } catch {
    return config;
  }
};
// 响应体处理
const defaultResFulfilled: ResFulfilledType = (response) => {
  // 取出最新的Authorization，存储到SessionStorage
  if(response.headers?.[AUTHORIZATION_KEY]) {
    sessionStorage.setItem(AUTHORIZATION_KEY, response.headers?.authorization);
  }
  return response.data;
};

// 响应错误处理
const defaultResRejected: ResRejectedType = async (error) => {
  const errResponseBody: any = error.response?.data;
  // 处理全局401报错，自动跳转登录地址
  if(errResponseBody && errResponseBody?.errorCode === 'SSO0401' && errResponseBody?.type === 'redirect') {
    // todo 替换为真正的跳转方法
    console.log('接下来要跳转的登录地址: ', errResponseBody?.loginUri)
  }
};

const defaultConfig: AxiosRequestConfig = {
  baseURL: '/',
  timeout: 600 * 1000,
  withCredentials: true,
  responseType: 'json',
  headers: {
    'Content-Type': 'application/json',
    'X-B3-BusinessId': process.env.BUSINESS_ID,
    'X-B3-TraceBaggage': process.env.TRACE_BAGGAGE
  },
};

class Service {
  private axios: AxiosInstance;

  constructor({
    config = {},
    onReqFulfilled = defaultReqFulfilled,
    onResFulfilled = defaultResFulfilled,
    onResRejected = defaultResRejected,
  }) {
    this.axios = axios.create(Object.assign({ ...defaultConfig }, config));
    this.axios.interceptors.request.use(onReqFulfilled);
    this.axios.interceptors.response.use(onResFulfilled, onResRejected);
  }

  get<T>(url: string, params?: DataType, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.get(url, {
      params,
      ...config,
    }) as unknown as Promise<T>;
  }

  post<T>(url: string, data?: DataType, config: AxiosRequestConfig = {}): Promise<{ data: T }> {
    return this.axios.post(url, data, config);
  }

  put<T>(url: string, data?: DataType, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.put(url, data, config) as unknown as Promise<T>;
  }

  delete<T>(url: string, data?: DataType, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.delete(url, {
      data,
      ...config,
    }) as unknown as Promise<T>;
  }

  all(axiosInstances: AxiosInstance[]) {
    return axios.all(axiosInstances);
  }

  setAuthorization(token: string) {
    this.axios.defaults.headers.Authorization = token;
  }
}
const service = new Service({});

export default service;
