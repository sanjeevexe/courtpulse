import { Link, Outlet } from 'react-router-dom';
import { AuthControls } from '../auth/AuthControls';

export function AppShell() {
  return (
    <div className="app-shell">
      <header className="site-header">
        <Link className="brand" to="/" aria-label="CourtPulse game slate">
          <span className="brand-mark" aria-hidden="true">CP</span>
          <span>
            <strong>CourtPulse</strong>
            <small>Every possession, in context.</small>
          </span>
        </Link>
        <div className="header-actions">
          <Link className="text-link" to="/replays">Replays</Link>
          <div className="live-key" aria-label="Data status key">
            <span className="pulse-dot" aria-hidden="true" /> Durable game feed
          </div>
          <AuthControls />
        </div>
      </header>
      <main id="main-content" className="page-frame" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer">
        <span>CourtPulse</span>
        <span>Public game data · PostgreSQL-backed</span>
      </footer>
    </div>
  );
}
