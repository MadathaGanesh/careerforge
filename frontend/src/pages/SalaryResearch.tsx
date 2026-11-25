import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ExternalLink, Search, Wallet } from 'lucide-react';
import { Badge, Card, Empty, ErrorNote, Hero, Spinner } from '../components/Shell';
import { api, post } from '../lib/api';
import type { Application, JobDetails, OfferComparison, SalaryObservation } from '../lib/api-types';

const currencies = ['INR', 'USD', 'GBP', 'EUR', 'CAD', 'AUD'] as const;
type Currency = typeof currencies[number];
type PayBasis = 'BASE_PAY' | 'TOTAL_PAY' | 'UNSPECIFIED';
type Criteria = {
  title: string; company: string; location: string;
  experienceFrom: number; experienceTo: number; currency: Currency;
};
type Source = {
  publisher: string; title: string; url: string; excerpt: string; lowAnnual: number | null; highAnnual: number | null;
  payBasis: PayBasis | null;
};
type RelatedSource = Source & {
  roleMatches: boolean; locationMatches: boolean;
  experience: 'WITHIN_RANGE' | 'BROADER' | 'OUTSIDE_RANGE' | 'UNSTATED';
};
type SearchStatus = 'COMPLETE' | 'ACCESS_REJECTED' | 'LIMIT_REACHED' | 'TIMED_OUT' | 'UNAVAILABLE';
type ResearchResult = {
  status: 'AVAILABLE' | 'INSUFFICIENT_EVIDENCE' | 'INCONSISTENT_EVIDENCE' | 'UNCONFIGURED' |
    'SEARCH_ACCESS_REJECTED' | 'SEARCH_LIMIT_REACHED' | 'SEARCH_UNAVAILABLE';
  criteria: Criteria;
  searchedAt: string | null;
  searchesCompleted: number;
  searchesAttempted: number;
  lowAnnual: number | null;
  highAnnual: number | null;
  payBasis: PayBasis | null;
  sourcesUsed: number;
  evidence: (Source & { usedForEstimate: boolean })[];
  relatedSources?: RelatedSource[];
  searchChecks?: { source: string; status: SearchStatus }[];
};

const statusMessages = {
  INSUFFICIENT_EVIDENCE: 'Two independent sources with comparable annual figures are needed for an estimate.',
  INCONSISTENT_EVIDENCE: 'The reported ranges are too far apart to give one estimate. Review the sources below.',
  UNCONFIGURED: 'Salary search needs a SerpApi key on the server.',
  SEARCH_ACCESS_REJECTED: 'SerpApi rejected the configured key or account access. Check your SerpApi account and key.',
  SEARCH_LIMIT_REACHED: 'SerpApi reached a search limit. Check your remaining searches in SerpApi or try again later.',
  SEARCH_UNAVAILABLE: 'The search service is unavailable. Please try again later.',
};
const basisLabels: Record<PayBasis, string> = {
  BASE_PAY: 'Base pay', TOTAL_PAY: 'Total pay / CTC', UNSPECIFIED: 'Pay basis unspecified',
};
const experienceLabels: Record<RelatedSource['experience'], string> = {
  WITHIN_RANGE: '',
  BROADER: 'Broader experience band',
  OUTSIDE_RANGE: 'Different experience band',
  UNSTATED: 'Experience band unconfirmed',
};
const searchLabels: Record<SearchStatus, string> = {
  COMPLETE: 'Completed', ACCESS_REJECTED: 'Access rejected', LIMIT_REACHED: 'Search limit reached',
  TIMED_OUT: 'Timed out', UNAVAILABLE: 'Unavailable',
};

function annualRange(low: number, high: number, currency: Currency) {
  if (currency === 'INR') {
    const lakh = new Intl.NumberFormat('en-IN', {maximumFractionDigits: 2});
    const formattedLow = lakh.format(low / 100_000);
    const formattedHigh = lakh.format(high / 100_000);
    return formattedLow === formattedHigh ? '\u20b9' + formattedLow + ' LPA' : '\u20b9' + formattedLow + '–' + formattedHigh + ' LPA';
  }
  const amount = new Intl.NumberFormat(undefined, {style: 'currency', currency, currencyDisplay: 'code', maximumFractionDigits: 2});
  return low === high ? amount.format(low) + ' / year' : amount.format(low) + '–' + amount.format(high) + ' / year';
}

function publicSource(url: string | null) {
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}

export function Salaries() {
  const [params] = useSearchParams();
  const jobId = params.get('job');
  const applicationId = params.get('application');
  const job = useQuery({
    queryKey: ['job', jobId], queryFn: () => api<JobDetails>('/jobs/' + encodeURIComponent(jobId ?? '')), enabled: !!jobId,
  });
  const application = useQuery({
    queryKey: ['application', applicationId],
    queryFn: () => api<Application>('/applications/' + encodeURIComponent(applicationId ?? '')), enabled: !!applicationId,
  });
  const selected = job.data ?? application.data;
  const offers = useQuery({queryKey: ['offers'], queryFn: () => api<OfferComparison>('/offers/compare')});

  return <>
    <Hero title="Compensation and offers" description="Research annual pay for a role, company and location. Review your saved offers alongside the results."
      action={<Link to={applicationId ? '/applications?application=' + encodeURIComponent(applicationId) : '/applications'} className="button muted">Back to Tracker</Link>}/>
    <ErrorNote error={job.error || application.error}/>
    {selected && <Card className="stack"><h2>{selected.title || 'Selected job'}</h2><p>{selected.companyName}</p>
      <p>Advertised salary: {selected.salaryDisclosed || 'Not disclosed'}</p>
      {application.data?.salaryOffered && <p>Saved offer: {application.data.salaryOffered}</p>}
    </Card>}
    {((jobId && job.isPending) || (applicationId && application.isPending)) ? <Spinner/>
      : <ResearchForm key={(jobId ?? applicationId ?? 'general')} selected={selected}/>}
    <Card className="top-gap">
      <h2>Offer comparison</h2>
      {offers.isLoading ? <Spinner/> : offers.error ? <ErrorNote error={offers.error}/> : offers.data?.offers.length ?
        <ul className="stack">{offers.data.offers.map(offer => <li key={offer.id}>
          <Link to={'/applications?application=' + encodeURIComponent(offer.id)}>{offer.companyName} — {offer.title}</Link>
          {': '}{offer.salaryOffered || 'No offer details saved'}
        </li>)}</ul> :
        <p className="subtext">Applications at the Offer or Accepted stage appear here. Record offer details on the application in Job Tracker.</p>}
    </Card>
    <details className="card form-disclosure top-gap"><summary>Personal salary notes</summary><PersonalSalaryNotes/></details>
  </>;
}

function ResearchForm({selected}: {selected?: JobDetails | Application}) {
  const [title, setTitle] = useState(selected?.title ?? '');
  const [company, setCompany] = useState(selected?.companyName ?? '');
  const [location, setLocation] = useState(selected?.location ?? '');
  const [experienceFrom, setExperienceFrom] = useState('');
  const [experienceTo, setExperienceTo] = useState('');
  const [currency, setCurrency] = useState<Currency>('INR');
  const [result, setResult] = useState<ResearchResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const reversed = experienceFrom !== '' && experienceTo !== '' && Number(experienceFrom) > Number(experienceTo);

  async function search(event: FormEvent) {
    event.preventDefault();
    if (busy || reversed || experienceFrom === '' || experienceTo === '') return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await post<ResearchResult>('/salary-research', {
        title, company, location, experienceFrom: Number(experienceFrom), experienceTo: Number(experienceTo), currency,
      }));
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  return <div className="two-column top-gap">
    <Card>
      <h2>Research salary</h2>
      <p>Search public Google results for AmbitionBox, Glassdoor, Naukri and other publishers.</p>
      <form className="form-grid" onSubmit={search} aria-label="Salary research" aria-busy={busy}>
        <label>Job title<input required maxLength={100} value={title} onChange={event => setTitle(event.target.value)} placeholder="Java Backend Developer"/></label>
        <label>Company<input required maxLength={100} value={company} onChange={event => setCompany(event.target.value)} placeholder="Google"/></label>
        <label>Location<input required maxLength={100} value={location} onChange={event => setLocation(event.target.value)} placeholder="Hyderabad"/></label>
        <label>Currency<select value={currency} onChange={event => setCurrency(event.target.value as Currency)}>
          {currencies.map(code => <option key={code}>{code}</option>)}
        </select></label>
        <label>Experience from (years)<input required type="number" min="0" max="60" step="1" value={experienceFrom} onChange={event => setExperienceFrom(event.target.value)}/></label>
        <label>Experience to (years)<input required type="number" min="0" max="60" step="1" value={experienceTo} onChange={event => setExperienceTo(event.target.value)}
          aria-invalid={reversed} aria-describedby={reversed ? 'experience-range-error' : undefined}/></label>
        {reversed && <p id="experience-range-error" className="wide alert warning">Enter an end year greater than or equal to the start year.</p>}
        <div className="wide"><button className="button primary" disabled={busy || reversed}><Search size={17}/> Search salary sources</button></div>
      </form>
    </Card>
    <Card>
      <h2>Salary estimate</h2>
      <div aria-live="polite">
        {busy ? <p role="status">Searching salary sources…</p> : result ? <MarketResult result={result}/> :
          <Empty title="Ready to research" description="Enter the job title, company, location and experience range to look up public salary evidence."/>}
        <ErrorNote error={error}/>
      </div>
    </Card>
  </div>;
}

function MarketResult({result}: {result: ResearchResult}) {
  const {criteria} = result;
  const related = result.relatedSources ?? [];
  const partial = result.searchesAttempted > 0 && result.searchesCompleted < result.searchesAttempted;
  return <div className="stack">
    <p><strong>{criteria.title} at {criteria.company}</strong> · {criteria.location} · {criteria.experienceFrom}–{criteria.experienceTo} years</p>
    {result.status === 'AVAILABLE' && result.lowAnnual !== null && result.highAnnual !== null ? <>
      <h3>{annualRange(result.lowAnnual, result.highAnnual, criteria.currency)}</h3>
      <p>{result.payBasis && basisLabels[result.payBasis]} · {result.sourcesUsed} independent publishers</p>
      <p className="fineprint">Estimate uses the median of each range endpoint in matching public search excerpts. Check the original sources for dates and pay components.</p>
    </> : result.status !== 'AVAILABLE' && <p className="alert warning">{statusMessages[result.status]}</p>}
    {(result.status === 'UNCONFIGURED' || result.status === 'SEARCH_ACCESS_REJECTED') &&
      <details className="form-disclosure"><summary>Local setup</summary>
        <p>In Docker, set <code>SERPAPI_API_KEY</code> in the root <code>.env</code> file and recreate the backend. In IntelliJ, set it in the backend run configuration and restart Java.</p>
      </details>}
    {partial && <details className="form-disclosure">
      <summary>Search coverage · {result.searchesCompleted} of {result.searchesAttempted} completed</summary>
      <ul>{result.searchChecks?.map(check => <li key={check.source}>{check.source} · {searchLabels[check.status]}</li>)}</ul>
    </details>}
    {result.searchedAt && <p className="fineprint">Searched {new Date(result.searchedAt).toLocaleString()}.</p>}
    {result.status === 'INSUFFICIENT_EVIDENCE' && result.evidence.length === 0 && related.length === 0 &&
      <div className="stack"><p className="fineprint">The completed searches returned no relevant salary pages.</p>
        <a target="_blank" rel="noreferrer" href={'https://www.google.com/search?q=' +
          encodeURIComponent(`${criteria.company} ${criteria.title} ${criteria.location} salary`)}>Search on Google <ExternalLink size={14} aria-hidden="true"/></a>
      </div>}
    {result.evidence.length > 0 && <div className="stack">
      <h3>Matching sources</h3>
      {result.evidence.map(item => <SourceCard key={item.publisher + item.payBasis} source={item}
        currency={criteria.currency} usedForEstimate={item.usedForEstimate}/>)}
    </div>}
    {related.length > 0 && <div className="stack">
      <h3>Related benchmarks and salary pages</h3>
      <p className="fineprint">These links can help with research. Each figure is reported by its source.</p>
      {related.map(item => <SourceCard key={item.url} source={item} currency={criteria.currency}
        scope={[item.roleMatches ? '' : 'Related job title', item.locationMatches ? '' : 'Requested city unconfirmed',
          experienceLabels[item.experience]].filter(Boolean).join(' · ')}/>)}
    </div>}
  </div>;
}

function SourceCard({source, currency, usedForEstimate = false, scope}: {
  source: Source; currency: Currency; usedForEstimate?: boolean; scope?: string;
}) {
  const {lowAnnual, highAnnual, payBasis} = source;
  return <article className="list-entry"><div className="stack">
    <div className="btn-row">
      <a href={publicSource(source.url)} target="_blank" rel="noreferrer">{source.publisher} <ExternalLink size={14} aria-hidden="true"/></a>
      {usedForEstimate && <Badge tone="green">Used in estimate</Badge>}
    </div>
    {lowAnnual !== null && highAnnual !== null &&
      <strong>{annualRange(lowAnnual, highAnnual, currency)}</strong>}
    <small>{[payBasis && basisLabels[payBasis], source.title].filter(Boolean).join(' · ')}</small>
    {scope && <p className="fineprint">{scope}</p>}
    {source.excerpt && <p className="fineprint">{source.excerpt}</p>}
  </div></article>;
}

function PersonalSalaryNotes() {
  const client = useQueryClient();
  const observations = useQuery({queryKey: ['salary-obs'], queryFn: () => api<SalaryObservation[]>('/salary-observations')});
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    setBusy(true);
    setSaved(false);
    setError(null);
    try {
      await post('/salary-observations', {
        ...data, lowValue: Number(data.lowValue), highValue: Number(data.highValue),
        experienceYears: data.experienceYears ? Number(data.experienceYears) : null,
      });
      setSaved(true);
      await client.invalidateQueries({queryKey: ['salary-obs']});
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  return <div className="stack top-gap">
    <p>Keep a private record of figures you collected, such as a written offer. Online estimates use public search evidence.</p>
    <details className="form-disclosure">
      <summary>Add salary note</summary>
      <form className="form-grid" onSubmit={save}>
        <label>Role<input name="role" required maxLength={250}/></label>
        <label>Location<input name="location" required maxLength={250}/></label>
        <label>Low annual compensation<input name="lowValue" required type="number" min="0" step="0.01"/></label>
        <label>High annual compensation<input name="highValue" required type="number" min="0" step="0.01"/></label>
        <label>Currency<input name="currency" required pattern="[A-Za-z]{3}" maxLength={3} defaultValue="INR"/></label>
        <label>Years of experience<input name="experienceYears" type="number" min="0" max="80" step="0.01"/></label>
        <label>Observation date<input name="observedAt" required type="date" defaultValue={new Date().toISOString().slice(0, 10)}/></label>
        <label>Source label<input name="sourceLabel" required maxLength={250}/></label>
        <label>Source URL (optional)<input name="sourceUrl" type="url" maxLength={2048}/></label>
        <div className="wide"><button className="button muted" disabled={busy}><Wallet size={16}/> Save salary note</button></div>
      </form>
    </details>
    <ErrorNote error={error || observations.error}/>
    {saved && <p className="alert success" role="status">Salary note saved.</p>}
    <h3>Saved salary notes</h3>
    {observations.isLoading ? <Spinner/> : observations.data?.length ? <div className="table-scroll">
      <table><thead><tr><th>Role</th><th>Location</th><th>Range</th><th>Source</th><th>Observed</th></tr></thead>
        <tbody>{observations.data.map(item => {
          const source = publicSource(item.sourceUrl);
          return <tr key={item.id}><td>{item.role}</td><td>{item.location}</td><td>{item.currency} {item.lowValue}–{item.highValue}</td>
            <td>{source ? <a href={source} target="_blank" rel="noreferrer">{item.sourceLabel}</a> : item.sourceLabel}</td><td>{item.observedAt}</td></tr>;
        })}</tbody>
      </table>
    </div> : <p className="fineprint">No personal salary observations yet.</p>}
  </div>;
}
