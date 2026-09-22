import { expect, test, type Page } from '@playwright/test';

const authAcceptance = process.env.COURTPULSE_AUTH_ACCEPTANCE === '1';

async function login(page: Page, username: string, password: string) {
  const configurationResponse = await page.request.get('/api/v1/auth/config');
  expect(configurationResponse.ok()).toBeTruthy();
  const configuration = await configurationResponse.json() as { issuer: string };
  const authorizationRequest = page.waitForRequest((request) =>
    request.url().startsWith(`${configuration.issuer}/protocol/openid-connect/auth`));
  await page.getByRole('button', { name: 'Sign in' }).click();
  const authorizationUrl = new URL((await authorizationRequest).url());
  expect(authorizationUrl.searchParams.get('response_type')).toBe('code');
  expect(authorizationUrl.searchParams.get('code_challenge_method')).toBe('S256');
  expect(authorizationUrl.searchParams.get('code_challenge')).toBeTruthy();
  expect(authorizationUrl.searchParams.has('client_secret')).toBeFalsy();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('body')).not.toContainText('access_token');
  expect(page.url()).not.toContain('code=');
}

function captureBrowserFailures(page: Page): string[] {
  const failures: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') failures.push(message.text());
  });
  page.on('pageerror', (error) => failures.push(error.message));
  return failures;
}

async function logout(page: Page) {
  const applicationOrigin = new URL(page.url()).origin;
  const configurationResponse = await page.request.get('/api/v1/auth/config');
  const configuration = await configurationResponse.json() as { issuer: string };
  const logoutRequest = page.waitForRequest((request) =>
    request.url().startsWith(`${configuration.issuer}/protocol/openid-connect/logout`));
  await page.getByRole('button', { name: 'Sign out' }).click();
  await logoutRequest;
  await page.waitForURL((url) => url.origin === applicationOrigin && url.pathname === '/');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible({ timeout: 20_000 });
}

async function accessToken(page: Page): Promise<string> {
  return page.evaluate(() => {
    for (let index = 0; index < window.sessionStorage.length; index++) {
      const value = window.sessionStorage.getItem(window.sessionStorage.key(index) ?? '');
      if (!value) continue;
      try {
        const candidate = JSON.parse(value) as { access_token?: unknown };
        if (typeof candidate.access_token === 'string') return candidate.access_token;
      } catch {
        // OIDC protocol state has multiple shapes; only the user record contains an access token.
      }
    }
    throw new Error('Authenticated session was not found');
  });
}

test('two browser identities keep owned games isolated and preserve public logout access', async ({ page }) => {
  test.setTimeout(90_000);
  const browserFailures = captureBrowserFailures(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
  if (!authAcceptance) {
    await expect(page.getByText('Public browsing')).toBeVisible();
    return;
  }

  const userA = process.env.COURTPULSE_TEST_USER_A ?? '';
  const passwordA = process.env.COURTPULSE_TEST_USER_A_PASSWORD ?? '';
  const userB = process.env.COURTPULSE_TEST_USER_B ?? '';
  const passwordB = process.env.COURTPULSE_TEST_USER_B_PASSWORD ?? '';
  expect(userA && passwordA && userB && passwordB).toBeTruthy();

  await login(page, userA, passwordA);
  await page.goto('/games/game_synthetic_001');
  await page.getByRole('button', { name: 'Follow game' }).click();
  await expect(page.getByRole('button', { name: 'Unfollow game' })).toBeVisible();
  const ordinaryResponse = await page.request.get('/api/v1/operations/processing', {
    headers: { Authorization: `Bearer ${await accessToken(page)}` },
  });
  expect(ordinaryResponse.status()).toBe(403);
  await logout(page);
  await expect(page.getByRole('region', { name: 'Game slate' })).toContainText('18');

  await login(page, userB, passwordB);
  await page.goto('/my-games');
  await expect(page.getByText('You are not following a game yet.')).toBeVisible();
  await page.goto('/games/game_synthetic_001');
  await expect(page.getByRole('button', { name: 'Follow game' })).toBeVisible();
  const userBToken = await accessToken(page);
  const isolatedDelete = await page.request.delete('/api/v1/me/followed-games/game_synthetic_001', {
    headers: { Authorization: `Bearer ${userBToken}` },
  });
  expect(isolatedDelete.status()).toBe(204);
  await logout(page);

  await login(page, userA, passwordA);
  await page.goto('/my-games');
  await expect(page.getByRole('link', { name: 'game_synthetic_001' })).toBeVisible();
  await logout(page);
  await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
  expect(browserFailures).toEqual([]);
});

test('operational browser identity can read the protected operations resource', async ({ page }) => {
  test.setTimeout(60_000);
  const browserFailures = captureBrowserFailures(page);
  await page.goto('/');
  if (!authAcceptance) {
    await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
    return;
  }
  const username = process.env.COURTPULSE_TEST_OPS_USER ?? '';
  const password = process.env.COURTPULSE_TEST_OPS_PASSWORD ?? '';
  expect(username && password).toBeTruthy();
  await login(page, username, password);
  const token = await accessToken(page);
  const meResponse = await page.request.get('/api/v1/me', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(meResponse.status()).toBe(200);
  const response = await page.request.get('/api/v1/operations/processing', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.status()).toBe(200);
  expect((await response.json()) as { processedEvents?: number }).toMatchObject({ processedEvents: 20 });
  await logout(page);
  expect(browserFailures).toEqual([]);
});
