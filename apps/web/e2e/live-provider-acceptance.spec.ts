import { expect, test } from '@playwright/test';

const gameId = 'bdl-game-990001';

test('a provider-fed overtime game shows names, play text, and its corrected final', async ({ page }) => {
  test.skip(process.env.COURTPULSE_LIVE_ACCEPTANCE !== '1', 'isolated M12 stack only');
  test.setTimeout(120_000);

  await page.goto('/');
  const card = page.getByRole('article').filter({ hasText: 'HCH' });
  await expect(card).toBeVisible();
  await expect(card.getByText('SVS')).toBeVisible();

  await page.goto(`/games/${gameId}`);
  const home = page.getByLabel('Home team score');
  await expect(home.getByRole('heading', { name: 'Harbor City Herons' })).toBeVisible();
  await expect(home.getByText('79')).toBeVisible();
  await expect(page.getByLabel('Away team score').getByText('77')).toBeVisible();
  await expect(page.getByText('OT1').first()).toBeVisible();

  const players = page.getByRole('heading', { name: 'Player totals' }).locator('xpath=ancestor::section');
  await expect(players.getByText('Ada Lane')).toBeVisible();
  await expect(players.locator('.player-row').filter({ hasText: 'Ada Lane' })).toContainText('33');

  const log = page.getByRole('heading', { name: 'Play by play' }).locator('xpath=ancestor::section');
  await expect(log.getByText(/gains possession/)).toBeVisible();
});
