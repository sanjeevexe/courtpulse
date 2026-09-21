import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <section className="empty-state" role="status">
      <span aria-hidden="true">404</span>
      <h1>This route is off the court</h1>
      <p>The page does not exist, but the game slate is ready.</p>
      <Link className="button button--primary" to="/">Return to game slate</Link>
    </section>
  );
}
