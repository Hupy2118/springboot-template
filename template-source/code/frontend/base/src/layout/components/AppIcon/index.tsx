import { PropsWithChildren } from 'react';
import { Tooltip } from "antd";
import XProAppBGIcon from "@/assets/xpro_app_icon_bg.svg?react";
import * as iconList from "@/assets/appIconList";
import { DEFAULT_ICON_NAME } from '@/constants/layout';

interface AppIconProps {
  icon: string;
  appName: string;
}

const AppIcon = (props: PropsWithChildren<AppIconProps>) => {
  const { icon, appName } = props;

  // 名称&图标渲染
  const RenderIcon =
    icon && icon !== "####"
      ? Reflect.get(iconList, icon)
      : () => {
          <></>;
        };
  return (
    <Tooltip title={appName}>
      {icon?.includes("http") ? (
        <img
          className='w-8 h-8 relative'
          src={icon}
          alt=""
        />
      ) : (
        <div className='w-8 h-8 relative text-primary rounded-lg bg-[linear-gradient(180deg,var(--color-primary-1)_0%,var(--color-primary-2)_100%)] overflow-hidden'>
          {!icon || icon === DEFAULT_ICON_NAME || !Reflect.get(iconList, icon) ? (
            <>
              <div
                className='absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[20px] font-bold cursor-default text-primary'
              >
                {appName?.[0]?.toLocaleUpperCase()}
              </div>
              <XProAppBGIcon className='absolute inset-0 text-primary text-[32px]' />
            </>
          ) : (
            <div
              className='w-full h-full flex justify-center items-center text-primary'
            >
              <RenderIcon />
            </div>
          )}
        </div>
      )}
    </Tooltip>
  );
};

export default AppIcon;
