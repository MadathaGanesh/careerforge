import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Mail, Plus, Wallet } from 'lucide-react';
import { api, patch, post } from '../lib/api';
import type { Application, JobDetails } from '../lib/api-types';
import { useCursorPage } from '../lib/useCursorPage';
import { Badge, Card, Empty, ErrorNote, Hero, Spinner } from '../components/Shell';
import { PageControls } from '../components/PageControls';
import { jobOption, RecordPicker, type RecordOption } from '../components/RecordPicker';

export { Interviews } from './Interviews';
export { Salaries } from './SalaryResearch';

const columns = ['SAVED', 'READY_TO_APPLY', 'APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'CLOSED'];
const stageLabel = (stage: string) => stage.replaceAll('_', ' ');

export function Applications() {
  const [params] = useSearchParams();
  const jobId = params.get('job') || '';
  const applicationId = params.get('application') || '';
  return <ApplicationBoard key={jobId + ':' + applicationId} jobId={jobId} applicationId={applicationId}/>;
}

function ApplicationBoard({ jobId, applicationId }: {jobId: string; applicationId: string}) {
  const client = useQueryClient();
  const [filter, setFilter] = useState('');
  const search = filter.trim();
  const tracker = useCursorPage<Application>('applications', '/applications/page' + (search ? '?q=' + encodeURIComponent(search) : ''));
  const target = useQuery({
    queryKey: ['job', jobId], queryFn: () => api<JobDetails>(`/jobs/${encodeURIComponent(jobId)}`), enabled: !!jobId,
  });
  const selectedRecord = useQuery({
    queryKey: ['application', applicationId],
    queryFn: () => api<Application>(`/applications/${encodeURIComponent(applicationId)}`), enabled: !!applicationId,
  });
  const [pickedJob, setPickedJob] = useState<RecordOption | null>();
  const newJob = pickedJob === undefined && target.data ? jobOption(target.data) : pickedJob ?? null;
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [movedApplication, setMovedApplication] = useState<Application | null>(null);
  const [busy, setBusy] = useState(false);
  const [reminderTitle, setReminderTitle] = useState('');
  const [reminderDate, setReminderDate] = useState('');
  const [selected, setSelected] = useState<Application | null>(null);
  const [notes, setNotes] = useState('');
  const [salary, setSalary] = useState('');
  const searchField = useRef<HTMLInputElement>(null);
  const detailsHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (selectedRecord.data) {
      setSelected(selectedRecord.data);
      setNotes(selectedRecord.data.notes);
      setSalary(selectedRecord.data.salaryOffered);
    }
  }, [selectedRecord.data]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setMessage('');
    setMovedApplication(null);
    try {
      await action();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  async function refreshTracker() {
    tracker.reset();
    await Promise.all([
      client.invalidateQueries({ queryKey: ['applications'] }),
      client.invalidateQueries({ queryKey: ['dashboard'] }),
      client.invalidateQueries({ queryKey: ['offers'] }),
      client.invalidateQueries({ queryKey: ['insights'] }),
    ]);
  }

  function showDetails(item: Application) {
    if (selected?.id !== item.id) {
      setSelected(item);
      setNotes(item.notes);
      setSalary(item.salaryOffered);
    }
    detailsHeading.current?.focus();
  }

  function move(item: Application, status: string) {
    if (busy || item.status === status) return;
    void run(async () => {
      await patch(`/applications/${item.id}`, { status });
      setSelected(previous => previous?.id === item.id ? {...previous, status} : previous);
      await refreshTracker();
      setMovedApplication({...item, status});
      setMessage(`Application moved to ${stageLabel(status).toLowerCase()}.`);
    });
  }

  function addApplication(event: FormEvent) {
    event.preventDefault();
    if (!newJob || busy) return;
    void run(async () => {
      await post('/applications', { jobId: newJob.id });
      setPickedJob(null);
      await refreshTracker();
      setMessage('Application added.');
    });
  }

  function createReminder(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await post('/reminders', {
        title: reminderTitle,
        dueAt: new Date(reminderDate).toISOString(),
        applicationId: selected?.id ?? null,
      });
      setReminderTitle('');
      setReminderDate('');
      await client.invalidateQueries({ queryKey: ['dashboard'] });
      setMessage('Reminder saved.');
    });
  }

  return <>
    <Hero title="Job Tracker" description="Keep the application stage and your next follow-up together."
      action={<Link className="button muted" to="/salaries"><Wallet size={17}/> Compensation and offers</Link>}/>
    <Card>
      <form className="form-row wrap" onSubmit={addApplication}>
        <div className="grow"><RecordPicker resource="jobs" label="Add a saved job" value={newJob} disabled={busy}
          onChange={setPickedJob} emptyAction={<Link to="/jobs/new">Save a job before adding an application</Link>}/></div>
        <button className="button primary align-bottom" disabled={!newJob || busy}><Plus size={17}/> Add application</button>
      </form>
      <ErrorNote error={(!pickedJob && target.error) || selectedRecord.error}/>
    </Card>
    <div aria-live="polite">
      <ErrorNote error={error}/>
      {message && <div className="alert success form-row wrap">
        <span>{message}</span>
        {movedApplication && <button type="button" className="button small" onClick={() => showDetails(movedApplication)}>View application</button>}
      </div>}
    </div>
    <div className="two-column main-side">
    <div className="stack">
    <Card>
      <label>Search applications<input ref={searchField} type="search" value={filter} maxLength={120}
        onChange={event => {
          if (event.target.value.trim() !== search) tracker.reset();
          setFilter(event.target.value);
        }} placeholder="Company or role"/></label>
      <PageControls pages={tracker} records="applications" focusTarget={searchField} disabled={busy}/>
    </Card>
    {tracker.isLoading ? <Spinner/> : tracker.error ? <ErrorNote error={tracker.error}/> : tracker.items.length === 0 ?
      (search ? <Empty title="No applications match your search" description="Try a different company or role."/>
        : <Empty title="No applications yet" description="Add an application from a saved job."/>) : <>
        {(tracker.canGoBack || tracker.hasNext) && <p className="fineprint">Stage counts show applications on this page.</p>}
        <div className="kanban">
          {columns.map(status => {
            const items = tracker.items.filter(item => item.status === status);
            return <div className="kanban-col" key={status} onDragOver={event => event.preventDefault()} onDrop={event => {
              event.preventDefault();
              const id = event.dataTransfer.getData('text/plain');
              const application = tracker.items.find(item => item.id === id);
              if (application) move(application, status);
            }}>
              <div className="kanban-head"><b>{stageLabel(status)}</b><Badge>{items.length}</Badge></div>
              {items.map(item => <article className="kanban-card" draggable={!busy}
                onDragStart={event => event.dataTransfer.setData('text/plain', item.id)} key={item.id}>
                <b>{item.companyName || 'Unknown company'}</b>
                <p>{item.title || 'Role'}</p>
                <small>{item.location || 'Location not provided'}</small>
                <select aria-label={`Change status for ${item.title || 'application'}`} value={item.status}
                  disabled={busy} onChange={event => move(item, event.target.value)}>
                  {columns.map(stage => <option key={stage} value={stage}>{stageLabel(stage)}</option>)}
                </select>
                <button className="text-button" onClick={() => showDetails(item)}>Notes and salary →</button>
              </article>)}
            </div>;
          })}
        </div>
      </>}
      <Card>
        <h2>Create a follow-up reminder</h2>
        <form className="form-stack" onSubmit={createReminder}>
          {selected && <p className="fineprint">For {selected.title || 'the selected application'}.</p>}
          <label>Reminder<input required maxLength={250} value={reminderTitle} onChange={event => setReminderTitle(event.target.value)} placeholder="Follow up after interview"/></label>
          <label>When<input required type="datetime-local" value={reminderDate} onChange={event => setReminderDate(event.target.value)}/></label>
          <button className="button primary" disabled={busy}>Save reminder</button>
        </form>
      </Card>
    </div>
    <Card id="application-detail" className="stack">
        <h2 ref={detailsHeading} tabIndex={-1}>Application details</h2>
        {selected ? <form className="form-stack" onSubmit={event => {
          event.preventDefault();
          void run(async () => {
            await patch(`/applications/${selected.id}`, { notes, salaryOffered: salary });
            setSelected(null);
            await refreshTracker();
            setMessage('Application details saved.');
          });
        }}>
          <strong>{selected.companyName} — {selected.title}</strong>
          <label>Application stage<select value={selected.status} disabled={busy} onChange={event => move(selected, event.target.value)}>
            {columns.map(stage => <option value={stage} key={stage}>{stageLabel(stage)}</option>)}
          </select></label>
          <label>Notes<textarea rows={4} maxLength={12000} value={notes} onChange={event => setNotes(event.target.value)}/></label>
          <label>Offer details / salary<input maxLength={1000} value={salary} onChange={event => setSalary(event.target.value)} placeholder="e.g. INR 18L fixed + 2L variable"/></label>
          <div className="btn-row">
            <button className="button primary" disabled={busy}>Save details</button>
            <button type="button" className="button muted" onClick={() => setSelected(null)}>Close details</button>
          </div>
        </form> : selectedRecord.isLoading && applicationId ? <Spinner/> : <Empty title="Select an application" description="Open a card to edit its notes and compensation details."/>}
        {selected && <>
          <div className="btn-row"><Link to={`/salaries?application=${selected.id}`} className="button muted small"><Wallet size={15}/> Compare compensation</Link>
            <Link to={`/interviews?job=${selected.jobId}`} className="button muted small">Practice interview</Link></div>
          <OutreachDraft key={selected.id} job={selected}/>
        </>}
      </Card>
    </div>
  </>;
}

function OutreachDraft({ job }: {job: Application}) {
  const [subject, setSubject] = useState(`Following up on the ${job.title || 'open'} role`);
  const [body, setBody] = useState(`Hello,\n\nI’m following up on my application for ${job.title || 'the position'}${job.companyName ? ' at ' + job.companyName : ''}. I’d be glad to share more information about my experience.\n\nThank you.`);
  const [result, setResult] = useState('');
  return <details className="form-disclosure"><summary><Mail size={15}/> Draft outreach</summary>
    <div className="form-stack">
      <label>Email subject<input value={subject} onChange={event => setSubject(event.target.value)}/></label>
      <label>Email draft<textarea rows={6} value={body} onChange={event => setBody(event.target.value)}/></label>
      <button type="button" className="button muted" onClick={async () => {
        try { await navigator.clipboard.writeText(`Subject: ${subject}\n\n${body}`); setResult('Draft copied.'); }
        catch { setResult('Clipboard unavailable. Select and copy the draft.'); }
      }}>Copy draft</button>
      {result && <p role="status">{result}</p>}
    </div>
  </details>;
}

