import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Check, ChevronDown } from 'lucide-react';
import { api } from '../lib/api';
import type { JobSummary, ResumeSummary } from '../lib/api-types';
import type { CursorResult } from '../lib/useCursorPage';

export type RecordOption = { id: string; label: string; hint?: string };
type Resource = 'jobs' | 'resumes';

export function jobOption(job: Pick<JobSummary, 'id' | 'companyName' | 'title'> & Partial<Pick<JobSummary, 'location' | 'createdAt'>>): RecordOption {
  const hint = [job.location, job.createdAt && new Date(job.createdAt).toLocaleString()].filter(Boolean).join(' · ');
  return { id: job.id, label: [job.companyName, job.title].filter(Boolean).join(' — ') || 'Saved job', hint };
}

function resumeOption(resume: ResumeSummary): RecordOption {
  const hint = [resume.ready ? 'Ready to use' : 'Needs review', new Date(resume.createdAt).toLocaleString()].join(' · ');
  return { id: resume.id, label: resume.title, hint };
}

type Props = {
  resource: Resource;
  label: string;
  value: RecordOption | null;
  onChange: (option: RecordOption | null) => void;
  disabled?: boolean;
  defaultToFirst?: boolean;
  clearLabel?: string;
  emptyAction?: React.ReactNode;
};

export function RecordPicker({ resource, label, value, onChange, disabled, defaultToFirst, clearLabel, emptyAction }: Props) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [term, setTerm] = useState('');
  const [activeId, setActiveId] = useState<string>();
  const trigger = useRef<HTMLButtonElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const searching = text.trim() !== term;
  const noun = resource === 'jobs' ? 'job' : 'resume';
  const searchLabel = resource === 'jobs' ? 'Search saved jobs' : 'Search resumes';
  const query = useInfiniteQuery({
    queryKey: [resource, 'picker', term],
    initialPageParam: '',
    enabled: open || (!value && (!!defaultToFirst || !!emptyAction) && term === ''),
    queryFn: async ({ pageParam, signal }) => {
      const params = new URLSearchParams();
      if (term) params.set('q', term);
      if (pageParam) params.set('cursor', pageParam);
      const path = '/' + resource + '/page?' + params;
      if (resource === 'jobs') {
        const page = await api<CursorResult<JobSummary>>(path, { signal });
        return { ...page, items: page.items.map(jobOption) };
      }
      const page = await api<CursorResult<ResumeSummary>>(path, { signal });
      return { ...page, items: page.items.map(resumeOption) };
    },
    getNextPageParam: page => page.nextCursor ?? undefined,
  });
  const first = query.data?.pages[0]?.items[0];
  const result = searching ? [] : (query.data?.pages.flatMap(page => page.items) ?? []);
  const choices: RecordOption[] = clearLabel ? [{ id: '', label: clearLabel }, ...result] : result;
  const activeIndex = choices.findIndex(option => option.id === activeId);
  const empty = query.isSuccess && !query.isFetching && !searching && result.length === 0;

  useEffect(() => {
    if (!open || !searching) return;
    const timer = window.setTimeout(() => setTerm(text.trim()), 180);
    return () => window.clearTimeout(timer);
  }, [open, searching, text]);

  useEffect(() => {
    if (defaultToFirst && !value && first && !term) onChange(first);
  }, [defaultToFirst, first, onChange, term, value]);

  useEffect(() => {
    if (open) input.current?.focus();
  }, [open]);

  useEffect(() => {
    if (activeIndex >= 0) list.current?.children[activeIndex]?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

  function close(restoreFocus = false) {
    setOpen(false);
    setText('');
    setTerm('');
    setActiveId(undefined);
    if (restoreFocus) trigger.current?.focus();
  }

  function choose(option: RecordOption) {
    onChange(option.id ? option : null);
    close(true);
  }

  function handleSearchKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      if (activeIndex >= 0) choose(choices[activeIndex]);
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const nextIndex = event.key === 'ArrowDown' ? Math.min(activeIndex + 1, choices.length - 1)
        : activeIndex < 0 ? choices.length - 1 : Math.max(0, activeIndex - 1);
      setActiveId(choices[nextIndex]?.id);
    }
  }

  return <div className="record-picker" onBlur={event => {
    if (open && !event.currentTarget.contains(event.relatedTarget)) close();
  }} onKeyDown={event => {
    if (open && event.key === 'Escape') {
      event.stopPropagation();
      close(true);
    }
  }}>
    <span className="field-label" id={id + '-label'}>{label}</span>
    <button ref={trigger} type="button" className="button picker-trigger" aria-expanded={open} disabled={disabled}
      aria-labelledby={id + '-label ' + id + '-value'} aria-controls={open ? id + '-panel' : undefined}
      onClick={() => open ? close() : setOpen(true)}>
      <span id={id + '-value'}>{value?.label ?? clearLabel ?? (query.isFetching && !open ? 'Loading…' : 'Select a ' + noun)}</span>
      <ChevronDown size={17} aria-hidden="true"/>
    </button>
    {open && <div className="picker-panel" id={id + '-panel'}>
      <input type="search" ref={input} role="combobox" aria-label={searchLabel} aria-expanded={choices.length > 0} aria-autocomplete="list"
        aria-controls={choices.length ? id + '-options' : undefined} aria-activedescendant={activeIndex < 0 ? undefined : id + '-option-' + activeIndex}
        placeholder={resource === 'jobs' ? 'Company or job title' : 'Resume title'} maxLength={120}
        value={text} onChange={event => { setText(event.target.value); setActiveId(undefined); }} onKeyDown={handleSearchKey}/>
      {choices.length > 0 && <ul className="picker-options" role="listbox" id={id + '-options'} aria-label={label + ' options'}
        aria-busy={query.isFetching || searching} ref={list}>
        {choices.map((option, index) => <li key={option.id} id={id + '-option-' + index} role="option"
          aria-selected={option.id === (value?.id ?? '')} className={index === activeIndex ? 'highlighted' : undefined}
          onMouseDown={event => event.preventDefault()} onClick={() => choose(option)}>
          <span>{option.label}{option.hint && <small>{option.hint}</small>}</span>
          {option.id === (value?.id ?? '') && <Check size={16} aria-hidden="true"/>}
        </li>)}
      </ul>}
      <p role="status" className="fineprint">
        {searching || query.isFetching ? 'Loading results…' : empty
          ? term ? 'No matching ' + resource + '.' : 'No saved ' + resource + ' available.'
          : result.length > 0 ? result.length + ' ' + resource + ' shown' : ''}
      </p>
      {query.isError && !searching && <div className="stack">
        <p role="alert">Unable to load {resource}.</p>
        <button type="button" className="button small" onClick={() => {
          input.current?.focus();
          if (query.isFetchNextPageError) void query.fetchNextPage();
          else void query.refetch();
        }}>Retry</button>
      </div>}
      {!searching && query.hasNextPage && !query.isFetchNextPageError && <button type="button" className="button small"
        disabled={query.isFetching} onClick={() => { input.current?.focus(); void query.fetchNextPage(); }}>Load more {resource}</button>}
      {empty && !term && emptyAction}
    </div>}
    {!open && !query.isFetching && query.isSuccess && !first && !term && !value && emptyAction}
  </div>;
}
