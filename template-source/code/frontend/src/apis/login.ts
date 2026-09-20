import service from './service';

export default function loginApi(code: string) {
  return Promise.resolve({
  userId:'001',
  userName:"haha"
});
  // return service.get(`/api/sso/login?code=${code}`);
}

function logoutApi() {
  return service.post(`/logout`);
}

export {
  loginApi,
  logoutApi
};
