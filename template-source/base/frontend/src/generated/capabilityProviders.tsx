import type { ComponentType, PropsWithChildren } from 'react';

const capabilityProviders: ComponentType<PropsWithChildren>[] = [
  // xcodeagent:capability-providers
];

/** 稳定扩展面；空能力集保持恒等包装。 */
export function CapabilityProviders({ children }: PropsWithChildren) {
  return capabilityProviders.reduceRight(
    (current, Provider) => <Provider>{current}</Provider>,
    children,
  );
}
