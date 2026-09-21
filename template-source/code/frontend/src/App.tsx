import React from 'react';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { Routes } from '@/routes/index';
import { AppProviders } from '@/providers/AppProviders';
/**
 * 应用入口文件
 */
const App: React.FC = () => {
  return (
    // <React.StrictMode>
    <BrowserRouter>
      <ErrorBoundary>
        <StyleProvider layer>
          <ConfigProvider theme={{ token: { colorPrimary: '#2c68ff' } }}>
            <AppProviders>
              <Routes />
            </AppProviders>
          </ConfigProvider>
        </StyleProvider>
      </ErrorBoundary>
    </BrowserRouter>
    // </React.StrictMode>
  );
};

export default App;
