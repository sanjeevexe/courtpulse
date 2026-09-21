import { expect, test, type Page } from '@playwright/test';

interface ObservedFrame {
  messageType?: string;
  messageId?: string;
  stateVersion?: number;
}

async function observeRealtimeFrames(page: Page) {
  await page.addInitScript(() => {
    const NativeWebSocket = window.WebSocket;
    const frames: unknown[] = [];
    Object.defineProperty(window, '__courtPulseRealtimeFrames', { value: frames });
    class ObservedWebSocket extends NativeWebSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols ?? []);
        this.addEventListener('message', (event) => {
          try {
            frames.push(JSON.parse(String(event.data)) as unknown);
          } catch {
            // Production code owns malformed-frame handling; acceptance records valid JSON only.
          }
        });
      }
    }
    Object.defineProperty(window, 'WebSocket', {
      configurable: true,
      writable: true,
      value: ObservedWebSocket,
    });
  });
}

async function frames(page: Page): Promise<ObservedFrame[]> {
  return page.evaluate(() => (
    (window as Window & { __courtPulseRealtimeFrames?: ObservedFrame[] })
      .__courtPulseRealtimeFrames ?? []
  ));
}

async function loadAndVerifyAllEvents(page: Page) {
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
  await expect(page.getByRole('button', { name: 'Load more possessions' })).toHaveCount(0);
}

test('realtime game view observes paced hints, converges, deduplicates, and survives deep refresh', async ({ page }) => {
  test.setTimeout(60_000);
  const paced = process.env.COURTPULSE_PACED_ACCEPTANCE === '1';
  const duplicateExpected = process.env.COURTPULSE_EXPECT_DUPLICATE === '1';
  await observeRealtimeFrames(page);
  await page.goto('/games/game_synthetic_001');
  await expect(page.getByText('Live updates connected')).toBeVisible();

  if (paced) {
    await expect.poll(async () => {
      const score = Number(await page.getByLabel('Home team score').locator('strong').textContent());
      return score > 0 && score < 18;
    }, {
      message: 'home score should visibly enter an intermediate state',
      timeout: 20_000,
    }).toBe(true);
    await expect.poll(async () => (await frames(page)).filter(
      (frame) => frame.messageType === 'GAME_STATE_UPDATED',
    ).length, {
      message: 'the browser should receive a real GAME_STATE_UPDATED WebSocket frame',
      timeout: 20_000,
    }).toBeGreaterThan(0);
  }

  await expect(page.getByLabel('Home team score')).toContainText('18', { timeout: 35_000 });
  await expect(page.getByLabel('Away team score')).toContainText('14');
  await expect(page.getByText('Checkpoint v20')).toBeVisible();
  const playerPanel = page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Player totals' }),
  });
  await expect(playerPanel).toContainText('player ace');
  await expect(playerPanel).toContainText('13');
  await expect(page.getByRole('heading', { name: /reached 10 points/i })).toHaveCount(1);

  if (duplicateExpected) {
    await expect.poll(async () => {
      const counts = new Map<string, number>();
      for (const frame of await frames(page)) {
        if (frame.messageType === 'ALERT_CREATED' && frame.messageId) {
          counts.set(frame.messageId, (counts.get(frame.messageId) ?? 0) + 1);
        }
      }
      return Math.max(0, ...counts.values());
    }, {
      message: 'the isolated run should redeliver the same alert message identity',
      timeout: 20_000,
    }).toBeGreaterThan(1);
    await expect(page.getByRole('heading', { name: /reached 10 points/i })).toHaveCount(1);
  }

  await loadAndVerifyAllEvents(page);

  await page.reload();
  await expect(page.getByText('Live updates connected')).toBeVisible();
  await expect(page.getByText('Checkpoint v20')).toBeVisible();
  await expect(page.getByLabel('Home team score')).toContainText('18');
  await expect(page.getByLabel('Away team score')).toContainText('14');
  const refreshedPlayerPanel = page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Player totals' }),
  });
  await expect(refreshedPlayerPanel).toContainText('player ace');
  await expect(refreshedPlayerPanel).toContainText('13');
  await expect(page.getByRole('heading', { name: /reached 10 points/i })).toHaveCount(1);
  await loadAndVerifyAllEvents(page);
});
