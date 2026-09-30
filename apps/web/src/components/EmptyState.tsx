import type { ReactNode } from 'react';

/** Empty, signed-out, and not-found states share one shape: mark, title, body, and one action. */
export function EmptyState({ mark, title, body, action, heading = 'h2', role }: {
  mark?: ReactNode;
  title: string;
  body?: ReactNode;
  action?: ReactNode;
  heading?: 'h1' | 'h2';
  role?: 'status' | 'alert';
}) {
  const Heading = heading;
  return (
    <section className="empty-state" role={role} aria-live={role ? undefined : 'polite'}>
      <span className="empty-state__mark" aria-hidden="true">{mark ?? <CourtMark />}</span>
      <Heading>{title}</Heading>
      {body ? <p>{body}</p> : null}
      {action}
    </section>
  );
}

/** A small half-court line drawing, used as the default empty-state mark. */
function CourtMark() {
  return (
    <svg viewBox="0 0 48 48" width="40" height="40" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="5" y="9" width="38" height="30" rx="3" />
      <path d="M24 9v30" />
      <circle cx="24" cy="24" r="6" />
      <path d="M5 17h6a7 7 0 0 1 0 14H5M43 17h-6a7 7 0 0 0 0 14h6" />
    </svg>
  );
}
