import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

const acceptance = process.env.COURTPULSE_RULE_ACCEPTANCE === '1';
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const gameId = 'game_synthetic_001';

async function login(page: Page, username: string, password: string) {
  const configuration = await (await page.request.get('/api/v1/auth/config')).json() as { issuer: string };
  const authorization = page.waitForRequest((request) =>
    request.url().startsWith(`${configuration.issuer}/protocol/openid-connect/auth`));
  await page.getByRole('button', { name: 'Sign in' }).click();
  const url = new URL((await authorization).url());
  expect(url.searchParams.get('code_challenge_method')).toBe('S256');
  expect(url.searchParams.has('client_secret')).toBeFalsy();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible({ timeout: 20_000 });
}

async function logout(page: Page) {
  const origin = new URL(page.url()).origin;
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL((url) => url.origin === origin && url.pathname === '/');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
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
        // OIDC's transient protocol records are not user sessions.
      }
    }
    throw new Error('Authenticated browser token was not found');
  });
}

function queueReplay(args: string) {
  return execFileSync(path.join(repository, 'gradlew'),
    [':apps:queue-replay-cli:run', `--args=${args}`, '--console=plain'],
    { cwd: repository, env: process.env, encoding: 'utf8', timeout: 180_000 });
}

async function startPublicSocket(page: Page) {
  await page.evaluate(async (id) => {
    const observed = window as Window & { __courtFrames?: string[]; __courtSocket?: WebSocket };
    observed.__courtFrames = [];
    const socket = new WebSocket(`${location.origin.replace(/^http/, 'ws')}/ws/v1/games`);
    observed.__courtSocket = socket;
    socket.addEventListener('message', (event) => observed.__courtFrames?.push(String(event.data)));
    await new Promise<void>((resolve, reject) => {
      socket.addEventListener('open', () => resolve(), { once: true });
      socket.addEventListener('error', () => reject(new Error('Public WebSocket failed')), { once: true });
    });
    socket.send(JSON.stringify({
      schemaVersion: 1, messageType: 'SUBSCRIBE', gameId: id, lastStateVersion: 0,
    }));
  }, gameId);
  await expect.poll(() => page.evaluate(() => (
    (window as Window & { __courtFrames?: string[] }).__courtFrames ?? []
  ).some((frame) => frame.includes('SUBSCRIPTION_ACKNOWLEDGED')))).toBe(true);
}

test('two real OIDC users create private rules before queue replay and see isolated alerts', async ({ page }) => {
  test.setTimeout(360_000);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
  if (!acceptance) {
    await expect(page.getByText('Public browsing')).toBeVisible();
    return;
  }

  const userA = process.env.COURTPULSE_TEST_USER_A ?? '';
  const passwordA = process.env.COURTPULSE_TEST_USER_A_PASSWORD ?? '';
  const userB = process.env.COURTPULSE_TEST_USER_B ?? '';
  const passwordB = process.env.COURTPULSE_TEST_USER_B_PASSWORD ?? '';
  expect(userA && passwordA && userB && passwordB).toBeTruthy();

  await login(page, userA, passwordA);
  await page.goto(`/my-rules?gameId=${gameId}`);
  await expect(page.getByLabel('Game ID')).toHaveValue(gameId);
  await page.getByLabel('Player ID').fill('player_ace');
  await page.getByRole('button', { name: 'Create rule' }).click();
  await expect(page.getByText('player_ace · 10 points')).toBeVisible();

  await page.getByLabel('Template').selectOption('CLOSE_GAME');
  await page.getByLabel('Maximum margin').fill('3');
  await page.getByLabel('Eligible period').fill('4');
  await page.getByLabel('Clock seconds remaining').fill('720');
  await page.getByRole('button', { name: 'Create rule' }).click();
  await expect(page.getByText(/Within 3 points · period 4/)).toBeVisible();

  await page.getByLabel('Template').selectOption('SCORING_RUN');
  await page.getByLabel('Team ID').fill('team_home');
  await page.getByLabel('Unanswered points').fill('5');
  await page.getByRole('button', { name: 'Create rule' }).click();
  await expect(page.getByText('team_home · 5 unanswered')).toBeVisible();
  await expect(page.locator('article.rule-card')).toHaveCount(3);

  const tokenA = await accessToken(page);
  const headersA = { Authorization: `Bearer ${tokenA}` };
  const meA = await (await page.request.get('/api/v1/me', { headers: headersA })).json() as { subject: string };
  const listed = await (await page.request.get('/api/v1/me/rules', {
    headers: headersA,
  })).json() as { items: { id: string; type: string }[] };
  expect(new Set(listed.items.map((rule) => rule.type))).toEqual(
    new Set(['PLAYER_POINTS', 'CLOSE_GAME', 'SCORING_RUN']));
  const privateIds = listed.items.map((rule) => rule.id);
  await logout(page);

  await login(page, userB, passwordB);
  const tokenB = await accessToken(page);
  const headersB = { Authorization: `Bearer ${tokenB}` };
  for (const id of privateIds) {
    expect((await page.request.get(`/api/v1/me/rules/${id}`, { headers: headersB })).status()).toBe(404);
    expect((await page.request.patch(`/api/v1/me/rules/${id}`, {
      headers: headersB, data: { enabled: false, version: 1 },
    })).status()).toBe(404);
    expect((await page.request.delete(`/api/v1/me/rules/${id}`, { headers: headersB })).status()).toBe(404);
  }
  await page.goto('/my-rules');
  await expect(page.getByText('No rules yet. Create your first structured alert.')).toBeVisible();
  expect((await (await page.request.get('/api/v1/me/alerts', { headers: headersB }))
    .json() as { items: unknown[] }).items).toHaveLength(0);
  await logout(page);

  await login(page, userA, passwordA);
  await page.goto('/');
  await startPublicSocket(page);
  expect(queueReplay('--publish')).toContain('sent=20');
  expect(queueReplay('--drain --simulate-consumer-after-commit')).toContain('Injected consumer crash');
  await new Promise((resolve) => setTimeout(resolve, 9_000));
  const recovered = queueReplay('--drain');
  expect(recovered).toContain('suppressed=1');
  expect(recovered).toContain('checkpointVersion=20');

  await expect.poll(async () => {
    const observed = await page.evaluate(() => (
      (window as Window & { __courtFrames?: string[] }).__courtFrames ?? []
    ));
    return observed.filter((frame) => frame.includes('"messageType":"ALERT_CREATED"')).length;
  }, { timeout: 30_000 }).toBe(1);
  const frames = await page.evaluate(() => (
    (window as Window & { __courtFrames?: string[] }).__courtFrames ?? []
  ));
  expect(frames.filter((frame) => frame.includes('"messageType":"ALERT_CREATED"'))).toHaveLength(1);
  for (const privateId of privateIds) expect(frames.join('\n')).not.toContain(privateId);
  expect(frames.join('\n')).not.toContain(meA.subject);
  expect(frames.join('\n')).not.toContain('runStartSequence');
  expect(frames.join('\n')).not.toContain('maximumMargin');
  await page.evaluate(() => (
    (window as Window & { __courtSocket?: WebSocket }).__courtSocket?.close()
  ));

  const publicAlerts = await (await page.request.get(`/api/v1/games/${gameId}/alerts`)).json() as {
    items: { ruleId: string }[];
  };
  expect(publicAlerts.items).toHaveLength(1);
  expect(publicAlerts.items[0]?.ruleId).toBe('milestone-player-ace-10');
  for (const privateId of privateIds) expect(JSON.stringify(publicAlerts)).not.toContain(privateId);
  const snapshot = await (await page.request.get(`/api/v1/games/${gameId}`)).json() as {
    homeScore: number; awayScore: number; stateVersion: number; playerPoints: Record<string, number>;
  };
  expect(snapshot).toMatchObject({
    homeScore: 18, awayScore: 14, stateVersion: 20, playerPoints: { player_ace: 13 },
  });
  await page.goto('/my-alerts');
  await expect(page.locator('article.alert-card')).toHaveCount(3);
  const owned = await (await page.request.get('/api/v1/me/alerts', {
    headers: { Authorization: `Bearer ${await accessToken(page)}` },
  })).json() as { items: { ruleType: string }[] };
  expect(new Set(owned.items.map((alert) => alert.ruleType))).toEqual(
    new Set(['PLAYER_POINTS', 'CLOSE_GAME', 'SCORING_RUN']));
  await logout(page);

  await login(page, userB, passwordB);
  await page.goto('/my-alerts');
  await expect(page.getByText('No personalized alerts have fired yet.')).toBeVisible();
  const privateB = await (await page.request.get('/api/v1/me/alerts', {
    headers: { Authorization: `Bearer ${await accessToken(page)}` },
  })).json() as { items: unknown[] };
  expect(privateB.items).toHaveLength(0);
});
