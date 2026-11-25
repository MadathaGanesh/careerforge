"""Run against disposable CareerForge services with development login and synthetic mail only."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from uuid import uuid4
import hashlib
import hmac
import json
import os
import re
import time
import zipfile

import requests

API = os.environ.get("CF_TEST_API", "http://localhost:8080")
ORIGIN = os.environ.get("CF_TEST_ORIGIN", "http://localhost:5173")
MAILPIT = os.environ.get("CF_TEST_MAILPIT", "http://localhost:8025")
WEBHOOK_SECRET = os.environ.get("CF_TEST_WEBHOOK_SECRET", "")
JAKE_REQUIRED = os.environ.get("CF_TEST_REQUIRE_JAKE", "true") == "true"
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
RESULTS = []
SUFFIX = uuid4().hex[:10]


def check(name, ok, detail=None):
    RESULTS.append({"name": name, "passed": bool(ok), "detail": detail})
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f" ({detail})" if detail is not None else ""), flush=True)


def call(user, method, path, data=None, expected=200, name=None, csrf=True, key=None, files=None):
    headers = {"Origin": ORIGIN}
    if csrf and user.cookies.get("CF_CSRF"):
        headers["X-CSRF-Token"] = user.cookies["CF_CSRF"]
    if key:
        headers["Idempotency-Key"] = key
    url = API + (path if path.startswith("/api/v1/") else "/api/v1" + path)
    response = user.request(method, url, headers=headers, json=data, files=files, timeout=45)
    if expected is not None:
        check(name or f"{method} {path.split('?')[0]}", response.status_code == expected, response.status_code)
        if 200 <= expected < 300 and response.status_code != expected:
            raise AssertionError(f"{method} {path} returned {response.status_code}: {response.text[:400]}")
    return response


def obj(*args, **kwargs):
    return call(*args, **kwargs).json()


def await_value(fn, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError("Timed out waiting for expected state")


def mail_text(address, subject):
    def find():
        inbox = requests.get(MAILPIT + "/api/v1/messages", timeout=3).json().get("messages", [])
        for message in inbox:
            if any(target.get("Address") == address for target in message.get("To", [])) and subject in message["Subject"]:
                return requests.get(MAILPIT + "/api/v1/message/" + message["ID"], timeout=3).json()["Text"]
        return None
    return await_value(find, 15)


def otp(email):
    return re.search(r"\b[0-9]{6}\b", mail_text(email, "verification code")).group()


def create_dev(tag):
    user = requests.Session()
    obj(user, "POST", "/auth/dev", {"email": f"{tag}-{SUFFIX}@example.test"})
    return user


def review(user, original, skills=None):
    structured = deepcopy(original["structuredContent"])
    if skills is not None:
        structured["skills"] = skills
    return obj(user, "PUT", f"/resume-versions/{original['versionId']}/structured-content", {
        "confirm": True, "structuredContent": structured,
    })["versionId"]


def wait_for_task(user, path):
    def terminal():
        data = obj(user, "GET", path, expected=None)
        return data if data.get("status") in {"SUCCEEDED", "FAILED"} else None
    return await_value(terminal)


def main():
    user = requests.Session()
    anonymous = requests.Session()
    email = f"careerforge-{SUFFIX}@example.test"
    password = "  " + "長" * 73 + "  "
    obj(user, "POST", "/auth/register", {"email": email, "name": "Sample Candidate", "password": password})
    call(user, "POST", "/auth/login", {"email": email, "password": password}, 403, "Unverified password login blocked")
    call(user, "POST", "/auth/login", {"email": email, "password": 123456789012}, 400,
         "Numeric passwords are rejected instead of coerced")
    code = otp(email)
    obj(user, "POST", "/auth/verify-email", {"email": email, "code": code})
    call(user, "POST", "/auth/verify-email", {"email": email, "code": code}, 401, "OTP is single-use")
    obj(user, "POST", "/auth/login", {"email": email, "password": password}, name="Unicode password retains leading and trailing spaces")
    call(anonymous, "POST", "/auth/login", {"email": email, "password": password.strip()}, 401, "Trimmed password differs")
    account = obj(user, "GET", "/users/me")
    owner = account["id"]
    check("Password sign-in method displayed", account["hasPassword"] and not account["hasGoogle"])
    call(anonymous, "GET", "/resumes", expected=401)
    call(user, "POST", "/resumes/text", {"title": "invalid", "pastedText": "short"}, 403, "CSRF required", csrf=False)
    call(user, "POST", "/resumes/text", {"title": "invalid", "pastedText": "short"}, 422)
    call(user, "PUT", "/users/me/career-memory-preference", {"enabled": "true"}, 400,
         "Career Memory consent requires a JSON boolean")
    call(user, "PUT", "/users/me/profile", {"yearsExperience": 81}, 422, "Invalid career experience rejected")
    obj(user, "PUT", "/users/me/career-memory-preference", {"enabled": True})
    obj(user, "PUT", "/users/me/profile", {"headline": "Engineer", "yearsExperience": 3, "skills": ["Java"], "targetRoles": ["Backend"]})
    check("Profile save preserves Career Memory preference", obj(user, "GET", "/users/me")["profile"]["preferences"]["useCareerMemory"])

    original = (FIXTURES / "sample_resume.txt").read_text()
    jd = (FIXTURES / "sample_job.txt").read_text() + "\nKafka is required. Java is also relevant."
    fresh = obj(user, "POST", "/resumes/text", {"title": "QA resume", "pastedText": original}, 201)
    rid = fresh["resumeId"]
    raw = fresh["versionId"]
    job = obj(user, "POST", "/jobs", {"title": "Engineer A", "companyName": "QA Company", "rawJd": jd}, 201)
    jid = job["jobId"]
    call(user, "POST", "/analyses", {"jobId": jid, "resumeVersionId": raw}, 409, "Draft resume cannot start analysis")
    vid = review(user, fresh, ["Kafka"])
    check("Confirmation creates new version", vid != raw)
    check("Original resume version remains intact", "Skills: Kafka" not in obj(user, "GET", f"/resume-versions/{raw}")["contentText"])
    check("Confirmed resume contains attested correction", "Skills: Kafka" in obj(user, "GET", f"/resume-versions/{vid}")["contentText"])
    requirements = job["structuredJd"]
    requirements["requirements"] = [{"id": "kafka", "label": "Kafka", "priority": "MANDATORY", "sourceQuote": "Kafka is required."}]
    obj(user, "PUT", f"/jobs/{jid}/structured-jd", {"confirm": True, "structuredJd": requirements})
    call(user, "PUT", f"/jobs/{jid}/structured-jd", {"confirm": True, "structuredJd": {**requirements, "requirements": "bad"}}, 422, "Reject malformed corrected JD")
    analysis_input = {"jobId": jid, "resumeVersionId": vid}
    analyze = obj(user, "POST", "/analyses", analysis_input, 202, key="analysis-a")
    duplicate = obj(user, "POST", "/analyses", analysis_input, 202, key="analysis-a")
    check("Analysis request replay returns the same resource", duplicate["analysisId"] == analyze["analysisId"])
    call(user, "POST", "/analyses", {**analysis_input, "resumeVersionId": raw}, 409, "Idempotency key cannot be reused with different input", key="analysis-a")
    requirements["requirements"][0].update(label="Java", sourceQuote="Java is also relevant.")
    obj(user, "PUT", f"/jobs/{jid}/structured-jd", {"confirm": True, "structuredJd": requirements})
    aid = analyze["analysisId"]
    result = wait_for_task(user, f"/analyses/{aid}")
    check("Analysis uses immutable JD snapshot and reviewed resume", result["status"] == "SUCCEEDED" and
          result["score"] == 100 and result["resultJson"]["requirements"][0]["label"] == "Kafka")
    check("Saved job links to its analyses", obj(user, "GET", f"/jobs/{jid}/analyses")[0]["id"] == aid)
    call(user, "POST", f"/ai-jobs/{analyze['aiJobId']}/retry", {}, 409, "Successful job cannot retry")

    tail = obj(user, "POST", "/tailoring-sessions", {"analysisId": aid}, 202, key="tailoring-a")
    tid = tail["sessionId"]
    session_path = f"/tailoring-sessions/{tid}"
    result = wait_for_task(user, session_path)
    check("Tailoring succeeds", result["status"] == "SUCCEEDED")
    edit = result["proposals"][0]
    for endpoint, fmt in [("exports", "PDF"), ("jake-exports", "TEX")]:
        call(user, "POST", f"{session_path}/{endpoint}", {"format": fmt}, 409, "Pending edits block " + endpoint)
    phrase = "QA reviewed applicant profile"
    edit_path = "/resume-edits/" + edit["id"]
    obj(user, "PATCH", edit_path, {"decision": "ACCEPTED", "proposedText": phrase, "revision": edit["revision"]})
    call(user, "PATCH", edit_path, {"decision": "REJECTED", "revision": edit["revision"]}, 409, "Stale revision is rejected")
    obj(user, "GET", f"{session_path}/enhancement-report")
    obj(user, "GET", f"{session_path}/recruiter-draft")

    call(user, "POST", f"{session_path}/exports", {"format": "DOCX"}, 422, "New exports are Jake PDF or TEX")
    tex_export = obj(user, "POST", f"{session_path}/exports", {"format": "TEX"}, 201, key="export-TEX")
    tex = call(user, "GET", tex_export["downloadUrl"]).content
    check("Jake source contains approved wording", phrase.encode() in tex and b"Based on Jake's Resume" in tex)
    replay = obj(user, "POST", f"{session_path}/jake-exports", {"format": "TEX"}, 201, key="export-TEX")
    check("Both export routes replay the same artifact", replay["exportId"] == tex_export["exportId"])
    capability = obj(user, "GET", "/export-capabilities")
    available = capability["jakePdf"]
    check("Jake PDF capability reports a ready renderer or setup guidance",
          (available and not capability.get("jakePdfMessage")) or
          (not available and bool(capability.get("jakePdfMessage"))))
    download_paths = [tex_export["downloadUrl"]]
    if JAKE_REQUIRED or available:
        pdf_export = obj(user, "POST", f"{session_path}/exports", {"format": "PDF"}, 201, key="export-PDF")
        download_paths.append(pdf_export["downloadUrl"])
        pdf = call(user, "GET", pdf_export["downloadUrl"]).content
        check("The main export uses Jake PDF", pdf.startswith(b"%PDF") and "_Jakes_" in pdf_export["filename"])
        call(user, "POST", "/resumes/upload", expected=201, files={"file": ("qa.pdf", pdf)})
    else:
        print("SKIP Jake PDF capability is not enabled for this run", flush=True)
    history = obj(user, "GET", "/exports")
    check("Jake export appears in the shared history", any(
        item["id"] == tex_export["exportId"] and item["template"] == "JAKE_RESUME_LATEX"
        and item["downloadUrl"] == tex_export["downloadUrl"] for item in history))
    call(user, "POST", "/resumes/upload", expected=415, files={"file": ("bad.txt", b"not a document")})

    def export_concurrently(i):
        browser = requests.Session()
        browser.cookies.update(user.cookies)
        return obj(browser, "POST", f"{session_path}/exports", {"format": "TEX"}, 201,
                   key=f"parallel-export-{i}")["versionId"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        parallel_versions = list(pool.map(export_concurrently, range(4)))
    existing_versions = obj(user, "GET", f"/resumes/{rid}/versions")
    numbers = [v["versionNumber"] for v in existing_versions if v["id"] in parallel_versions]
    check("Concurrent exports allocate unique committed version numbers", len(set(numbers)) == 4)

    questions_a = obj(user, "POST", "/interview-questions/generate", {"jobId": jid, "resumeVersionId": vid})["questions"]
    sid = obj(user, "POST", "/interview-sessions", {"jobId": jid})["id"]
    answer = obj(user, "POST", f"/interview-sessions/{sid}/answers",
                 {"questionId": questions_a[0]["id"], "answer": "Built backend APIs and verified their behavior."})
    check("Local assessment is unavailable instead of zero", answer["feedback"]["score"] is None)
    j2 = obj(user, "POST", "/jobs", {"title": "Engineer B", "rawJd": jd}, 201)["jobId"]
    page1 = obj(user, "GET", "/jobs/page?size=1")
    page2 = obj(user, "GET", "/jobs/page?size=1&cursor=" + page1["nextCursor"])
    check("Cursor advances without repeats", page1["items"][0]["id"] != page2["items"][0]["id"])
    call(user, "GET", "/jobs/page?cursor=not-a-valid-cursor", expected=422)
    upload = b"%PDF" + b"0" * (10 * 1024 * 1024)
    call(user, "POST", "/resumes/upload", expected=413, name="Document upload over 10 MiB is blocked",
         files={"file": ("too-large.pdf", upload)})
    questions_b = obj(user, "POST", "/interview-questions/generate", {"jobId": j2})["questions"]
    call(user, "POST", f"/interview-sessions/{sid}/answers", {
        "questionId": questions_b[0]["id"], "answer": "This belongs to job B."
    }, 404, "Wrong job cannot use the previous interview session")
    sid_b = obj(user, "POST", "/interview-sessions", {"jobId": j2})["id"]
    obj(user, "POST", f"/interview-sessions/{sid_b}/answers", {
        "questionId": questions_b[0]["id"], "answer": "I analyzed this second role."
    })
    obj(user, "POST", f"/interview-sessions/{sid}/complete", {})
    check("Saved interview answers include the question", obj(user, "GET", f"/interview-sessions/{sid}")["answers"][0]["question"])
    application = obj(user, "POST", "/applications", {"jobId": jid, "resumeVersionId": vid})
    for stage in ("APPLIED", "INTERVIEW", "OFFER"):
        obj(user, "PATCH", f"/applications/{application['id']}", {"status": stage, "salaryOffered": "INR 20L"})
    app_id = application["id"]
    updated = obj(user, "PATCH", f"/applications/{app_id}", {"notes": "Fixture notes submitted separately"})
    check("Application patch retains omitted stage and salary", updated["status"] == "OFFER" and updated["salaryOffered"] == "INR 20L")
    second_application = obj(user, "POST", "/applications", {"jobId": j2})
    next_app = obj(user, "GET", "/applications/page?size=1")
    prev_app = obj(user, "GET", "/applications/page?size=1&cursor=" + next_app["nextCursor"])
    check("Tracker cursor returns both saved applications in order",
          next_app["items"][0]["id"] == second_application["id"] and prev_app["items"][0]["id"] == app_id)
    obj(user, "GET", "/offers/compare")
    call(user, "POST", "/reminders", {"title": "Invalid date", "dueAt": "sometime"}, 422, "Malformed reminder time rejected")
    reminder = obj(user, "POST", "/reminders", {"title": "QA reminder",
        "dueAt": datetime.now(timezone.utc).isoformat(), "applicationId": app_id})
    call(user, "PATCH", f"/reminders/{reminder['id']}", {}, 422, "Reminder update requires explicit completion state")
    obj(user, "PATCH", f"/reminders/{reminder['id']}", {"completed": True})
    incomplete = obj(user, "POST", "/salary-estimates", {"role": "Engineer", "location": "Hyderabad"})
    check("Salary estimate reports insufficient evidence", incomplete["status"] == "INSUFFICIENT_DATA")
    sample_salary = {"role": "Engineer", "location": "Hyderabad", "lowValue": 100, "highValue": 200,
        "sourceLabel": "Synthetic", "observedAt": datetime.now(timezone.utc).date().isoformat(), "currency": "INR"}
    call(user, "POST", "/salary-observations", {**sample_salary, "highValue": 99}, 422, "Reversed salary range rejected")
    call(user, "POST", "/salary-observations", {**sample_salary, "lowValue": 1000000000000}, 422,
         "Salary outside the database precision rejected")
    call(user, "POST", "/salary-observations", {**sample_salary, "currency": "INVALID"}, 422,
         "Invalid currency format rejected")
    call(user, "POST", "/salary-observations", {**sample_salary, "observedAt": "2026-02-30"}, 400,
         "Impossible salary observation date rejected")
    call(user, "POST", "/salary-observations", {**sample_salary, "sourceUrl": "mailto:fixture@example.test"}, 422,
         "Salary source requires a public web URL")
    for i in range(3):
        obj(user, "POST", "/salary-observations", {"role": "Engineer", "location": "Hyderabad",
            "lowValue": 100 + i * 10, "highValue": 200 + i * 10,
            "sourceLabel": "Synthetic", "observedAt": datetime.now(timezone.utc).date().isoformat(), "currency": "inr"})
    estimate = obj(user, "POST", "/salary-estimates", {"role": "Engineer", "location": "Hyderabad", "currency": "INR"})
    check("Salary arithmetic", estimate.get("lowEstimate") == 110 and estimate.get("highEstimate") == 210)
    fact = obj(user, "POST", "/career-facts", {"description": "Built QA services", "verifiedByUser": True})
    obj(user, "PATCH", f"/career-facts/{fact['id']}", {"description": "Built tested QA services", "verifiedByUser": True})
    for path in ("/dashboard/summary", "/insights", "/search?q=QA", "/subscription", "/notifications?page=1", "/notifications/unread-count"):
        obj(user, "GET", path)
    call(user, "POST", "/admin/notifications", {"userId": owner, "title": "QA", "message": "Test"}, 403, "Non-admin cannot send notifications")

    data = obj(user, "GET", "/privacy/data-export")
    check("Privacy export includes career facts, answers, versions and proposal history",
          all(data.get(key) for key in ("careerFacts", "interviewAnswers", "resumeVersions", "editProposals", "documents")))
    check("Privacy manifest excludes password and provider credentials", "passwordHash" not in data["account"] and "storageKey" not in data["documents"][0])
    payload = call(user, "GET", "/privacy/data-export/archive").content
    with zipfile.ZipFile(BytesIO(payload)) as archive:
        archive_data = json.loads(archive.read("careerforge-data.json"))
        check("Privacy archive contains user manifest and original/generated files", archive_data["account"]["id"] == owner and
              any(path.startswith("originals/") for path in archive.namelist()) and
              any(path.startswith("jake-exports/") for path in archive.namelist()))

    other = create_dev("other")
    for path in [f"/resumes/{rid}", f"/resume-versions/{vid}", f"/jobs/{jid}", f"/analyses/{aid}",
                 session_path, f"/applications/{application['id']}", f"/interview-sessions/{sid}", *download_paths]:
        call(other, "GET", path, expected=404, name="Owner isolation " + path.removeprefix("/api/v1").split("/")[1])

    call(other, "DELETE", f"/resumes/{rid}/permanent", {"confirmation": "DELETE RESUME"}, 404,
         "Permanent resume deletion requires ownership")
    call(other, "PATCH", edit_path, {"decision": "REJECTED", "revision": 1}, 404,
         "Edit decisions require ownership")
    call(user, "GET", "/nonexistent-route", expected=404, name="Missing API route returns 404")

    second = requests.Session()
    obj(second, "POST", "/auth/login", {"email": email, "password": password})
    obj(user, "POST", "/auth/forgot-password", {"email": email})
    token = re.search(r"[a-f0-9-]{36}\.[a-f0-9-]{36}", mail_text(email, "password reset")).group()
    replacement = "Replacement Password QA!11"
    obj(anonymous, "POST", "/auth/reset-password", {"token": token, "password": replacement})
    check("Reset revokes all previous sessions", all(not obj(browser, "GET", "/auth/session")["authenticated"] for browser in (user, second)))
    call(anonymous, "POST", "/auth/reset-password", {"token": token, "password": replacement}, 401, "Reset token is single-use")
    obj(user, "POST", "/auth/login", {"email": email, "password": replacement})
    obj(second, "POST", "/auth/login", {"email": email, "password": replacement})
    next_password = "New Current Password QA!11"
    obj(user, "POST", "/users/me/change-password", {"currentPassword": replacement, "newPassword": next_password})
    check("Change-password revokes the other session", not obj(second, "GET", "/auth/session")["authenticated"])
    new_email = f"new-{email}"
    obj(user, "POST", "/users/me/email-change", {"email": new_email, "password": next_password})
    check("Email changes only after verification", obj(user, "GET", "/users/me")["email"] == email)
    obj(user, "POST", "/users/me/email-change/confirm", {"email": new_email, "code": otp(new_email)})
    check("Verified email change applied", obj(user, "GET", "/users/me")["email"] == new_email)
    obj(second, "POST", "/auth/login", {"email": new_email, "password": next_password})
    for _ in range(11):
        failed = call(anonymous, "POST", "/auth/login", {"email": f"throttle-{SUFFIX}@example.test", "password": "wrong password"}, expected=None)
    check("Login abuse bound is enforced", failed.status_code == 429)

    if WEBHOOK_SECRET:
        body = json.dumps({"id": "evt_" + SUFFIX, "type": "test.fixture", "data": {"object": {}}})
        at = int(time.time())
        digest = hmac.new(WEBHOOK_SECRET.encode(), f"{at}.{body}".encode(), hashlib.sha256).hexdigest()
        r = anonymous.post(API + "/api/v1/billing/webhook", data=body, headers={"Content-Type": "application/json", "Stripe-Signature": f"t={at},v1={digest}"}, timeout=5)
        check("Signed fixture webhook accepted", r.status_code == 200)
        r = anonymous.post(API + "/api/v1/billing/webhook", data=body, headers={"Content-Type": "application/json", "Stripe-Signature": f"t={at},v1={digest}"}, timeout=5)
        check("Signed webhook event replay deduplicated", r.status_code == 200 and r.json().get("duplicate"))
    call(anonymous, "POST", "/billing/webhook", {}, 400, "Unsigned webhook is rejected")

    quota_resume = obj(other, "POST", "/resumes/text", {"title": "Quota resume", "pastedText": original}, 201)
    quota_version = review(other, quota_resume)
    quota_job = obj(other, "POST", "/jobs", {"title": "Quota job", "rawJd": jd}, 201)["jobId"]
    def quota_attempt(i):
        client = requests.Session()
        client.cookies.update(other.cookies)
        return call(client, "POST", "/analyses", {"jobId": quota_job, "resumeVersionId": quota_version}, expected=None, key=f"quota-{i}").status_code
    with ThreadPoolExecutor(max_workers=12) as pool:
        statuses = list(pool.map(quota_attempt, range(12)))
    check("Concurrent FREE quota accepts exactly three requests", statuses.count(202) == 3 and statuses.count(429) == 9, statuses)

    call(user, "DELETE", f"/resumes/{rid}", expected=204)
    call(user, "GET", f"/resumes/{rid}", expected=404, name="Archived resume hidden")
    call(user, "GET", tex_export["downloadUrl"], name="Archive retains historic export")
    call(user, "POST", f"/resumes/{rid}/restore", {}, 204)
    obj(user, "GET", f"/resumes/{rid}", name="Restore re-enables resume")
    call(user, "DELETE", f"/resumes/{rid}/permanent", {"confirmation": "DELETE RESUME"}, 204)
    call(user, "GET", tex_export["downloadUrl"], expected=404, name="Permanent deletion removes exports")
    call(user, "GET", f"/analyses/{aid}", expected=404, name="Permanent deletion removes derived analysis")
    check("Application survives resume deletion", obj(user, "GET", f"/applications/{application['id']}")["resumeVersionId"] is None)
    call(user, "DELETE", "/users/me", {"confirmation": "DELETE MY ACCOUNT"}, 204, "Populated account including interview answers deletes successfully")
    check("Deleted account sessions are rejected", all(not obj(browser, "GET", "/auth/session")["authenticated"] for browser in (user, second)))
    obj(other, "POST", "/auth/logout", {})
    check("Logout revokes session", not obj(other, "GET", "/auth/session")["authenticated"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        check("Smoke suite finished", False, str(error))
    finally:
        result = os.environ.get("CF_TEST_RESULTS")
        if result:
            Path(result).write_text(json.dumps(RESULTS, indent=2))
        passed = sum(row["passed"] for row in RESULTS)
        print(f"{passed}/{len(RESULTS)} assertions passed.", flush=True)
        raise SystemExit(0 if passed == len(RESULTS) else 1)
