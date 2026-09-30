import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { saveNotificationSettings, type NotificationSettings } from '../api/client';
import { useNotificationSettings } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { Icon } from '../components/Icons';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { SignedOut } from '../components/SignedOut';

export function NotificationSettingsPage() {
  const auth = useAuth();
  const settings = useNotificationSettings(auth.accessToken, auth.status === 'AUTHENTICATED');

  if (auth.status !== 'AUTHENTICATED') {
    return (
      <SignedOut title="Notification settings" returnPath="/notification-settings"
        body="Sign in to choose how your alerts reach you." />
    );
  }
  return (
    <>
      <PageHeader eyebrow="Your account" title="Notifications" lede="Alerts always appear in the app. Email is optional." />
      {settings.isPending ? <LoadingState label="Loading notification settings" variant="list" /> : null}
      {settings.isError ? <ErrorPanel error={settings.error} onRetry={() => void settings.refetch()} /> : null}
      {settings.isSuccess ? <SettingsForm initial={settings.data} accessToken={auth.accessToken ?? ''} /> : null}
    </>
  );
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
    <section className="panel settings-panel" aria-labelledby="email-title">
      <div className="panel-heading"><h2 id="email-title"><Icon name="mail" />Email</h2></div>
      <form className="form" onSubmit={(event) => { event.preventDefault(); save.mutate(); }}>
        <label className="switch-row">
          <span className="switch-row__text">
            <strong>Email me when an alert fires</strong>
            <small>In this local demo, email is captured by Mailpit at 127.0.0.1:8025.</small>
          </span>
          <input className="switch" type="checkbox" role="switch" checked={emailEnabled}
            onChange={(event) => setEmailEnabled(event.target.checked)} />
        </label>
        <label className="field">
          <span>Email address</span>
          <input type="email" value={emailAddress} maxLength={254}
            onChange={(event) => setEmailAddress(event.target.value)} autoComplete="email" />
        </label>
        <div className="form__actions">
          <button className="button button--primary" type="submit"
            disabled={save.isPending || (emailEnabled && !emailAddress.trim())}>
            {save.isPending ? 'Saving…' : 'Save settings'}
          </button>
          {save.isSuccess ? <p className="success-message" role="status"><Icon name="check" size={16} />Settings saved.</p> : null}
          {save.isError ? <p className="form-error" role="alert">{save.error.message}</p> : null}
        </div>
      </form>
    </section>
  );
}
