"""Exercise rollback, lease recovery and restore on the disposable PostgreSQL fixture."""
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import uuid4
import json
import os
import subprocess
import time

import psycopg2

from smoke import call, check, create_dev, mail_text, obj, review, await_value


def sample_pdf():
    stream = b"BT /F1 12 Tf 40 700 Td (Synthetic resume for the backup restore fixture with PostgreSQL.) Tj ET"
    entries = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    file = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, entry in enumerate(entries, 1):
        offsets.append(len(file))
        file.extend(f"{number} 0 obj\n".encode() + entry + b"\nendobj\n")
    start = len(file)
    file.extend(f"xref\n0 6\n0000000000 65535 f \n".encode())
    for pos in offsets[1:]:
        file.extend(f"{pos:010d} 00000 n \n".encode())
    file.extend(f"trailer\n<< /Root 1 0 R /Size 6 >>\nstartxref\n{start}\n%%EOF\n".encode())
    return bytes(file)


def execute(connection, sql, parameters=()):
    with connection.cursor() as cursor:
        cursor.execute(sql, parameters)
        return cursor.fetchall() if cursor.description else []


def verify(connection, storage, outdir):
    candidate = create_dev("resilience")
    owner = obj(candidate, "GET", "/users/me")["id"]
    version = obj(candidate, "POST", "/resumes/upload", expected=201,
                  files={"file": ("maintenance.pdf", sample_pdf())})
    vid = review(candidate, version)
    job = obj(candidate, "POST", "/jobs", {"title": "Recovery test", "rawJd":
          "Build normalized databases with PostgreSQL. Diagnose slow database activity and write clear operational guidance."}, 201)
    jid = job["jobId"]

    analysis, task = str(uuid4()), str(uuid4())
    execute(connection, """
        INSERT INTO analyses(id,user_id,job_id,resume_version_id,status,jd_snapshot)
        VALUES (%s,%s,%s,%s,'RUNNING',%s::jsonb)
        """, (analysis, owner, jid, vid, json.dumps(job["structuredJd"])))
    execute(connection, """
        INSERT INTO ai_jobs(id,user_id,task_type,resource_id,status,attempts,lease_until)
        VALUES (%s,%s,'ANALYSIS',%s,'RUNNING',1,now()-interval '1 second')
        """, (task, owner, analysis))
    connection.commit()
    result = await_value(lambda: (value if (value := obj(candidate, "GET", f"/ai-jobs/{task}",
                         expected=None))["status"] == "SUCCEEDED" else None))
    check("An expired worker lease is reclaimed and resumed", result["attempts"] == 2)

    path = storage / owner
    before = {file.name for file in path.iterdir()}
    execute(connection, """
        CREATE OR REPLACE FUNCTION cf_fixture_reject_upload() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'Synthetic transaction rollback check'; END $$;
        CREATE TRIGGER cf_fixture_reject_upload BEFORE INSERT ON documents FOR EACH ROW
        WHEN (NEW.filename='cf-rollback.pdf') EXECUTE FUNCTION cf_fixture_reject_upload();
        """)
    connection.commit()
    try:
        call(candidate, "POST", "/resumes/upload", expected=500, name="Synthetic database failure rolls back upload",
             files={"file": ("cf-rollback.pdf", sample_pdf())})
        check("Failed upload removed its stored object", {file.name for file in path.iterdir()} == before)
        check("No resume is published for a failed write", not execute(connection,
            "SELECT id FROM resumes WHERE user_id=%s AND title='cf-rollback.pdf'", (owner,)))
    finally:
        execute(connection, "DROP TRIGGER cf_fixture_reject_upload ON documents; DROP FUNCTION cf_fixture_reject_upload();")
        connection.commit()

    orphan = path / (str(uuid4()) + ".pdf")
    orphan.write_bytes(b"fixture orphan")
    aged = time.time() - 400
    os.utime(orphan, (aged, aged))
    await_value(lambda: not orphan.exists(), timeout=25)
    check("Janitor deletes aged orphan and keeps versioned file", {file.name for file in path.iterdir()} == before)

    no_storage = create_dev("storage-failure")
    other = obj(no_storage, "GET", "/users/me")["id"]
    blocker = storage / other
    blocker.write_text("Synthetic unavailable directory")
    try:
        call(no_storage, "POST", "/resumes/upload", expected=503, name="Storage unavailability returns 503",
             files={"file": ("fixture-unavailable.pdf", sample_pdf())})
        check("Storage failure leaves no published resume", obj(no_storage, "GET", "/resumes") == [])
    finally:
        blocker.unlink()

    admin = create_dev("notification-admin")
    sender = obj(admin, "GET", "/users/me")
    recipient = obj(no_storage, "GET", "/users/me")
    execute(connection, "INSERT INTO notification_admins(user_id) VALUES (%s)", (sender["id"],))
    connection.commit()
    notification = {
        "userId": recipient["id"], "title": "Operations fixture", "message": "Synthetic notification delivered.",
        "targetUrl": "/settings", "email": True,
    }
    obj(admin, "POST", "/admin/notifications", notification)
    listing = obj(no_storage, "GET", "/notifications/page")
    hit = next(item for item in listing["items"] if item["title"] == notification["title"])
    check("Admin notification reaches recipient with a private settings link",
          hit["targetUrl"] == "/settings" and not hit["isRead"])
    check("Notification outbox delivers the requested email",
          "Synthetic notification delivered." in mail_text(recipient["email"], notification["title"]))
    call(admin, "POST", f"/notifications/{hit['id']}/read", {}, 404, "Notification ownership enforced")
    call(no_storage, "POST", f"/notifications/{hit['id']}/read", {}, 204)
    check("Reading notification clears unread badge", obj(no_storage, "GET", "/notifications/unread-count")["count"] == 0)

    backup = outdir / "backup"
    archive = outdir / "restored-files"
    bin_path = Path(os.environ["CF_TEST_PG_BIN"])
    scripts = Path(__file__).resolve().parents[2] / "ops"
    connection.commit()
    path_env = str(bin_path) + os.pathsep + os.environ["PATH"]
    subprocess.run(["bash", str(scripts / "backup-filesystem.sh"), str(backup)],
        env={**os.environ, "PATH": path_env, "PG_DSN": os.environ["CF_TEST_DB_DSN"], "STORAGE_ROOT": str(storage)}, check=True)
    restore_db = "cf_restore_" + uuid4().hex[:8]
    connection.autocommit = True
    execute(connection, "CREATE DATABASE " + restore_db)
    host = os.environ.get("CF_TEST_DB_HOST", "127.0.0.1")
    port = os.environ.get("CF_TEST_DB_PORT", "55432")
    role = os.environ.get("CF_TEST_DB_ROLE", "careerforge")
    dsn = f"host={host} port={port} user={role} dbname={restore_db}"
    subprocess.run(["bash", str(scripts / "restore-filesystem.sh"), str(backup)],
        env={**os.environ, "PATH": path_env, "PG_DSN": dsn, "STORAGE_ROOT": str(archive)}, check=True)
    with closing(psycopg2.connect(dsn)) as recovered:
        restored = execute(recovered, "SELECT storage_key FROM documents WHERE user_id=%s AND filename='maintenance.pdf'", (owner,))
        key = restored[0][0]
        check("Restored database resolves its uploaded PDF in the backup volume",
              key.startswith("local:") and (archive / key.removeprefix("local:")).read_bytes() == sample_pdf())
        check("Restored database retains reviewed text and completed analysis",
              execute(recovered, "SELECT status FROM analyses WHERE id=%s", (analysis,)) == [("SUCCEEDED",)] and
              execute(recovered, "SELECT confirmed_at IS NOT NULL FROM resume_versions WHERE id=%s", (vid,)) == [(True,)])
    execute(connection, "DROP DATABASE " + restore_db)


if __name__ == "__main__":
    from smoke import RESULTS
    if os.environ.get("CF_DISPOSABLE_FIXTURE") != "true":
        raise SystemExit("This fault-injection suite requires an isolated disposable PostgreSQL and storage fixture.")
    with TemporaryDirectory(dir=os.environ.get("CF_TEST_TEMP")) as tmp:
        connection = psycopg2.connect(os.environ["CF_TEST_DB_DSN"])
        try:
            verify(connection, Path(os.environ["CF_TEST_STORAGE"]), Path(tmp))
        finally:
            connection.close()
    print(f"Resilience and restore: {sum(r['passed'] for r in RESULTS)}/{len(RESULTS)} assertions passed.")
    raise SystemExit(0 if all(r["passed"] for r in RESULTS) else 1)
