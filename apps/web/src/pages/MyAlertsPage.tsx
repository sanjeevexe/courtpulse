import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { type DeliveryHistoryPage } from '../api/client';
import { useDeliveryAttempts, useDeliveryHistory, useMyAlerts } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { EmptyState } from '../components/EmptyState';
import { Icon } from '../components/Icons';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { SignedOut } from '../components/SignedOut';
import { alertDetails, formatDateTime, readableEventType } from '../lib/format';
import { playoffTitleForLabel } from '../lib/playoffs';

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
      <SignedOut title="My Alerts" returnPath="/my-alerts"
        body="Sign in to see your private alert history. Personalized alerts never appear on public game channels." />
    );
  }
  return (
    <>
      <PageHeader eyebrow="Alerts" title="My Alerts" lede="Every alert your rules have fired, and how it reached you."
        actions={<Link className="button button--secondary button--sm" to="/my-rules"><Icon name="rules" size={16} />Manage rules</Link>} />
      {query.isPending ? <LoadingState label="Loading private alerts" variant="list" /> : null}
      {query.isError ? <ErrorPanel error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && alerts.length === 0 ? (
        <EmptyState
          mark={<Icon name="bell" size={32} />}
          title="No personalized alerts have fired yet."
          body="Create a rule on a live game or a replay, and alerts will collect here."
          action={<Link className="button button--secondary" to="/my-rules">Create a rule</Link>}
        />
      ) : null}
      {alerts.length > 0 ? (
        <div className="alert-list alert-list--page">
          {alerts.map((alert) => (
            <article className="alert-card alert-card--mine" key={alert.id}>
              <span className="alert-icon" aria-hidden="true"><Icon name="bell" size={16} /></span>
              <div className="alert-card__body">
                <span className="tag">{readableEventType(alert.ruleType)}</span>
                <h3>{alert.title}</h3>
                <p>{alertDetails(alert.context, alert.ruleType)}</p>
                <small>
                  <Link to={`/games/${encodeURIComponent(alert.gameId)}`}>{alert.gameLabel ?? alert.gameId}</Link>
                  {withTitle(alert.gameId, alert.gameLabel)}
                  {' · '}{formatDateTime(alert.createdAt)}
                </small>
                {deliveryByAlert.get(alert.id)?.length ? (
                  <div className="delivery-list">
                    {deliveryByAlert.get(alert.id)?.map((delivery) => (
                      <DeliveryState key={delivery.id} delivery={delivery} accessToken={auth.accessToken} />
                    ))}
                  </div>
                ) : null}
              </div>
            </article>
          ))}
        </div>
      ) : null}
      {query.hasNextPage ? (
        <button className="button button--secondary button--full" onClick={() => void query.fetchNextPage()}>
          Load more alerts
        </button>
      ) : null}
    </>
  );
}

/** " · Western Conference Finals · Game 1" for a playoff game, otherwise nothing. */
function withTitle(gameId: string, label?: string | null): string {
  const title = playoffTitleForLabel(gameId, label);
  return title ? ` · ${title}` : '';
}

function DeliveryState({
  delivery, accessToken,
}: { delivery: DeliveryHistoryPage['items'][number]; accessToken: string | null }) {
  const [expanded, setExpanded] = useState(false);
  const attempts = useDeliveryAttempts(accessToken, accessToken !== null, delivery.id, expanded);
  const label = delivery.channel === 'IN_APP' ? 'In app' : 'Email';
  return (
    <div className={`delivery-state delivery-state--${delivery.status.toLowerCase()}`}>
      <p className="delivery-chip"><span className="delivery-chip__dot" aria-hidden="true" />{label}: {delivery.status.replaceAll('_', ' ').toLowerCase()}
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
