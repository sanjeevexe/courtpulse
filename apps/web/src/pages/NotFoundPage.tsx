import { Link } from 'react-router-dom';
import { EmptyState } from '../components/EmptyState';

export function NotFoundPage() {
  return (
    <EmptyState
      heading="h1"
      role="status"
      mark={<span className="empty-state__code">404</span>}
      title="Out of bounds"
      body="This page does not exist, but the scores are ready."
      action={<Link className="button button--primary" to="/">Back to scores</Link>}
    />
  );
}
