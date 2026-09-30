import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Icon } from '../components/Icons';
import { useAuth } from './useAuth';

function initials(name: string | null): string {
  const letters = (name ?? '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim().split(' ')
    .filter(Boolean).slice(0, 2).map((part) => part.charAt(0)).join('');
  return (letters || '?').toUpperCase();
}

export function AuthControls() {
  const auth = useAuth();
  const location = useLocation();

  if (auth.status === 'LOADING') {
    return <span className="auth-status auth-status--loading" role="status">Checking sign-in…</span>;
  }
  if (auth.status === 'AUTHENTICATED') return <AccountMenu />;
  return (
    <div className="auth-controls">
      {auth.error ? <span className="form-error auth-error" role="alert">{auth.error}</span> : null}
      {auth.enabled ? (
        <button
          className="button button--primary button--sm"
          disabled={auth.signInPending}
          onClick={() => void auth.signIn(location.pathname + location.search)}
        >
          {auth.signInPending ? 'Signing in…' : 'Sign in'}
        </button>
      ) : <span className="auth-status">Public browsing</span>}
    </div>
  );
}

/** The signed-in account: an avatar button that opens a small menu of personal pages and sign-out. */
function AccountMenu() {
  const auth = useAuth();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);

  // Close on navigation, outside click, and Escape.
  const [path, setPath] = useState(location.pathname);
  if (path !== location.pathname) {
    setPath(location.pathname);
    setOpen(false);
  }
  useEffect(() => {
    if (!open) return undefined;
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="account" ref={root}>
      <button
        className="account__button"
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls={menuId}
        aria-label={`Account menu for ${auth.displayName ?? 'you'}`}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="avatar" aria-hidden="true">{initials(auth.displayName)}</span>
        <span className="account__name" aria-hidden="true">{auth.displayName}</span>
      </button>
      {open ? (
        <div className="account__menu" id={menuId}>
          <p className="account__who">Signed in as {auth.displayName}</p>
          <Link className="account__item" to="/my-games"><Icon name="star" />My Games</Link>
          <Link className="account__item" to="/my-alerts"><Icon name="bell" />My Alerts</Link>
          <Link className="account__item" to="/my-rules"><Icon name="rules" />My Rules</Link>
          <Link className="account__item" to="/notification-settings"><Icon name="mail" />Notifications</Link>
          <button
            className="account__item account__item--danger"
            disabled={auth.signOutPending}
            onClick={() => void auth.signOut()}
          >
            <Icon name="logout" />{auth.signOutPending ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      ) : null}
    </div>
  );
}
