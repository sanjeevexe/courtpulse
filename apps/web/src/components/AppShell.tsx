import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { AuthControls } from '../auth/AuthControls';
import { useAuth } from '../auth/useAuth';
import { AlertToasts } from './AlertToasts';
import { Icon } from './Icons';
import { useMediaQuery } from '../lib/useMediaQuery';

/** Scores stays active on game pages, which are reached from it. */
function scoresActive(pathname: string) {
  return pathname === '/' || pathname.startsWith('/games/');
}

export function AppShell() {
  const location = useLocation();
  const auth = useAuth();
  const signedIn = auth.status === 'AUTHENTICATED';
  const navClass = ({ isActive }: { isActive: boolean }) => `nav-link${isActive ? ' is-active' : ''}`;
  const tabClass = ({ isActive }: { isActive: boolean }) => `tab-bar__item${isActive ? ' is-active' : ''}`;
  const onScores = scoresActive(location.pathname);
  // The bottom tab bar replaces the header links on narrow screens (the header nav hides in CSS).
  const compact = useMediaQuery('(max-width: 720px)');

  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="site-header__inner">
          <Link className="brand" to="/" aria-label="CourtPulse game slate">
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32" width="32" height="32">
                <circle cx="16" cy="16" r="15" />
                <path d="M4 17h6l3-7 5 13 3-6h7" />
              </svg>
            </span>
            <span className="brand-name" aria-hidden="true">CourtPulse</span>
          </Link>
          <nav className="site-nav" aria-label="Primary">
            <Link className={`nav-link${onScores ? ' is-active' : ''}`} to="/"
              aria-current={onScores ? 'page' : undefined}>Scores</Link>
            <NavLink className={navClass} to="/replays">Replays</NavLink>
            {signedIn ? <NavLink className={navClass} to="/my-alerts">Alerts</NavLink> : null}
            {signedIn ? <NavLink className={navClass} to="/my-rules">Rules</NavLink> : null}
          </nav>
          <AuthControls />
        </div>
      </header>
      <main id="main-content" className="page-frame" tabIndex={-1}>
        <div className="page-enter" key={location.pathname}>
          <Outlet />
        </div>
      </main>
      <footer className="site-footer">
        <span>CourtPulse · Live scores, play-by-play, and alerts</span>
        <span>Not affiliated with the NBA</span>
      </footer>
      {compact ? (
        <nav className="tab-bar" aria-label="Primary (compact)">
        <Link className={`tab-bar__item${onScores ? ' is-active' : ''}`} to="/"
            aria-current={onScores ? 'page' : undefined}>
            <Icon name="scores" size={20} /><span>Scores</span>
          </Link>
          <NavLink className={tabClass} to="/replays"><Icon name="replay" size={20} /><span>Replays</span></NavLink>
          <NavLink className={tabClass} to="/my-alerts"><Icon name="bell" size={20} /><span>Alerts</span></NavLink>
          <NavLink className={tabClass} to="/my-rules"><Icon name="rules" size={20} /><span>Rules</span></NavLink>
        </nav>
      ) : null}
      <AlertToasts />
    </div>
  );
}
