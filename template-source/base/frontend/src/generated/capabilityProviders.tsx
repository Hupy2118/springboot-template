import type { PropsWithChildren } from 'react';

/** Engine 生成的 Capability Provider 聚合入口；空能力集保持恒等包装。 */
export function CapabilityProviders({ children }: PropsWithChildren) {
  return <>{children}</>;
}
