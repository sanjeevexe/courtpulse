import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listFollowedGames } from '../api/client';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';

export function MyGamesPage() {
  const auth = useAuth();
  const query = useQuery({
    queryKey: ['me', 'followed-games'],
    queryFn: () => listFollowedGames(auth.accessToken ?? ''),
    enabled: auth.status === 'AUTHENTICATED' && auth.accessToken !== null,
  });

  if (auth.status !== 'AUTHENTICATED') {
    return (
      <section className="empty-state">
        <h1>My Games</h1>
        <p>Sign in to keep a private list of followed games. Public scores remain available.</p>
        {auth.enabled ? (
          <button
            className="button"
            disabled={auth.signInPending}
            onClick={() => void auth.signIn('/my-games')}
          >
            {auth.signInPending ? 'Signing in…' : 'Sign in'}
          </button>
        ) : null}
      </section>
    );
  }
  if (query.isPending) return <LoadingState label="Loading followed games" />;
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />;
  return (
    <section className="panel panel--wide">
      <div className="panel-heading"><div><p className="eyebrow">Personal</p><h1>My Games</h1></div></div>
      {query.data.items.length === 0 ? <p className="muted">You are not following a game yet.</p> : (
        <ul className="followed-games">
          {query.data.items.map((item) => (
            <li key={item.gameId}><Link to={`/games/${encodeURIComponent(item.gameId)}`}>{item.gameId}</Link></li>
          ))}
        </ul>
      )}
    </section>
  );
}
