import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { Routes } from '@/routes';
import { CapabilityProviders } from '@/generated/capabilityProviders';
export default function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <StyleProvider layer>
          <CapabilityProviders>
            <ConfigProvider><Routes /></ConfigProvider>
          </CapabilityProviders>
        </StyleProvider>
      </ErrorBoundary>
    </BrowserRouter>
  );
}
