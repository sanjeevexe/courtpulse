import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient, type InfiniteData } from '@tanstack/react-query';
import type { OwnedAlert, OwnedAlertPage } from '../api/client';
import { useMyAlerts } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { playoffTitleForLabel } from '../lib/playoffs';
import { Icon } from './Icons';

const TOAST_MS = 7_000;
const POLL_MS = 10_000;

/**
 * Pops a toast when one of the fan's alerts fires, wherever they are in the app. The alerts already
 * present when the page loads are only remembered, never announced.
 */
export function AlertToasts() {
  const auth = useAuth();
  const queryClient = useQueryClient();
  const authenticated = auth.status === 'AUTHENTICATED';
  // Keeps the fan's alert list fresh while signed in; this also refreshes My Alerts and game pages.
  useMyAlerts(auth.accessToken, authenticated, authenticated ? POLL_MS : false);
  const [toasts, setToasts] = useState<OwnedAlert[]>([]);

  useEffect(() => {
    if (!authenticated) return undefined;
    const seen = new Set<string>();
    let seeded = false;
    const check = () => {
      const data = queryClient.getQueryData<InfiniteData<OwnedAlertPage>>(['me', 'alerts']);
      if (!data) return;
      const items = data.pages[0]?.items ?? [];
      if (!seeded) {
        seeded = true;
        for (const item of items) seen.add(item.id);
        return;
      }
      const fresh = items.filter((item) => !seen.has(item.id));
      for (const item of fresh) seen.add(item.id);
      if (fresh.length > 0) setToasts((current) => [...fresh, ...current].slice(0, 3));
    };
    check();
    return queryClient.getQueryCache().subscribe((event) => {
      const [scope, kind] = event.query.queryKey as readonly unknown[];
      if (event.type === 'updated' && scope === 'me' && kind === 'alerts') {
        check();
      }
    });
  }, [authenticated, auth.subject, queryClient]);

  const dismiss = useCallback(
    (id: string) => setToasts((current) => current.filter((toast) => toast.id !== id)),
    [],
  );

  return (
    <div className="toast-region" aria-live="polite">
      {authenticated ? toasts.map((toast) => <Toast key={toast.id} alert={toast} onDismiss={dismiss} />) : null}
    </div>
  );
}

function Toast({ alert, onDismiss }: { alert: OwnedAlert; onDismiss: (id: string) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => onDismiss(alert.id), TOAST_MS);
    return () => window.clearTimeout(timer);
  }, [alert.id, onDismiss]);
  const title = playoffTitleForLabel(alert.gameId, alert.gameLabel);
  return (
    <div className="toast">
      <span className="toast__icon" aria-hidden="true"><Icon name="bell" size={16} /></span>
      <div className="toast__body">
        <strong>{alert.title}</strong>
        <span>{alert.gameLabel ?? 'Your alert fired'}</span>
        {title ? <span>{title}</span> : null}
        <Link to={`/games/${encodeURIComponent(alert.gameId)}`} onClick={() => onDismiss(alert.id)}>Open game</Link>
      </div>
      <button className="icon-button icon-button--sm" aria-label="Dismiss alert" onClick={() => onDismiss(alert.id)}>
        <Icon name="close" size={16} />
      </button>
    </div>
  );
}
