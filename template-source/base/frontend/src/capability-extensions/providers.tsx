import type { ComponentType, PropsWithChildren } from 'react';

/*
 * CAPABILITY EXTENSION SURFACE
 *
 * 此文件允许 Capability Authoring 修改。
 * 允许新增 import，以及在 xcodeagent Anchor 前新增内容。
 * 禁止删除或修改 Anchor，或修改 Anchor 之外的 Base 内容。
 */

/** 仅当 Capability 需要应用级 Context 或 Provider 时在此注册。 */
export const capabilityProviders: ComponentType<PropsWithChildren>[] = [
  // xcodeagent:capability-providers
];
