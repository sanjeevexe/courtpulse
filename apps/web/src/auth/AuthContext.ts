import { createContext } from 'react';

export type AuthenticationStatus = 'LOADING' | 'ANONYMOUS' | 'AUTHENTICATED' | 'ERROR';

export interface AuthContextValue {
  status: AuthenticationStatus;
  enabled: boolean;
  subject: string | null;
  accessToken: string | null;
  error: string | null;
  signInPending: boolean;
  signOutPending: boolean;
  signIn: (returnPath?: string) => Promise<void>;
  completeCallback: () => Promise<string>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
