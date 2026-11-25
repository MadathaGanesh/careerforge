
import type { JobExtraction } from '../lib/contracts.generated';

type Requirement = JobExtraction['requirements'][number];

export function JobRequirements({ json, onChange }: {json: string; onChange: (json: string) => void}) {
  let structured: Record<string, unknown> = {};
  try {
    structured = JSON.parse(json);
  } catch {
    // The advanced editor accepts incomplete JSON while the user types.
  }
  const requirements = Array.isArray(structured?.requirements)
    ? structured.requirements.filter(entry => entry && typeof entry === 'object') as Requirement[]
    : [];
  function change(index: number, update: Partial<Requirement>) {
    write(requirements.map((r, i) => i === index ? {...r, ...update} : r));
  }
  function write(rows: Requirement[]) {
    onChange(JSON.stringify({...structured, requirements: rows}, null, 2));
  }
  return <>
    {requirements.map((row, i) => <fieldset key={row.id ?? i} className="form-stack">
      <legend>Requirement {i + 1}</legend>
      <label>Skill or qualification<input value={row.label} maxLength={250} onChange={e => change(i, {label: e.target.value})}/></label>
      <label>Priority<select value={row.priority} onChange={e => change(i, {priority: e.target.value === 'PREFERRED' ? 'PREFERRED' : 'MANDATORY'})}>
        <option value="MANDATORY">Mandatory</option><option value="PREFERRED">Preferred</option>
      </select></label>
      <label>Category<select value={row.category ?? ''} onChange={e => change(i, {
        category: (e.target.value || undefined) as Requirement['category'],
      })}>
        <option value="">Use priority and text</option><option value="MANDATORY">Mandatory skills</option>
        <option value="EXPERIENCE">Experience</option><option value="RESPONSIBILITIES">Responsibilities</option>
        <option value="PREFERRED">Preferred skills</option><option value="EDUCATION">Education and certifications</option>
      </select></label>
      <label>Exact quote from job description<input value={row.sourceQuote} maxLength={2000} onChange={e => change(i, {sourceQuote: e.target.value})}/></label>
      <button type="button" className="button muted small" onClick={() => write(requirements.filter((_, j) => i !== j))}>Remove requirement</button>
    </fieldset>)}
    <button type="button" className="button muted small" disabled={requirements.length >= 200} onClick={() => write([...requirements, {
      id: crypto.randomUUID(), label: '', priority: 'MANDATORY', sourceQuote: '',
    }])}>Add requirement</button>
  </>;
}
