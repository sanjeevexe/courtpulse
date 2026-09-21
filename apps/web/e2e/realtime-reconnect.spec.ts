import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const composeProject = 'courtpulse-m6-acceptance';
const composeEnvironment = {
  ...process.env,
  COURTPULSE_DB_PASSWORD: process.env.COURTPULSE_DB_PASSWORD ?? 'courtpulse-m6-isolated',
  COURTPULSE_POSTGRES_HOST_PORT: process.env.COURTPULSE_POSTGRES_HOST_PORT ?? '55432',
  COURTPULSE_LOCALSTACK_HOST_PORT: process.env.COURTPULSE_LOCALSTACK_HOST_PORT ?? '54566',
  COURTPULSE_API_HOST_PORT: process.env.COURTPULSE_API_HOST_PORT ?? '58080',
  COURTPULSE_WEB_HOST_PORT: process.env.COURTPULSE_WEB_HOST_PORT ?? '54173',
};

function compose(...arguments_: string[]) {
  execFileSync('docker', ['compose', '-p', composeProject, ...arguments_], {
    cwd: repository,
    env: composeEnvironment,
    stdio: 'pipe',
  });
}

test('browser recovers through polling and HTTP resynchronization after an API interruption', async ({ page }) => {
  let snapshotResponses = 0;
  page.on('response', (response) => {
    if (response.url().includes('/api/v1/games/game_synthetic_001')
      && !response.url().includes('/events')
      && !response.url().includes('/alerts')
      && [200, 304].includes(response.status())) {
      snapshotResponses += 1;
    }
  });

  await page.goto('/games/game_synthetic_001');
  await expect(page.getByText('Live updates connected')).toBeVisible();
  await expect(page.getByText('Checkpoint v20')).toBeVisible();

  if (process.env.COURTPULSE_ISOLATED_RECOVERY !== '1') {
    await expect(page.getByLabel('Home team score')).toContainText('18');
    return;
  }

  compose('stop', 'api');
  await expect(page.getByText(/Live updates reconnecting|Synced by polling/)).toBeVisible({ timeout: 15_000 });
  const responsesBeforeRestore = snapshotResponses;

  compose('up', '-d', 'api');
  await expect.poll(async () => {
    try {
      return (await page.request.get('/api/v1/games/game_synthetic_001')).status();
    } catch {
      return 0;
    }
  }, { message: 'the restored API should serve the durable snapshot', timeout: 40_000 }).toBe(200);
  await expect(page.getByText('Live updates connected')).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => snapshotResponses, {
    message: 'the reconnect should force an authoritative HTTP snapshot resynchronization',
  }).toBeGreaterThan(responsesBeforeRestore);

  await expect(page.getByText('Checkpoint v20')).toBeVisible();
  await expect(page.getByLabel('Home team score')).toContainText('18');
  await expect(page.getByLabel('Away team score')).toContainText('14');
  await expect(page.getByRole('heading', { name: /reached 10 points/i })).toHaveCount(1);
  const eventsPanel = page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Play by play' }),
  });
  for (const expected of [16, 20]) {
    await page.getByRole('button', { name: 'Load more possessions' }).click();
    await expect(eventsPanel.locator('.event-row')).toHaveCount(expected);
  }
  await expect(eventsPanel.locator('.event-sequence')).toHaveText(
    Array.from({ length: 20 }, (_, index) => String(index + 1)),
  );
});
