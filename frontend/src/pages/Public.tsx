import React, { useEffect, useId, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { GoogleIdentityButton, useGoogleOptions } from "../components/GoogleIdentityButton";
import { useCompleteSignIn } from "../lib/session";
import { ArrowRight, CalendarCheck, Files, ScanText, ShieldCheck, Eye, EyeOff } from "lucide-react";

import { post } from "../lib/api";
import { Badge, Card, FitGauge, Logo, ErrorNote } from "../components/Shell";

function PublicNav({ landing = false }: {landing?: boolean}) {
  return <header className="public-nav">
    <Logo/>
    <nav aria-label="Public navigation">
      {landing && <a className="public-guide" href="#process">How it works</a>}
      <Link to="/login">Sign in</Link><Link to="/register">Create account</Link>
    </nav>
  </header>;
}

export function Landing() {
  return <>
    <PublicNav landing/>
    <main className="public-wrap">
      <section className="landing-hero">
        <div>
          <span className="eyebrow">Job search workspace</span>
          <h1>Give every application a stronger start.</h1>
          <p>See how your resume matches a role, tailor it with evidence, and keep interviews and follow-ups together.</p>
          <div className="btn-row">
            <Link to="/register" className="button lime"><ArrowRight size={17}/> Create my account</Link>
            <a className="text-button" href="#process">Explore the workflow <ArrowRight size={15}/></a>
          </div>
        </div>
        <div className="landing-preview">
          <div className="preview-header"><span>Job fit overview</span><span>Example analysis</span></div>
          <div className="preview-role"><div><small>Northstar Labs</small><h2>Backend Engineer</h2><p>Reviewed against your backend resume</p></div><FitGauge score={84}/></div>
          <div className="preview-evidence"><strong>Build Java and Spring Boot APIs</strong><Badge tone="green">Matched</Badge></div>
          <div className="preview-evidence"><strong>Operate workloads on Kubernetes</strong><Badge tone="orange">Gap</Badge></div>
        </div>
      </section>
      <section id="process" className="feature-grid" aria-label="How CareerForge works">
        {[
          [Files, 'Review your resume', 'Confirm your skills and experience once, then reuse the version you trust.'],
          [ScanText, 'Check fit and tailor', 'See the evidence behind each match, then approve the edits you want.'],
          [CalendarCheck, 'Track and prepare', 'Plan the next follow-up or practice questions for a saved job.'],
        ].map(([Icon, title, description]) => {
          const FeatureIcon = Icon as typeof Files;
          return <Card key={String(title)}><div className="feature-icon"><FeatureIcon size={19}/></div><h3>{String(title)}</h3><p>{String(description)}</p></Card>;
        })}
      </section>
      <footer className="landing-footer"><small>CareerForge</small><small>Fit estimates do not predict hiring outcomes.</small></footer>
    </main>
  </>;
}

function AuthFrame({ children, title, description }: {children: React.ReactNode; title: string; description: string}) {
  return <>
    <PublicNav/>
    <main className="auth-page">
      <aside className="auth-brand">
        <div><h2>Your next role starts here</h2><p>Keep each job, tailored resume and follow-up together in your CareerForge workspace.</p></div>
        <div><Files size={24}/><h3>Your work stays in one place</h3><p>Saved jobs, reviewed resumes and interview notes.</p></div>
      </aside>
      <section className="auth-panel"><div className="auth-content">
        <div><h1>{title}</h1><p>{description}</p></div>{children}
        <div className="auth-trust"><ShieldCheck size={17}/> Your resume belongs to you.</div>
      </div></section>
    </main>
  </>;
}

type PasswordFieldProps = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete?: string;
  minLength?: number;
};

function PasswordField({
  label,
  value,
  onChange,
  autoComplete = "current-password",
  minLength,
}: PasswordFieldProps) {
  const id = useId();
  const [visible, setVisible] = useState(false);

  return (
    <div className="form-stack password-field">
      <label htmlFor={id}>{label}</label>

      <span
        style={{
          display: "block",
          position: "relative",
        }}
      >
        <input
          id={id}
          required
          type={visible ? "text" : "password"}
          value={value}
          autoComplete={autoComplete}
          minLength={minLength}
          onChange={(event) =>
            onChange(event.target.value)
          }
          style={{ paddingRight: 48 }}
        />

        <button
          type="button"
          onClick={() =>
            setVisible((previous) => !previous)
          }
          aria-label={
            visible ? "Hide password" : "Show password"
          }
          aria-pressed={visible}
          title={
            visible ? "Hide password" : "Show password"
          }
          style={{
            position: "absolute",
            right: 10,
            top: "50%",
            transform: "translateY(-50%)",
            background: "transparent",
            border: 0,
            padding: 7,
            color: "#64748b",
            display: "grid",
            placeItems: "center",
            cursor: "pointer",
          }}
        >
          {visible ? (
            <EyeOff size={19} />
          ) : (
            <Eye size={19} />
          )}
        </button>
      </span>
    </div>
  );
}

// ======================================================
// GOOGLE LOGIN / REGISTRATION
// ======================================================

function GoogleAuthButton({ setError, register = false }: {
  setError: (error: unknown) => void;
  register?: boolean;
}) {
  const loginSuccess = useCompleteSignIn();
  const options = useGoogleOptions();
  const clientId = options.data?.googleClientId;
  const [busy, setBusy] = useState(false);

  async function signIn(credential?: string) {
    if (busy) return;
    if (!credential) {
      setError(new Error("Google did not return a credential."));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await post("/auth/google", { credential });
      await loginSuccess();
    } catch (error) {
      setError(error);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="google-button" aria-busy={busy || options.isPending}>
      {clientId ? <GoogleIdentityButton clientId={clientId}
        text={register ? 'signup_with' : 'signin_with'} onSuccess={signIn}
        onError={() => setError(new Error('Google sign-in failed. Please try again.'))}/>
        : <p className="fineprint" role="status">{options.isPending
          ? 'Checking sign-in options…'
          : options.isError ? 'Google sign-in could not load. You can still use email.'
            : 'Google sign-in is unavailable on this installation.'}</p>}
      {busy && <p role="status">Signing in with Google…</p>}
    </div>
    {clientId && <div className="separator">{register ? 'or create an account with email' : 'or sign in with email'}</div>}
  </>;
}

// ======================================================
// LOGIN PAGE
// ======================================================

export function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const loginSuccess = useCompleteSignIn();

  const handleSubmit = async (
    event: React.FormEvent
  ) => {
    event.preventDefault();

    setBusy(true);
    setError(null);

    try {
      await post("/auth/login", {
        email,
        password,
      });

      await loginSuccess();
    } catch (error) {
      console.error("Email login failed:", error);
      setError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame
      title="Sign in to CareerForge AI"
      description="Continue your career journey with a private workspace."
    >
      <GoogleAuthButton setError={setError} />

      <form
        onSubmit={handleSubmit}
        className="form-stack"
      >
        <label>
          Email address

          <input
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(event) =>
              setEmail(event.target.value)
            }
          />
        </label>

        <PasswordField
          label="Password"
          value={password}
          onChange={setPassword}
        />

        <div className="form-row end">
          <Link to="/forgot-password">
            Forgot password?
          </Link>
        </div>

        <ErrorNote error={error} />

        <button
          className="button primary full"
          type="submit"
          disabled={busy}
        >
          {busy ? "Signing in..." : "Sign In"}
          <ArrowRight size={18} />
        </button>
      </form>

      {import.meta.env.VITE_DEMO_LOGIN === "true" && (
        <button
          className="button muted full"
          type="button"
          onClick={async () => {
            try {
              setError(null);

              await post("/auth/dev", {
                email: "demo@example.test",
              });

              await loginSuccess();
            } catch (error) {
              setError(error);
            }
          }}
        >
          Local Development Demo Login
        </button>
      )}

      <p className="below-form">
        <Link to="/verify-email">Verify your email</Link> · Don't have an account?{" "}
        <Link to="/register">
          Create account
        </Link>
      </p>
    </AuthFrame>
  );
}

// ======================================================
// REGISTRATION PAGE
// ======================================================

export function Register() {
  const navigate = useNavigate();
  const [fields, setFields] = useState({
    name: "",
    email: "",
    password: "",
  });

  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (
    event: React.FormEvent
  ) => {
    event.preventDefault();

    setBusy(true);
    setError(null);

    try {
      await post("/auth/register", fields);
      navigate("/verify-email", { state: { email: fields.email } });
    } catch (error) {
      console.error(
        "Account registration failed:",
        error
      );

      setError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame
      title="Create your CareerForge account"
      description="Start building an evidence-backed career profile."
    >
      <GoogleAuthButton setError={setError} register />

      <form
        className="form-stack"
        onSubmit={handleSubmit}
      >
        <label>
          Full name

          <input
            required
            autoComplete="name"
            value={fields.name}
            onChange={(event) =>
              setFields({
                ...fields,
                name: event.target.value,
              })
            }
          />
        </label>

        <label>
          Email address

          <input
            type="email"
            required
            autoComplete="email"
            value={fields.email}
            onChange={(event) =>
              setFields({
                ...fields,
                email: event.target.value,
              })
            }
          />
        </label>

        <PasswordField
          label="Password (12+ characters)"
          autoComplete="new-password"
          minLength={12}
          value={fields.password}
          onChange={(password) =>
            setFields({
              ...fields,
              password,
            })
          }
        />

        <ErrorNote error={error} />

        <button
          type="submit"
          className="button primary full"
          disabled={busy}
        >
          {busy
            ? "Creating account..."
            : "Create Account"}
        </button>
      </form>

      <p className="below-form">
        Already registered?{" "}
        <Link to="/login">
          Sign in
        </Link>
      </p>
    </AuthFrame>
  );
}

// ======================================================
// EMAIL VERIFICATION PAGE
// ======================================================

export function VerifyEmail() {
  const location = useLocation();
  const [email, setEmail] = useState<string>(location.state?.email ?? "");
  const [code, setCode] = useState("");
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    if (!token) return;
    window.history.replaceState(null, "", "/verify-email");
    setBusy(true);
    post("/auth/verify-email", { token }).then(() => setVerified(true))
      .catch(setError).finally(() => setBusy(false));
  }, []);

  async function submit(action: "verify-email" | "resend-verification") {
    setError(null);
    setMessage("");
    setBusy(true);
    try {
      const result = await post<{message?: string}>(`/auth/${action}`, { email, code });
      if (action === "verify-email") setVerified(true);
      else setMessage(result.message ?? "");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return <AuthFrame title="Verify your email" description={email ? `Enter the six-digit code sent to ${email}.` : "Enter the six-digit code sent to your email address."}>
    {verified ? <div className="form-stack">
      <p className="alert success" role="status">Email verified. You can now sign in.</p>
      <Link className="button primary full" to="/login">Continue to Sign In</Link>
    </div> : <form className="form-stack" onSubmit={event => { event.preventDefault(); void submit("verify-email"); }}>
      <label>Email address<input type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)}/></label>
      <label>Verification code<input inputMode="numeric" autoComplete="one-time-code" required pattern="[0-9]{6}" maxLength={6} value={code} onChange={e => setCode(e.target.value)}/></label>
      <ErrorNote error={error}/>
      {message && <p role="status">{message}</p>}
      <button className="button primary full" disabled={busy}>Verify email</button>
      <button type="button" className="button muted" disabled={busy || !email} onClick={() => void submit("resend-verification")}>Send a new code</button>
      <Link to="/login">Return to Sign In</Link>
    </form>}
  </AuthFrame>;
}

// ======================================================
// PASSWORD RECOVERY PAGE
// ======================================================

export function Recover() {
  const [email, setEmail] = useState("");
  const [token, setToken] = useState("");
  const [password, setPassword] = useState("");
  const [result, setResult] = useState("");
  const [error, setError] = useState<unknown>(null);

  const sendResetEmail = async () => {
    try {
      setError(null);
      setResult("");

      const response = await post<{
        message: string;
      }>("/auth/forgot-password", {
        email,
      });

      setResult(response.message);
    } catch (error) {
      setError(error);
    }
  };

  const resetPassword = async () => {
    try {
      setError(null);

      await post("/auth/reset-password", {
        token,
        password,
      });

      setResult(
        "Password reset successfully. You can sign in."
      );
    } catch (error) {
      setError(error);
    }
  };

  return (
    <AuthFrame
      title="Password recovery"
      description="Recover access to your CareerForge AI account."
    >
      <div className="form-stack">
        <label>
          Email address

          <input
            type="email"
            value={email}
            onChange={(event) =>
              setEmail(event.target.value)
            }
          />
        </label>

        <button
          type="button"
          className="button muted"
          disabled={!email}
          onClick={sendResetEmail}
        >
          Send Password Reset Instructions
        </button>

        <hr />

        <label>
          Password Reset Token

          <input
            value={token}
            onChange={(event) =>
              setToken(event.target.value)
            }
            placeholder="Enter reset token"
          />
        </label>

        <PasswordField
          label="New Password (12+ characters)"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
          minLength={12}
        />

        <button
          type="button"
          className="button primary"
          disabled={!token || Array.from(password).length < 12}
          onClick={resetPassword}
        >
          Reset Password
        </button>

        <ErrorNote error={error} />

        {result && (
          <div
            className="alert success"
            role="status"
          >
            {result}
          </div>
        )}

        <Link to="/login">
          Return to Sign In
        </Link>
      </div>
    </AuthFrame>
  );
}

