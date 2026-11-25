
import React from "react";

import {
  Routes,
  Route,
  Navigate,
  useLocation,
} from "react-router-dom";

import { useQuery } from "@tanstack/react-query";

import { sessionQuery } from "./lib/session";

import {
  Notifications,
  Shell,
  Spinner,
} from "./components/Shell";

// =====================================================
// EXISTING PUBLIC PAGES
// =====================================================

import {
  Landing,
  Login,
  Register,
  Recover,
  VerifyEmail,
} from "./pages/Public";

// =====================================================
// EXISTING DASHBOARD AND PROFILE PAGES
// =====================================================

import {
  Dashboard,
  Onboarding,
  Insights,
} from "./pages/General";

// =====================================================
// EXISTING RESUME AND JOB PAGES
// =====================================================

import {
  Resumes,
  ResumeReview,
  ResumeVersions,
  Jobs,
  JobAnalysis,
  AnalysisDetail,
  Tailoring,
  Exports,
} from "./pages/ResumeJobs";

// =====================================================
// EXISTING WORKSPACE PAGES
// =====================================================

import {
  Interviews,
  Applications,
  Salaries,
} from "./pages/Workspace";

// =====================================================
// NEW SEARCH AND JOB DETAILS PAGES
// =====================================================

import SearchPage from "./pages/SearchPage";
import { MemoryPage } from "./pages/CareerMemory";
import SettingsPage from "./pages/AccountSettings";
import JobDetail from "./pages/JobDetail";

// =====================================================
// AUTHENTICATION-PROTECTED ROUTE
// =====================================================

function Protected({
  children,
}: {
  children: React.ReactNode;
}) {
  const location = useLocation();

  // Check backend session using existing API.
  const { data, isLoading, isError } = useQuery(sessionQuery);

  // Display loading indicator while checking session.
  if (isLoading) {
    return <Spinner />;
  }

  // Do not confuse server errors with logged-out sessions.
  if (isError) {
    return (
      <div
        className="alert error"
        role="alert"
        style={{ margin: 24 }}
      >
        Unable to verify your session.
        Please check your backend connection and refresh.
      </div>
    );
  }

  // Redirect unauthenticated users to login.
  if (!data?.authenticated) {
    return (
      <Navigate
        to="/login"
        replace
        state={{
          from:
            location.pathname +
            location.search + location.hash,
        }}
      />
    );
  }

  // Render protected pages inside CareerForge layout.
  return (
    <Shell user={data.user}>
      {children}
    </Shell>
  );
}

// =====================================================
// MAIN CAREERFORGE APPLICATION ROUTES
// =====================================================

export default function App() {
  return (
    <Routes>

      {/* =============================================
          PUBLIC ROUTES
          ============================================= */}

      <Route
        path="/"
        element={<Landing />}
      />

      <Route
        path="/login"
        element={<Login />}
      />

      <Route
        path="/register"
        element={<Register />}
      />

      <Route
        path="/forgot-password"
        element={<Recover />}
      />

      {/* Existing clickable email verification */}
      <Route
        path="/verify-email"
        element={<VerifyEmail />}
      />

      {/* =============================================
          DASHBOARD
          ============================================= */}

      <Route
        path="/dashboard"
        element={
          <Protected>
            <Dashboard />
          </Protected>
        }
      />

      {/* =============================================
          CAREER PROFILE / ONBOARDING
          ============================================= */}

      <Route
        path="/onboarding"
        element={
          <Protected>
            <Onboarding />
          </Protected>
        }
      />

      {/* =============================================
          RESUME MANAGEMENT
          ============================================= */}

      <Route
        path="/resumes"
        element={
          <Protected>
            <Resumes />
          </Protected>
        }
      />

      <Route
        path="/resumes/:id/review"
        element={
          <Protected>
            <ResumeReview />
          </Protected>
        }
      />

      <Route
        path="/resumes/:id/versions"
        element={
          <Protected>
            <ResumeVersions />
          </Protected>
        }
      />

      {/* =============================================
          JOB MANAGEMENT AND ANALYSIS
          ============================================= */}

      <Route
        path="/jobs"
        element={
          <Protected>
            <Jobs />
          </Protected>
        }
      />

      <Route
        path="/jobs/new"
        element={
          <Protected>
            <JobAnalysis />
          </Protected>
        }
      />

      <Route
        path="/analyses/:id"
        element={
          <Protected>
            <AnalysisDetail />
          </Protected>
        }
      />

      {/* =============================================
          NEW GLOBAL SEARCH
          ============================================= */}

      <Route
        path="/search"
        element={
          <Protected>
            <SearchPage />
          </Protected>
        }
      />

      {/* =============================================
          NEW SAVED JOB DETAILS
          ============================================= */}

      <Route
        path="/jobs/:id"
        element={
          <Protected>
            <JobDetail />
          </Protected>
        }
      />

      {/* =============================================
          RESUME STUDIO AND EXPORTS
          ============================================= */}

      <Route
        path="/tailoring"
        element={
          <Protected>
            <Exports />
          </Protected>
        }
      />

      <Route
        path="/tailoring/:id"
        element={
          <Protected>
            <Tailoring />
          </Protected>
        }
      />

      {/* =============================================
          INTERVIEW LAB
          ============================================= */}

      <Route
        path="/interviews"
        element={
          <Protected>
            <Interviews />
          </Protected>
        }
      />

      {/* =============================================
          JOB APPLICATION TRACKER
          ============================================= */}

      <Route
        path="/applications"
        element={
          <Protected>
            <Applications />
          </Protected>
        }
      />

      {/* =============================================
          SALARY INTELLIGENCE
          ============================================= */}

      <Route
        path="/salaries"
        element={
          <Protected>
            <Salaries />
          </Protected>
        }
      />

      {/* =============================================
          CAREER ANALYTICS
          ============================================= */}

      <Route
        path="/insights"
        element={
          <Protected>
            <Insights />
          </Protected>
        }
      />

      {/* =============================================
          CAREER MEMORY
          ============================================= */}

      <Route
        path="/career-memory"
        element={
          <Protected>
            <MemoryPage />
          </Protected>
        }
      />

      {/* =============================================
          ACCOUNT SETTINGS
          ============================================= */}

      <Route
        path="/settings"
        element={
          <Protected>
            <SettingsPage />
          </Protected>
        }
      />

      {/* =============================================
          UNKNOWN ROUTES
          ============================================= */}

      <Route path="/notifications" element={<Protected><Notifications /></Protected>} />
      <Route
        path="*"
        element={
          <Navigate
            to="/"
            replace
          />
        }
      />

    </Routes>
  );
}

