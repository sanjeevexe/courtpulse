import { useState } from 'react';
import type { GameSummary } from '../api/client';
import { uniqueGames, useGames } from '../api/hooks';
import { ErrorPanel } from '../components/ErrorPanel';
import { GameCard } from '../components/GameCard';
import { LoadingState } from '../components/LoadingState';

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

  return (
    <>
      <section className="slate-heading">
        <div>
          <p className="eyebrow">Game center</p>
          <h1>Today’s pulse</h1>
          <p>Durable scores, possession history, and the moments worth remembering.</p>
        </div>
        <div className="slate-stat" aria-label={`${String(games.length)} games currently shown`}>
          <strong>{games.length.toString().padStart(2, '0')}</strong>
          <span>games shown</span>
        </div>
      </section>

      <nav className="filter-bar" aria-label="Filter games by status">
        {filters.map((filter) => (
          <button
            key={filter.value}
            className={filter.value === status ? 'filter-chip filter-chip--active' : 'filter-chip'}
            aria-pressed={filter.value === status}
            onClick={() => setStatus(filter.value)}
          >
            {filter.label}
          </button>
        ))}
      </nav>

      {gamesQuery.isPending ? <LoadingState /> : null}
      {gamesQuery.isError ? (
        <ErrorPanel error={gamesQuery.error} onRetry={() => void gamesQuery.refetch()} />
      ) : null}
      {!gamesQuery.isPending && !gamesQuery.isError && games.length === 0 ? (
        <section className="empty-state" aria-live="polite">
          <span aria-hidden="true">○</span>
          <h2>No {status === 'ALL' ? '' : status.toLowerCase()} games yet</h2>
          <p>When games enter this state, they will appear here automatically.</p>
        </section>
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
