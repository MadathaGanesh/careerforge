import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, BriefcaseBusiness, CalendarCheck, Check, Plus, ScanText } from 'lucide-react';
import { api, patch, put } from '../lib/api';
import type { Account, DashboardSummary, InsightsSummary } from '../lib/api-types';
import { Badge, Card, Empty, ErrorNote, Hero, Spinner, Stat, statusLabel } from '../components/Shell';

function StageSummary({ stages }: {stages: InsightsSummary['applicationStages']}) {
  const maximum = Math.max(1, ...stages.map(item => item.count));
  return <Card><h2>Applications by stage</h2>
    {stages.length ? stages.map(item => <div className="bar-row" key={item.status}>
      <span>{statusLabel(item.status)}</span><div className="bar-track"><div className="bar-fill" style={{ width: `${item.count / maximum * 100}%` }}/></div><strong>{item.count}</strong>
    </div>) : <p>Track a saved job to see your application stages here.</p>}
  </Card>;
}

export function Dashboard() {
  const client = useQueryClient();
  const summary = useQuery({queryKey: ['dashboard'], queryFn: () => api<DashboardSummary>('/dashboard/summary')});
  const insights = useQuery({queryKey: ['insights'], queryFn: () => api<InsightsSummary>('/insights')});
  const profile = useQuery({queryKey: ['me'], queryFn: () => api<Account>('/users/me')});
  const [reminderError, setReminderError] = useState<unknown>(null);
  const [workingReminder, setWorkingReminder] = useState('');
  if (summary.isLoading) return <Spinner/>;
  if (summary.error) return <ErrorNote error={summary.error}/>;
  const data = summary.data;
  if (!data) return null;
  const firstName = profile.data?.fullName.trim().split(/\s+/)[0];
  const tailoring = data.recentTailoring;
  const recentFit = data.recentAnalyses.find(item => item.status === 'SUCCEEDED');
  const next = tailoring
    ? { title: tailoring.title || 'Your tailored resume', description: tailoring.companyName || 'Review the suggested edits before you export.', href: `/tailoring/${tailoring.id}`, label: 'Continue tailoring' }
    : recentFit
      ? { title: recentFit.title || 'Review your job fit', description: 'Your latest fit report is ready to review.', href: `/analyses/${recentFit.id}`, label: 'View fit analysis' }
      : data.resumes === 0
        ? { title: 'Prepare your first resume', description: 'Upload a resume and confirm it before running job fit analysis.', href: '/resumes#upload', label: 'Add a resume' }
        : { title: 'Save your next opportunity', description: 'Add a posting to compare it with your confirmed resume.', href: '/jobs/new', label: 'Add a job' };

  async function complete(id: string) {
    setWorkingReminder(id);
    setReminderError(null);
    try {
      await patch(`/reminders/${id}`, { completed: true });
      await client.invalidateQueries({ queryKey: ['dashboard'] });
    } catch (error) { setReminderError(error); }
    finally { setWorkingReminder(''); }
  }

  return <>
    <Hero title={firstName ? `Welcome back, ${firstName}` : 'Welcome back'} description={new Intl.DateTimeFormat(undefined, {weekday:'long', month:'long', day:'numeric'}).format(new Date())}
      action={<Link className="button primary" to="/jobs/new"><Plus size={17}/> Add a job</Link>}/>
    <section className="context-banner">
      <div><span className="eyebrow">Continue your search</span><h2>{next.title}</h2><p>{next.description}</p></div>
      <Link className="button lime" to={next.href}><ArrowRight size={16}/> {next.label}</Link>
    </section>
    <div className="stats-grid">
      <Stat icon={BriefcaseBusiness} title="Saved jobs" value={data.jobs}/>
      <Stat icon={ScanText} title="Tracked applications" value={data.applications}/>
      <Stat icon={CalendarCheck} title="Upcoming tasks shown" value={data.reminders.length}/>
    </div>
    {data.mode === 'LOCAL_HEURISTIC' && <p className="alert">AI demo mode uses limited keyword checks and templates.</p>}
    <div className="two-column main-side">
      <div className="stack">
        <Card><div className="section-heading"><h2>Upcoming follow-ups</h2><Link to="/applications">Job Tracker →</Link></div>
          <ErrorNote error={reminderError}/>
          {data.reminders.length === 0
            ? <Empty title="No upcoming reminders" description="Schedule a follow-up from one of your applications."/>
            : data.reminders.map(reminder => <div className="reminder" key={reminder.id}>
              <button type="button" aria-label="Complete reminder" disabled={!!workingReminder} onClick={() => void complete(reminder.id)}><Check size={16}/></button>
              <div><strong>{reminder.title}</strong><small>{new Date(reminder.dueAt).toLocaleString()}</small></div>
            </div>)}
        </Card>
        <Card><div className="section-heading"><h2>Recent applications</h2><Link to="/applications">View tracker →</Link></div>
          {data.recentApplications?.length ? data.recentApplications.map(item => <div key={item.id} className="list-entry form-row wrap">
            <div><Link to={`/applications?application=${item.id}`}>{item.title || 'Application'}</Link><p>{item.companyName}</p></div>
            <Badge tone="blue">{statusLabel(item.status)}</Badge>
          </div>) : <p>Add a saved job to Tracker to follow its progress here.</p>}
        </Card>
      </div>
      <div className="stack">
        <ErrorNote error={insights.error}/>
        {insights.data && <StageSummary stages={insights.data.applicationStages}/>}
        <Card><div className="section-heading"><h2>Recent Job Analyses</h2><Link to="/jobs">View jobs →</Link></div>
          {data.recentAnalyses.length ? data.recentAnalyses.slice(0,4).map(item => <div key={item.id} className="list-entry form-row wrap">
            <div><Link to={`/analyses/${item.id}`}>{item.title || 'Job analysis'}</Link><p>{item.companyName}</p></div>
            <Badge tone={item.status === 'SUCCEEDED' ? 'green' : 'orange'}>{item.score === null ? statusLabel(item.status) : `${item.score}% fit`}</Badge>
          </div>) : <p>Completed analyses will appear here.</p>}
        </Card>
      </div>
    </div>
  </>;
}

export function CareerPreferences({ compact = false }: {compact?: boolean}) {
  const client = useQueryClient();
  const profile = useQuery({queryKey: ['me'], queryFn: () => api<Account>('/users/me')});
  const [form, setForm] = useState({headline: '', location: '', yearsExperience: '0', skills: '', targetRoles: ''});
  const [notice, setNotice] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const saved = profile.data?.profile;
    if (saved) setForm({
      headline: saved.headline || '', location: saved.location || '', yearsExperience: String(saved.yearsExperience ?? 0),
      skills: (saved.skills || []).join(', '), targetRoles: (saved.targetRoles || []).join(', '),
    });
  }, [profile.data?.profile]);
  const update = (key: keyof typeof form, value: string) => setForm(previous => ({...previous, [key]:value}));
  if (profile.isLoading) return <Spinner/>;
  if (profile.error) return <ErrorNote error={profile.error}/>;
  return <form className={compact ? 'form-stack' : 'form-grid'} onSubmit={async event => {
    event.preventDefault();
    setBusy(true); setError(null); setNotice('');
    const list = (value: string) => value.split(',').map(item => item.trim()).filter(Boolean);
    try {
      await put('/users/me/profile', {...form, yearsExperience: Number(form.yearsExperience), skills: list(form.skills), targetRoles: list(form.targetRoles)});
      await client.invalidateQueries({queryKey: ['me']});
      setNotice('Profile saved.');
    } catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }}>
    <label>Current role<input value={form.headline} onChange={e => update('headline',e.target.value)} placeholder="Backend Engineer"/></label>
    <label>Years of experience<input type="number" min="0" max="80" step="0.5" value={form.yearsExperience} onChange={e => update('yearsExperience',e.target.value)}/></label>
    <label>Location<input value={form.location} onChange={e => update('location',e.target.value)} placeholder="Bengaluru, India"/></label>
    <label>Target roles (comma-separated)<input value={form.targetRoles} onChange={e => update('targetRoles',e.target.value)}/></label>
    <label className="wide">Skills (comma-separated)<input value={form.skills} onChange={e => update('skills',e.target.value)}/></label>
    <div className="wide"><ErrorNote error={error}/>{notice && <p className="alert success" role="status">{notice}</p>}
      <button className="button primary" disabled={busy}>Save career profile</button>
    </div>
  </form>;
}

export function Onboarding() {
  return <><Hero title="Build your Career Profile" description="Keep your career preferences up to date." action={<Link className="button muted" to="/settings">Back to settings</Link>}/><Card><CareerPreferences/></Card></>;
}

export function Insights() {
  const result = useQuery({queryKey: ['insights'], queryFn: () => api<InsightsSummary>('/insights')});
  if (result.isLoading) return <Spinner/>;
  if (result.error || !result.data) return <ErrorNote error={result.error}/>;
  return <><Hero title="Career Insights" description="Review application stages and recent fit assessments." action={<Link className="button muted" to="/dashboard">Back to dashboard</Link>}/>
    <div className="two-column"><StageSummary stages={result.data.applicationStages}/>
      <Card><h2>Recent fit assessments</h2>
        {result.data.analysisScores.map(item => <div key={item.id} className="bar-row">
          <span>{new Date(item.createdAt).toLocaleDateString()}</span><div className="bar-track"><div className="bar-fill" style={{width:`${item.score ?? 0}%`}}/></div><strong>{item.score ?? '—'}%</strong>
        </div>)}
      </Card>
    </div><p className="fineprint">{result.data.disclaimer}</p></>;
}
