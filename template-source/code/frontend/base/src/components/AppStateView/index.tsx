import type { ReactNode } from 'react';
import { Flex, Result, Spin, Typography, theme } from 'antd';
import './index.css';

type AppStateViewProps = {
  status: 'loading' | 'error' | 'warning' | 'forbidden';
  title: string;
  description?: string;
  action?: ReactNode;
  fullScreen?: boolean;
};

export function AppStateView({
  status,
  title,
  description,
  action,
  fullScreen = false,
}: AppStateViewProps) {
  const { token } = theme.useToken();

  return (
    <div
      className={
        fullScreen
          ? 'app-state-screen app-state-screen--fullscreen'
          : 'app-state-screen'
      }
      style={fullScreen ? { backgroundColor: token.colorBgLayout } : undefined}
    >
      {status === 'loading' ? (
        <Flex vertical align='center' gap='middle' role='status'>
          <Spin size='large' />
          <Typography.Text type='secondary'>{title}</Typography.Text>
        </Flex>
      ) : (
        <Result
          status={status === 'forbidden' ? '403' : status}
          title={title}
          subTitle={description}
          extra={action}
        />
      )}
    </div>
  );
}
