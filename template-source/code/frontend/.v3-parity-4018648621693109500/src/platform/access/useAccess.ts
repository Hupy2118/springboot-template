import { useContext } from 'react';
import { AccessContext } from './AccessContext';

export const useAccess = () => useContext(AccessContext);
