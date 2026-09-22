import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';

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
      <section className="empty-state" role="alert">
        <h1>Sign-in did not complete</h1>
        <p>No credentials were saved. Return to the public game slate and try again.</p>
        <button className="button" onClick={() => void navigate('/', { replace: true })}>Return to games</button>
      </section>
    );
  }
  return <div className="loading-state" role="status">Completing secure sign-in…</div>;
}
