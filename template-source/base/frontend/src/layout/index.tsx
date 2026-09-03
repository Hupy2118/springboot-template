import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ProConfigProvider, ProLayout } from '@ant-design/pro-components';
import { PAGE_ROUTE, PAGE_ROUTES } from '@/constants/routes';
import { createLayoutMenus } from '@/utils/route';
import { resolveCapabilityMenus } from '@/generated/capabilityMenus';

export default function Layout() {
  const navigate = useNavigate();
  const location = useLocation();
  const menus = resolveCapabilityMenus(createLayoutMenus(PAGE_ROUTES, PAGE_ROUTE));
  return <ProConfigProvider><ProLayout title="测试应用" route={{ path: '/', routes: [{ path: `/${PAGE_ROUTE}`, routes: menus }] }} location={{ pathname: location.pathname }} menuItemRender={(item, dom) => <span onClick={() => item.path && navigate(item.path)}>{dom}</span>}><Outlet /></ProLayout></ProConfigProvider>;
}
