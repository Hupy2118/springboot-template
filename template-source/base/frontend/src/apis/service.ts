import axios, { AxiosInstance, AxiosError, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
export type DataType = string | number | Object;
export type ReqFulfilledType = (config: InternalAxiosRequestConfig) => InternalAxiosRequestConfig;
export type ResFulfilledType = (response: AxiosResponse) => AxiosResponse['data'];
export type ResRejectedType = (error: AxiosError<{ message: string }>) => Promise<never>;

// 请求体处理
const defaultReqFulfilled: ReqFulfilledType = (config) => {
  return config;
};
// 响应体处理
const defaultResFulfilled: ResFulfilledType = (response) => {
  return response.data;
};

// 响应错误处理
const defaultResRejected: ResRejectedType = async (error) => {
  return Promise.reject(error);
  return Promise.reject(error);
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

  get<T>(url: string, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.get(url, config) as unknown as Promise<T>;
  }

  post<T>(url: string, data?: DataType, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.post(url, data, config) as unknown as Promise<T>;
  }

  put<T>(url: string, data?: DataType, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.put(url, data, config) as unknown as Promise<T>;
  }

  delete<T>(url: string, config: AxiosRequestConfig = {}): Promise<T> {
    return this.axios.delete(url, config) as unknown as Promise<T>;
  }

  all(axiosInstances: AxiosInstance[]) {
    return axios.all(axiosInstances);
  }

}
const service = new Service({});

export default service;
