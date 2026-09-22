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
} from '../api/client';
import { useMyRules } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';

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
  const [targetId, setTargetId] = useState('');
  const [threshold, setThreshold] = useState('10');
  const [margin, setMargin] = useState('5');
  const [period, setPeriod] = useState('4');
  const [clockSeconds, setClockSeconds] = useState('180');
  const [validation, setValidation] = useState<string | null>(null);
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
      <section className="empty-state">
        <h1>My Rules</h1>
        <p>Sign in to create private alert rules. Public game browsing remains available.</p>
        {auth.enabled ? <button className="button" onClick={() => void auth.signIn('/my-rules')}>Sign in</button> : null}
      </section>
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
          || !Number.isInteger(parsedPeriod) || parsedPeriod < 1 || parsedPeriod > 4
          || !Number.isInteger(seconds) || seconds < 0 || seconds > 720) {
        return setValidation('Margin must be 1–20, period 1–4, and clock seconds 0–720.');
      }
      createMutation.mutate({
        type, gameId: gameId.trim(), maximumMargin: parsedMargin, eligiblePeriod: parsedPeriod,
        maximumClockMillisRemaining: seconds * 1000, enabled: true,
      });
    }
    setValidation(null);
  }

  return (
    <div className="rules-layout">
      <section className="panel rule-builder" aria-labelledby="create-rule-title">
        <div className="panel-heading"><div><p className="eyebrow">Private alerts</p><h1 id="create-rule-title">Create a rule</h1></div></div>
        <p className="muted">Choose a validated template. CourtPulse never executes user-authored expressions.</p>
        <form className="rule-form" onSubmit={submit}>
          <label>Template
            <select value={type} onChange={(event) => setType(event.target.value as RuleKind)}>
              <option value="PLAYER_POINTS">Player points</option>
              <option value="CLOSE_GAME">Close game</option>
              <option value="SCORING_RUN">Scoring run</option>
            </select>
          </label>
          <p className="template-description">{descriptions[type]}</p>
          <label>Game ID<input value={gameId} maxLength={200} onChange={(event) => setGameId(event.target.value)} /></label>
          {type !== 'CLOSE_GAME' ? (
            <label>{type === 'PLAYER_POINTS' ? 'Player ID' : 'Team ID'}
              <input value={targetId} maxLength={200} onChange={(event) => setTargetId(event.target.value)} />
            </label>
          ) : null}
          {type !== 'CLOSE_GAME' ? (
            <label>{type === 'PLAYER_POINTS' ? 'Points threshold' : 'Unanswered points'}
              <input type="number" min="1" max={type === 'PLAYER_POINTS' ? '200' : '100'} value={threshold} onChange={(event) => setThreshold(event.target.value)} />
            </label>
          ) : (
            <div className="rule-form__row">
              <label>Maximum margin<input type="number" min="1" max="20" value={margin} onChange={(event) => setMargin(event.target.value)} /></label>
              <label>Eligible period<input type="number" min="1" max="4" value={period} onChange={(event) => setPeriod(event.target.value)} /></label>
              <label>Clock seconds remaining<input type="number" min="0" max="720" value={clockSeconds} onChange={(event) => setClockSeconds(event.target.value)} /></label>
            </div>
          )}
          {validation ? <p className="auth-error" role="alert">{validation}</p> : null}
          {createMutation.isError ? <MutationError error={createMutation.error} /> : null}
          {createMutation.isSuccess ? <p className="success-message" role="status">Rule saved.</p> : null}
          <button className="button button--primary" disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating…' : 'Create rule'}
          </button>
        </form>
      </section>

      <section className="panel" aria-labelledby="my-rules-title">
        <div className="panel-heading"><div><p className="eyebrow">Owned by you</p><h2 id="my-rules-title">My Rules</h2></div><span className="count-pill">{rules.length}</span></div>
        {query.isPending ? <LoadingState label="Loading alert rules" /> : null}
        {query.isError ? <ErrorPanel error={query.error} onRetry={() => void query.refetch()} /> : null}
        {!query.isPending && rules.length === 0 ? <p className="muted">No rules yet. Create your first structured alert.</p> : null}
        <div className="rule-list">
          {rules.map((rule) => (
            <article className="rule-card" key={rule.id}>
              <div><span className="rule-type">{ruleLabel(rule.type)}</span><h3>{ruleSummary(rule)}</h3><p>{rule.gameId}</p></div>
              <div className="rule-actions">
                <button className="button button--secondary" disabled={toggleMutation.isPending} onClick={() => toggleMutation.mutate(rule)}>{rule.enabled ? 'Disable' : 'Enable'}</button>
                <button className="button button--quiet" disabled={deleteMutation.isPending} onClick={() => deleteMutation.mutate(rule.id)}>Delete</button>
              </div>
            </article>
          ))}
        </div>
        {toggleMutation.isError ? <MutationError error={toggleMutation.error} /> : null}
        {deleteMutation.isError ? <MutationError error={deleteMutation.error} /> : null}
        {query.hasNextPage ? <button className="button button--secondary button--full" onClick={() => void query.fetchNextPage()}>Load more rules</button> : null}
      </section>
    </div>
  );
}

function MutationError({ error }: { error: Error }) {
  const message = error instanceof ApiError && error.status === 401
    ? 'Your session expired. Sign in again before retrying.'
    : error.message;
  return <p className="auth-error" role="alert">{message}</p>;
}

function ruleLabel(type: AlertRule['type']) {
  return type === 'PLAYER_POINTS' ? 'Player points' : type === 'CLOSE_GAME' ? 'Close game' : 'Scoring run';
}

function ruleSummary(rule: AlertRule) {
  if (rule.type === 'PLAYER_POINTS') return `${rule.playerId} · ${String(rule.pointsThreshold)} points`;
  if (rule.type === 'SCORING_RUN') return `${rule.teamId} · ${String(rule.pointsThreshold)} unanswered`;
  return `Within ${String(rule.maximumMargin)} points · period ${String(rule.eligiblePeriod)} · ${String(rule.maximumClockMillisRemaining / 1000)}s or less`;
}
