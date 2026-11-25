import { ResumeLinks } from '../components/WorkspaceLinks';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, del, patch, post, put } from '../lib/api';
import { Badge, Card, ErrorNote, Hero, Spinner } from '../components/Shell';

interface Fact { id: string; description: string; verifiedByUser: boolean }

export function MemoryPage() {
  const facts = useQuery({queryKey: ['memory'], queryFn: () => api<Fact[]>('/career-facts')});
  const account = useQuery({queryKey: ['me'], queryFn: () => api<{ profile: {preferences: { useCareerMemory?: boolean }} }>('/users/me')});
  const client = useQueryClient();
  const [editing, setEditing] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [verified, setVerified] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  function reset() {
    setEditing(null);
    setDescription('');
    setVerified(false);
  }

  async function run(action: () => Promise<unknown>, key: 'memory' | 'me') {
    setError(null);
    setBusy(true);
    try {
      await action();
      await client.invalidateQueries({queryKey: [key]});
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <Hero title="Career facts" description="Save achievements you can verify and choose whether to use them as AI context."/>
    <ResumeLinks/>
    <ErrorNote error={error || facts.error || account.error}/>
    <div className="two-column">
      <Card>
        <h2>{editing ? 'Edit career fact' : 'Add a career fact'}</h2>
        <form className="form-stack" onSubmit={e => { e.preventDefault(); void run(async () => {
          if (editing) await patch(`/career-facts/${editing}`, {description, verifiedByUser: verified});
          else await post('/career-facts', {description, verifiedByUser: verified, factType: 'ACHIEVEMENT'});
          reset();
        }, 'memory'); }}>
          <label>Achievement or responsibility<textarea rows={6} required maxLength={2000} value={description} onChange={e => setDescription(e.target.value)}/></label>
          <label className="inline-check"><input type="checkbox" checked={verified} onChange={e => setVerified(e.target.checked)}/> I confirm this statement is accurate</label>
          <div className="btn-row">
            <button className="button primary" disabled={busy || !description.trim()}>Save career fact</button>
            {editing && <button type="button" className="button muted" onClick={reset}>Cancel</button>}
          </div>
        </form>
      </Card>
      <Card>
        <h2>Saved facts</h2>
        <label className="inline-check"><input type="checkbox" checked={account.data?.profile.preferences.useCareerMemory === true}
          disabled={busy || !account.data} onChange={e => {
            const enabled = e.target.checked;
            void run(() => put('/users/me/career-memory-preference', {enabled}), 'me');
          }}/> Allow confirmed facts to be sent to the configured AI provider for tailoring and interview practice</label>
        {facts.isLoading && <Spinner/>}
        {facts.data?.length === 0 && <p>No saved facts yet.</p>}
        {facts.data?.map(f => <div className="list-entry" key={f.id}>
          <Badge tone={f.verifiedByUser ? 'green' : 'orange'}>{f.verifiedByUser ? 'Confirmed' : 'Unconfirmed'}</Badge>
          <p>{f.description}</p>
          <div className="btn-row">
            <button className="button muted small" disabled={busy} onClick={() => { setEditing(f.id); setDescription(f.description); setVerified(f.verifiedByUser); }}>Edit</button>
            <button className="button danger small" disabled={busy} onClick={() => {
              void run(async () => { await del(`/career-facts/${f.id}`); if (editing === f.id) reset(); }, 'memory');
            }}>Delete fact</button>
          </div>
        </div>)}
      </Card>
    </div>
  </>;
}
