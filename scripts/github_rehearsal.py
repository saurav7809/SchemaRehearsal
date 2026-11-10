"""Run an approved same-repository PR's changed SQL against a pinned baseline."""

import base64
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path


CONTEXT = "SchemaRehearsal"
MAX_FILES = 20
MAX_SQL_BYTES = 256 * 1024
SHA = re.compile(r"[a-f0-9]{40}")
FILENAME = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_. -]{0,154}\.sql")
REPOSITORY = re.compile(r"[a-zA-Z0-9_.-]{1,100}/[a-zA-Z0-9_.-]{1,100}")


class CheckError(Exception):
    """A bounded, non-sensitive message suitable for the PR check."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, name, origin, token):
        self.name = name
        self.origin = origin
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, *, payload=None, raw=None, content_type=None):
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json",
                   "User-Agent": "SchemaRehearsal-GitHub-Check"}
        if self.name == "GitHub":
            headers["Accept"] = "application/vnd.github+json"
        if payload is not None:
            raw = json.dumps(payload).encode()
            content_type = "application/json"
        if raw is not None:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.origin + path, data=raw, headers=headers)
        try:
            with self.opener.open(req, timeout=30) as response:
                body = response.read(8 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as error:
            hints = {400: "check SQL validation and request settings", 401: "check credentials",
                     403: "check credentials and permissions", 404: "check the configured project and baseline",
                     413: "migration upload exceeds the size limit", 429: "the service is busy",
                     503: "the service is unavailable or execution is disabled"}
            hint = hints.get(error.code, "check service logs")
            raise CheckError(f"{self.name} returned HTTP {error.code}. Please {hint}.") from None
        except OSError:
            raise CheckError(f"Could not reach {self.name}. Check runner connectivity and TLS.") from None
        if len(body) > 8 * 1024 * 1024:
            raise CheckError(f"{self.name} response exceeded the supported size.")
        try:
            return json.loads(body)
        except (ValueError, UnicodeError):
            raise CheckError(f"{self.name} returned an invalid response.") from None


def origin(value, setting, github=False):
    parsed = urllib.parse.urlsplit(value)
    if not re.fullmatch(r"[a-zA-Z0-9.:\[\]-]+", parsed.netloc) or any(c.isspace() for c in value):
        raise CheckError(f"{setting} must be an HTTPS origin. HTTP is accepted only on loopback.")
    port = parsed.port
    loopback = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
    path_ok = parsed.path in {"", "/"} or (github and parsed.path in {"/api/v3", "/api/v3/"})
    if (not parsed.hostname or parsed.username is not None or parsed.password is not None
            or port == 0 or parsed.query or parsed.fragment or not path_ok
            or parsed.scheme != "https" and not (parsed.scheme == "http" and loopback)):
        raise CheckError(f"{setting} must be an HTTPS origin. HTTP is accepted only on loopback.")
    return value.rstrip("/")


@dataclass(frozen=True)
class PullRequest:
    repository: str
    number: int
    head: str
    base: str

    @property
    def path(self):
        return f"/repos/{self.repository}/pulls/{self.number}"

    def require_current(self, github):
        current = github.request(self.path)
        if (current["state"] != "open" or current["head"]["sha"] != self.head
                or current["base"]["sha"] != self.base
                or current["head"]["repo"]["full_name"].casefold() != self.repository.casefold()):
            raise CheckError("The PR changed while being checked. Rerun for the current commit.")
        return current

    def status(self, github, state, description, report_url=None):
        fields = {"state": state, "context": CONTEXT, "description": description}
        if report_url:
            fields["target_url"] = report_url
        github.request(f"/repos/{self.repository}/statuses/{self.head}", payload=fields)


def event_request(event, repository):
    pr = event["pull_request"]
    if not REPOSITORY.fullmatch(repository) or pr["base"]["repo"]["full_name"].casefold() != repository.casefold():
        raise CheckError("The event does not belong to this repository.")
    if (pr["head"]["repo"] or {}).get("full_name", "").casefold() != repository.casefold():
        raise CheckError("Only same-repository pull requests are supported.")
    head, base, number = pr["head"]["sha"], pr["base"]["sha"], pr["number"]
    if not SHA.fullmatch(head) or not SHA.fullmatch(base) or not isinstance(number, int) or number < 1:
        raise CheckError("The pull request event does not identify a valid commit.")
    return PullRequest(repository, number, head, base)


def migration_directory(value):
    value = value.strip().rstrip("/")
    segments = value.split("/")
    if not value or any(not segment or segment in {".", ".."} or "\\" in segment for segment in segments):
        raise CheckError("SR_MIGRATIONS_PATH must be a repository-relative directory.")
    return value + "/"


def changed_migrations(github, pr, directory, changed_count):
    if not isinstance(changed_count, int) or not 0 <= changed_count <= 3000:
        raise CheckError("The PR is too large to enumerate its changed files safely.")
    files = []
    for page in range(1, (changed_count + 99) // 100 + 1):
        batch = github.request(f"{pr.path}/files?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise CheckError("GitHub did not return a file list.")
        files.extend(batch)
    if len(files) != changed_count:
        raise CheckError("GitHub returned an incomplete file list. Rerun the check.")

    def migration(path):
        return path.startswith(directory) and path.lower().endswith(".sql")

    selected = []
    names = set()
    for file in files:
        path = file["filename"]
        status = file["status"]
        if (status == "removed" and migration(path)
                or status == "renamed" and migration(file.get("previous_filename", "")) and not migration(path)):
            raise CheckError("A SQL migration was removed from the configured directory.")
        if not migration(path):
            continue
        name = path.rsplit("/", 1)[-1]
        if (status not in {"added", "modified", "renamed", "copied"}
                or not FILENAME.fullmatch(name) or not SHA.fullmatch(file.get("sha", ""))):
            raise CheckError("A changed SQL migration has an unsupported name or change type.")
        if name.lower() in names:
            raise CheckError("Two changed SQL migrations have the same filename.")
        names.add(name.lower())
        selected.append((name, file["sha"]))
    if len(selected) > MAX_FILES:
        raise CheckError(f"The PR changes more than {MAX_FILES} SQL migrations.")
    return selected


def download_migrations(github, repository, selected):
    remaining = MAX_SQL_BYTES
    files = []
    for name, sha in selected:
        blob = github.request(f"/repos/{repository}/git/blobs/{sha}")
        size = blob["size"]
        if not isinstance(size, int) or not 0 < size <= remaining:
            raise CheckError("Changed SQL files must be non-empty and total at most 256 KiB.")
        if blob["sha"] != sha or blob["encoding"] != "base64":
            raise CheckError("GitHub returned unexpected migration blob metadata.")
        data = base64.b64decode("".join(blob["content"].split()), validate=True)
        digest = hashlib.sha1(f"blob {len(data)}\0".encode() + data, usedforsecurity=False).hexdigest()
        if len(data) != size or digest != sha:
            raise CheckError("A downloaded migration did not match its Git blob.")
        remaining -= size
        files.append((name, data))
    return files


def upload(ci, pr, files):
    boundary = uuid.uuid4().hex
    parts = []
    for key, value in (("repository", pr.repository), ("pullRequest", pr.number), ("commit", pr.head)):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    for name, data in files:
        parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                      'Content-Type: application/sql\r\n\r\n'.encode(), data, b"\r\n"])
    parts.append(f"--{boundary}--\r\n".encode())
    return ci.request("/api/ci/rehearsals", raw=b"".join(parts), content_type=f"multipart/form-data; boundary={boundary}")


def wait_for_run(ci, run):
    run_id = run["runId"]
    if type(run_id) is not int or run_id <= 0:
        raise CheckError("SchemaRehearsal returned an invalid run identifier.")
    deadline = time.monotonic() + 600
    while run["verdict"] == "PENDING" and time.monotonic() < deadline:
        time.sleep(2)
        run = ci.request(f"/api/ci/rehearsals/{run_id}")
        if run["runId"] != run_id:
            raise CheckError("SchemaRehearsal returned an unexpected run.")
    if run["verdict"] not in {"PASS", "FAIL"}:
        raise CheckError("SchemaRehearsal did not return a completed verdict in time.")
    if (run["status"] not in {"SUCCEEDED", "FAILED"}
            or run["verdict"] == "PASS" and run["status"] != "SUCCEEDED"):
        raise CheckError("SchemaRehearsal returned an inconsistent verdict.")
    return run


def summary(message, report_url=None, run=None):
    print(message)
    lines = ["## SchemaRehearsal", "", message]
    if run:
        lines.append(f"- Rehearsal run #{run['runId']}")
        name, state = run.get("failedFile"), run.get("sqlState")
        if isinstance(name, str) and FILENAME.fullmatch(name):
            lines.append(f"- Failing file `{name}`")
        if isinstance(state, str) and re.fullmatch(r"[A-Z0-9]{5}", state):
            lines.append(f"- PostgreSQL SQLSTATE `{state}`")
        count = run.get("compatibilityRegressions")
        if type(count) is int and count > 0:
            lines.append(f"- Existing query regressions: {count}")
        count = run.get("assertionFailures")
        if type(count) is int and count > 0:
            lines.append(f"- Failed query assertions: {count}")
    if report_url:
        lines.append(f"- [Open the authenticated report]({report_url})")
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        try:
            with open(target, "a", encoding="utf-8") as output:
                output.write("\n".join(lines) + "\n")
        except OSError:
            print("Could not write the Actions step summary.")


def check(github, ci, pr, directory, report_origin):
    try:
        current = pr.require_current(github)
        pr.status(github, "pending", "Checking database migrations")
        selected = changed_migrations(github, pr, directory, current["changed_files"])
        pr.require_current(github)
        if not selected:
            message = "Skipped: no changed SQL migrations."
            pr.status(github, "success", message)
            summary(message)
            return 0
        files = download_migrations(github, pr.repository, selected)
        pr.require_current(github)
        run = wait_for_run(ci, upload(ci, pr, files))
        pr.require_current(github)
        report_url = f"{report_origin}/reports/{run['runId']}" if report_origin else None
        passed = run["verdict"] == "PASS"
        message = ("Migration rehearsal passed." if passed else
                   "Query result assertion failed." if run.get("assertionFailures", 0) > 0 else
                   "Existing query compatibility regressed." if run["status"] == "SUCCEEDED" else
                   "Migration rehearsal failed.")
        pr.status(github, "success" if passed else "failure", message, report_url)
        summary(message, report_url, run)
        return 0 if passed else 1
    except (CheckError, KeyError, TypeError, ValueError) as error:
        message = str(error) if isinstance(error, CheckError) else "The PR or API response was incomplete."
        try:
            pr.status(github, "error", "SchemaRehearsal could not complete the check")
        except CheckError:
            pass
        summary(message)
        return 1


def main():
    try:
        env = os.environ
        event = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        pr = event_request(event, env["GITHUB_REPOSITORY"])
        github = Api("GitHub", origin(env.get("GITHUB_API_URL", "https://api.github.com"), "GITHUB_API_URL", True),
                     env["GITHUB_TOKEN"])
        ci = Api("SchemaRehearsal", origin(env["SR_CI_URL"], "SR_CI_URL"), env["SR_CI_TOKEN"])
        report_url = env.get("SR_REPORT_URL")
        return check(github, ci, pr, migration_directory(env["SR_MIGRATIONS_PATH"]),
                     origin(report_url, "SR_REPORT_URL") if report_url else None)
    except (CheckError, KeyError, TypeError, ValueError, OSError) as error:
        summary(str(error) if isinstance(error, CheckError) else "Check the workflow configuration and PR event.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
