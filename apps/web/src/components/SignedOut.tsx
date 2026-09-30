import { useAuth } from '../auth/useAuth';
import { EmptyState } from './EmptyState';
import { Icon } from './Icons';

/** The shared signed-out state for personal pages: what the page is for, and one way in. */
export function SignedOut({ title, body, returnPath }: { title: string; body: string; returnPath: string }) {
  const auth = useAuth();
  return (
    <EmptyState
      heading="h1"
      mark={<Icon name="user" size={32} />}
      title={title}
      body={body}
      action={auth.enabled ? (
        <button
          className="button button--primary"
          disabled={auth.signInPending}
          onClick={() => void auth.signIn(returnPath)}
        >
          {auth.signInPending ? 'Signing in…' : 'Sign in'}
        </button>
      ) : null}
    />
  );
}
