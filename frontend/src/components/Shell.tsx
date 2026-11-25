import { useEffect, useRef, useState, type ReactNode, type ElementType } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Bell, BriefcaseBusiness, ChevronDown, Columns3, Files, Layers3, LayoutDashboard, LogOut, MessagesSquare, Search, Settings, Target, X } from 'lucide-react';
import { api, post } from '../lib/api';
import type { SessionUser } from '../lib/api-types';
import { internalDestination } from '../lib/session';
import { useCursorPage } from '../lib/useCursorPage';
import './career-topbar.css';

const destinations = [
  ['/dashboard', 'Dashboard', LayoutDashboard],
  ['/resumes', 'My Resumes', Files],
  ['/jobs', 'Jobs', BriefcaseBusiness],
  ['/applications', 'Job Tracker', Columns3],
  ['/interviews', 'Interview Lab', MessagesSquare],
] as const;

function destinationFor(path: string) {
  if (/^\/(analyses|tailoring)\//.test(path)) return '/jobs';
  if (/^\/(career-memory|tailoring)(\/|$)/.test(path)) return '/resumes';
  if (path === '/salaries') return '/applications';
  if (path === '/insights') return '/dashboard';
  return destinations.find(([url]) => path === url || path.startsWith(url + '/'))?.[0];
}

export function Logo() {
  return <Link to="/" className="brand"><span className="brand-mark"><Layers3 size={21} aria-hidden="true"/></span>CareerForge</Link>;
}

interface SearchResults {
  jobs: {id: string; title: string; companyName: string; location: string}[];
  applications: {id: string; title: string; companyName: string; status: string}[];
}

function GlobalSearch() {
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [term, setTerm] = useState('');
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setTerm(input.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [input]);
  const results = useQuery({
    queryKey: ['global-search', term],
    queryFn: () => api<SearchResults>('/search?q=' + encodeURIComponent(term)),
    enabled: open && term.length >= 2,
  });
  const fullResults = '/search?q=' + encodeURIComponent(input.trim());

  return <div className="cf-search-wrap" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }} onKeyDown={event => {
    if (event.key === 'Escape') setOpen(false);
  }}>
    <Link to="/search" className="icon-button mobile-search" aria-label="Search workspace"><Search size={18}/></Link>
    <form className="cf-search-form" role="search" onSubmit={event => {
      event.preventDefault();
      if (input.trim().length < 2) return;
      setOpen(false);
      navigate(fullResults);
    }}>
      <Search size={17} aria-hidden="true"/>
      <input type="search" aria-label="Search saved companies and job titles" placeholder="Search jobs and applications" value={input}
        onFocus={() => setOpen(true)} onChange={event => { setInput(event.target.value); setOpen(true); }}/>
      <button type="submit" disabled={input.trim().length < 2}>Search</button>
    </form>
    {open && term.length >= 2 && input.trim().length >= 2 && <div className="cf-floating cf-search-results">
      {results.isFetching && <Spinner/>}
      <ErrorNote error={results.error}/>
      {results.data && !results.data.jobs.length && !results.data.applications.length && <p>No saved jobs or applications found.</p>}
      {results.data?.jobs.map(job => <Link key={'job-'+job.id} to={'/jobs/'+job.id} onClick={() => setOpen(false)}>
        <strong>{job.title || 'Saved job'}</strong><small>{[job.companyName, job.location].filter(Boolean).join(' · ')}</small>
      </Link>)}
      {results.data?.applications.map(item => <Link key={'application-'+item.id} to={'/applications?application='+item.id} onClick={() => setOpen(false)}>
        <strong>{item.title || 'Tracked application'}</strong><small>{item.companyName} · Application: {statusLabel(item.status)}</small>
      </Link>)}
      <Link to={fullResults} onClick={() => setOpen(false)} className="cf-see-all">View full results <ArrowRight size={14}/></Link>
    </div>}
  </div>;
}

const notificationCount = {
  queryKey: ['notification-count'],
  queryFn: () => api<{count: number}>('/notifications/unread-count'),
  refetchInterval: 30_000,
};

interface Notice {
  id: string;
  title: string;
  body: string;
  targetUrl: string;
  isRead: boolean;
  createdAt: string;
}

function NotificationList({ inbox = false, onOpen }: {inbox?: boolean; onOpen?: () => void}) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const count = useQuery(notificationCount);
  const history = useCursorPage<Notice>('notifications', '/notifications/page' + (unreadOnly ? '?unreadOnly=true' : ''));

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await action(); }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }

  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: notificationCount.queryKey }),
      client.invalidateQueries({ queryKey: ['notifications'] }),
    ]);
  }

  return <>
    <div className="form-row wrap">
      {inbox && <div className="view-tabs" aria-label="Notification filter">
        {[false, true].map(filter => <button key={String(filter)} type="button" aria-pressed={unreadOnly === filter}
          onClick={() => { history.reset(); setUnreadOnly(filter); }}>{filter ? 'Unread' : 'All'}</button>)}
      </div>}
      {(count.data?.count ?? 0) > 0 && <button className="button muted small cf-read-all" disabled={busy} onClick={() => void run(async () => {
        await post('/notifications/read-all', {});
        await refresh();
      })}>Mark all as read</button>}
    </div>
    <ErrorNote error={error || history.error || count.error}/>
    {history.isLoading && <Spinner/>}
    {!history.isLoading && !history.error && history.items.length === 0 && <p className="fineprint">
      {unreadOnly ? 'No unread notifications.' : 'No notifications yet.'}
    </p>}
    <div className="cf-notification-list">
      {history.items.map(notice => <button type="button" key={notice.id} className={'cf-notice' + (notice.isRead ? '' : ' unread')}
        disabled={busy} onClick={() => void run(async () => {
          if (!notice.isRead) {
            await post('/notifications/' + notice.id + '/read', {});
            await refresh();
          }
          onOpen?.();
          navigate(internalDestination(notice.targetUrl));
        })}>
        <span><strong>{notice.title}</strong><span>{notice.body}</span><small>{new Date(notice.createdAt).toLocaleString()}</small></span>
        {!notice.isRead && <Badge tone="green">Unread</Badge>}
      </button>)}
    </div>
    <div className="btn-row">
      <button className="button muted small" disabled={!history.canGoBack || history.isFetching} onClick={history.previous}>Newer</button>
      <button className="button muted small" disabled={!history.hasNext || history.isFetching} onClick={history.next}>Older</button>
    </div>
  </>;
}

function NotificationBell() {
  const { pathname } = useLocation();
  const count = useQuery(notificationCount);
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [pathname]);
  const unread = count.data?.count ?? 0;
  return <div className="cf-menu-wrap" onKeyDown={event => { if (event.key === 'Escape') setOpen(false); }}>
    <button className="icon-button cf-bell" type="button" aria-label={`${unread} unread notifications`} aria-expanded={open} onClick={() => setOpen(value => !value)}>
      <Bell size={18}/>{unread > 0 && <span className="cf-notice-count" aria-hidden="true">{Math.min(unread, 99)}</span>}
    </button>
    {open && <div className="cf-floating cf-notification-panel">
      <div className="cf-panel-head"><strong>Notifications</strong><button className="icon-button" type="button" onClick={() => setOpen(false)} aria-label="Close notifications"><X size={17}/></button></div>
      <NotificationList onOpen={() => setOpen(false)}/>
      <Link className="text-button cf-see-all" to="/notifications" onClick={() => setOpen(false)}>Open inbox <ArrowRight size={14}/></Link>
    </div>}
  </div>;
}

export function Notifications() {
  return <>
    <Hero title="Notifications" description="Reminders and updates about your CareerForge workspace."/>
    <div className="two-column main-side">
      <Card><h2>Inbox</h2><NotificationList inbox/></Card>
      <Card><h2>Follow-ups</h2><p>Find upcoming reminders alongside your tracked applications.</p><Link to="/applications">Open Job Tracker</Link></Card>
    </div>
  </>;
}

function AccountMenu({ user }: {user: SessionUser}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function signOut() {
    setBusy(true);
    setError(null);
    try {
      await post('/auth/logout', {});
      dialog.current?.close();
      client.clear();
      navigate('/login', { replace: true });
    } catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }

  return <div className="cf-menu-wrap" onKeyDown={event => { if (event.key === 'Escape') setOpen(false); }}>
    <button ref={trigger} className="cf-profile-button" type="button" aria-label="Account options" aria-expanded={open} onClick={() => setOpen(value => !value)}>
      <span className="avatar" aria-hidden="true">{user.fullName.trim().split(/\s+/).slice(0, 2).map(part => part[0]).join('').toUpperCase() || 'CF'}</span>
      <span className="top-user">{user.fullName}</span><ChevronDown size={15} className="top-user"/>
    </button>
    {open && <div className="cf-floating cf-account-dropdown">
      <Link to="/settings" onClick={() => setOpen(false)}><Settings size={17}/> Settings</Link>
      <button type="button" onClick={() => {
        setOpen(false);
        setError(null);
        dialog.current?.showModal();
        cancel.current?.focus();
      }}><LogOut size={17}/> Log Out</button>
    </div>}
    <dialog ref={dialog} className="cf-dialog" role="alertdialog" aria-labelledby="logout-title" aria-describedby="logout-description"
      onCancel={event => { if (busy) event.preventDefault(); }} onClose={() => trigger.current?.focus()}
      onKeyDown={event => {
        if (event.key !== 'Tab' || busy) return;
        if (event.shiftKey && document.activeElement === cancel.current) {
          event.preventDefault();
          dialog.current?.querySelector<HTMLButtonElement>('[data-confirm]')?.focus();
        } else if (!event.shiftKey && document.activeElement?.hasAttribute('data-confirm')) {
          event.preventDefault();
          cancel.current?.focus();
        }
      }}>
      <h2 id="logout-title">Are you sure you want to log out?</h2>
      <p id="logout-description">You can sign in again to return to your saved work.</p>
      <ErrorNote error={error}/>
      <div className="cf-dialog-actions">
        <button ref={cancel} type="button" className="button muted" disabled={busy} onClick={() => dialog.current?.close()}>No</button>
        <button data-confirm type="button" className="button primary" disabled={busy} onClick={() => void signOut()}>Yes, Log Out</button>
      </div>
    </dialog>
  </div>;
}

export function Shell({ children, user }: {children: ReactNode; user: SessionUser}) {
  const { pathname } = useLocation();
  const active = destinationFor(pathname);
  return <div className="app-shell">
    <aside className="sidebar"><Logo/>
      <nav aria-label="Primary navigation">{destinations.map(([path, label, Icon]) =>
        <Link key={path} to={path} className={'nav-link' + (active === path ? ' selected' : '')}
          aria-current={active === path ? 'page' : undefined}><Icon size={18} aria-hidden="true"/><span>{label}</span></Link>)}
      </nav>
    </aside>
    <header className="topbar"><div className="mobile-brand"><Logo/></div><GlobalSearch/>
      <div className="top-right"><NotificationBell/><AccountMenu user={user}/></div>
    </header>
    <main className="page"><div className="page-content">{children}</div></main>
  </div>;
}

export function Hero({ title, description, action }: {title: string; description: string; action?: ReactNode}) {
  return <header className="hero"><div><h1>{title}</h1><p>{description}</p></div>
    {action && <div className="hero-actions">{action}</div>}</header>;
}

export function Empty({ title, description, action }: {title: string; description: string; action?: ReactNode}) {
  return <div className="empty"><Target size={30} aria-hidden="true"/><h3>{title}</h3><p>{description}</p>{action}</div>;
}

export function Badge({ children, tone = 'purple' }: {children: ReactNode; tone?: 'purple' | 'green' | 'orange' | 'red' | 'blue'}) {
  return <span className={'badge ' + tone}>{children}</span>;
}

export function Card({ children, className = '', id }: {children: ReactNode; className?: string; id?: string}) {
  return <section id={id} className={'card ' + className}>{children}</section>;
}

export function ErrorNote({ error }: {error: unknown}) {
  return error ? <div className="alert error" role="alert">{error instanceof Error ? error.message : String(error)}</div> : null;
}

export function Spinner() {
  return <div className="loading" role="status">Loading…</div>;
}

export function FitGauge({ score }: {score: number | null | undefined}) {
  const value = Math.min(100, Math.max(0, Number(score ?? 0)));
  return <div className="fit-circle" style={{ background: `conic-gradient(var(--accent) ${value}%, var(--mint) 0)` }}>
    <div><strong>{score == null ? '—' : Math.round(value) + '%'}</strong><small>Estimated fit</small></div>
  </div>;
}

export function Stat({ icon: Icon, title, value }: {icon: ElementType; title: string; value: number | string}) {
  return <Card className="stat"><span className="stat-icon"><Icon size={18} aria-hidden="true"/></span><div><strong>{value}</strong><small>{title}</small></div></Card>;
}

export function statusLabel(status: string) {
  const text = status.toLowerCase().replaceAll('_', ' ');
  return text[0]?.toUpperCase() + text.slice(1);
}
