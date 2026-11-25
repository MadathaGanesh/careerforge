import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { Card, Empty, ErrorNote, Hero, Spinner, statusLabel } from '../components/Shell';

type Result = {
  jobs: {id: string; title: string; companyName: string; location: string}[];
  applications: {id: string; title: string; companyName: string; status: string}[];
};

export default function SearchPage() {
  const [params, setParams] = useSearchParams();
  const term = (params.get('q') || '').trim();
  const results = useQuery({
    queryKey: ['search-page', term],
    queryFn: () => api<Result>('/search?q=' + encodeURIComponent(term)),
    enabled: term.length >= 2,
  });

  return <>
    <Hero title="Search your workspace" description="Find a job or application you’ve already saved."/>
    <Card>
      <form key={term} className="form-row wrap" role="search" onSubmit={event => {
        event.preventDefault();
        const query = String(new FormData(event.currentTarget).get('q')).trim();
        setParams(query ? { q: query } : {});
      }}>
        <label className="grow">Search jobs and applications<input type="search" name="q" defaultValue={term} placeholder="Company or job title"/></label>
        <button className="button primary align-bottom"><Search size={17}/> Search</button>
      </form>
    </Card>
    {term.length < 2 ? <p className="fineprint">Enter at least two characters to search your workspace.</p> :
      results.isLoading ? <Spinner/> : results.error ? <ErrorNote error={results.error}/> :
        <div className="two-column">
          <Card><h2>Saved jobs ({results.data?.jobs.length || 0})</h2>
            {!results.data?.jobs.length ? <Empty title="No saved jobs found" description="Try another company or job title."/> :
              results.data.jobs.map(job => <div className="list-entry" key={job.id}>
                <strong>{job.title || 'Saved job'}</strong><p>{[job.companyName, job.location].filter(Boolean).join(' · ')}</p>
                <Link to={`/jobs/${job.id}`}>View job details</Link>
              </div>)}
          </Card>
          <Card><h2>Tracked applications ({results.data?.applications.length || 0})</h2>
            {!results.data?.applications.length ? <Empty title="No applications found" description="Try searching for another company or role."/> :
              results.data.applications.map(application => <div className="list-entry" key={application.id}>
                <strong>{application.title || 'Application'}</strong><p>{application.companyName} · {statusLabel(application.status)}</p>
                <Link to={`/applications?application=${application.id}`}>Open application</Link>
              </div>)}
          </Card>
        </div>}
  </>;
}
