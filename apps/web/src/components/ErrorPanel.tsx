import { ApiError } from '../api/client';

function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Something unexpected interrupted CourtPulse.';
  if (error.status === 404) return 'That game is not available.';
  if (error.status === 503) return 'Game data is temporarily unavailable.';
  if (error.kind === 'timeout') return 'The game feed took too long to respond.';
  if (error.kind === 'network') return 'CourtPulse cannot reach the game feed.';
  if (error.kind === 'malformed') return 'CourtPulse received an unexpected response.';
  return error.message || 'The request could not be completed.';
}

export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <section className="error-panel" role="alert" aria-labelledby="error-title">
      <span className="error-kicker">Unable to load</span>
      <h2 id="error-title">{errorMessage(error)}</h2>
      <p>Your place is safe. Retry when you are ready.</p>
      <div className="error-actions">
        {onRetry ? <button className="button button--secondary" onClick={onRetry}>Try again</button> : null}
        {apiError?.correlationId ? (
          <details>
            <summary>Technical details</summary>
            <p>Support code: <code>{apiError.correlationId}</code></p>
          </details>
        ) : null}
      </div>
    </section>
  );
}
