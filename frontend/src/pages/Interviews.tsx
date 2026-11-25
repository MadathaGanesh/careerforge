import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiFailure, post } from '../lib/api';
import type { JobSummary } from '../lib/api-types';
import { jobOption, RecordPicker, type RecordOption } from '../components/RecordPicker';
import { Badge, Card, Empty, ErrorNote, Hero, Spinner } from '../components/Shell';

interface Question { id: string; category: string; question: string; suggestedAnswer: string }
interface Feedback { score: number | null; strengths: string[]; improvements: string[]; suggestedAnswer?: string }
interface Practice {
  id: string;
  jobId: string;
  companyName?: string;
  title?: string;
  status: 'IN_PROGRESS' | 'COMPLETED';
  overallScore?: number | null;
}
interface Answer { id: string; questionId: string; question: string; answer: string; feedbackJson: Feedback }
interface SpeechResult { results: [{ 0: { transcript: string } }] }
interface SpeechSession { lang: string; start(): void; abort(): void; onresult: (e: SpeechResult) => void; onerror: () => void }

export function Interviews() {
  const [params] = useSearchParams();
  const initialJobId = params.get('job') || '';
  return <InterviewWorkspace key={initialJobId} initialJobId={initialJobId}/>;
}

function InterviewWorkspace({ initialJobId }: {initialJobId: string}) {
  const client = useQueryClient();
  const initialJob = useQuery({
    queryKey: ['job', initialJobId], queryFn: () => api<JobSummary>(`/jobs/${encodeURIComponent(initialJobId)}`), enabled: !!initialJobId,
  });
  const sessions = useQuery({queryKey: ['interview-sessions'], queryFn: () => api<Practice[]>('/interview-sessions')});
  const [job, setJob] = useState<RecordOption | null>(null);
  const [resume, setResume] = useState<RecordOption | null>(null);
  const [questions, setQuestions] = useState<Question[]>([]);
  const [bankEmpty, setBankEmpty] = useState(false);
  const [selected, setSelected] = useState<Question | null>(null);
  const [answer, setAnswer] = useState('');
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [session, setSession] = useState('');
  const [history, setHistory] = useState<(Practice & {answers: Answer[]}) | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const dictation = useRef<SpeechSession | null>(null);
  const practice = useRef<HTMLElement>(null);
  const win = window as Window & {SpeechRecognition?: new () => SpeechSession; webkitSpeechRecognition?: new () => SpeechSession};
  const speech = win.SpeechRecognition || win.webkitSpeechRecognition;
  const resumeId = resume?.id;
  const versions = useQuery({
    queryKey: ['versions', resumeId],
    queryFn: () => api<{id: string; confirmedAt: string | null}[]>(`/resumes/${resumeId}/versions`),
    enabled: !!resumeId,
  });
  const confirmed = versions.data?.find(v => v.confirmedAt);
  const selectedJob = job ?? (initialJob.data ? jobOption(initialJob.data) : null);
  const target = selectedJob?.id;
  const limitReached = error instanceof ApiFailure && error.code === 'PLAN_LIMIT';

  useEffect(() => () => dictation.current?.abort(), []);
  useEffect(() => { if (questions.length) practice.current?.focus(); }, [questions]);

  function select(question: Question | null, saved?: Answer) {
    dictation.current?.abort();
    setSelected(question);
    setAnswer(saved?.answer ?? '');
    setFeedback(saved?.feedbackJson ?? null);
  }

  function switchJob(option: RecordOption | null) {
    if (option?.id === target) return;
    setJob(option);
    setQuestions([]);
    setBankEmpty(false);
    setSession('');
    setHistory(null);
    setError(null);
    select(null);
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  function showBank(items: Question[], saved: Answer[] = []) {
    setQuestions(items);
    setBankEmpty(items.length === 0);
    select(items[0] ?? null, saved.find(a => a.questionId === items[0]?.id));
  }

  function openHistory(id: string, resume: boolean) {
    return run(async () => {
      const record = await api<Practice & {answers: Answer[]}>(`/interview-sessions/${id}`);
      setHistory(record);
      if (resume) {
        const bank = await api<Question[]>(`/interview-questions?jobId=${record.jobId}`);
        const entry = sessions.data?.find(item => item.id === id);
        setJob(jobOption({id: record.jobId, title: entry?.title ?? record.title ?? 'Saved practice job',
          companyName: entry?.companyName ?? record.companyName ?? null}));
        setSession(record.id);
        showBank(bank, record.answers);
      }
    });
  }

  function dictate() {
    if (!speech) return;
    dictation.current?.abort();
    const recognition = new speech();
    dictation.current = recognition;
    recognition.lang = 'en-IN';
    recognition.onresult = e => setAnswer(text => (text + ' ' + e.results[0][0].transcript).trim());
    recognition.onerror = () => setError(new Error('Microphone transcription was interrupted.'));
    try { recognition.start(); } catch (err) { setError(err); }
  }

  return <>
    <Hero title="Interview Lab" description="Practice questions for a saved job and review your answers."/>
    {limitReached && <p className="alert warning" role="alert">You’ve reached your monthly limit for generating interview questions. You can still open saved questions and practice sessions. <Link to="/settings">Review your plan</Link></p>}
    <ErrorNote error={(!limitReached && error) || (!job && initialJob.error) || versions.error || sessions.error}/>
    <div className="two-column">
      <Card>
        <h2>Prepare for a job</h2>
        <div className="form-stack top-gap">
          <RecordPicker resource="jobs" label="Target job" defaultToFirst={!initialJobId} value={selectedJob}
            disabled={busy} onChange={switchJob} emptyAction={<Link to="/jobs/new">Add a target job</Link>}/>
          {target && <>
            <RecordPicker resource="resumes" label="Resume" value={resume} disabled={busy}
              onChange={setResume} clearLabel="No resume personalization"/>
            {resumeId && versions.isPending && <Spinner/>}
            {resumeId && versions.isSuccess && !confirmed && <Link to={`/resumes/${resumeId}/review`}>Review and confirm this resume first</Link>}
            <div className="btn-row">
              <button className="button primary" disabled={!target || busy || (!!resumeId && !confirmed)} onClick={() => void run(async () => {
                const result = await post<{questions: Question[]}>('/interview-questions/generate', {jobId: target, resumeVersionId: confirmed?.id});
                showBank(result.questions);
              })}>Generate interview questions</button>
              <button className="button muted" disabled={!target || busy} onClick={() => void run(async () => {
                showBank(await api<Question[]>(`/interview-questions?jobId=${target}`));
              })}>Open saved questions</button>
            </div>
            {bankEmpty && <p role="status">No saved questions for this job yet.</p>}
          </>}
        </div>
      </Card>
      <Card>
        <h2>Saved practice sessions</h2>
        {sessions.isLoading && <Spinner/>}
        {sessions.data?.length === 0 && <p>No practice sessions yet.</p>}
        {sessions.data?.map(s => <div className="list-entry" key={s.id}>
          <Badge tone={s.status === 'COMPLETED' ? 'green' : 'blue'}>{s.status}</Badge>
          <p>{s.companyName} — {s.title} · {s.overallScore == null ? 'Not assessed' : s.overallScore + '%'}</p>
          <div className="btn-row">
            <button className="button muted small" disabled={busy} onClick={() => void openHistory(s.id, false)}>View answers</button>
            {s.status === 'IN_PROGRESS' && <button className="button muted small" disabled={busy} onClick={() => void openHistory(s.id, true)}>Resume practice</button>}
          </div>
        </div>)}
      </Card>
    </div>
    {history && <Card className="top-gap">
      <h2>Previous answers</h2>
      {history.answers.length === 0 && <p>No saved answers in this session.</p>}
      {history.answers.map(row => <div className="list-entry" key={row.id}>
        <h3>{row.question}</h3><p>{row.answer}</p><FeedbackPanel feedback={row.feedbackJson}/>
      </div>)}
    </Card>}
    {questions.length > 0 && <section className="stack practice-panel" ref={practice} tabIndex={-1} aria-labelledby="practice-title">
    <h2 id="practice-title">Interview practice</h2>
    <div className="two-column side-main">
      <Card>
        <h2>Question bank</h2>
        <div className="question-list">{questions.map(q => <button className={`question-item ${selected?.id === q.id ? 'selected' : ''}`} key={q.id} disabled={busy} onClick={() => select(q, history?.id === session ? history.answers.find(a => a.questionId === q.id) : undefined)}>
          <Badge tone="purple">{q.category}</Badge><strong>{q.question}</strong>
        </button>)}</div>
      </Card>
      {selected && <Card>
        <h2>Practice your answer</h2>
        <p>{selected.question}</p>
        <details><summary>Suggested answer framework</summary><p>{selected.suggestedAnswer}</p></details>
        <label>Your response<textarea rows={8} maxLength={12000} value={answer} onChange={e => setAnswer(e.target.value)}/></label>
        <div className="btn-row">
          {speech && <button className="button muted" disabled={busy} onClick={dictate}>Dictate</button>}
          <button className="button primary" disabled={busy || answer.trim().length < 10} onClick={() => void run(async () => {
            let id = session;
            if (!id) {
              id = (await post<{id: string}>('/interview-sessions', {jobId: target})).id;
              setSession(id);
            }
            const result = await post<{feedback: Feedback}>(`/interview-sessions/${id}/answers`, {questionId: selected.id, answer});
            setFeedback(result.feedback);
            await client.invalidateQueries({queryKey: ['interview-sessions']});
          })}>Submit answer</button>
        </div>
        {feedback && <FeedbackPanel feedback={feedback}/>}
        {session && <button className="button muted" disabled={busy} onClick={() => void run(async () => {
          await post(`/interview-sessions/${session}/complete`, {});
          setSession('');
          await client.invalidateQueries({queryKey: ['interview-sessions']});
        })}>Finish practice</button>}
      </Card>}
    </div></section>}
  </>;
}

function FeedbackPanel({feedback}: {feedback: Feedback}) {
  return <div className="feedback">
    <h3>Feedback</h3>
    <p>{feedback.score == null ? 'Not assessed' : `Practice score ${feedback.score}/100`}</p>
    {feedback.strengths?.length > 0 && <><strong>Strengths</strong><ul>{feedback.strengths.map((s, i) => <li key={i}>{s}</li>)}</ul></>}
    {feedback.improvements?.length > 0 && <><strong>To improve</strong><ul>{feedback.improvements.map((s, i) => <li key={i}>{s}</li>)}</ul></>}
    {feedback.suggestedAnswer && <p>{feedback.suggestedAnswer}</p>}
  </div>;
}
