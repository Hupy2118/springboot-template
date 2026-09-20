import React, { useContext, useMemo, useCallback } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom'; // 引入路由
import type { ProSettings } from '@ant-design/pro-components';
import { ProLayout, ProConfigProvider } from '@ant-design/pro-components';
import { Spin } from 'antd';
import { USER_INFO_KEY } from '@/constants';
import { PAGE_ROUTE } from '@/constants/routes';
import { LayoutTypeEnum } from '@typings/workbench';
import type { IUserInfo } from '@/typings';
import { GlobalContext } from '@/providers';
import { openNewPage, renderIcon } from '@utils/workbench';
import AppIcon from './components/AppIcon';
import { AuthStateView } from '@/components/Authorization/RouteGuard';
import { usePageMenus } from '@/hooks/usePageMenus';

const HEADER_FOOTER_SETTING: Partial<ProSettings> = {
  headerRender: undefined, // 启用Header时渲染默认的Header，可以手动复写为自定义Header
  footerRender: false, // 不渲染Footer
};

const DEFAULT_LAYOUT_SETTING: Partial<ProSettings> = {
  fixSiderbar: true,
  layout: LayoutTypeEnum.SIDE,
  splitMenus: false,
  contentWidth: 'Fixed',
  siderMenuType: 'sub',
  navTheme: 'light' as any,
};

export default () => {
  const navigate = useNavigate();
  const location = useLocation();

  const settings = useMemo(() => {
    return {
      ...DEFAULT_LAYOUT_SETTING,
      ...HEADER_FOOTER_SETTING,
    };
  }, []);

  const { userInfo: contextUserInfo } = useContext(GlobalContext);
  const { state, menuRoutes } = usePageMenus();
  const routes = useMemo(() => {
    return {
      path: `/${PAGE_ROUTE}`,
      name: '',
      flatMenu: true, // 当前路由不在菜单中显示，直接展示子菜单
      routes: menuRoutes,
    };
  }, [menuRoutes]);

  const handleMenuClick = useCallback((item: any, isMobile?: boolean) => {
    if (item?.path) {
      if (item?.isUrl) {
        // 移动端只能当前页面打开跳转链接
        if (isMobile) {
          openNewPage(item.path, '_self');
        } else {
          openNewPage(item.path, item.target);
        }
      } else {
        navigate(item.path);
      }
    }
  }, []);

  const userInfoFromSessionStr = sessionStorage.getItem(USER_INFO_KEY);
  let storedUserInfo: IUserInfo | null = null;

  try {
    if (userInfoFromSessionStr) {
      storedUserInfo = JSON.parse(userInfoFromSessionStr);
    }
  } catch {}
  const userInfo = contextUserInfo || storedUserInfo;

  return (
    <ProConfigProvider>
      <ProLayout
        siderWidth={216}
        title='测试应用4'
        logo={
          <AppIcon
            icon={
              'https://s3gw.cmbchina.com/lt5230-images-prd/TFQ1NC4wMQ==/059a63b2804b41e987952290540b6f77'
            }
            appName={'测试应用4'}
          />
        }
        route={{ path: '/', routes: [routes] }}
        location={{
          pathname: location.pathname,
        }}
        avatarProps={
          userInfo
            ? {
                src: userInfo?.avatar ?? '',
                title: userInfo?.userName ?? '用户',
                size: 'small',
                children: (userInfo?.userName ?? '用户').slice(0, 1),
                style: { background: 'var(--color-primary)' },
              }
            : undefined
        }
        // 添加系统全局快捷操作，比如：跳转到帮助中心、文档中心等
        actionsRender={(props) => {
          if (props.isMobile) return [];
          return [];
        }}
        menuItemRender={(item, _dom, props) => (
          <div
            className={`flex items-center gap-1.5`}
            onClick={() => {
              handleMenuClick(item, props.isMobile);
            }}
          >
            {item?.icon ? <span>{renderIcon(item.icon)}</span> : null}
            <span>{item.name}</span>
          </div>
        )}
        menuRender={(_, defaultDom) => {
          if (state === 'ready') return defaultDom;
          return state === 'loading' ? (
            <div className='authorization-state'>
              <Spin tip='正在加载权限…' />
            </div>
          ) : (
            <AuthStateView state={state} />
          );
        }}
        {...settings}
      >
        <Outlet />
      </ProLayout>
    </ProConfigProvider>
  );
};
