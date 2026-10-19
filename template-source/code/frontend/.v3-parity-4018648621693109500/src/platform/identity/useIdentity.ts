import { useContext } from 'react';
import { IdentityContext } from './IdentityContext';

export const useIdentity = () => useContext(IdentityContext);
