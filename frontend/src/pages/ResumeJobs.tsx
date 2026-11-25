
import React, { useEffect, useRef, useState } from 'react';
import JakeResumeExperience from './JakeResumeExperience';
import { JobRequirements } from './JobRequirements';
import { JobTrail, ResumeLinks } from '../components/WorkspaceLinks';
import { PageControls } from '../components/PageControls';
import { RecordPicker, type RecordOption } from '../components/RecordPicker';
import { useIdempotentPost } from '../lib/useIdempotentPost';
import { useCursorPage } from '../lib/useCursorPage';
import type { ResumeExtraction } from '../lib/contracts.generated';
import type {
  AnalysisDetails, ExportSummary, JobCreated, JobDetails, JobSummary, Proposal,
  ResumeCreated, ResumeDetails, ResumeSummary, ResumeVersion, TailoringDetails,
} from '../lib/api-types';

import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import {
  UploadCloud,
  FileText,
  Plus,
  ArrowRight,
  Download,
  Trash2,
  Target,
  WandSparkles,
} from 'lucide-react';

import {
  api,
  post,
  put,
  patch,
  del,
  download,
} from '../lib/api';

import {
  Hero,
  Card,
  Empty,
  Spinner,
  ErrorNote,
  Badge,
  FitGauge,
  statusLabel,
} from '../components/Shell';

// ======================================================
// DEVELOPMENT-ONLY DIAGNOSTIC LOGGING
// Never log resume text, JD text, or credentials.
// ======================================================

const studioLog = (
  event: string,
  details: Record<string, unknown> = {}
) => {
  if (import.meta.env.DEV) {
    console.info(
      '[CareerForge:ResumeStudio]',
      event,
      details
    );
  }
};

const studioError = (
  event: string,
  error: unknown,
  details: Record<string, unknown> = {}
) => {
  if (import.meta.env.DEV) {
    console.error(
      '[CareerForge:ResumeStudio]',
      event,
      {
        ...details,
        errorType:
          error instanceof Error
            ? error.name
            : 'Unknown',
        status:
          typeof error === 'object' &&
          error !== null &&
          'status' in error
            ? (error as { status?: number }).status
            : undefined,
      }
    );
  }
};

// ======================================================
// 1. MY RESUMES — EXISTING FUNCTIONALITY
// ======================================================

export function Resumes() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const targetJob = params.get('job');
  const reviewQuery = targetJob ? '?job=' + encodeURIComponent(targetJob) : '';

  const resumes = useCursorPage<ResumeSummary>('resumes', '/resumes/page');
  const { items: data, isLoading, error } = resumes;
  const activeHeading = useRef<HTMLHeadingElement>(null);
  const { canGoBack, isFetching, isSuccess, previous } = resumes;
  const emptyLaterPage = canGoBack && data.length === 0;

  useEffect(() => {
    if (emptyLaterPage && isSuccess && !isFetching) {
      activeHeading.current?.focus();
      previous();
    }
  }, [emptyLaterPage, isSuccess, isFetching, previous]);

  const archived = useQuery({
    queryKey: ['archived-resumes'],
    queryFn: () => api<{id: string; title: string}[]>('/resumes/archived'),
  });
  async function archiveAction(action: () => Promise<unknown>) {
    setBusy(true);
    setArchiveError(null);
    try {
      await action();
      await Promise.all([
        client.invalidateQueries({queryKey: ['resumes']}),
        client.invalidateQueries({queryKey: ['archived-resumes']}),
        client.invalidateQueries({queryKey: ['exports']}),
        client.invalidateQueries({queryKey: ['jake-exports']}),
      ]);
    } catch (error) {
      setArchiveError(error);
    } finally { setBusy(false); }
  }

  const [text, setText] = useState('');
  const [title, setTitle] = useState('Master Resume');
  const [busy, setBusy] = useState(false);
  const [archiveError, setArchiveError] = useState<unknown>(null);
  const [uploadError, setUploadError] =
    useState<unknown>(null);

  const upload = async (file: File) => {
    const started = performance.now();

    studioLog('resume upload started', {
      fileBytes: file.size,
    });

    setBusy(true);
    setUploadError(null);

    try {
      const data = new FormData();
      data.append('file', file);

      const result = await api<ResumeCreated>(
        '/resumes/upload',
        {
          method: 'POST',
          body: data,
        }
      );

      await client.invalidateQueries({
        queryKey: ['resumes'],
      });

      studioLog('resume upload succeeded', {
        resumeId: result.resumeId,
        durationMs: Math.round(
          performance.now() - started
        ),
      });

      navigate(`/resumes/${result.resumeId}/review${reviewQuery}`);
    } catch (e) {
      studioError('resume upload failed', e);
      setUploadError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Hero
        title="My Resumes"
        description="Store original resumes, verify extracted facts, and create job-specific versions without losing your history."
        action={
          <a href="#upload" className="button primary"><Plus size={17}/> Add resume</a>
        }
      />

      <ResumeLinks/>
      <div className="two-column main-side">
        <Card id="upload" className="resume-intake">
          <h2>Upload your resume</h2>

          <label className="dropzone">
            <UploadCloud size={40} />

            <strong>
              {busy
                ? 'Uploading and extracting…'
                : 'Choose a PDF or DOCX file'}
            </strong>

            <small>
              Up to 10 MB, text-based documents
            </small>

            <input
              aria-label="Upload resume"
              type="file"
              accept=".pdf,.docx"
              disabled={busy}
              onChange={(e) => {
                if (e.target.files?.[0]) {
                  upload(e.target.files[0]);
                }
                e.target.value = '';
              }}
            />
          </label>

          <details>
            <summary>Or paste your resume text</summary>

            <form
              className="form-stack compact"
              onSubmit={async (e) => {
                e.preventDefault();
                setUploadError(null);
                setBusy(true);

                try {
                  const r = await post<ResumeCreated>(
                    '/resumes/text',
                    {
                      title,
                      pastedText: text,
                    }
                  );

                  client.invalidateQueries({
                    queryKey: ['resumes'],
                  });

                  navigate(
                    `/resumes/${r.resumeId}/review${reviewQuery}`
                  );
                } catch (err) {
                  setUploadError(err);
                } finally {
                  setBusy(false);
                }
              }}
            >
              <label>
                Resume title
                <input
                  value={title}
                  onChange={(e) =>
                    setTitle(e.target.value)
                  }
                />
              </label>

              <label>
                Resume text
                <textarea
                  required
                  minLength={25}
                  rows={8}
                  value={text}
                  onChange={(e) =>
                    setText(e.target.value)
                  }
                />
              </label>

              <button
                className="button primary"
                disabled={busy}
              >
                Save pasted resume
              </button>
            </form>
          </details>

          <ErrorNote error={uploadError} />
        </Card>

      <div className="stack resume-library"><Card>
        <div className="section-heading">
          <h2 ref={activeHeading} tabIndex={-1}>Active resumes</h2>
        </div>

        {isLoading ? (
          <Spinner />
        ) : error ? (
          <ErrorNote error={error} />
        ) : emptyLaterPage ? (
          <Spinner />
        ) : data.length === 0 ? (
          <Empty
            title="You haven't uploaded a resume"
            description="Upload a PDF/DOCX or paste your experience to begin."
          />
        ) : (
          <div className="resume-grid">
            {data.map(r => (
              <article className="resume-card" key={r.id}>
                <div className="resume-icon">
                  <FileText />
                </div>

                <div>
                  <h3>{r.title}</h3>
                  <small>{r.versions} saved {r.versions === 1 ? 'version' : 'versions'} · {new Date(r.createdAt).toLocaleDateString()}</small>
                </div>
                <Badge tone={r.ready ? 'green' : 'orange'}>{r.ready ? 'Ready to use' : 'Needs review'}</Badge>

                <div className="btn-row">
                  <Link
                    className="button muted small"
                    to={`/resumes/${r.id}/review${reviewQuery}`}
                  >
                    Review
                  </Link>

                  <Link
                    className="button muted small"
                    to={`/resumes/${r.id}/versions`}
                  >
                    Versions
                  </Link>

                  <button
                    className="button icon-danger small"
                    title="Archive resume" aria-label="Archive resume" disabled={busy}
                    onClick={async () => {
                      if (
                        window.confirm(
                          'Archive this resume? Previously exported files remain available.'
                        )
                      ) {
                        await archiveAction(() => del(`/resumes/${r.id}`));
                      }
                    }}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}
        <PageControls pages={resumes} records="resumes" focusTarget={activeHeading} disabled={busy}/>
      </Card>
      <Card className="top-gap">
        <h2>Archived resumes</h2>
        <p>Archived resumes are hidden from new work. Existing exports remain in your history.</p>
        <ErrorNote error={archived.error || archiveError}/>
        {archived.isLoading ? <Spinner/> : archived.data?.length === 0 && <p>No archived resumes.</p>}
        {archived.data?.map(resume => <div className="list-entry" key={resume.id}>
          <b>{resume.title}</b>
          <div className="btn-row">
            <button className="button muted small" disabled={busy} onClick={() => void archiveAction(() => post(`/resumes/${resume.id}/restore`, {}))}>Restore</button>
            <button className="button danger small" disabled={busy} onClick={() => {
              if (window.confirm('Permanently delete this resume, its versions, analyses, and exported files?')) {
                void archiveAction(() => del(`/resumes/${resume.id}/permanent`, {confirmation: 'DELETE RESUME'}));
              }
            }}>Delete permanently</button>
          </div>
        </div>)}
      </Card>
      </div></div>
    </>
  );
}

// ======================================================
// 2. RESUME REVIEW — EXISTING FUNCTIONALITY
// ======================================================

export function ResumeReview() {
  const { id } = useParams();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [text, setText] = useState("");
  const [skillsInput, setSkillsInput] = useState("");
  const [editing, setEditing] = useState(false);
  const [attested, setAttested] = useState(false);

  const {
    data: resume,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['resume', id],
    queryFn: () => api<ResumeDetails>(`/resumes/${id}`),
  });

  const { data: versions = [], error: versionsError, isLoading: versionsLoading } = useQuery({
    queryKey: ['versions', id],
    queryFn: () =>
      api<ResumeVersion[]>(`/resumes/${id}/versions`),
    enabled: !!id,
  });

  const [structured, setStructured] = useState<Partial<ResumeExtraction>>({});
  const [errorEdit, setErrorEdit] = useState<unknown>(null);
  const [saved, setSaved] = useState('');

  const v = versions.find(v => v.id === params.get("version")) ?? versions[0];

  useEffect(() => {
    if (v) {
      setText(v.contentText);
      setAttested(false);
      const structure = v.contentJson?.schemaVersion ? v.contentJson : resume?.structuredJson ?? {};
      setStructured(structure);
      setSkillsInput(Array.isArray(structure.skills) ? structure.skills.filter((skill: unknown) => typeof skill === 'string').join('\n') : '');
    }
  }, [v?.id, resume?.id]);

  const updateStructured = (values: Partial<ResumeExtraction>) =>
    setStructured(current => ({...current, ...values}));
  const job = params.get('job');
  const next = job ? `/jobs/new?job=${encodeURIComponent(job)}` : '/jobs/new';

  return (
    <>
      <Hero
        title="Review extracted resume"
        description="AI extraction is provisional. Confirm or correct information before using it in a job analysis."
        action={
          <Link className="button muted" to="/resumes">
            ← All resumes
          </Link>
        }
      />

      <ResumeLinks resumeId={id}/>
      {isLoading || versionsLoading ? (
        <Spinner />
      ) : error || versionsError ? (
        <ErrorNote error={error || versionsError} />
      ) : resume && (
        <div className="two-column">
          <Card>
            <h2>Original document text</h2>

            <div className="document-preview">
              {resume.originalText}
            </div>
          </Card>

          <Card className="stack">
            <h2>Reviewed resume</h2>
            {v?.confirmedAt && <Badge tone="green">Version {v.versionNumber} confirmed</Badge>}
            <label>Candidate name<input value={structured.candidate?.name ?? ''} maxLength={200} onChange={e => updateStructured({candidate: {headline: '', ...structured.candidate, name: e.target.value}})}/></label>
            <label>Resume text<textarea rows={14} value={text} onChange={e => setText(e.target.value)} maxLength={100000}/></label>
            <label>Verified skills (one per line)<textarea rows={4} value={skillsInput} onChange={e => { setSkillsInput(e.target.value); updateStructured({skills: e.target.value.split('\n').map(t => t.trim()).filter(Boolean)}); }}/></label>
            <label className="inline-check"><input type="checkbox" checked={attested} onChange={e => setAttested(e.target.checked)}/> I confirm the text and skills reflect my experience.</label>
            <p className="fineprint">Confirmed skills missing from the text will be included in the reviewed version.</p>

            <ErrorNote error={errorEdit} />

            {saved && (
              <div className="alert success">
                {saved}
              </div>
            )}

            <div className="btn-row">
              <button
                className="button primary"
                disabled={editing || !attested || !v}
                onClick={async () => {
                  setEditing(true);
                  try {
                    if (!v) {
                      throw new Error('Version not found');
                    }

                    const result = await put<{versionId: string}>(
                      `/resume-versions/${v.id}/structured-content`,
                      {
                        structuredContent: structured,
                        contentText: text,
                        confirm: true,
                      }
                    );

                    await client.invalidateQueries({queryKey: ['versions', id]});
                    setParams(current => { const next = new URLSearchParams(current); next.set('version', result.versionId); return next; }, {replace: true});
                    await client.invalidateQueries({queryKey: ['resumes']});
                    setSaved('Reviewed version confirmed.');
                    setErrorEdit(null);
                  } catch (e) {
                    setErrorEdit(e);
                  } finally { setEditing(false); }
                }}
              >
                Confirm reviewed version
              </button>

              <Link className="button muted" to={next}>
                {job ? 'Next: return to job analysis' : 'Next: analyze a job'} <ArrowRight size={16} />
              </Link>
            </div>
          </Card>
        </div>
      )}
    </>
  );
}

// ======================================================
// 3. RESUME VERSIONS — EXISTING FUNCTIONALITY
// ======================================================

export function ResumeVersions() {
  const { id } = useParams();

  const { data = [], isLoading, error } = useQuery({
    queryKey: ['versions', id],
    queryFn: () =>
      api<ResumeVersion[]>(`/resumes/${id}/versions`),
  });

  return (
    <>
      <Hero
        title="Resume Version History"
        description="Your original and each tailored version remain separately accessible."
      />

      <ResumeLinks resumeId={id}/>
      <Card>
        {isLoading ? (
          <Spinner />
        ) : error ? <ErrorNote error={error}/> : (
          data.map(v => (
            <details key={v.id} className="version-row">
              <summary>
                Version {v.versionNumber} —{' '}
                {String(v.createdAt).slice(0, 10)}{' '}
                {v.sourceJobId ? '(Tailored)' : v.confirmedAt ? '(Reviewed)' : '(Original)'}
              </summary>

              <Link to={`/resumes/${id}/review?version=${v.id}`}>Review this version</Link>
              <div className="document-preview">
                {v.contentText}
              </div>
            </details>
          ))
        )}
      </Card>
    </>
  );
}

// ======================================================
// 4. SAVED JOBS — EXISTING FUNCTIONALITY
// ======================================================

export function Jobs() {
  const jobs = useCursorPage<JobSummary>('jobs', '/jobs/page');
  const { items: data, isLoading, error } = jobs;
  const [filter, setFilter] = useState('');
  const filterField = useRef<HTMLInputElement>(null);
  const term = filter.trim().toLowerCase();
  const jobRows = data.filter(job => [job.title, job.companyName, job.location].some(value => value?.toLowerCase().includes(term)));

  return (
    <>
      <Hero
        title="Saved Jobs"
        description="Every target job and its analysis history in one place."
        action={
          <Link to="/jobs/new" className="button primary">
            <Plus size={17} />
            Add job
          </Link>
        }
      />

      <Card>
        {isLoading ? (
          <Spinner />
        ) : error ? <ErrorNote error={error}/> : data.length === 0 ? (
          <Empty
            title="No jobs saved"
            description="Paste a JD to create your first job record."
            action={
              <Link to="/jobs/new" className="button primary">
                Add job
              </Link>
            }
          />
        ) : (
          <>
            <label>Filter this page<input ref={filterField} type="search" value={filter} onChange={event => setFilter(event.target.value)} placeholder="Company or job title"/></label>
            {jobRows.length ? jobRows.map(job => <div key={job.id} className="list-entry form-row wrap">
              <div><Link to={`/jobs/${job.id}`}>{job.title || 'Saved job'}</Link><p>{[job.companyName, job.location].filter(Boolean).join(' · ')}</p></div>
              <small>{new Date(job.createdAt).toLocaleDateString()}</small>
            </div>) : <p>No jobs on this page match your filter.</p>}
          </>
        )}
        <PageControls pages={jobs} records="jobs" focusTarget={filterField}/>
      </Card>
    </>
  );
}

// ======================================================
// 5. JOB ANALYSIS — EXISTING + DEBUG LOGGING
// ======================================================

export function JobAnalysis() {
  const navigate = useNavigate();
  const submit = useIdempotentPost();
  const [params] = useSearchParams();
  const savedId = params.get('job');
  const existing = useQuery({
    queryKey: ['job', savedId],
    queryFn: () => api<JobDetails>(`/jobs/${encodeURIComponent(savedId ?? '')}`),
    enabled: !!savedId,
  });
  const client = useQueryClient();

  const [form, setForm] = useState({
    rawJd: '',
    companyName: '',
    title: '',
    location: '',
    salaryDisclosed: '',
    jobUrl: '',
  });

  const [saved, setSaved] = useState<JobCreated | null>(null);
  const [resume, setResume] = useState<RecordOption | null>(null);
  const [versionId, setVersionId] = useState('');
  const [busy, setBusy] = useState(false);
  const [requirementsError, setRequirementsError] = useState<unknown>(null);
  const [analysisError, setAnalysisError] = useState<unknown>(null);
  const [error, setError] = useState<unknown>(null);
  const [jdEdit, setJdEdit] = useState('');
  const [persistedJd, setPersistedJd] = useState('');

  const { data: versions = [], error: versionError, isPending: versionsPending } = useQuery({
    queryKey: ['versions', resume?.id],
    queryFn: () =>
      api<ResumeVersion[]>(`/resumes/${resume?.id}/versions`),
    enabled: !!resume,
  });
  const confirmedVersions = versions.filter(version => version.confirmedAt);
  const selectedVersionId = confirmedVersions.some(version => version.id === versionId) ? versionId : confirmedVersions[0]?.id ?? '';

  useEffect(() => {
    if (!existing.data) return;
    const {id, extractedJson, rawJd, companyName, title, location, salaryDisclosed, jobUrl} = existing.data;
    setForm({rawJd, companyName: companyName ?? '', title: title ?? '', location: location ?? '',
      salaryDisclosed: salaryDisclosed ?? '', jobUrl: jobUrl ?? ''});
    setSaved({jobId: id, structuredJd: extractedJson});
    const formatted = JSON.stringify(extractedJson, null, 2);
    setJdEdit(formatted);
    setPersistedJd(formatted);
  }, [existing.data]);

  const activeStep = !saved ? 0 : jdEdit !== persistedJd ? 1 : !selectedVersionId ? 2 : 3;

  return (
    <>
      <JobTrail jobId={saved?.jobId} current="Job Analysis"/>
      <Hero
        title="Job Analysis"
        description="Paste a complete job description, review extracted requirements and match them against your actual resume."
      />

      <ol className="step-list" aria-label="Job analysis steps">
        {['Save job', 'Review requirements', 'Choose resume', 'Analyze fit'].map((label,index) =>
          <li key={label} aria-current={activeStep === index ? 'step' : undefined}>{index+1}. {label}</li>)}
      </ol>
      <ErrorNote error={existing.error}/>
      <div className="two-column main-side">
        <Card>
          <h2>Target job description</h2>
          <ErrorNote error={existing.error}/>
          {savedId && <p>Analysis will use the saved job. To save a different description, start a new job.</p>}

          <form
            className="form-stack"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              setError(null);

              try {
                studioLog('job save requested', {
                  jdCharacters: form.rawJd.length,
                });

                const r = await post<JobCreated>('/jobs', form);

                setSaved(r);
                const formatted = JSON.stringify(r.structuredJd, null, 2);
                setJdEdit(formatted);
                setPersistedJd(formatted);

                await client.invalidateQueries({
                  queryKey: ['jobs'],
                });

                studioLog('job saved', {
                  jobId: r.jobId,
                });
              } catch (err) {
                studioError('job save failed', err);
                setError(err);
              } finally {
                setBusy(false);
              }
            }}
          >
            <fieldset disabled={!!saved || !!savedId} className="form-stack">
            <label>
              Job description
              <textarea
                required
                minLength={80}
                maxLength={50000}
                rows={14}
                value={form.rawJd}
                onChange={(e) =>
                  setForm({
                    ...form,
                    rawJd: e.target.value,
                  })
                }
                placeholder="Paste the full JD here…"
              />
            </label>

            <div className="form-grid">
              <label>
                Company
                <input
                  value={form.companyName}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      companyName: e.target.value,
                    })
                  }
                />
              </label>

              <label>
                Job title
                <input
                  value={form.title}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      title: e.target.value,
                    })
                  }
                />
              </label>

              <label>
                Location
                <input
                  value={form.location}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      location: e.target.value,
                    })
                  }
                />
              </label>

              <label>
                Advertised salary (optional)
                <input
                  value={form.salaryDisclosed}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      salaryDisclosed: e.target.value,
                    })
                  }
                />
              </label>

              <label className="wide">
                Job URL (optional — stored, not automatically fetched)
                <input
                  type="url"
                  value={form.jobUrl}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      jobUrl: e.target.value,
                    })
                  }
                />
              </label>
            </div>

            <ErrorNote error={error} />

            <button
              disabled={busy}
              className="button primary"
            >
              {busy
                ? 'Extracting requirements…'
                : saved ? 'Job description saved' : 'Save and extract job description'}
            </button>
            </fieldset>
          </form>
        </Card>

        <Card>
          <h2>Extracted job details</h2>

          {!saved ? (
            <Empty
              title="Your requirements will appear here"
              description="Save the job description to extract relevant technologies, responsibilities and experience requirements."
            />
          ) : (
            <>
              <Badge tone="green">Job saved</Badge>

              <p className="fineprint">
                Review and edit the structured requirements
                before analysis.
              </p>

              <fieldset disabled={busy} className="form-stack"><legend>Review requirements</legend><JobRequirements json={jdEdit} onChange={setJdEdit}/></fieldset>

              <ErrorNote error={requirementsError}/>
              <button
                className="button muted"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setRequirementsError(null);
                  try {
                    await put(
                      `/jobs/${saved.jobId}/structured-jd`,
                      {
                        structuredJd: JSON.parse(jdEdit),
                        confirm: true,
                      }
                    );

                    setPersistedJd(jdEdit);
                    await client.invalidateQueries({queryKey: ['job', saved.jobId]});
                  } catch (e) {
                    setRequirementsError(e);
                  } finally { setBusy(false); }
                }}
              >
                Save corrected requirements
              </button>

              <hr />

              <h3>Choose your resume</h3>
              <ErrorNote error={analysisError}/>

              <RecordPicker resource="resumes" label="Resume" defaultToFirst value={resume} disabled={busy}
                onChange={option => {
                  if (option?.id !== resume?.id) setVersionId('');
                  setResume(option);
                }}
                emptyAction={<Link to={`/resumes?job=${saved.jobId}#upload`}>Upload a resume first →</Link>}/>
              <ErrorNote error={versionError}/>
              {resume && versionsPending ? <Spinner/> : resume && (
                <>
                  {confirmedVersions.length > 0 && <label>
                    Version
                    <select value={selectedVersionId} disabled={busy} onChange={event => setVersionId(event.target.value)}>
                      {confirmedVersions.map(v => (
                        <option key={v.id} value={v.id}>
                          Version {v.versionNumber}
                        </option>
                      ))}
                    </select>
                  </label>}

                  {jdEdit !== persistedJd && <p>Save the corrected requirements before starting analysis.</p>}
                  {!versionError && !selectedVersionId && <p><Link to={`/resumes/${resume.id}/review?job=${saved.jobId}`}>Review and confirm this resume first</Link>.</p>}
                  <button
                    disabled={busy || !!versionError || !selectedVersionId || jdEdit !== persistedJd}
                    className="button primary full"
                    onClick={async () => {
                      setBusy(true);
                      setAnalysisError(null);
                      try {
                        studioLog('analysis requested', {
                          jobId: saved.jobId,
                          resumeVersionId: selectedVersionId,
                        });

                        const r = await submit<{analysisId: string}>(
                          '/analyses',
                          {
                            jobId: saved.jobId,
                            resumeVersionId: selectedVersionId,
                          }
                        );

                        studioLog('analysis created', {
                          analysisId: r.analysisId,
                        });

                        navigate(`/analyses/${r.analysisId}`);
                      } catch (e) {
                        studioError('analysis create failed', e);
                        setAnalysisError(e);
                      } finally { setBusy(false); }
                    }}
                  >
                    Analyze job fit
                    <ArrowRight size={17} />
                  </button>
                </>
              )}
            </>
          )}
        </Card>
      </div>
    </>
  );
}

// ======================================================
// 6. ANALYSIS DETAIL — EXISTING + JAKE CTA
// ======================================================

export function AnalysisDetail() {
  const { id } = useParams();

  const {
    data,
    error,
    isLoading,
  } = useQuery({
    queryKey: ['analysis', id],
    queryFn: () => api<AnalysisDetails>(`/analyses/${id}`),
    refetchInterval: (q) =>
      ['QUEUED', 'RUNNING'].includes(
        q.state.data?.status ?? ''
      )
        ? 2500
        : false,
  });

  const navigate = useNavigate();
  const submit = useIdempotentPost();
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] =
    useState<unknown>(null);

  if (isLoading) return <Spinner />;
  if (error) return <ErrorNote error={error} />;

  if (!data) return null;

  const result = data.resultJson ?? {};
  async function tailor() {
    setBusy(true);
    setActionError(null);
    try {
      const session = await submit<{sessionId: string}>('/tailoring-sessions', { analysisId: id });
      navigate(`/tailoring/${session.sessionId}`);
    } catch (failure) { setActionError(failure); }
    finally { setBusy(false); }
  }

  return (
    <>
      <JobTrail jobId={data.jobId} current="Job Fit Analysis"/>
      <Hero
        title="Job Fit Analysis"
        description="Evidence-backed requirement matching, transparent scoring, and clear limitations."
        action={
          data.status === 'SUCCEEDED'
            ? <button className="button primary" disabled={busy} onClick={() => void tailor()}><WandSparkles size={17}/> Generate tailoring proposals</button>
            : <Link className="button muted" to="/jobs/new">Analyze another job</Link>
        }
      />

      <div className="two-column main-side fit-layout">
        <Card>
          <h2>Overall job alignment</h2>

          {data.status === 'SUCCEEDED' ? (
            <>
              <FitGauge score={data.score} />

              <div className="alert warning">
                {result.disclaimer}
              </div>

              {result.categoryBreakdown?.length ? <ul aria-label="Weighted score categories">
                {result.categoryBreakdown.map(category => <li key={category.category}>
                  {category.category.replaceAll('_', ' ').toLowerCase()} · {category.score}% · {category.weight}% weight
                </li>)}
              </ul> : null}
              {result.mode === 'LOCAL_HEURISTIC' && <p className="fineprint">AI demo mode uses keyword-based evidence.</p>}

              <ErrorNote error={actionError} />
            </>
          ) : (
            <>
              <Badge
                tone={
                  data.status === 'FAILED'
                    ? 'red'
                    : 'orange'
                }
              >
                {data.status}
              </Badge>

              <p>
                {data.status === 'FAILED'
                  ? 'Analysis failed. Review the job and resume or retry this request.'
                  : 'Analysis is processing. This page refreshes automatically.'}
              </p>
              {data.status === 'FAILED' && data.aiJobId && (data.attempts ?? 3) < 3 && <button className="button primary" disabled={busy} onClick={async () => {
                setBusy(true); setActionError(null);
                try {
                  await post(`/ai-jobs/${data.aiJobId}/retry`, {});
                  await client.invalidateQueries({queryKey: ['analysis', id]});
                } catch (e) { setActionError(e); } finally { setBusy(false); }
              }}>Retry analysis</button>}
              <ErrorNote error={actionError}/>
            </>
          )}
        </Card>

        <Card>
          <h2>Requirement-by-requirement evidence</h2>

          {result.requirements?.length ? (
            result.requirements.map((r) => (
              <div
                className="requirement"
                key={r.requirementId}
              >
                <div className="form-row">
                  <strong>{r.label}</strong>

                  <Badge
                    tone={
                      r.status === 'DEMONSTRATED'
                        ? 'green'
                        : 'orange'
                    }
                  >
                    {statusLabel(r.status)}
                  </Badge>
                </div>

                <p>{r.rationale}</p>

                {Array.isArray(r.evidence) ? r.evidence.map(ref =>
                  <blockquote key={ref.start} title={`Resume characters ${ref.start}–${ref.end}`}>{ref.excerpt}</blockquote>
                ) : r.evidence ? <blockquote>{r.evidence}</blockquote> : null}
              </div>
            ))
          ) : (
            <Empty
              title="No result yet"
              description="Waiting for your analysis to complete."
            />
          )}
        </Card>
      </div>
    </>
  );
}

// ======================================================
// 7. RESUME STUDIO — EXISTING + NEW JAKE FEATURES
// ======================================================

export function Tailoring() {
  const { id } = useParams();
  const client = useQueryClient();
  const submit = useIdempotentPost();
  const capabilities = useQuery({
    queryKey: ['export-capabilities'],
    queryFn: () => api<{jakePdf: boolean; jakePdfMessage?: string | null}>('/export-capabilities'),
  });

  const {
    data,
    error,
    isLoading,
  } = useQuery({
    queryKey: ['tailoring', id],
    queryFn: () =>
      api<TailoringDetails>(`/tailoring-sessions/${id}`),
    refetchInterval: (q) =>
      ['QUEUED', 'RUNNING'].includes(
        q.state.data?.status ?? ''
      )
        ? 2500
        : false,
  });

  const { data: source, error: sourceError } = useQuery({
    queryKey: ['version', data?.sourceVersionId],
    queryFn: () =>
      api<ResumeVersion>(
        `/resume-versions/${data?.sourceVersionId}`
      ),
    enabled: !!data?.sourceVersionId,
  });

  const [actionError, setActionError] =
    useState<unknown>(null);

  const [busy, setBusy] = useState(false);
  const [exportDone, setExportDone] = useState('');

  const [editDrafts, setEditDrafts] = useState<
    Record<string, string>
  >({});

  if (isLoading) return <Spinner />;
  if (error) return <ErrorNote error={error} />;

  if (!data) return null;
  const pending = data.proposals?.filter(edit => edit.decision === 'PENDING').length ?? 0;
  const total = data.proposals?.length ?? 0;

  const update = async (
    edit: Proposal,
    decision: 'ACCEPTED' | 'REJECTED'
  ) => {
    studioLog('edit decision requested', {
      sessionId: id,
      editId: edit.id,
      decision,
    });

    try {
      await patch(`/resume-edits/${edit.id}`, {
        decision,
        revision: edit.revision,
        ...(decision === "ACCEPTED" ? {proposedText: editDrafts[edit.id] ?? edit.proposedText} : {}),
      });

      await client.invalidateQueries({
        queryKey: ['tailoring', id],
      });

      studioLog('edit decision saved', {
        sessionId: id,
        editId: edit.id,
        decision,
      });
    } catch (e) {
      studioError('edit decision failed', e, {
        sessionId: id,
        editId: edit.id,
      });

      setActionError(e);
    }
  };

  async function exportResume(format: 'PDF' | 'TEX') {
    setBusy(true);
    setActionError(null);
    setExportDone('');
    try {
      const file = await submit<{downloadUrl: string; filename: string}>(`/tailoring-sessions/${id}/exports`, {format});
      await client.invalidateQueries({queryKey: ['exports']});
      await download(file.downloadUrl, file.filename);
      setExportDone(`Jake ${format} export created.`);
    } catch (error) {
      setActionError(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <JobTrail jobId={data.jobId} current="Resume Studio"/>
      <Hero
        title="Resume Studio"
        description="Compare the source resume with AI proposals. Approve every material change before creating a new version."
      />
      {data.status === 'FAILED' && data.aiJobId && (data.attempts ?? 3) < 3 && <button className="button primary" disabled={busy} onClick={async () => {
        setBusy(true); setActionError(null);
        try {
          await post(`/ai-jobs/${data.aiJobId}/retry`, {});
          await client.invalidateQueries({queryKey: ['tailoring', id]});
        } catch (e) { setActionError(e); } finally { setBusy(false); }
      }}>Retry tailoring</button>}


      {total > 0 && <Card><div className="section-heading"><h2>Review suggestions</h2><Badge tone={pending ? 'orange' : 'green'}>{pending} remaining</Badge></div>
        <p>{total - pending} of {total} suggestions reviewed</p>
        <progress className="review-progress" max={total} value={total - pending} aria-label="Tailoring review progress"/>
      </Card>}
      <div className="studio-layout">
        <Card>
          <h2>Original resume</h2>
          <p className="subtext">Immutable source version</p>

          <div className="document-preview">
            {sourceError ? <ErrorNote error={sourceError}/> : source?.contentText ?? 'Loading source…'}
          </div>
        </Card>

        <Card>
          <h2>Proposed improvements</h2>

          {data.status !== 'SUCCEEDED' ? (
            <Badge tone="orange">
              {data.status}
            </Badge>
          ) : data.proposals.length === 0 ? (
            <Empty
              title="No safe edit proposals"
              description="No verified improvements were generated. Your original resume remains available."
            />
          ) : (
            data.proposals.map(e => (
              <div className="proposal" key={e.id}>
                <div className="form-row">
                  <Badge
                    tone={
                      e.decision === 'ACCEPTED'
                        ? 'green'
                        : e.decision === 'REJECTED'
                        ? 'red'
                        : 'orange'
                    }
                  >
                    {statusLabel(e.decision)}
                  </Badge>

                  <Badge
                    tone={
                      e.risk === 'SOURCE_SUPPORTED'
                        ? 'blue'
                        : 'orange'
                    }
                  >
                    {statusLabel(e.risk)}
                  </Badge>
                </div>

                <h4>Original</h4>
                <p className="diff-before">
                  {e.originalText}
                </p>

                <h4>Proposed</h4>

                <textarea
                  aria-label={`Edit proposed text for ${e.id}`}
                  rows={4}
                  className="diff-after"
                  value={
                    editDrafts[e.id] ?? e.proposedText
                  }
                  onChange={(ev) =>
                    setEditDrafts((d) => ({
                      ...d,
                      [e.id]: ev.target.value,
                    }))
                  }
                />

                <p className="fineprint">
                  Why: {e.reason}
                </p>

                <p className="fineprint">
                  Evidence:{' '}
                  {e.evidence ||
                    'User confirmation required'}
                </p>

                <div className="btn-row">
                  <button
                    className="button small muted"
                    onClick={async () => {
                      try {
                        await put(
                          `/resume-edits/${e.id}`,
                          {
                            proposedText:
                              editDrafts[e.id] ??
                              e.proposedText,
                            revision: e.revision,
                          }
                        );

                        client.invalidateQueries({
                          queryKey: ['tailoring', id],
                        });
                      } catch (err) {
                        studioError(
                          'edit correction failed',
                          err,
                          {
                            editId: e.id,
                            sessionId: id,
                          }
                        );

                        setActionError(err);
                      }
                    }}
                  >
                    Save my correction
                  </button>

                  <button
                    className="button small primary"
                    onClick={() =>
                      update(e, 'ACCEPTED')
                    }
                  >
                    {editDrafts[e.id] !== undefined && editDrafts[e.id] !== e.proposedText ? 'Save and accept' : 'Accept'}
                  </button>

                  <button
                    className="button small muted"
                    onClick={() =>
                      update(e, 'REJECTED')
                    }
                  >
                    Reject
                  </button>
                </div>
              </div>
            ))
          )}
        </Card>

        <Card>
          <h2>Export Jake’s Resume</h2>
          <p>Approved wording is placed into Jake’s single-column template, with headings, contact details and bullets formatted for the final document.</p>
          {pending > 0 && <p className="alert warning">Review {pending} more {pending === 1 ? 'change' : 'changes'} before exporting.</p>}
          <div className="form-stack">
            <button type="button" className="button primary"
              disabled={busy || data.status !== 'SUCCEEDED' || pending > 0 || !capabilities.data?.jakePdf}
              onClick={() => void exportResume('PDF')}><Download size={17}/> Download Jake PDF</button>
            <button type="button" className="button muted"
              disabled={busy || data.status !== 'SUCCEEDED' || pending > 0}
              onClick={() => void exportResume('TEX')}><Download size={17}/> Download Overleaf .tex</button>
            {capabilities.data?.jakePdf === false && <p className="fineprint" role="status">
              {capabilities.data.jakePdfMessage} Jake’s Overleaf source is available above.
            </p>}
            <ErrorNote error={capabilities.error}/>
            {exportDone && <div className="alert success" role="status">{exportDone}</div>}
            <ErrorNote error={actionError}/>
            <Link to="/tailoring">View all exports →</Link>
          </div>
        </Card>
      </div>

      {data.status === 'SUCCEEDED' && (
        <details className="card form-disclosure"><summary>Resume evaluation and recruiter draft</summary>
        <JakeResumeExperience
          sessionId={id!}
          revisionKey={JSON.stringify(
            (data.proposals || []).map(p => [
              p.id,
              p.revision,
              p.decision,
            ])
          )}
        />
        </details>
      )}
    </>
  );
}

// ======================================================
// 8. EXPORT HISTORY — EXISTING FUNCTIONALITY
// ======================================================

export function Exports() {
  const [params] = useSearchParams();
  const job = params.get('job');
  const { data = [], isLoading, error } = useQuery({
    queryKey: ['exports'],
    queryFn: () => api<ExportSummary[]>('/exports'),
  });

  const files = job ? data.filter(item => item.jobId === job) : data;

  return (
    <>
      <Hero
        title="Export history"
        description={job ? "Generated resume files for the selected job." : "Find and download the files you created for each role."}
        action={
          job ? <Link to={`/jobs/${encodeURIComponent(job)}`} className="button muted">Back to job</Link> : <Link to="/jobs/new" className="button primary">Analyze a new job <ArrowRight size={17}/></Link>
        }
      />

      <ResumeLinks/>
      <Card>
        {isLoading ? (
          <Spinner />
        ) : error ? <ErrorNote error={error}/> : files.length === 0 ? (
          <Empty
            title="No tailored resumes yet"
            description="Analyze a job, generate edit proposals, then export your approved resume."
          />
        ) : (
          files.map(e => (
            <div className="list-entry" key={e.id}>
              <div className="form-row">
                <div>
                  <b>{e.filename}</b>

                  <p className="fineprint">
                    {String(e.createdAt).slice(0, 10)}
                    {' · '}
                    {e.format}
                    {e.versionNumber && ` · Version ${e.versionNumber}`}
                    {e.jobTitle && ` · ${e.jobTitle}`}
                    {e.companyName && ` · ${e.companyName}`}
                  </p>
                </div>

                <button
                  className="button muted small"
                  onClick={() =>
                    download(
                      e.downloadUrl,
                      e.filename
                    )
                  }
                >
                  <Download size={16} />
                  Download
                </button>
              </div>
            </div>
          ))
        )}
      </Card>
    </>
  );
}

