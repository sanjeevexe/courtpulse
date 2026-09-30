import { useMemo, useRef, useState, type SyntheticEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import {
  ApiError,
  createRule,
  deleteRule,
  updateRule,
  type AlertRule,
  type CreateAlertRule,
  type GameSummary,
} from '../api/client';
import { uniqueGames, useGames, useMyRules, useRuleTargets } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { Icon } from '../components/Icons';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { SignedOut } from '../components/SignedOut';
import { formatClock, formatPeriod, teamLabel } from '../lib/format';

type RuleKind = CreateAlertRule['type'];

const descriptions: Record<RuleKind, string> = {
  PLAYER_POINTS: 'Fires once when a player’s verified total crosses the selected points threshold.',
  CLOSE_GAME: 'Fires when a live game enters the selected margin and clock window in the exact period. A later exit and re-entry can fire again.',
  SCORING_RUN: 'Fires when a team crosses an unanswered-points threshold. An opponent score ends the run; a later run is separate.',
};

export function MyRulesPage() {
  const auth = useAuth();
  const [search] = useSearchParams();
  const queryClient = useQueryClient();
  const query = useMyRules(auth.accessToken, auth.status === 'AUTHENTICATED');
  const [type, setType] = useState<RuleKind>('PLAYER_POINTS');
  const [gameId, setGameId] = useState(search.get('gameId') ?? '');
  // Opened from a game page: suggest that game's teams and scoring players instead of raw IDs.
  const targets = useRuleTargets(search.get('gameId'));
  const suggestions = gameId === search.get('gameId')
    ? (type === 'PLAYER_POINTS' ? targets.data?.players : targets.data?.teams) ?? []
    : [];
  const [targetId, setTargetId] = useState('');
  const [threshold, setThreshold] = useState('10');
  const [margin, setMargin] = useState('5');
  const [period, setPeriod] = useState('4');
  const [clockSeconds, setClockSeconds] = useState('180');
  const [validation, setValidation] = useState<string | null>(null);
  // Suggest games from the slate so a rule can be aimed without knowing an ID.
  const gameOptions = uniqueGames(useGames('ALL').data);
  const pendingCreate = useRef<{ body: string; key: string } | null>(null);

  const rules = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);
  const createMutation = useMutation({
    mutationFn: (request: CreateAlertRule) => {
      const body = JSON.stringify(request);
      if (pendingCreate.current?.body !== body) {
        pendingCreate.current = { body, key: crypto.randomUUID() };
      }
      return createRule(auth.accessToken ?? '', pendingCreate.current.key, request);
    },
    onSuccess: async () => {
      pendingCreate.current = null;
      setValidation(null);
      await queryClient.invalidateQueries({ queryKey: ['me', 'rules'] });
    },
  });
  const toggleMutation = useMutation({
    mutationFn: (rule: AlertRule) => updateRule(auth.accessToken ?? '', rule.id, {
      enabled: !rule.enabled,
      version: rule.version,
    }),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ['me', 'rules'] }),
  });
  const deleteMutation = useMutation({
    mutationFn: (ruleId: string) => deleteRule(auth.accessToken ?? '', ruleId),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ['me', 'rules'] }),
  });

  if (auth.status !== 'AUTHENTICATED') {
    return (
      <SignedOut title="My Rules" returnPath="/my-rules"
        body="Sign in to create private alert rules. Public game browsing remains available." />
    );
  }

  function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const points = Number(threshold);
    if (!gameId.trim()) return setValidation('Choose a game ID.');
    if (type === 'PLAYER_POINTS') {
      if (!targetId.trim() || !Number.isInteger(points) || points < 1 || points > 200) {
        return setValidation('Player ID is required and points must be between 1 and 200.');
      }
      createMutation.mutate({
        type, gameId: gameId.trim(), playerId: targetId.trim(), pointsThreshold: points, enabled: true,
      });
    } else if (type === 'SCORING_RUN') {
      if (!targetId.trim() || !Number.isInteger(points) || points < 1 || points > 100) {
        return setValidation('Team ID is required and run points must be between 1 and 100.');
      }
      createMutation.mutate({
        type, gameId: gameId.trim(), teamId: targetId.trim(), pointsThreshold: points, enabled: true,
      });
    } else {
      const parsedMargin = Number(margin);
      const parsedPeriod = Number(period);
      const seconds = Number(clockSeconds);
      if (!Number.isInteger(parsedMargin) || parsedMargin < 1 || parsedMargin > 20
          || !Number.isInteger(parsedPeriod) || parsedPeriod < 1 || parsedPeriod > 10
          || !Number.isInteger(seconds) || seconds < 0 || seconds > 720) {
        return setValidation('Margin must be 1–20, period 1–10 (5+ is overtime), and clock seconds 0–720.');
      }
      createMutation.mutate({
        type, gameId: gameId.trim(), maximumMargin: parsedMargin, eligiblePeriod: parsedPeriod,
        maximumClockMillisRemaining: seconds * 1000, enabled: true,
      });
    }
    setValidation(null);
  }

  const chosenGame = gameOptions.find((game) => game.gameId === gameId.trim());
  return (
    <>
      <PageHeader eyebrow="Alerts" title="My Rules"
        lede="Rules watch games for you and alert you the moment something happens." />
      <div className="rules-layout">
        <section className="panel rule-builder" aria-labelledby="create-rule-title">
          <div className="panel-heading"><h2 id="create-rule-title">Create a rule</h2></div>
          <p className="muted panel-intro">Pick a template. Rules are checked on the server and never run as code.</p>
          <form className="form" onSubmit={submit}>
            <label className="field">
              <span>Template</span>
              <select value={type} onChange={(event) => setType(event.target.value as RuleKind)}>
                <option value="PLAYER_POINTS">Player points</option>
                <option value="CLOSE_GAME">Close game</option>
                <option value="SCORING_RUN">Scoring run</option>
              </select>
            </label>
            <p className="template-description">{descriptions[type]}</p>
            <div className="field">
              <label htmlFor="rule-game">Game ID</label>
              <input id="rule-game" value={gameId} maxLength={200} list="rule-games" autoComplete="off"
                aria-describedby="rule-game-hint" onChange={(event) => setGameId(event.target.value)} />
              <datalist id="rule-games">
                {gameOptions.map((game) => (
                  <option key={game.gameId} value={game.gameId} label={gameOptionLabel(game)} />
                ))}
              </datalist>
              <small id="rule-game-hint" className="field__hint">
                {chosenGame ? gameOptionLabel(chosenGame) : 'Start typing to pick a game, or open a game and choose Create alert.'}
              </small>
            </div>
            {type !== 'CLOSE_GAME' ? (
              <label className="field">
                <span>{type === 'PLAYER_POINTS' ? 'Player ID' : 'Team ID'}</span>
                <input value={targetId} maxLength={200} list={suggestions.length > 0 ? 'rule-targets' : undefined}
                  autoComplete="off" onChange={(event) => setTargetId(event.target.value)} />
                {suggestions.length > 0 ? (
                  <datalist id="rule-targets" data-testid="rule-targets">
                    {suggestions.map((target) => <option key={target.id} value={target.id} label={target.label} />)}
                  </datalist>
                ) : null}
              </label>
            ) : null}
            {type !== 'CLOSE_GAME' ? (
              <label className="field">
                <span>{type === 'PLAYER_POINTS' ? 'Points threshold' : 'Unanswered points'}</span>
                <input type="number" inputMode="numeric" min="1" max={type === 'PLAYER_POINTS' ? '200' : '100'}
                  value={threshold} onChange={(event) => setThreshold(event.target.value)} />
              </label>
            ) : (
              <div className="form__row">
                <label className="field"><span>Maximum margin</span>
                  <input type="number" inputMode="numeric" min="1" max="20" value={margin}
                    onChange={(event) => setMargin(event.target.value)} /></label>
                <label className="field"><span>Eligible period <small>1–4 · OT1 = 5</small></span>
                  <input type="number" inputMode="numeric" min="1" max="10" value={period}
                    onChange={(event) => setPeriod(event.target.value)} /></label>
                <label className="field"><span>Clock seconds remaining</span>
                  <input type="number" inputMode="numeric" min="0" max="720" value={clockSeconds}
                    onChange={(event) => setClockSeconds(event.target.value)} /></label>
              </div>
            )}
            {validation ? <p className="form-error" role="alert">{validation}</p> : null}
            {createMutation.isError ? <MutationError error={createMutation.error} /> : null}
            {createMutation.isSuccess ? (
              <p className="success-message" role="status"><Icon name="check" size={16} />Rule saved.</p>
            ) : null}
            <button className="button button--primary" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating…' : 'Create rule'}
            </button>
          </form>
        </section>

        <section className="panel" aria-labelledby="my-rules-title">
          <div className="panel-heading">
            <h2 id="my-rules-title">Your rules</h2>
            {rules.length > 0 ? <span className="count-pill">{rules.length}</span> : null}
          </div>
          {query.isPending ? <LoadingState label="Loading alert rules" variant="list" /> : null}
          {query.isError ? <ErrorPanel error={query.error} onRetry={() => void query.refetch()} /> : null}
          {!query.isPending && rules.length === 0 ? (
            <div className="inline-empty">
              <strong>No rules yet. Create your first structured alert.</strong>
              <span>Try a player points rule on a live game.</span>
            </div>
          ) : null}
          <div className="rule-list">
            {rules.map((rule) => (
              <article className={`rule-card${rule.enabled ? '' : ' rule-card--off'}`} key={rule.id}>
                <div className="rule-card__text">
                  <span className="rule-card__meta">
                    <span className="tag">{ruleLabel(rule.type)}</span>
                    <span className={`rule-state${rule.enabled ? ' rule-state--on' : ''}`}>{rule.enabled ? 'On' : 'Off'}</span>
                  </span>
                  <h3>{ruleSummary(rule)}</h3>
                  <p>{rule.gameLabel ?? rule.gameId}</p>
                </div>
                <div className="rule-actions">
                  <button className="button button--secondary button--sm" disabled={toggleMutation.isPending}
                    onClick={() => toggleMutation.mutate(rule)}>{rule.enabled ? 'Disable' : 'Enable'}</button>
                  <button className="button button--danger button--sm" disabled={deleteMutation.isPending}
                    onClick={() => deleteMutation.mutate(rule.id)}>Delete</button>
                </div>
              </article>
            ))}
          </div>
          {toggleMutation.isError ? <MutationError error={toggleMutation.error} /> : null}
          {deleteMutation.isError ? <MutationError error={deleteMutation.error} /> : null}
          {query.hasNextPage ? (
            <button className="button button--secondary button--full" onClick={() => void query.fetchNextPage()}>
              Load more rules
            </button>
          ) : null}
        </section>
      </div>
    </>
  );
}

function gameOptionLabel(game: GameSummary): string {
  const away = game.awayTeamAbbreviation ?? teamLabel(game.awayTeamId, game.awayTeamName);
  const home = game.homeTeamAbbreviation ?? teamLabel(game.homeTeamId, game.homeTeamName);
  const state = game.status === 'LIVE' ? 'Live' : game.status === 'FINAL' ? 'Final' : 'Upcoming';
  return `${away} at ${home} · ${state}`;
}

function MutationError({ error }: { error: Error }) {
  const message = error instanceof ApiError && error.status === 401
    ? 'Your session expired. Sign in again before retrying.'
    : error.message;
  return <p className="form-error" role="alert">{message}</p>;
}

function ruleLabel(type: AlertRule['type']) {
  return type === 'PLAYER_POINTS' ? 'Player points' : type === 'CLOSE_GAME' ? 'Close game' : 'Scoring run';
}

function ruleSummary(rule: AlertRule) {
  if (rule.type === 'PLAYER_POINTS') return `${rule.playerName ?? rule.playerId} reaches ${String(rule.pointsThreshold)} points`;
  if (rule.type === 'SCORING_RUN') return `${rule.teamName ?? rule.teamId} goes on a ${String(rule.pointsThreshold)}-0 run`;
  return `Within ${String(rule.maximumMargin)} points in the last ${formatClock(rule.maximumClockMillisRemaining)} of ${formatPeriod(rule.eligiblePeriod)}`;
}
