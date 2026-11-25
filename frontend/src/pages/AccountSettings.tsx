import { useEffect, useState } from 'react';
import { GoogleIdentityButton, useGoogleOptions } from '../components/GoogleIdentityButton';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CreditCard, KeyRound, ShieldCheck, UserRound } from 'lucide-react';
import { CareerPreferences } from './General';
import { api, del, download, post, put } from '../lib/api';
import { Badge, Card, ErrorNote, Hero, Spinner } from '../components/Shell';

import type { Account } from '../lib/api-types';

interface Subscription {
  plan: string;
  status: string;
  periodEnd?: string | null;
  hasBillingCustomer: boolean;
}

export default function SettingsPage() {
  const client = useQueryClient();
  const googleClientId = useGoogleOptions().data?.googleClientId;
  const account = useQuery({ queryKey: ['me'], queryFn: () => api<Account>('/users/me') });
  const subscription = useQuery({ queryKey: ['subscription'], queryFn: () => api<Subscription>('/subscription') });
  const [name, setName] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [password, setPassword] = useState('');
  const [next, setNext] = useState('');
  const [email, setEmail] = useState('');
  const [pendingEmail, setPendingEmail] = useState('');
  const [code, setCode] = useState('');
  const [googleAction, setGoogleAction] = useState<'link' | 'setup' | 'email' | null>(null);
  const user = account.data;
  const plan = subscription.data;

  useEffect(() => {
    if (user) setName(user.fullName);
  }, [user?.fullName]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setMessage('');
    try {
      await action();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function reload(message: string) {
    setMessage(message);
    await Promise.all([
      client.invalidateQueries({ queryKey: ['me'] }),
      client.invalidateQueries({ queryKey: ['session'] }),
    ]);
  }

  function requestEmailChange(credential?: string) {
    return run(async () => {
      const result = await post<{message: string}>('/users/me/email-change', { email, password, credential });
      setPendingEmail(email);
      setPassword('');
      setGoogleAction(null);
      setMessage(result.message);
    });
  }

  function googleSuccess(credential?: string) {
    if (!credential) {
      setError(new Error('Google did not return a credential.'));
      return;
    }
    if (googleAction === 'email') {
      void requestEmailChange(credential);
      return;
    }
    void run(async () => {
      const path = googleAction === 'link' ? 'link-google' : 'setup-password';
      const result = await post<{message: string}>(`/users/me/${path}`, {
        credential, password: googleAction === 'link' ? password : next,
      });
      setGoogleAction(null);
      setPassword('');
      setNext('');
      await reload(result.message);
    });
  }

  return <>
    <Hero title="Settings" description="Manage your account, sign-in methods, billing and data." />
    <div aria-live="polite"><ErrorNote error={error || account.error || subscription.error}/>{message && <p className="alert success">{message}</p>}</div>
    {account.isLoading ? <Spinner/> : user && <div className="account-layout">
      <Card><h2>Account</h2><nav className="settings-links" aria-label="Settings sections">
        <a href="#profile"><UserRound size={16}/> Profile and preferences</a>
        <a href="#security"><KeyRound size={16}/> Sign-in methods</a>
        <a href="#billing"><CreditCard size={16}/> Billing</a>
        <a href="#privacy"><ShieldCheck size={16}/> Data and privacy</a>
      </nav></Card>
      <div className="two-column">
      <div className="stack">
        <Card id="profile">
          <h2>Profile</h2>
          <p>{user.email}</p>
          <form className="form-stack" onSubmit={e => { e.preventDefault(); void run(async () => {
            await put('/users/me/account', { name });
            await reload('Display name updated.');
          }); }}>
            <label>Display name<input required maxLength={200} value={name} onChange={e => setName(e.target.value)}/></label>
            <button className="button primary" disabled={busy}>Save name</button>
          </form>
          <details className="form-disclosure top-gap"><summary>Edit career preferences</summary><CareerPreferences compact/></details>
        </Card>
        <Card id="security">
          <h2>Sign-in methods</h2>
          <p>{user.hasPassword ? 'Password enabled' : 'No password set'} · {user.hasGoogle ? 'Google linked' : 'Google unlinked'}</p>
          {user.hasPassword && <label>Current password<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)}/></label>}
          <label>New password<input type="password" autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)}/></label>
          <div className="btn-row">
            {user.hasPassword ? <button className="button primary" disabled={busy || !password || next.length < 12} onClick={() => void run(async () => {
              const result = await post<{message: string}>('/users/me/change-password', { currentPassword: password, newPassword: next });
              setPassword(''); setNext(''); setMessage(result.message);
            })}>Change password</button> : user.hasGoogle && <button className="button primary" disabled={busy || next.length < 12 || !googleClientId} onClick={() => setGoogleAction('setup')}>Verify with Google to set password</button>}
            {user.hasPassword && !user.hasGoogle && googleClientId && <button className="button muted" disabled={busy || !password} onClick={() => setGoogleAction('link')}>Link Google</button>}
          </div>
          <hr/>
          <h3>Change email address</h3>
          <form className="form-stack" onSubmit={e => { e.preventDefault(); if (user.hasPassword) void requestEmailChange(); else setGoogleAction('email'); }}>
            <label>New email address<input type="email" required value={email} onChange={e => setEmail(e.target.value)}/></label>
            <button className="button muted" disabled={busy || (user.hasPassword ? !password : !googleClientId)}>Request verification code</button>
          </form>
          {googleAction && googleClientId && <div className="form-stack">
            <GoogleIdentityButton clientId={googleClientId} text="continue_with" onSuccess={googleSuccess} onError={() => setError(new Error('Google verification failed.'))}/>
            <button className="button muted" onClick={() => setGoogleAction(null)}>Cancel Google verification</button>
          </div>}
          {pendingEmail && <form className="form-stack" onSubmit={e => { e.preventDefault(); void run(async () => {
            const result = await post<{message: string}>('/users/me/email-change/confirm', { email: pendingEmail, code });
            setPendingEmail(''); setEmail(''); setCode('');
            await reload(result.message);
          }); }}>
            <label>Code sent to {pendingEmail}<input inputMode="numeric" autoComplete="one-time-code" required pattern="[0-9]{6}" maxLength={6} value={code} onChange={e => setCode(e.target.value)}/></label>
            <button className="button primary" disabled={busy}>Verify new email</button>
          </form>}
          <button className="button muted top-gap" disabled={busy} onClick={() => void run(async () => {
            await post('/auth/logout', {});
            client.clear();
            window.location.assign('/login');
          })}>Sign out</button>
        </Card>
      </div>
      <div className="stack">
        <Card id="billing">
          <h2>Billing</h2>
          {subscription.isLoading && <Spinner/>}
          {plan && <>
            <Badge tone="purple">{plan.plan} · {plan.status}</Badge>
            {plan.periodEnd && <p>Billing period ends {new Date(plan.periodEnd).toLocaleDateString()}.</p>}
            <div className="btn-row">
              {plan.plan === 'FREE' && <button className="button primary" disabled={busy} onClick={() => void run(async () => {
                const {url} = await post<{url: string}>('/billing/checkout', {});
                window.location.assign(url);
              })}>Upgrade to Pro</button>}
              {plan.hasBillingCustomer && <button className="button muted" disabled={busy} onClick={() => void run(async () => {
                const {url} = await post<{url: string}>('/billing/portal', {});
                window.location.assign(url);
              })}>Manage billing</button>}
            </div>
          </>}
        </Card>
        <Card id="privacy">
          <h2>Data and privacy</h2>
          <p>Download your account data, original documents and generated files.</p>
          <button className="button muted" disabled={busy} onClick={() => void run(() => download('/privacy/data-export/archive', 'careerforge-data.zip'))}>Export my data</button>
          <hr/>
          <h3 className="danger-text">Delete account</h3>
          <p>Deletion signs you out and removes live records. File cleanup runs in the background. Cancel any active subscription first.</p>
          <label>Type DELETE MY ACCOUNT<input value={confirmation} onChange={e => setConfirmation(e.target.value)}/></label>
          <button className="button danger" disabled={busy || confirmation !== 'DELETE MY ACCOUNT'} onClick={() => void run(async () => {
            await del('/users/me', { confirmation });
            client.clear();
            window.location.assign('/');
          })}>Delete my account</button>
        </Card>
      </div>
      </div>
    </div>}
  </>;
}
