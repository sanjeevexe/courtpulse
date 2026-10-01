import { describe, expect, it } from 'vitest';
import { nbaGameIdOf, playoffTitle, playoffTitleForLabel } from './playoffs';

describe('playoff titles', () => {
  it('names every round with its conference and game number', () => {
    expect(playoffTitle('nba-replay-0042500101-1', 'DET')).toBe('Eastern Conference First Round · Game 1');
    expect(playoffTitle('nba-replay-0042500223-2', 'OKC')).toBe('Western Conference Semifinals · Game 3');
    expect(playoffTitle('0042500311', 'San Antonio Spurs')).toBe('Western Conference Finals · Game 1');
    expect(playoffTitle('0042500405', 'NYK')).toBe('NBA Finals · Game 5');
  });

  it('falls back without a known team and ignores games that are not playoff games', () => {
    expect(playoffTitle('0042500305', null)).toBe('Conference Finals · Game 5');
    expect(playoffTitle('0042500102', 'HCH')).toBe('First Round · Game 2');
    expect(playoffTitle('0052500101', 'MIA')).toBe('Play-In Tournament');
    expect(playoffTitle('nba-replay-0022500101-1', 'BOS')).toBeNull();
    expect(playoffTitle('game_synthetic_001', 'HOME')).toBeNull();
  });

  it('reads the conference from a game label', () => {
    expect(playoffTitleForLabel('nba-replay-0042500223-2', 'Oklahoma City Thunder at Los Angeles Lakers'))
      .toBe('Western Conference Semifinals · Game 3');
    expect(playoffTitleForLabel('nba-replay-0042500223-2', null)).toBe('Conference Semifinals · Game 3');
  });

  it('finds the NBA game ID inside a replay game ID', () => {
    expect(nbaGameIdOf('nba-replay-0042500405-12')).toBe('0042500405');
    expect(nbaGameIdOf('bdl-game-990001')).toBeNull();
  });
});
