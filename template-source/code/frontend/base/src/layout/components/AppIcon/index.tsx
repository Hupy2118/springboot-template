import { PropsWithChildren } from 'react';
import { Tooltip } from 'antd';
import {
  AppstoreOutlined,
  BankOutlined,
  CloudOutlined,
  DashboardOutlined,
  DesktopOutlined,
  FundOutlined,
  MessageOutlined,
  ShopOutlined,
  ShoppingOutlined,
  TeamOutlined,
  ToolOutlined,
  UserOutlined,
} from '@ant-design/icons';
import * as iconList from '@/assets/appIconList';
import { DEFAULT_ICON_NAME } from '@/constants/layout';

interface AppIconProps {
  icon: string;
  appName: string;
}

const ANT_DESIGN_ICON_MAP = {
  AppstoreOutlined,
  BankOutlined,
  CloudOutlined,
  DashboardOutlined,
  DesktopOutlined,
  FundOutlined,
  MessageOutlined,
  ShopOutlined,
  ShoppingOutlined,
  TeamOutlined,
  ToolOutlined,
  UserOutlined,
};

const AppIcon = (props: PropsWithChildren<AppIconProps>) => {
  const { icon, appName } = props;

  const SvgIcon = icon && icon !== '####' ? Reflect.get(iconList, icon) : undefined;
  const AntDesignIcon = icon
    ? ANT_DESIGN_ICON_MAP[icon as keyof typeof ANT_DESIGN_ICON_MAP]
    : undefined;
  const RenderIcon = SvgIcon || AntDesignIcon;
  return (
    <Tooltip title={appName}>
      {icon?.includes('http') ? (
        <img className='w-8 h-8 relative' src={icon} alt='' />
      ) : (
        <div className='w-8 h-8 relative text-primary rounded-lg bg-[linear-gradient(180deg,var(--color-primary-1)_0%,var(--color-primary-2)_100%)] overflow-hidden'>
          {!icon ||
          icon === DEFAULT_ICON_NAME ||
          !RenderIcon ? (
            <>
              <div className='absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[20px] font-bold cursor-default text-primary'>
                {appName?.[0]?.toLocaleUpperCase() || '?'}
              </div>
            </>
          ) : (
            <div className='w-full h-full flex justify-center items-center text-primary'>
              <RenderIcon />
            </div>
          )}
        </div>
      )}
    </Tooltip>
  );
};

export default AppIcon;
