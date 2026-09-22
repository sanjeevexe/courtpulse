import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { saveNotificationSettings, type NotificationSettings } from '../api/client';
import { useNotificationSettings } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';

export function NotificationSettingsPage() {
  const auth = useAuth();
  const settings = useNotificationSettings(auth.accessToken, auth.status === 'AUTHENTICATED');

  if (auth.status !== 'AUTHENTICATED') {
    return <section className="empty-state"><h1>Notification settings</h1><p>Sign in to manage private delivery.</p></section>;
  }
  if (settings.isPending) return <LoadingState label="Loading notification settings" />;
  if (settings.isError) return <ErrorPanel error={settings.error} onRetry={() => void settings.refetch()} />;
  return <SettingsForm initial={settings.data} accessToken={auth.accessToken ?? ''} />;
}

function SettingsForm({ initial, accessToken }: { initial: NotificationSettings; accessToken: string }) {
  const queryClient = useQueryClient();
  const [emailEnabled, setEmailEnabled] = useState(initial.emailEnabled);
  const [emailAddress, setEmailAddress] = useState(initial.emailAddress ?? '');
  const save = useMutation({
    mutationFn: () => saveNotificationSettings(accessToken, { emailEnabled, emailAddress }),
    onSuccess: (value) => queryClient.setQueryData(['me', 'notification-settings'], value),
  });

  return (
    <section className="panel panel--wide">
      <p className="eyebrow">Private delivery</p>
      <h1>Notification settings</h1>
      <p>In-app alerts are always saved. Email is opt-in and currently goes only to the local Mailpit test sink.</p>
      <form className="rule-form" onSubmit={(event) => { event.preventDefault(); save.mutate(); }}>
        <label className="form-field">
          <span>Email address</span>
          <input type="email" value={emailAddress} maxLength={254}
            onChange={(event) => setEmailAddress(event.target.value)} autoComplete="email" />
        </label>
        <label className="form-field">
          <span><input type="checkbox" checked={emailEnabled} onChange={(event) => setEmailEnabled(event.target.checked)} /> Enable local email alerts</span>
        </label>
        <button className="button" type="submit" disabled={save.isPending || (emailEnabled && !emailAddress.trim())}>
          {save.isPending ? 'Saving…' : 'Save settings'}
        </button>
        {save.isSuccess ? <p role="status">Settings saved.</p> : null}
        {save.isError ? <p role="alert">{save.error.message}</p> : null}
      </form>
    </section>
  );
}
