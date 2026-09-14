import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';
import type { PropsWithChildren } from 'react';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { Routes } from '@/routes';
import { capabilityProviders } from '@/capability-extensions/providers';

function CapabilityProviders({ children }: PropsWithChildren) {
  return capabilityProviders.reduceRight(
    (current, Provider) => <Provider>{current}</Provider>,
    children,
  );
}

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
