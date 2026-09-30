import { useMemo, useState } from 'react';
import { type DeliveryHistoryPage } from '../api/client';
import { useDeliveryAttempts, useDeliveryHistory, useMyAlerts } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';
import { alertDetails, formatDateTime } from '../lib/format';

export function MyAlertsPage() {
  const auth = useAuth();
  const query = useMyAlerts(auth.accessToken, auth.status === 'AUTHENTICATED');
  const deliveries = useDeliveryHistory(auth.accessToken, auth.status === 'AUTHENTICATED');
  const alerts = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);
  const deliveryByAlert = useMemo(() => {
    const byAlert = new Map<string, DeliveryHistoryPage['items']>();
    for (const delivery of deliveries.data?.items ?? []) {
      const existing = byAlert.get(delivery.alertId) ?? [];
      existing.push(delivery);
      existing.sort((left, right) => left.channel.localeCompare(right.channel) * -1);
      byAlert.set(delivery.alertId, existing);
    }
    return byAlert;
  }, [deliveries.data]);

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
            <div><span className="rule-type">{alert.ruleType.replaceAll('_', ' ')}</span><h3>{alert.title}</h3><p>{alertDetails(alert.context, alert.ruleType)}</p><small>{alert.gameLabel ?? alert.gameId} · {formatDateTime(alert.createdAt)}</small>
              {deliveryByAlert.get(alert.id)?.map((delivery) => (
                <DeliveryState key={delivery.id} delivery={delivery} accessToken={auth.accessToken} />
              ))}
            </div>
          </article>
        ))}
      </div>
      {query.hasNextPage ? <button className="button button--secondary button--full" onClick={() => void query.fetchNextPage()}>Load more alerts</button> : null}
    </section>
  );
}

function DeliveryState({
  delivery, accessToken,
}: { delivery: DeliveryHistoryPage['items'][number]; accessToken: string | null }) {
  const [expanded, setExpanded] = useState(false);
  const attempts = useDeliveryAttempts(accessToken, accessToken !== null, delivery.id, expanded);
  const label = delivery.channel === 'IN_APP' ? 'In app' : 'Email';
  return (
    <div className="delivery-state">
      <p className="muted">{label}: {delivery.status.replaceAll('_', ' ').toLowerCase()}
        {delivery.channel === 'EMAIL' ? ` · ${String(delivery.attempts)} attempt${delivery.attempts === 1 ? '' : 's'}` : ''}
        {delivery.lastErrorCode ? ` · ${delivery.lastErrorCode.replaceAll('_', ' ')}` : ''}
        {delivery.nextAttemptAt && delivery.status === 'RETRY_SCHEDULED'
          ? ` · retry ${formatDateTime(delivery.nextAttemptAt)}` : ''}
      </p>
      {delivery.channel === 'EMAIL' && delivery.attempts > 0 ? (
        <details onToggle={(event) => setExpanded(event.currentTarget.open)}>
          <summary>Attempt history</summary>
          {attempts.isPending ? <p>Loading attempts…</p> : null}
          {attempts.isError ? <p role="alert">Attempt history is temporarily unavailable.</p> : null}
          {attempts.data?.items.map((attempt) => (
            <p key={attempt.id}>Attempt {attempt.attemptNumber}: {attempt.outcome.replaceAll('_', ' ').toLowerCase()}
              {attempt.errorCode ? ` · ${attempt.errorCode.replaceAll('_', ' ')}` : ''}</p>
          ))}
        </details>
      ) : null}
    </div>
  );
}
