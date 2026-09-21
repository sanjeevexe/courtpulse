import type { DataStatus } from '../api/client';

const statusCopy: Record<DataStatus, { label: string; detail: string }> = {
  SCHEDULED: { label: 'Scheduled', detail: 'Game updates begin at tipoff.' },
  LIVE: { label: 'Live', detail: 'Game data is current.' },
  FINAL: { label: 'Final', detail: 'The final result is durable.' },
  STALE: { label: 'Updates delayed', detail: 'The game is live, but updates may be delayed.' },
  PROCESSING_BLOCKED: {
    label: 'Processing attention',
    detail: 'Game processing needs attention. The latest durable score remains available.',
  },
};

export function DataStatusBadge({ status, explain = false }: { status: DataStatus; explain?: boolean }) {
  const copy = statusCopy[status];
  return (
    <span className={`data-status data-status--${status.toLowerCase()}`} title={copy.detail}>
      <span className="status-symbol" aria-hidden="true" />
      <span>{copy.label}</span>
      {explain && (status === 'STALE' || status === 'PROCESSING_BLOCKED') ? (
        <span className="status-explanation">{copy.detail}</span>
      ) : null}
    </span>
  );
}
