# GitHub access — 25 October 2026

The existing starter was located in yesterday's Codex workspace and moved to
`/Users/Spandan/github.com/spandan-kumar/OpenTripper`. The destination repository is
[spandan-kumar/OpenTripper](https://github.com/spandan-kumar/OpenTripper); no second repository is needed.

The connected GitHub integration identifies the account as `spandan-kumar`, and repository
metadata reports admin/push permissions. However, an attempted Git blob write still returns
HTTP 403, `Resource not accessible by integration`. The collaborator-permission endpoint
also returns 403. Repository visibility and the human account's rights do not establish
the integration token's write capability. The exact missing installation permission cannot
be distinguished from an installation/token mismatch from these responses alone.

The local GitHub CLI is independently authenticated as `spandan-kumar`, has the `repo` scope,
and reports push permission on this repository. This client is used for publishing rather
than the blocked integration. No tokens are copied into project files or remote URLs.
The actual publication result is recorded in [validation](VALIDATION.md).

To repair the integration itself, inspect its [GitHub App installation](https://github.com/settings/installations):
ensure OpenTripper is selected and the App has **Contents: Read and write**. If the provider
does not request that permission, selecting the repository cannot grant it; the provider
must add the permission and the installation must approve it. Reauthorize the connection
after any permission changes. A successful write is the definitive verification.

References: [GitHub REST — create a blob](https://docs.github.com/en/rest/git/blobs#create-a-blob),
[GitHub REST — create or update file contents](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents).
