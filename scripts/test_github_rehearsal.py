import base64
import hashlib

import pytest

import github_rehearsal as ci


PR = ci.PullRequest("team/repo", 17, "a" * 40, "b" * 40)
SQL = b"ALTER TABLE users ADD COLUMN nickname text;"
OID = hashlib.sha1(f"blob {len(SQL)}\0".encode() + SQL, usedforsecurity=False).hexdigest()


class GitHub:
    def __init__(self, files):
        self.files = files
        self.statuses = []
        self.blob = {"size": len(SQL), "encoding": "base64", "sha": OID,
                     "content": base64.b64encode(SQL).decode()}

    def request(self, path, *, payload=None):
        if path == PR.path:
            return {"state": "open", "head": {"sha": PR.head, "repo": {"full_name": PR.repository}},
                    "base": {"sha": PR.base}, "changed_files": len(self.files)}
        if path == PR.path + "/files?per_page=100&page=1":
            return self.files
        if path == f"/repos/{PR.repository}/git/blobs/{OID}":
            return self.blob
        assert path == f"/repos/{PR.repository}/statuses/{PR.head}"
        self.statuses.append(payload)
        return {}


class Rehearsal:
    def __init__(self, verdict, status):
        self.submitted = False
        self.verdict = verdict
        self.status = status

    def request(self, path, *, raw=None, content_type=None):
        if path == "/api/ci/rehearsals":
            assert content_type.startswith("multipart/form-data; boundary=")
            assert b'name="file"; filename="V1__nickname.sql"' in raw
            assert SQL in raw and PR.head.encode() in raw
            self.submitted = True
            return {"runId": 41, "verdict": "PENDING"}
        assert path == "/api/ci/rehearsals/41"
        return {"runId": 41, "status": self.status, "verdict": self.verdict,
                "compatibilityRegressions": int(self.verdict == "FAIL" and self.status == "SUCCEEDED")}


@pytest.mark.parametrize(("verdict", "run_status", "github_status", "exit_code"), [
    ("PASS", "SUCCEEDED", "success", 0),
    ("FAIL", "FAILED", "failure", 1),
    ("FAIL", "SUCCEEDED", "failure", 1),
])
def test_posts_verdict_to_head_with_report_link(monkeypatch, verdict, run_status, github_status, exit_code):
    monkeypatch.setattr(ci.time, "sleep", lambda _seconds: None)
    github = GitHub([{"filename": "db/V1__nickname.sql", "status": "added", "sha": OID}])
    rehearsal = Rehearsal(verdict, run_status)
    assert ci.check(github, rehearsal, PR, "db/", "https://reports.example.test") == exit_code
    assert rehearsal.submitted
    assert [item["state"] for item in github.statuses] == ["pending", github_status]
    assert github.statuses[-1]["target_url"] == "https://reports.example.test/reports/41"


def test_skips_unrelated_files_without_submitting():
    github = GitHub([{"filename": "README.md", "status": "modified"}])
    rehearsal = Rehearsal("PASS", "SUCCEEDED")
    assert ci.check(github, rehearsal, PR, "db/", "https://reports.example.test") == 0
    assert not rehearsal.submitted
    assert github.statuses[-1]["state"] == "success"
    assert "Skipped" in github.statuses[-1]["description"]


def test_missing_report_origin_never_publishes_an_internal_link(monkeypatch):
    monkeypatch.setattr(ci.time, "sleep", lambda _seconds: None)
    github = GitHub([{"filename": "db/V1__nickname.sql", "status": "added", "sha": OID}])
    assert ci.check(github, Rehearsal("PASS", "SUCCEEDED"), PR, "db/", None) == 0
    assert "target_url" not in github.statuses[-1]


@pytest.mark.parametrize("change", [
    {"filename": "db/V1__nickname.sql", "status": "removed"},
    {"filename": "archive/V1__nickname.sql", "previous_filename": "db/V1__nickname.sql", "status": "renamed"},
])
def test_removing_a_migration_fails_closed(change):
    github = GitHub([change])
    rehearsal = Rehearsal("PASS", "SUCCEEDED")
    assert ci.check(github, rehearsal, PR, "db/", "https://reports.example.test") == 1
    assert not rehearsal.submitted
    assert github.statuses[-1]["state"] == "error"


def test_wrong_blob_hash_never_submits_sql():
    github = GitHub([{"filename": "db/V1__nickname.sql", "status": "added", "sha": OID}])
    github.blob["content"] = base64.b64encode(b"x" * len(SQL)).decode()
    rehearsal = Rehearsal("PASS", "SUCCEEDED")
    assert ci.check(github, rehearsal, PR, "db/", "https://reports.example.test") == 1
    assert not rehearsal.submitted


def test_stale_base_prevents_the_rehearsal():
    class MovedBase(GitHub):
        def request(self, path, *, payload=None):
            result = super().request(path, payload=payload)
            if path == PR.path:
                result["base"]["sha"] = "c" * 40
            return result

    github = MovedBase([{"filename": "db/V1__nickname.sql", "status": "added", "sha": OID}])
    rehearsal = Rehearsal("PASS", "SUCCEEDED")
    assert ci.check(github, rehearsal, PR, "db/", "https://reports.example.test") == 1
    assert not rehearsal.submitted
    assert [item["state"] for item in github.statuses] == ["error"]


def test_incomplete_github_file_list_cannot_be_treated_as_no_changes():
    with pytest.raises(ci.CheckError, match="incomplete"):
        ci.changed_migrations(GitHub([]), PR, "db/", 1)


def test_fork_event_rejected():
    event = {"pull_request": {"head": {"repo": {"full_name": "fork/repo"}, "sha": PR.head},
                              "base": {"repo": {"full_name": PR.repository}, "sha": PR.base}, "number": PR.number}}
    with pytest.raises(ci.CheckError, match="same-repository"):
        ci.event_request(event, PR.repository)


def test_remote_plaintext_api_is_rejected():
    assert ci.origin("http://localhost:5173/", "SR_CI_URL") == "http://localhost:5173"
    with pytest.raises(ci.CheckError, match="HTTPS"):
        ci.origin("http://service.example.test", "SR_CI_URL")
