import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listFollowedGames } from '../api/client';
import { useGameSnapshot } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { EmptyState } from '../components/EmptyState';
import { ErrorPanel } from '../components/ErrorPanel';
import { GameCard } from '../components/GameCard';
import { Icon } from '../components/Icons';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { SignedOut } from '../components/SignedOut';

export function MyGamesPage() {
  const auth = useAuth();
  const query = useQuery({
    queryKey: ['me', 'followed-games'],
    queryFn: () => listFollowedGames(auth.accessToken ?? ''),
    enabled: auth.status === 'AUTHENTICATED' && auth.accessToken !== null,
  });

  if (auth.status !== 'AUTHENTICATED') {
    return (
      <SignedOut title="My Games" returnPath="/my-games"
        body="Sign in to keep a private list of followed games. Public scores remain available." />
    );
  }
  return (
    <>
      <PageHeader eyebrow="Your account" title="My Games" lede="Games you follow, with live scores." />
      {query.isPending ? <LoadingState label="Loading followed games" /> : null}
      {query.isError ? <ErrorPanel error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && query.data.items.length === 0 ? (
        <EmptyState
          mark={<Icon name="star" size={32} />}
          title="You are not following a game yet."
          body="Open any game and choose Follow game to keep it here."
          action={<Link className="button button--secondary" to="/">Browse scores</Link>}
        />
      ) : null}
      {query.isSuccess && query.data.items.length > 0 ? (
        <section className="game-grid" aria-label="Followed games">
          {query.data.items.map((item) => <FollowedGame key={item.gameId} gameId={item.gameId} />)}
        </section>
      ) : null}
    </>
  );
}

/** A followed game as a live score card; if its score cannot load, a plain link still reaches it. */
function FollowedGame({ gameId }: { gameId: string }) {
  const snapshot = useGameSnapshot(gameId);
  if (snapshot.isSuccess) return <GameCard game={snapshot.data} />;
  return (
    <article className="game-card game-card--placeholder">
      {snapshot.isPending ? <span className="skeleton skeleton--line" aria-hidden="true" /> : null}
      <Link className="game-card__link" to={`/games/${encodeURIComponent(gameId)}`}>{gameId}</Link>
    </article>
  );
}
