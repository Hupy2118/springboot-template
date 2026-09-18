import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ProConfigProvider, ProLayout } from '@ant-design/pro-components';
import { PAGE_ROUTE } from '@/constants/routes';
import { createLayoutMenus } from '@/utils/route';
import { appPageRoutes } from '@/routes/pageRegistry';
import { useCapabilityMenuTransforms } from '@/capability-extensions/menuTransforms';

export default function Layout() {
  const navigate = useNavigate();
  const location = useLocation();
  const menus = useCapabilityMenuTransforms(
    createLayoutMenus(appPageRoutes, PAGE_ROUTE),
  );
  return (
    <ProConfigProvider>
      <ProLayout
        title='测试应用'
        route={{ path: '/', routes: menus }}
        location={{ pathname: location.pathname }}
        menuItemRender={(item, dom) => item.path && item.isUrl
          ? <a href={item.path} target={item.target || '_blank'} rel='noreferrer'>{dom}</a>
          : <span onClick={() => item.path && navigate(item.path)}>{dom}</span>}
      >
        <Outlet />
      </ProLayout>
    </ProConfigProvider>
  );
}
