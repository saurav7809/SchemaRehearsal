# GitHub pull request rehearsals

SchemaRehearsal can post a `SchemaRehearsal` status to the exact head commit of an approved pull request. The runner sends changed migration files to an internal staging project and waits for its report. The pull request contributes SQL data. The runner executes the integration script from the protected base commit.

## Configure staging and issue a token

1. Deploy an [internal staging instance](production.md) with SQL execution enabled and a dedicated rehearsal PostgreSQL cluster. Use sanitized test data and restrict HTTPS ingress to the trusted runner and authorized operators.
2. Create a staging project, save the release you intend to test as a [project baseline](api.md#projects-and-baselines), and add the OLD compatibility queries that should keep working.
3. As the project owner, open the project's **GitHub CI** tab, select the published baseline, and choose **Issue token**. Copy the bearer token into the environment secret while it is displayed. See [CI token management](api.md#ci-token-management) for the API contract.

Each project has one active CI token. Use **Replace token** to bind CI to a newer release, or **Revoke token** to disable future requests. An in-flight request that already authenticated may complete.

## Install the opt-in workflow

1. Protect the consumer repository's `main` branch and restrict edits to the workflow and integration script through code review. The template targets `main`. Change that branch filter only to match your protected integration branch.
2. Create an environment named `schema-rehearsal` with required reviewers. Prevent self-review and administrator bypass, and restrict deployments to the protected branch. Review the SQL in the current pull request revision before approving its job. Enable the template only where the repository can enforce an approval gate. [GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
3. Configure these settings in that environment.

| Name | Kind | Value |
| --- | --- | --- |
| `SR_CI_URL` | Secret | Internal HTTPS origin serving the API, such as `https://rehearsal-staging.example.com` |
| `SR_CI_TOKEN` | Secret | Project CI bearer token |
| `SR_MIGRATIONS_PATH` | Variable | Repository-relative migration directory, such as `db/migrations` |
| `SR_REPORT_URL` | Optional variable | Browser origin used in report links visible to repository readers |

4. Provision a dedicated, isolated Windows x64 runner with the `schema-rehearsal` label, Python 3, PowerShell 7, and a current GitHub Actions runner runtime. Use an ephemeral runner and limit its network access to GitHub and the staging HTTPS origin. Limit which workflows can use it. [GitHub runner security](https://docs.github.com/en/actions/reference/security/secure-use)
5. Copy the [workflow template](../deployment/github/schema-rehearsal.yml) to `.github/workflows/schema-rehearsal.yml` in the consumer repository. Copy [the Python client](../scripts/github_rehearsal.py) to `scripts/github_rehearsal.py` there. Merge both through review onto the protected base branch.

The template runs on pull request open, reopen, and new commits for branches in the same repository. It uses a pinned checkout action, checks out the base commit, and starts only the reviewed Python client. Its GitHub token has read access to contents and pull requests and write access to commit statuses. Keep the template limited to trusted same-repository pull requests and an approval-protected environment. [GitHub pull request target security](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

## Read the result

- The client lists changed SQL files for the event's pull request and fetches each eligible file by Git blob SHA. It verifies the downloaded bytes against that SHA. Deleting a migration or moving it out of the configured directory fails the check. With zero eligible SQL changes, the status succeeds and records a skip.
- Files are sent as one rehearsal against the baseline bound to the token. A migration execution failure, OLD query regression or shape change, or failed result assertion fails the check. The Actions summary gives the outcome, run ID for a completed rehearsal, and available sanitized evidence, including the number of failed assertions. Report links appear only when `SR_REPORT_URL` is explicitly set. Open linked reports with a SchemaRehearsal account that owns the staging project.
- The workflow cancels superseded jobs for the same pull request. The client checks both the base and head SHA against the event before fetching changes, before sending them to staging, and before publishing the result. Update the branch and review the latest revision if either commit changes.

After setup, open a same-repository staging pull request with a safe migration and approve the job. Confirm the exact head SHA receives the `SchemaRehearsal` success status. When report links are enabled, verify that the link opens the matching report. Repeat with a migration that breaks an OLD query and confirm the status fails. Try a pull request without migration changes to verify the skip. For branches that accept same-repository pull requests only, require the `SchemaRehearsal` commit status and enable **Require branches to be up to date before merging** in branch protection. [GitHub required status checks](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches#require-status-checks-before-merging)
