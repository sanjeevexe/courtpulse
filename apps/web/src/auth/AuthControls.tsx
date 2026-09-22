import { Link, useLocation } from 'react-router-dom';
import { useAuth } from './useAuth';

export function AuthControls() {
  const auth = useAuth();
  const location = useLocation();

  if (auth.status === 'LOADING') return <span className="auth-status" role="status">Checking sign-in…</span>;
  if (auth.status === 'AUTHENTICATED') {
    return (
      <div className="auth-controls">
        <Link className="text-link" to="/my-games">My Games</Link>
        <span className="auth-subject">Signed in as {auth.subject}</span>
        <button
          className="button button--quiet"
          disabled={auth.signOutPending}
          onClick={() => void auth.signOut()}
        >
          {auth.signOutPending ? 'Signing out…' : 'Sign out'}
        </button>
      </div>
    );
  }
  return (
    <div className="auth-controls">
      {auth.error ? <span className="auth-error" role="alert">{auth.error}</span> : null}
      {auth.enabled ? (
        <button
          className="button button--quiet"
          disabled={auth.signInPending}
          onClick={() => void auth.signIn(location.pathname + location.search)}
        >
          {auth.signInPending ? 'Signing in…' : 'Sign in'}
        </button>
      ) : <span className="auth-status">Public browsing</span>}
    </div>
  );
}
