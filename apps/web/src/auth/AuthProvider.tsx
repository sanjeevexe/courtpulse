import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { AuthContext, type AuthContextValue } from './AuthContext';

type AuthenticationStatus = 'LOADING' | 'ANONYMOUS' | 'AUTHENTICATED' | 'ERROR';

interface PublicAuthConfiguration {
  enabled: boolean;
  issuer: string;
  clientId: string;
  scope: string;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [manager, setManager] = useState<UserManager | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthenticationStatus>('LOADING');
  const [error, setError] = useState<string | null>(null);
  const [signInPending, setSignInPending] = useState(false);
  const [signOutPending, setSignOutPending] = useState(false);
  const signInActive = useRef(false);
  const signOutActive = useRef(false);

  useEffect(() => {
    let active = true;
    void fetch('/api/v1/auth/config', { headers: { Accept: 'application/json' } })
      .then(async (response) => {
        if (!response.ok) throw new Error('Authentication configuration is unavailable.');
        return response.json() as Promise<PublicAuthConfiguration>;
      })
      .then(async (configuration) => {
        if (!active) return;
        setEnabled(configuration.enabled);
        if (!configuration.enabled) {
          setStatus('ANONYMOUS');
          return;
        }
        const next = new UserManager({
          authority: configuration.issuer,
          client_id: configuration.clientId,
          redirect_uri: `${window.location.origin}/auth/callback`,
          post_logout_redirect_uri: window.location.origin,
          response_type: 'code',
          scope: configuration.scope,
          automaticSilentRenew: false,
          userStore: new WebStorageStateStore({ store: window.sessionStorage }),
          stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
        });
        next.events.addAccessTokenExpired(() => {
          void next.removeUser().catch(() => undefined);
          queryClient.removeQueries({ queryKey: ['me'] });
          if (active) {
            setUser(null);
            setError('Your session expired. Sign in again to continue with My Games.');
            setStatus('ERROR');
          }
        });
        setManager(next);
        const existing = await next.getUser();
        // The cleanup closure can run while getUser awaits storage/provider work.
        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
        if (!active) return;
        if (existing && !existing.expired) {
          setUser(existing);
          setStatus('AUTHENTICATED');
        } else {
          if (existing) await next.removeUser();
          setStatus('ANONYMOUS');
        }
      })
      .catch(() => {
        if (active) {
          setError('Sign-in configuration could not be loaded. Public games are still available.');
          setStatus('ERROR');
        }
      });
    return () => { active = false; };
  }, [queryClient]);

  const signIn = useCallback(async (returnPath = '/') => {
    if (!manager || signInActive.current) return;
    signInActive.current = true;
    setSignInPending(true);
    setError(null);
    try {
      await manager.signinRedirect({ state: { returnPath } });
    } catch {
      setUser(null);
      setStatus('ERROR');
      setError('Sign-in could not be started. Public games are still available; retry when ready.');
    } finally {
      signInActive.current = false;
      setSignInPending(false);
    }
  }, [manager]);

  const completeCallback = useCallback(async () => {
    if (!manager) throw new Error('Sign-in is not ready.');
    try {
      const authenticated = await manager.signinRedirectCallback();
      queryClient.removeQueries({ queryKey: ['me'] });
      setUser(authenticated);
      setStatus('AUTHENTICATED');
      setError(null);
      const state = authenticated.state as { returnPath?: unknown } | undefined;
      return typeof state?.returnPath === 'string' && state.returnPath.startsWith('/')
        ? state.returnPath
        : '/';
    } catch {
      await manager.removeUser().catch(() => undefined);
      queryClient.removeQueries({ queryKey: ['me'] });
      setUser(null);
      setStatus('ERROR');
      setError('Sign-in could not be completed. No credentials were saved.');
      throw new Error('OIDC callback failed');
    }
  }, [manager, queryClient]);

  const signOut = useCallback(async () => {
    if (!manager || signOutActive.current) return;
    signOutActive.current = true;
    setSignOutPending(true);
    setUser(null);
    setStatus('ANONYMOUS');
    setError(null);
    queryClient.removeQueries({ queryKey: ['me'] });
    try {
      await manager.signoutRedirect();
    } catch {
      setStatus('ERROR');
      setError('You were signed out locally. The identity provider could not be reached.');
    } finally {
      await manager.removeUser().catch(() => undefined);
      signOutActive.current = false;
      setSignOutPending(false);
    }
  }, [manager, queryClient]);

  const value = useMemo<AuthContextValue>(() => ({
    status,
    enabled,
    subject: user?.profile.sub ?? null,
    accessToken: user?.access_token ?? null,
    error,
    signInPending,
    signOutPending,
    signIn,
    completeCallback,
    signOut,
  }), [completeCallback, enabled, error, signIn, signInPending, signOut, signOutPending, status, user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
