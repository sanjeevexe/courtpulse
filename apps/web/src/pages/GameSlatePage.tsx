import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { GameSummary } from '../api/client';
import { uniqueGames, useGames } from '../api/hooks';
import { ErrorPanel } from '../components/ErrorPanel';
import { GameCard } from '../components/GameCard';
import { EmptyState } from '../components/EmptyState';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { SegmentedControl } from '../components/SegmentedControl';

type StatusFilter = GameSummary['status'] | 'ALL';
const filters: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: 'All games' },
  { value: 'LIVE', label: 'Live' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'FINAL', label: 'Final' },
];

export function GameSlatePage() {
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const gamesQuery = useGames(status);
  const games = uniqueGames(gamesQuery.data);

  const liveCount = games.filter((game) => game.status === 'LIVE').length;

  return (
    <>
      <PageHeader
        eyebrow={new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' }).format(new Date())}
        title="Scores"
        lede="Live scores, every play, and alerts for the moments you care about."
        actions={liveCount > 0 && status === 'ALL' ? (
          <span className="live-summary"><span className="live-dot" aria-hidden="true" />{liveCount} live now</span>
        ) : null}
      />

      <div className="toolbar">
        <SegmentedControl label="Filter games by status" value={status} onChange={setStatus} options={filters} />
      </div>

      {gamesQuery.isPending ? <LoadingState /> : null}
      {gamesQuery.isError ? (
        <ErrorPanel error={gamesQuery.error} onRetry={() => void gamesQuery.refetch()} />
      ) : null}
      {!gamesQuery.isPending && !gamesQuery.isError && games.length === 0 ? (
        <EmptyState
          title={`No ${status === 'ALL' ? '' : `${status.toLowerCase()} `}games yet`}
          body="When games enter this state, they will appear here automatically."
          action={status === 'ALL' ? <Link className="button button--secondary" to="/replays">Replay a real game</Link> : null}
        />
      ) : null}
      {games.length > 0 ? (
        <section className="game-grid" aria-label="Game slate" aria-busy={gamesQuery.isFetching}>
          {games.map((game) => <GameCard game={game} key={game.gameId} />)}
        </section>
      ) : null}
      {gamesQuery.hasNextPage ? (
        <div className="load-more-row">
          <button
            className="button button--secondary"
            disabled={gamesQuery.isFetchingNextPage}
            onClick={() => void gamesQuery.fetchNextPage()}
          >
            {gamesQuery.isFetchingNextPage ? 'Loading more…' : 'Load more games'}
          </button>
        </div>
      ) : null}
    </>
  );
}
