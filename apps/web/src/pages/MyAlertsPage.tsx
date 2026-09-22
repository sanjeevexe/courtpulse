import { useMemo } from 'react';
import { useMyAlerts } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';
import { formatDateTime } from '../lib/format';

export function MyAlertsPage() {
  const auth = useAuth();
  const query = useMyAlerts(auth.accessToken, auth.status === 'AUTHENTICATED');
  const alerts = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);

  if (auth.status !== 'AUTHENTICATED') {
    return (
      <section className="empty-state">
        <h1>My Alerts</h1>
        <p>Sign in to see your private alert history. Personalized alerts never appear on public game channels.</p>
        {auth.enabled ? <button className="button" onClick={() => void auth.signIn('/my-alerts')}>Sign in</button> : null}
      </section>
    );
  }
  if (query.isPending) return <LoadingState label="Loading private alerts" />;
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />;
  return (
    <section className="panel panel--wide">
      <div className="panel-heading"><div><p className="eyebrow">Private history</p><h1>My Alerts</h1></div><span className="count-pill">{alerts.length}</span></div>
      {alerts.length === 0 ? <p className="muted">No personalized alerts have fired yet.</p> : null}
      <div className="alert-list">
        {alerts.map((alert) => (
          <article className="alert-card" key={alert.id}>
            <span className="alert-icon" aria-hidden="true">!</span>
            <div><span className="rule-type">{alert.ruleType.replaceAll('_', ' ')}</span><h3>{alert.title}</h3><p>{Object.entries(alert.context).map(([key, value]) => `${key}: ${value}`).join(' · ')}</p><small>{alert.gameId} · {formatDateTime(alert.createdAt)}</small></div>
          </article>
        ))}
      </div>
      {query.hasNextPage ? <button className="button button--secondary button--full" onClick={() => void query.fetchNextPage()}>Load more alerts</button> : null}
    </section>
  );
}
