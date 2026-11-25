import { Link, useLocation } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';

type Destination = { label: string; to: string };

function ViewLinks({ links, label }: {links: Destination[]; label: string}) {
  const { pathname } = useLocation();
  return <nav className="view-tabs" aria-label={label}>
    {links.map(link => <Link key={link.to} to={link.to} aria-current={pathname === link.to ? 'page' : undefined}>{link.label}</Link>)}
  </nav>;
}

export function ResumeLinks({ resumeId }: {resumeId?: string}) {
  const links = [{label: 'Overview', to: '/resumes'}];
  if (resumeId) links.push({label: 'Version history', to: `/resumes/${resumeId}/versions`});
  links.push({label: 'Career facts', to: '/career-memory'}, {label: 'Exports', to: '/tailoring'});
  return <ViewLinks links={links} label="Resume views"/>;
}

export function JobTrail({ jobId, current }: {jobId?: string; current: string}) {
  return <nav className="breadcrumb" aria-label="Breadcrumb"><Link to="/jobs">Jobs</Link><ChevronRight size={13}/>
    {jobId && <><Link to={`/jobs/${jobId}`}>Saved job</Link><ChevronRight size={13}/></>}
    <span aria-current="page">{current}</span>
  </nav>;
}
