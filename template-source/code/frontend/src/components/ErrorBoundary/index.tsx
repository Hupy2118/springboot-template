import React, { PropsWithChildren } from 'react';
import { reportError } from '@/observability/errorReporter';

export interface ErrorBoundaryState {
  hasError: boolean;
}

/**
 * 错误监控捕获
 */
export class ErrorBoundary extends React.Component<PropsWithChildren, ErrorBoundaryState> {
  constructor(props: PropsWithChildren) {
    super(props);
    this.state = { hasError: false };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    this.setState({ hasError: true });
    console.error('[ErrorBoundary]', error);
    reportError(error, info);
  }

  render() {
    const { hasError } = this.state;
    if (hasError) {
      return <div>出错了！</div>;
    }
    const { children } = this.props;
    return children;
  }
}
