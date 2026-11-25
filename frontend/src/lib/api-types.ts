import type { FitAssessment, JobExtraction, ResumeExtraction } from './contracts.generated';

export interface SessionUser {
  id: string;
  email: string;
  fullName: string;
  emailVerified: boolean;
}

export type Session = { authenticated: false } | { authenticated: true; user: SessionUser };

export interface Account extends SessionUser {
  hasPassword: boolean;
  hasGoogle: boolean;
  profile: {
    headline?: string;
    location?: string;
    yearsExperience?: number;
    skills?: string[];
    targetRoles?: string[];
  };
}

export interface ResumeSummary {
  id: string;
  title: string;
  versions: number;
  ready: boolean;
  createdAt: string;
}

export interface ResumeCreated {
  resumeId: string;
  versionId: string;
}

export interface ResumeDetails {
  id: string;
  originalText: string;
  structuredJson: Partial<ResumeExtraction> | null;
}

export interface ResumeVersion {
  id: string;
  versionNumber: number;
  parentVersionId: string | null;
  sourceJobId?: string | null;
  contentText: string;
  contentJson: Partial<ResumeExtraction> | null;
  createdAt: string;
  confirmedAt: string | null;
}

export interface JobSummary {
  id: string;
  title: string | null;
  companyName: string | null;
  location: string | null;
  createdAt: string;
}

export interface JobDetails extends JobSummary {
  rawJd: string;
  salaryDisclosed: string | null;
  jobUrl: string | null;
  extractedJson: JobExtraction;
}

export interface JobCreated {
  jobId: string;
  structuredJd: JobExtraction;
}

type FitRequirement = FitAssessment['requirements'][number];
type StoredFit = Omit<Partial<FitAssessment>, 'requirements' | 'rubricVersion'> & {
  rubricVersion?: string;
  requirements?: (Omit<FitRequirement, 'evidence' | 'status'> & {
    evidence: FitRequirement['evidence'] | string | null;
    status: FitRequirement['status'] | 'NOT_EVIDENCED';
  })[];
};

interface TaskStatus {
  status: 'QUEUED' | 'RUNNING' | 'FAILED' | 'SUCCEEDED';
  aiJobId: string | null;
  attempts: number | null;
}

export interface AnalysisDetails extends TaskStatus {
  jobId: string;
  score: number | null;
  resultJson: StoredFit | null;
}

export interface Proposal {
  id: string;
  decision: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  originalText: string;
  proposedText: string;
  reason: string;
  evidence: string | null;
  risk: string;
  revision: number;
}

export interface TailoringDetails extends TaskStatus {
  jobId: string;
  analysisId: string;
  sourceVersionId: string;
  proposals: Proposal[];
}

export interface ExportSummary {
  id: string;
  sessionId?: string | null;
  jobId?: string | null;
  jobTitle?: string | null;
  companyName?: string | null;
  versionNumber?: number;
  format: 'PDF' | 'DOCX' | 'TEX';
  downloadUrl: string;
  filename: string;
  createdAt: string;
}

export interface DashboardSummary {
  jobs: number;
  recentApplications: Pick<Application, 'id' | 'status' | 'title' | 'companyName'>[];
  recentTailoring: { id: string; title: string | null; companyName: string | null } | null;
  resumes: number;
  analyses: number;
  applications: number;
  interviewSessions: number;
  mode: string;
  recentAnalyses: { id: string; title: string | null; companyName: string | null; score: number | null; status: string }[];
  reminders: { id: string; title: string; dueAt: string }[];
}

export interface InsightsSummary {
  applicationStages: { status: string; count: number }[];
  analysisScores: { id: string; createdAt: string; score: number | null }[];
  disclaimer: string;
}

interface Offer {
  id: string;
  title: string | null;
  companyName: string | null;
  salaryOffered: string;
}

export interface Application extends Offer {
  jobId: string;
  salaryDisclosed?: string | null;
  status: string;
  location: string | null;
  notes: string;
}

export interface OfferComparison {
  offers: Offer[];
  disclaimer: string;
}

export interface SalaryObservation {
  id: string;
  role: string;
  location: string;
  currency: string;
  lowValue: number;
  highValue: number;
  sourceLabel: string;
  sourceUrl: string | null;
  observedAt: string;
}

export type SalaryEstimate = { dataScope: string; currency: string } & (
  { status: 'AVAILABLE'; disclaimer: string; lowEstimate: number; highEstimate: number } |
  { status: 'INSUFFICIENT_DATA'; message: string }
);
