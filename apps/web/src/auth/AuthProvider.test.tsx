import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderApp } from '../test/render';
import { server } from '../test/server';

const oidc = vi.hoisted(() => ({
  settings: null as Record<string, unknown> | null,
  getUser: vi.fn(),
  signinRedirect: vi.fn(),
  signinRedirectCallback: vi.fn(),
  signoutRedirect: vi.fn(),
  removeUser: vi.fn(),
  expired: null as (() => void) | null,
}));

vi.mock('oidc-client-ts', () => ({
  WebStorageStateStore: class WebStorageStateStore { readonly sessionOnly = true; },
  UserManager: class UserManager {
    events = { addAccessTokenExpired: (listener: () => void) => { oidc.expired = listener; } };
    constructor(settings: Record<string, unknown>) { oidc.settings = settings; }
    getUser = oidc.getUser;
    signinRedirect = oidc.signinRedirect;
    signinRedirectCallback = oidc.signinRedirectCallback;
    signoutRedirect = oidc.signoutRedirect;
    removeUser = oidc.removeUser;
  },
}));

const authenticatedUser = {
  profile: { sub: 'user-a' },
  access_token: 'test-access-token',
  expired: false,
  state: { returnPath: '/' },
};

function enableAuthentication() {
  server.use(http.get('*/api/v1/auth/config', () => HttpResponse.json({
    enabled: true,
    issuer: 'https://identity.example/realms/courtpulse',
    clientId: 'courtpulse-web',
    scope: 'openid profile',
  })));
}

beforeEach(() => {
  oidc.settings = null;
  oidc.expired = null;
  oidc.getUser.mockReset().mockResolvedValue(null);
  oidc.signinRedirect.mockReset().mockResolvedValue(undefined);
  oidc.signinRedirectCallback.mockReset();
  oidc.signoutRedirect.mockReset().mockResolvedValue(undefined);
  oidc.removeUser.mockReset().mockResolvedValue(undefined);
});

describe('OIDC browser authentication', () => {
  it('initiates Authorization Code with PKCE as a public session-scoped client', async () => {
    enableAuthentication();
    const user = userEvent.setup();
    renderApp('/games/game_synthetic_001');
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(oidc.settings).toMatchObject({
      authority: 'https://identity.example/realms/courtpulse',
      client_id: 'courtpulse-web',
      response_type: 'code',
      scope: 'openid profile',
      automaticSilentRenew: false,
    });
    expect(oidc.settings).not.toHaveProperty('client_secret');
    expect(oidc.signinRedirect).toHaveBeenCalledWith({
      state: { returnPath: '/games/game_synthetic_001' },
    });
  });

  it('shows a sanitized recoverable error when discovery or redirect startup fails', async () => {
    enableAuthentication();
    oidc.signinRedirect.mockRejectedValue(new Error('https://internal.example/provider-details'));
    const user = userEvent.setup();
    renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in could not be started');
    expect(document.body).not.toHaveTextContent('internal.example');
    expect(screen.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
  });

  it('prevents concurrent authorization requests', async () => {
    enableAuthentication();
    let finishRedirect!: () => void;
    oidc.signinRedirect.mockImplementation(() => new Promise<void>((resolve) => {
      finishRedirect = resolve;
    }));
    const user = userEvent.setup();
    renderApp('/');
    const button = await screen.findByRole('button', { name: 'Sign in' });

    await user.click(button);
    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Signing in…' }));
    expect(oidc.signinRedirect).toHaveBeenCalledOnce();
    act(() => { finishRedirect(); });
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('completes a callback without rendering tokens or authorization codes', async () => {
    enableAuthentication();
    oidc.signinRedirectCallback.mockResolvedValue(authenticatedUser);
    renderApp('/auth/callback?code=sensitive-code');
    expect(await screen.findByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
    expect(document.body).not.toHaveTextContent('test-access-token');
    expect(document.body).not.toHaveTextContent('sensitive-code');
  });

  it('waits for authentication configuration before completing the callback once', async () => {
    let releaseConfiguration!: () => void;
    const configurationReady = new Promise<void>((resolve) => { releaseConfiguration = resolve; });
    server.use(http.get('*/api/v1/auth/config', async () => {
      await configurationReady;
      return HttpResponse.json({
        enabled: true,
        issuer: 'https://identity.example/realms/courtpulse',
        clientId: 'courtpulse-web',
        scope: 'openid profile',
      });
    }));
    oidc.signinRedirectCallback.mockResolvedValue(authenticatedUser);
    renderApp('/auth/callback?code=sensitive-code');

    expect(screen.getByText('Completing secure sign-in…')).toBeVisible();
    expect(oidc.signinRedirectCallback).not.toHaveBeenCalled();
    releaseConfiguration();
    expect(await screen.findByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
    expect(oidc.signinRedirectCallback).toHaveBeenCalledOnce();
  });

  it('shows a recoverable callback failure without saving credentials', async () => {
    enableAuthentication();
    oidc.signinRedirectCallback.mockRejectedValue(new Error('provider details'));
    renderApp('/auth/callback?error=access_denied');
    expect(await screen.findByRole('heading', { name: 'Sign-in did not complete' })).toBeVisible();
    expect(screen.queryByText('provider details')).not.toBeInTheDocument();
    expect(oidc.removeUser).toHaveBeenCalledOnce();
  });

  it('clears every prior identity cache before installing a callback identity', async () => {
    enableAuthentication();
    let complete!: (value: typeof authenticatedUser) => void;
    oidc.signinRedirectCallback.mockImplementation(() => new Promise((resolve) => {
      complete = resolve;
    }));
    const { queryClient } = renderApp('/auth/callback?code=safe-code');
    queryClient.setQueryData(['me', 'followed-games'], { items: [{ gameId: 'user-a-game' }] });
    queryClient.setQueryData(['me', 'rules'], { items: [{ id: 'user-a-rule' }] });
    queryClient.setQueryData(['me', 'alerts'], { pages: [{ items: [{ id: 'user-a-alert' }] }], pageParams: [null] });

    await vi.waitFor(() => expect(oidc.signinRedirectCallback).toHaveBeenCalledOnce());
    complete({ ...authenticatedUser, profile: { sub: 'user-b' } });
    expect(await screen.findByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
    expect(queryClient.getQueryData(['me', 'followed-games'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'rules'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'alerts'])).toBeUndefined();
  });

  it('shows authenticated state, follows and unfollows, and logs out', async () => {
    enableAuthentication();
    oidc.getUser.mockResolvedValue(authenticatedUser);
    let followed = false;
    server.use(
      http.get('*/api/v1/me/followed-games', ({ request }) => {
        expect(request.headers.get('Authorization')).toBe('Bearer test-access-token');
        return HttpResponse.json({ items: followed ? [{ gameId: 'game_synthetic_001', followedAt: '2026-09-21T20:00:00Z' }] : [] });
      }),
      http.put('*/api/v1/me/followed-games/:gameId', ({ request }) => {
        expect(request.headers.get('Authorization')).toBe('Bearer test-access-token');
        followed = true;
        return HttpResponse.json({ gameId: 'game_synthetic_001', followedAt: '2026-09-21T20:00:00Z' }, { status: 201 });
      }),
      http.delete('*/api/v1/me/followed-games/:gameId', () => {
        followed = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderApp('/games/game_synthetic_001');
    expect(await screen.findByText('Signed in as user-a')).toBeVisible();
    await user.click(await screen.findByRole('button', { name: 'Follow game' }));
    expect(await screen.findByRole('button', { name: 'Unfollow game' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Unfollow game' }));
    expect(await screen.findByRole('button', { name: 'Follow game' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(oidc.signoutRedirect).toHaveBeenCalledOnce();
  });

  it('uses Cognito logout parameters only when the API supplies an end-session endpoint', async () => {
    server.use(http.get('*/api/v1/auth/config', () => HttpResponse.json({
      enabled: true,
      issuer: 'https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example',
      clientId: 'app-client-123',
      scope: 'openid email profile',
      endSessionEndpoint: 'https://courtpulse-staging.auth.us-east-1.amazoncognito.com/logout',
    })));
    oidc.getUser.mockResolvedValue(authenticatedUser);
    const user = userEvent.setup();
    renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(oidc.settings?.metadataSeed).toEqual({
      end_session_endpoint: 'https://courtpulse-staging.auth.us-east-1.amazoncognito.com/logout',
    });
    expect(oidc.signoutRedirect).toHaveBeenCalledWith({
      extraQueryParams: { client_id: 'app-client-123', logout_uri: `${window.location.origin}/` },
    });
  });

  it('keeps standard discovery-driven logout for providers such as Keycloak', async () => {
    enableAuthentication();
    oidc.getUser.mockResolvedValue(authenticatedUser);
    const user = userEvent.setup();
    renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(oidc.settings?.metadataSeed).toBeUndefined();
    expect(oidc.signoutRedirect).toHaveBeenCalledWith(undefined);
  });

  it('clears protected queries and local identity when provider logout fails', async () => {
    enableAuthentication();
    oidc.getUser.mockResolvedValue(authenticatedUser);
    oidc.signoutRedirect.mockRejectedValue(new Error('provider logout details'));
    const user = userEvent.setup();
    const { queryClient } = renderApp('/');
    queryClient.setQueryData(['me', 'followed-games'], { items: [{ gameId: 'private-game' }] });
    queryClient.setQueryData(['me', 'rules'], { items: [{ id: 'private-rule' }] });
    queryClient.setQueryData(['me', 'alerts'], { pages: [{ items: [{ id: 'private-alert' }] }], pageParams: [null] });

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('signed out locally');
    expect(document.body).not.toHaveTextContent('provider logout details');
    expect(queryClient.getQueryData(['me', 'followed-games'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'rules'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'alerts'])).toBeUndefined();
    expect(oidc.removeUser).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });

  it('handles session expiration and distinguishes forbidden follow failures', async () => {
    enableAuthentication();
    oidc.getUser.mockResolvedValue(authenticatedUser);
    server.use(
      http.get('*/api/v1/me/followed-games', () => HttpResponse.json({ items: [] })),
      http.put('*/api/v1/me/followed-games/:gameId', () => HttpResponse.json({
        type: 'https://courtpulse.dev/problems/insufficient_authority',
        title: 'Access denied', status: 403,
        detail: 'The authenticated identity is not permitted to use this resource.',
        instance: '/api/v1/me/followed-games/game_synthetic_001',
        correlationId: 'safe-id', code: 'insufficient_authority',
      }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } })),
    );
    const user = userEvent.setup();
    const { queryClient } = renderApp('/games/game_synthetic_001');
    await user.click(await screen.findByRole('button', { name: 'Follow game' }));
    expect(await screen.findByText('Your account is not permitted to change this follow.')).toBeVisible();

    queryClient.setQueryData(['me', 'followed-games'], { items: [{ gameId: 'private-game' }] });
    queryClient.setQueryData(['me', 'rules'], { items: [{ id: 'private-rule' }] });
    queryClient.setQueryData(['me', 'alerts'], { pages: [{ items: [{ id: 'private-alert' }] }], pageParams: [null] });
    act(() => { oidc.expired?.(); });
    expect(await screen.findByText(/session expired/i)).toBeVisible();
    expect(oidc.removeUser).toHaveBeenCalled();
    expect(queryClient.getQueryData(['me', 'followed-games'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'rules'])).toBeUndefined();
    expect(queryClient.getQueryData(['me', 'alerts'])).toBeUndefined();
  });

  it('ends the session when a private request is refused with 401 instead of loading forever', async () => {
    enableAuthentication();
    oidc.getUser.mockResolvedValue(authenticatedUser);
    let requests = 0;
    server.use(http.get('*/api/v1/me/rules', () => {
      requests++;
      return HttpResponse.json({
        type: 'https://courtpulse.dev/problems/authentication_required', title: 'Authentication required',
        status: 401, detail: 'A valid bearer token is required.', instance: '/api/v1/me/rules',
        correlationId: 'safe-id', code: 'authentication_required',
      }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } });
    }));
    renderApp('/my-rules');

    expect(await screen.findByText(/session expired/i)).toBeVisible();
    expect(screen.getAllByRole('button', { name: 'Sign in' }).length).toBeGreaterThan(0);
    expect(oidc.removeUser).toHaveBeenCalled();
    expect(requests).toBe(1);
  });
});
