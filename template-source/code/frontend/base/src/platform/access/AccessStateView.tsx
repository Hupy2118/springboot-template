import { Button } from 'antd';
import { AppStateView } from '@/components/AppStateView';
import type { AccessState } from './AccessContext';
import { useAccess } from './useAccess';

export type AccessDisplayState = AccessState | 'forbidden';

export function AccessStateView({
  state,
  fullScreen = false,
}: {
  state: AccessDisplayState;
  fullScreen?: boolean;
}) {
  const { refresh } = useAccess();
  const retry = refresh ? (
    <Button onClick={() => void refresh()}>重试</Button>
  ) : undefined;

  if (state === 'loading') {
    return (
      <AppStateView
        status='loading'
        title='正在加载权限…'
        fullScreen={fullScreen}
      />
    );
  }
  if (state === 'not-ready') {
    return (
      <AppStateView
        status='warning'
        title='权限运行时尚未就绪'
        description='请在服务初始化完成后重试。'
        action={retry}
        fullScreen={fullScreen}
      />
    );
  }
  if (state === 'unauthenticated') {
    return (
      <AppStateView
        status='forbidden'
        title='登录状态已失效'
        description='请重新登录后再访问系统。'
        fullScreen={fullScreen}
      />
    );
  }
  if (state === 'forbidden') {
    return (
      <AppStateView
        status='forbidden'
        title='无权限访问'
        description='当前成员没有访问该页面的权限。'
      />
    );
  }
  return (
    <AppStateView
      status='error'
      title='权限状态加载失败'
      description='暂时无法验证您的访问权限，请稍后重试。'
      action={retry}
      fullScreen={fullScreen}
    />
  );
}
