# ForestIQ merge policy

ForestIQ uses pull requests and **squash merge** as the normal merge strategy.

## Required gates

Before auto-merge is enabled, `main` protection must require:

- `fast-quality-gate`
- `heavy-quality-gate`

with strict/up-to-date branch checking.

The auto-merge helper refuses to continue when those protections are absent.

## Configure repository merge settings

Requires GitHub repository administration permission:

~~~bash
bash scripts/safe-auto-merge.sh configure
bash scripts/safe-auto-merge.sh verify
~~~

The configuration:

- enables GitHub auto-merge;
- enables squash merge;
- disables merge commits;
- disables rebase merge.

This keeps one traceable commit per merged PR.

## Enable auto-merge on a PR

The PR must:

- be open;
- not be a draft;
- target `main`;
- reference its originating GitHub issue in the PR body;
- be subject to the required branch-protection gates.

Then run:

~~~bash
bash scripts/safe-auto-merge.sh pr 123
~~~

The helper calls GitHub auto-merge with `--squash`. It does **not** use `--admin` and cannot bypass required checks. GitHub waits for branch protection, status checks and any configured review/conversation requirements before merging.

## Traceability

PR titles should begin with the issue number where practical, and PR bodies should contain `Closes #NNN`, `Fixes #NNN`, `Resolves #NNN`, or another explicit issue reference.

The resulting squash commit remains linked to the PR, which remains linked to the issue.


## Policy source

Repository merge settings, the protected branch and required gate names are defined once in `.github/forestiq-repository-policy.json`. Both governance scripts read this file rather than maintaining duplicate hard-coded policy.
