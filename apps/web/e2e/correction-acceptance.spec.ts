import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const gameId = 'game_synthetic_001';

async function login(page: Page, username: string, password: string) {
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('button', { name: /^Account menu/ })).toBeVisible({ timeout: 20_000 });
}

async function token(page: Page): Promise<string> {
  return page.evaluate(() => {
    for (let index = 0; index < sessionStorage.length; index++) {
      const value = sessionStorage.getItem(sessionStorage.key(index) ?? '');
      if (!value) continue;
      try {
        const candidate = JSON.parse(value) as { access_token?: unknown };
        if (typeof candidate.access_token === 'string') return candidate.access_token;
      } catch { /* OIDC transient protocol entry. */ }
    }
    throw new Error('Browser token was not found');
  });
}

test('connected browser receives correction resync and reloads selected history', async ({ page }) => {
  test.skip(process.env.COURTPULSE_CORRECTION_ACCEPTANCE !== '1', 'isolated M10 stack only');
  test.setTimeout(240_000);
  await page.goto(`/games/${gameId}`);
  await expect(page.getByText('Checkpoint v20')).toBeVisible();
  const before = await page.request.get(`/api/v1/games/${gameId}`);
  const beforeEtag = before.headers().etag;
  expect((await before.json() as { playerPoints: Record<string, number> }).playerPoints.player_ace).toBe(13);

  await page.evaluate(async (id) => {
    const observed = window as Window & { __correctionFrames?: string[]; __correctionSocket?: WebSocket };
    observed.__correctionFrames = [];
    const socket = new WebSocket(`${location.origin.replace(/^http/, 'ws')}/ws/v1/games`);
    observed.__correctionSocket = socket;
    socket.addEventListener('message', (event) => observed.__correctionFrames?.push(String(event.data)));
    await new Promise<void>((resolve, reject) => {
      socket.addEventListener('open', () => resolve(), { once: true });
      socket.addEventListener('error', () => reject(new Error('Public WebSocket failed')), { once: true });
    });
    socket.send(JSON.stringify({
      schemaVersion: 1, messageType: 'SUBSCRIBE', gameId: id, lastStateVersion: 20,
    }));
  }, gameId);
  await expect.poll(() => page.evaluate(() => (
    (window as Window & { __correctionFrames?: string[] }).__correctionFrames ?? []
  ).some((frame) => frame.includes('SUBSCRIPTION_ACKNOWLEDGED')))).toBe(true);

  const fixture = path.join(repository, 'fixtures/corrections/ace-to-home-2.json');
  execFileSync(path.join(repository, 'gradlew'),
    [':apps:queue-replay-cli:run', `--args=--correction-fixture=${fixture}`, '--console=plain'],
    { cwd: repository, env: process.env, encoding: 'utf8', timeout: 180_000 });
  await expect.poll(() => page.evaluate(() => (
    (window as Window & { __correctionFrames?: string[] }).__correctionFrames ?? []
  ).some((frame) => frame.includes('"messageType":"RESYNC_REQUIRED"')
    && frame.includes('"code":"game_correction"'))), { timeout: 45_000 }).toBe(true);
  await expect(page.getByText('Checkpoint v21')).toBeVisible({ timeout: 30_000 });

  const after = await page.request.get(`/api/v1/games/${gameId}`, {
    headers: { 'If-None-Match': beforeEtag ?? '' },
  });
  expect(after.status()).toBe(200);
  expect(after.headers().etag).not.toBe(beforeEtag);
  expect(await after.json()).toMatchObject({
    stateVersion: 21, playerPoints: { player_ace: 11, player_home_2: 7 },
  });
  const events = await (await page.request.get(`/api/v1/games/${gameId}/events?afterSequence=10&limit=1`))
    .json() as { items: { revision: number; eventId: string }[] };
  expect(events.items).toEqual([expect.objectContaining({ revision: 2, eventId: 'event-011-rev-2' })]);
  const frames = await page.evaluate(() => (
    (window as Window & { __correctionFrames?: string[] }).__correctionFrames ?? []
  ));
  expect(frames.join('\n')).not.toContain('ownerSubject');
  expect(frames.join('\n')).not.toContain('recipient@example.test');
  await page.evaluate(() => (
    (window as Window & { __correctionSocket?: WebSocket }).__correctionSocket?.close()
  ));

  await page.goto('/');
  await login(page, process.env.COURTPULSE_TEST_USER_A ?? '',
    process.env.COURTPULSE_TEST_USER_A_PASSWORD ?? '');
  const ownedA = await (await page.request.get('/api/v1/me/alerts', {
    headers: { Authorization: `Bearer ${await token(page)}` },
  })).json() as { items: { id: string; status: string }[] };
  expect(ownedA.items.filter((item) => item.status === 'CORRECTED')).toHaveLength(1);
  await page.getByRole('button', { name: /^Account menu/ }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

  await login(page, process.env.COURTPULSE_TEST_USER_B ?? '',
    process.env.COURTPULSE_TEST_USER_B_PASSWORD ?? '');
  const ownedB = await (await page.request.get('/api/v1/me/alerts', {
    headers: { Authorization: `Bearer ${await token(page)}` },
  })).json() as { items: { id: string; status: string }[] };
  expect(ownedB.items).toHaveLength(1);
  expect(ownedB.items[0]?.status).toBe('CREATED');
  expect(ownedA.items.map((item) => item.id)).not.toContain(ownedB.items[0]?.id);
});
