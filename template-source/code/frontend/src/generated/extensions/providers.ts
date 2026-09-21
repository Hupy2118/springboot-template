import type { ComponentType, PropsWithChildren } from 'react';
import { LoginProvider } from '@/providers/LoginProvider';

export const extensionProviders: ComponentType<PropsWithChildren>[] = [LoginProvider];
