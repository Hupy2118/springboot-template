import React, { useEffect } from 'react';
import { Button, Result } from 'antd';
import { getServerData } from '@/apis/welcome';

const DefaultPage: React.FC = () => {
  useEffect(() => {
    // 测试平台认证通过
    getServerData();
  }, []);

  return (
    <div style={{ margin: 'auto' }}>
      <Result status='info' title='默认页面' subTitle='默认页面' />
      <Button type="primary">
        Antd组件跟随主题
      </Button>
      <Button type="primary" className="text-3xl font-bold underline bg-red-500">
        测试 Tailwind 覆盖
      </Button>
    </div>
  );
};

export default DefaultPage;
