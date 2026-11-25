import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, Building2, Columns3, MessagesSquare, ScanText, Wallet } from 'lucide-react';
import type { JobDetails } from '../lib/api-types';
import { api } from '../lib/api';
import { Badge, Card, ErrorNote, Hero, Spinner, statusLabel } from '../components/Shell';
import { JobTrail } from '../components/WorkspaceLinks';

export default function JobDetail() {
  const { id } = useParams();
  const job = useQuery({ queryKey: ['job', id], queryFn: () => api<JobDetails>(`/jobs/${id}`), enabled: !!id });
  const history = useQuery({
    queryKey: ['job-analyses', id],
    queryFn: () => api<{id: string; score: number | null; status: string; createdAt: string; tailoringSessionId?: string | null}[]>(`/jobs/${id}/analyses`),
    enabled: !!id,
  });
  if (job.isLoading) return <Spinner/>;
  if (job.error || !job.data) return <ErrorNote error={job.error}/>;
  const {title, companyName, location, rawJd, salaryDisclosed, createdAt} = job.data;
  const latest = history.data?.find(item => item.status === 'SUCCEEDED');
  const studio = history.data?.find(item => item.tailoringSessionId)?.tailoringSessionId;
  return <>
    <JobTrail current="Job details"/>
    <Hero title={title || 'Saved job'} description={[companyName, location].filter(Boolean).join(' · ') || 'Saved job details'}
      action={<Link className="button primary" to={latest ? `/analyses/${latest.id}` : `/jobs/new?job=${id}`}><ScanText size={17}/> {latest ? 'View fit analysis' : 'Analyze job fit'}</Link>}/>
    <div className="two-column main-side">
      <div className="stack">
        <Card className="stack"><h2>Saved job description</h2><small>Saved {new Date(createdAt).toLocaleDateString()}</small>
          <div className="doc">{rawJd}</div>
        </Card>
        <Card><h2>Analysis history</h2><ErrorNote error={history.error}/>
          {history.isLoading ? <Spinner/> : history.data?.length ? history.data.map(item => <div className="list-entry form-row wrap" key={item.id}>
            <Link to={`/analyses/${item.id}`}>{new Date(item.createdAt).toLocaleDateString()} · View analysis</Link>
            <Badge tone={item.status === 'SUCCEEDED' ? 'green' : 'orange'}>{item.score ?? statusLabel(item.status)}{item.score != null ? '% fit' : ''}</Badge>
          </div>) : <p>No analyses for this job yet.</p>}
        </Card>
      </div>
      <div className="stack">
        <Card><h2>Next actions</h2>
          {studio && <div className="list-entry form-row"><ScanText size={18}/><Link to={`/tailoring/${studio}`}>Continue tailoring <ArrowRight size={14}/></Link></div>}
          <div className="list-entry form-row"><ScanText size={18}/><Link to={`/jobs/new?job=${id}`}>Analyze this saved job <ArrowRight size={14}/></Link></div>
          <div className="list-entry form-row"><MessagesSquare size={18}/><Link to={`/interviews?job=${id}`}>Practice for this job <ArrowRight size={14}/></Link></div>
          <div className="list-entry form-row"><Columns3 size={18}/><Link to={`/applications?job=${id}`}>Track this job <ArrowRight size={14}/></Link></div>
          {studio && <div className="list-entry"><Link to={`/tailoring?job=${id}`}>View exports for this job</Link></div>}
        </Card>
        <Card className="stack"><div className="form-row"><Wallet size={18}/><h2>Compensation</h2></div>
          <p>{salaryDisclosed || 'The posting does not list a salary.'}</p><Link to={`/salaries?job=${id}`}>Compare compensation</Link>
        </Card>
        <Link className="text-button" to="/jobs"><Building2 size={16}/> Back to saved jobs</Link>
      </div>
    </div>
  </>;
}
