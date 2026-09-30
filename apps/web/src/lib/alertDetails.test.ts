import { describe, expect, it } from 'vitest';
import { alertDetails, playerLabel } from './format';

describe('alert wording', () => {
  it('describes verified alert facts instead of raw keys', () => {
    expect(alertDetails({ verifiedMargin: '3', clockMillisRemaining: '6000', period: '4', maximumMargin: '5' }, 'CLOSE_GAME'))
      .toBe('3-point margin · 0:06 left in Q4 · alert within 5');
    expect(alertDetails({ verifiedMargin: '0', clockMillisRemaining: '115000', period: '5' }, 'CLOSE_GAME'))
      .toBe('Tied · 1:55 left in OT1');
    expect(alertDetails({ verifiedRunPoints: '9', threshold: '7', teamId: 'bdl-team-90001' }, 'SCORING_RUN'))
      .toBe('9 unanswered points · alert at 7');
    expect(alertDetails({ verifiedTotal: '16', threshold: '16', playerId: 'nba-player-1628983' }, 'PLAYER_POINTS'))
      .toBe('16 points · alert at 16');
    expect(alertDetails({ playerId: 'player_ace', points: '10' })).toBe('');
  });

  it('falls back to a readable player number when a name is not known yet', () => {
    expect(playerLabel('nba-player-1628983', {})).toBe('Player #1628983');
    expect(playerLabel('nba-player-1628983', { 'nba-player-1628983': 'S. Gilgeous-Alexander' })).toBe('S. Gilgeous-Alexander');
  });
});
