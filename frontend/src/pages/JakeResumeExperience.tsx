import React, {
  useEffect,
  useMemo,
  useState,
} from "react";

import { useQuery } from "@tanstack/react-query";

import {
  Mail,
  Copy,
  ExternalLink,
  ShieldCheck,
  AlertTriangle,
} from "lucide-react";

import {
  api,
  post,
} from "../lib/api";

import {
  Card,
  Spinner,
  ErrorNote,
} from "../components/Shell";

import "./jake-resume.css";

// =====================================================
// TYPES
// =====================================================

type ScoreRow = {
  ats: number;
  recruiter: number;
  hiringManager: number;
  components: Record<string, number>;
};

type Change = {
  reason: string;
  originalText: string;
  tailoredText: string;
  impactNote: string;
  newlyExplicitJdTerms: string[];
};

type Report = {
  rubricVersion: string;
  scoreKind: string;
  original: ScoreRow;
  tailored: ScoreRow;
  deltas: {
    ats: number;
    recruiter: number;
    hiringManager: number;
  };
  topChanges: Change[];
  acceptedEditCount: number;
  pendingEdits: number;
  disclaimer: string;
};

type EmailDraft = {
  to: string;
  suggestedRecipients: string[];
  registeredEmail: string;
  subject: string;
  body: string;
  notice: string;
};

// =====================================================
// SAFE FRONTEND DEBUG LOGGER
// =====================================================

function debugJake(
  event: string,
  details: Record<string, unknown> = {}
) {
  if (import.meta.env.DEV) {
    console.info(
      "[CareerForge:Jake]",
      event,
      details
    );
  }
}

function errorJake(
  event: string,
  error: unknown,
  details: Record<string, unknown> = {}
) {
  if (import.meta.env.DEV) {
    console.error(
      "[CareerForge:Jake]",
      event,
      {
        ...details,
        errorType:
          error instanceof Error
            ? error.name
            : "Unknown",
      }
    );
  }
}

const labels = {
  ats: "ATS formatting & job alignment",
  recruiter: "Recruiter-readiness estimate",
  hiringManager: "Hiring-manager-readiness estimate",
} as const;

// =====================================================
// MAIN COMPONENT
// =====================================================

export default function JakeResumeExperience({
  sessionId,
  revisionKey,
}: {
  sessionId: string;
  revisionKey: string;
}) {
  const [error, setError] = useState<unknown>(null);

  const [recipient, setRecipient] =
    useState("");

  const [subject, setSubject] =
    useState("");

  const [body, setBody] =
    useState("");

  const [draftLoadedFor, setDraftLoadedFor] =
    useState("");

  // ===================================================
  // INITIAL DEBUG MESSAGE
  // ===================================================

  useEffect(() => {
    debugJake("Jake enhancement view opened", {
      sessionId,
      revisionKeyLength: revisionKey.length,
    });
  }, [sessionId, revisionKey]);

  // ===================================================
  // FETCH COMPARISON REPORT
  // ===================================================

  const reportQuery = useQuery({
    queryKey: [
      "jake-report",
      sessionId,
      revisionKey,
    ],

    queryFn: () =>
      api<Report>(
        `/tailoring-sessions/${sessionId}/enhancement-report`
      ),

    retry: false,
  });

  // ===================================================
  // FETCH RECRUITER EMAIL DRAFT
  // ===================================================

  const draftQuery = useQuery({
    queryKey: [
      "jake-email",
      sessionId,
      revisionKey,
    ],

    queryFn: () =>
      api<EmailDraft>(
        `/tailoring-sessions/${sessionId}/recruiter-draft`
      ),

    retry: false,
  });

  // ===================================================
  // POPULATE EDITABLE EMAIL DRAFT
  // ===================================================

  useEffect(() => {
    if (
      draftQuery.data &&
      draftLoadedFor !== sessionId + revisionKey
    ) {
      setRecipient(draftQuery.data.to || "");
      setSubject(draftQuery.data.subject || "");
      setBody(draftQuery.data.body || "");

      setDraftLoadedFor(
        sessionId + revisionKey
      );

      debugJake("Recruiter email draft populated", {
        sessionId,
        suggestedRecipients:
          draftQuery.data.suggestedRecipients.length,
      });
    }
  }, [
    draftQuery.data,
    draftLoadedFor,
    revisionKey,
    sessionId,
  ]);

  // ===================================================
  // LOG COMPARISON RESULTS
  // ===================================================

  useEffect(() => {
    if (reportQuery.isSuccess) {
      debugJake("Enhancement report loaded", {
        sessionId,
        approvedEdits:
          reportQuery.data.acceptedEditCount,
        pendingEdits:
          reportQuery.data.pendingEdits,
        deltas:
          reportQuery.data.deltas,
      });
    }

    if (reportQuery.isError) {
      errorJake(
        "Enhancement report request failed",
        reportQuery.error,
        { sessionId }
      );
    }
  }, [
    sessionId,
    reportQuery.isSuccess,
    reportQuery.isError,
    reportQuery.dataUpdatedAt,
  ]);

  // ===================================================
  // LOG RECRUITER EMAIL REQUEST
  // ===================================================

  useEffect(() => {
    if (draftQuery.isSuccess) {
      debugJake("Recruiter draft loaded", {
        sessionId,
        recipientSuggestions:
          draftQuery.data.suggestedRecipients.length,
      });
    }

    if (draftQuery.isError) {
      errorJake(
        "Recruiter draft request failed",
        draftQuery.error,
        { sessionId }
      );
    }
  }, [
    sessionId,
    draftQuery.isSuccess,
    draftQuery.isError,
    draftQuery.dataUpdatedAt,
  ]);

  // ===================================================
  // VALIDATE EMAIL DRAFT
  // ===================================================

  const canSend = useMemo(() => {
    return (
      /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(
        recipient.trim()
      ) &&
      subject.trim().length > 0 &&
      body.trim().length > 0
    );
  }, [
    recipient,
    subject,
    body,
  ]);

  // ===================================================
  // OPEN DEFAULT EMAIL APPLICATION
  // ===================================================

  const openMail = () => {
    if (!canSend) {
      debugJake("Email draft validation failed");
      return;
    }

    debugJake("Opening default email application", {
      sessionId,
    });

    const href =
      `mailto:${encodeURIComponent(
        recipient.trim()
      )}` +
      `?subject=${encodeURIComponent(subject)}` +
      `&body=${encodeURIComponent(body)}`;

    window.location.href = href;
  };

  // ===================================================
  // OPEN GMAIL
  // ===================================================

  const openGmail = () => {
    if (!canSend) {
      return;
    }

    debugJake("Opening Gmail composer", {
      sessionId,
    });

    const href =
      "https://mail.google.com/mail/?view=cm&fs=1" +
      `&to=${encodeURIComponent(
        recipient.trim()
      )}` +
      `&su=${encodeURIComponent(subject)}` +
      `&body=${encodeURIComponent(body)}`;

    window.open(
      href,
      "_blank",
      "noopener,noreferrer"
    );
  };

  // ===================================================
  // COPY EMAIL DRAFT
  // ===================================================

  const copyDraft = async () => {
    try {
      await navigator.clipboard.writeText(
        `To: ${recipient}\n` +
        `Subject: ${subject}\n\n` +
        body
      );

      debugJake("Recruiter draft copied", {
        sessionId,
      });

    } catch (exception) {
      errorJake(
        "Recruiter draft clipboard failed",
        exception,
        { sessionId }
      );

      setError(
        new Error(
          "Clipboard unavailable; manually copy the email."
        )
      );
    }
  };

  // ===================================================
  // RENDER
  // ===================================================

  return (
    <section
      className="jake-enhancement"
      aria-label="Jake resume enhancements"
    >
      <div className="jake-section-title">
        <div>
          <h2>
            Resume evaluation and outreach
          </h2>

          <p>
            Review the effect of approved changes and prepare a recruiter email.
          </p>
        </div>
      </div>

      {/* ==============================================
          RESUME SCORE COMPARISON
          ============================================== */}

      {reportQuery.isLoading ? (
        <Spinner />
      ) : reportQuery.error ? (
        <ErrorNote error={reportQuery.error} />
      ) : (
        reportQuery.data && (
          <>
            <Card>
              <h3>
                Original resume vs. tailored resume
              </h3>

              <p className="jake-muted">
                Each number is a CareerForge heuristic
                estimate out of 100, not a score from
                an actual employer ATS or hiring panel.
              </p>

              <div className="jake-table-wrap">
                <table className="jake-score-table">
                  <thead>
                    <tr>
                      <th>
                        Evaluation perspective
                      </th>
                      <th>Original</th>
                      <th>Tailored</th>
                      <th>Change</th>
                    </tr>
                  </thead>

                  <tbody>
                    {(
                      Object.keys(labels) as
                        (keyof typeof labels)[]
                    ).map((key) => {
                      const delta =
                        reportQuery.data!.deltas[key];

                      return (
                        <tr key={key}>
                          <td>
                            {labels[key]}
                          </td>

                          <td>
                            {reportQuery.data!
                              .original[key]}/100
                          </td>

                          <td>
                            <strong>
                              {reportQuery.data!
                                .tailored[key]}/100
                            </strong>
                          </td>

                          <td
                            className={
                              delta > 0
                                ? "jake-positive"
                                : delta < 0
                                ? "jake-negative"
                                : ""
                            }
                          >
                            {delta > 0 ? "+" : ""}
                            {delta}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <p className="jake-muted">
                <ShieldCheck
                  size={15}
                  aria-hidden="true"
                />
                {reportQuery.data.disclaimer}
              </p>

              <p className="jake-muted">
                Rubric:{" "}
                {reportQuery.data.rubricVersion}
                {" · "}
                Approved edits:{" "}
                {reportQuery.data.acceptedEditCount}
              </p>
            </Card>

            {/* ======================================
                TOP FIVE RESUME IMPROVEMENTS
                ====================================== */}

            <Card>
              <h3>
                Up to 5 reasons for the changes
              </h3>

              <p className="jake-muted">
                Only real approved edits are included.
                No score increase is invented when
                the rubric detects none.
              </p>

              <ol className="jake-reasons">
                {reportQuery.data.topChanges.map(
                  (change, index) => (
                    <li key={index}>
                      <strong>
                        {change.reason}
                      </strong>

                      {change.originalText && (
                        <p>
                          <b>Before:</b>{" "}
                          {change.originalText}
                        </p>
                      )}

                      {change.tailoredText && (
                        <p>
                          <b>After:</b>{" "}
                          {change.tailoredText}
                        </p>
                      )}

                      {change.newlyExplicitJdTerms
                        .length > 0 && (
                        <p>
                          <b>JD terminology:</b>{" "}
                          {change.newlyExplicitJdTerms
                            .join(", ")}
                        </p>
                      )}

                      <p className="jake-muted">
                        {change.impactNote}
                      </p>
                    </li>
                  )
                )}
              </ol>
            </Card>

          </>
        )
      )}

      {/* ==============================================
          RECRUITER EMAIL DRAFT
          ============================================== */}

      <Card>
        <h3>
          Draft an email to the recruiter
        </h3>

        <p className="jake-muted">
          Recruiter email addresses are suggested
          only when present in the saved JD.
          You remain in control of the recipient
          and message.
        </p>

        {draftQuery.isLoading ? (
          <Spinner />
        ) : draftQuery.error ? (
          <ErrorNote error={draftQuery.error} />
        ) : (
          draftQuery.data && (
            <div className="jake-email-form">

              {draftQuery.data.suggestedRecipients
                .length > 1 && (
                <label>
                  Emails found in JD

                  <select
                    value={recipient}
                    onChange={(event) =>
                      setRecipient(
                        event.target.value
                      )
                    }
                  >
                    <option value="">
                      Select a recruiter email
                    </option>

                    {draftQuery.data
                      .suggestedRecipients.map(
                        (address) => (
                          <option
                            key={address}
                            value={address}
                          >
                            {address}
                          </option>
                        )
                      )}
                  </select>
                </label>
              )}

              <label>
                Recruiter email address

                <input
                  type="email"
                  autoComplete="off"
                  value={recipient}
                  onChange={(event) =>
                    setRecipient(
                      event.target.value
                    )
                  }
                  placeholder="recruiter@company.com"
                />
              </label>

              <label>
                Email subject

                <input
                  value={subject}
                  onChange={(event) =>
                    setSubject(
                      event.target.value
                    )
                  }
                />
              </label>

              <label>
                Email draft

                <textarea
                  rows={12}
                  value={body}
                  onChange={(event) =>
                    setBody(
                      event.target.value
                    )
                  }
                />
              </label>

              <p className="jake-muted">
                <Mail size={15} />
                Registered CareerForge account email:{" "}
                {draftQuery.data.registeredEmail}.
                Your email application may use
                another sending account.
              </p>

              <div className="btn-row">
                <button
                  type="button"
                  className="button primary"
                  disabled={!canSend}
                  onClick={openMail}
                >
                  <Mail size={16} />
                  Open email draft
                </button>

                <button
                  type="button"
                  className="button muted"
                  disabled={!canSend}
                  onClick={openGmail}
                >
                  <ExternalLink size={16} />
                  Open in Gmail
                </button>

                <button
                  type="button"
                  className="button muted"
                  onClick={copyDraft}
                >
                  <Copy size={16} />
                  Copy draft
                </button>
              </div>

              <div className="alert warning">
                <AlertTriangle size={16} />
                These buttons open a draft, not
                send email. Check the sending
                account and attach the tailored
                resume PDF manually before sending.
              </div>

              <ErrorNote error={error} />
            </div>
          )
        )}
      </Card>
    </section>
  );
}

