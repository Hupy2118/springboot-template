import React from 'react';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import GlobalContextProvider from '@/providers';
import { Routes } from '@/routes';
/**
 * 应用入口文件
 */
const App: React.FC = () => {
  return (
    // <React.StrictMode>
    <BrowserRouter>
      <ErrorBoundary>
        <GlobalContextProvider>
          <StyleProvider layer>
            <ConfigProvider
              theme={{
                token: {
                  // 统一设置antd组件的主题色
                  colorPrimary: '#2c68ff',
                },
              }}
            >
              <Routes />
            </ConfigProvider>
          </StyleProvider>
        </GlobalContextProvider>
      </ErrorBoundary>
    </BrowserRouter>
    // </React.StrictMode>
  );
};

export default App;
