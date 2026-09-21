import { expect, test } from '@playwright/test';

test('slate opens the seeded game and reaches all durable detail data', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
  await expect(page.getByRole('article')).toContainText('18');
  await expect(page.getByRole('article')).toContainText('14');

  await page.getByRole('link', { name: /view game/i }).click();
  await expect(page).toHaveURL(/\/games\/game_synthetic_001$/);
  await expect(page.getByLabel('Home team score')).toContainText('18');
  await expect(page.getByLabel('Away team score')).toContainText('14');
  const playerPanel = page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Player totals' }),
  });
  await expect(playerPanel).toContainText('player ace');
  await expect(playerPanel).toContainText('13');

  const eventsPanel = page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Play by play' }),
  });
  await expect(eventsPanel.locator('.event-row')).toHaveCount(8);

  await page.getByRole('button', { name: 'Load more possessions' }).click();
  await expect(eventsPanel.locator('.event-row')).toHaveCount(16);

  await page.getByRole('button', { name: 'Load more possessions' }).click();
  await expect(eventsPanel.locator('.event-row')).toHaveCount(20);
  await expect(page.getByRole('button', { name: 'Load more possessions' })).toHaveCount(0);
  await expect(eventsPanel.locator('.event-row .event-sequence')).toHaveText(
    Array.from({ length: 20 }, (_, index) => String(index + 1)),
  );
  await expect(page.getByRole('heading', { name: /reached 10 points/i })).toHaveCount(1);
});

test('direct game navigation and browser refresh preserve the deep route', async ({ page }) => {
  await page.goto('/games/game_synthetic_001');
  await expect(page.getByText('Checkpoint v20')).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(/\/games\/game_synthetic_001$/);
  await expect(page.getByLabel('Home team score')).toContainText('18');
});

test('unknown games render a safe not-found state', async ({ page }) => {
  await page.goto('/games/not-a-real-game');
  await expect(page.getByRole('heading', { name: 'That game is not available.' })).toBeVisible();
  await expect(page.getByText(/exception|select |stack trace/i)).toHaveCount(0);
  await expect(page.getByRole('link', { name: /back to game slate/i })).toBeVisible();
});

test('backend network outage presents a recoverable error', async ({ page }) => {
  await page.route('**/api/**', (route) => route.abort('connectionrefused'));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'CourtPulse cannot reach the game feed.' })).toBeVisible();
  const retry = page.getByRole('button', { name: 'Try again' });
  await expect(retry).toBeVisible();
  await page.unroute('**/api/**');
  await retry.click();
  await expect(page.getByRole('heading', { name: 'Today’s pulse' })).toBeVisible();
});

test('responsive game flow keeps controls and score usable', async ({ page }) => {
  await page.goto('/');
  const filter = page.getByRole('button', { name: 'Final' });
  await expect(filter).toBeVisible();
  await filter.click();
  await page.getByRole('link', { name: /view game/i }).click();
  await expect(page.getByLabel('Home team score')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Refresh game snapshot' })).toBeVisible();
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBe(true);
});
