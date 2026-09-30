import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { EmptyState } from '../components/EmptyState';

export function AuthCallbackPage() {
  const { completeCallback, status } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (status === 'LOADING' || started.current) return undefined;
    started.current = true;
    let active = true;
    void completeCallback()
      .then((returnPath) => { if (active) void navigate(returnPath, { replace: true }); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [completeCallback, navigate, status]);

  if (failed) {
    return (
      <EmptyState
        heading="h1"
        role="alert"
        title="Sign-in did not complete"
        body="No credentials were saved. Return to the scores and try again."
        action={
          <button className="button button--primary" onClick={() => void navigate('/', { replace: true })}>
            Return to scores
          </button>
        }
      />
    );
  }
  return (
    <div className="callback-state" role="status">
      <span className="spinner" aria-hidden="true" />
      Completing secure sign-in…
    </div>
  );
}
