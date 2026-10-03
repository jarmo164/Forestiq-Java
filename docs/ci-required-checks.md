# Required CI status checks

ForestIQ exposes two stable aggregate checks for repository rules and branch protection:

- workflow UI: `CI / fast-quality-gate`; required-check context: `fast-quality-gate`
- workflow UI: `Heavy CI / heavy-quality-gate`; required-check context: `heavy-quality-gate`

Only aggregate gates should be configured as required checks. Individual implementation jobs may be intentionally skipped by changed-path classification.

## Repository rules

Apply the ForestIQ `main` policy with:

~~~bash
gh auth status
bash scripts/configure-main-protection.sh jarmo164/ForestIQ apply
bash scripts/configure-main-protection.sh jarmo164/ForestIQ verify
~~~

The canonical policy is `.github/forestiq-repository-policy.json`.

## Live governance evidence

After applying the repository settings, export the live state:

~~~bash
gh api repos/jarmo164/ForestIQ > /tmp/forestiq-repository.json
gh api repos/jarmo164/ForestIQ/branches/main/protection > /tmp/forestiq-main-protection.json

python scripts/github_repository_governance_audit.py \
  --repository-json /tmp/forestiq-repository.json \
  --protection-json /tmp/forestiq-main-protection.json \
  --report /tmp/forestiq-governance-audit.md
~~~

The audit compares live GitHub settings with the committed policy, including both aggregate gates, strict branch freshness, pull-request protection, admin enforcement, conversation resolution, force-push/deletion policy and merge methods.

This is the closure evidence for #434/#456. Static policy validation alone does not prove that the GitHub repository settings were actually applied.

## Machine-readable policy

`scripts/validate-repository-policy.py` guards repository files against policy drift. `scripts/github_repository_governance_audit.py` validates exported live GitHub state against the same policy.


## Apply and capture evidence in one command

With a GitHub CLI login that has repository administration permission:

~~~bash
bash scripts/apply-github-governance.sh jarmo164/ForestIQ apply
~~~

The command performs the full #456 sequence:

1. applies `main` branch protection from `.github/forestiq-repository-policy.json`;
2. configures repository merge settings from the same policy;
3. re-reads the live repository and `main` protection configuration;
4. runs the governance audit;
5. writes `repository.json`, `main-protection.json`, `governance-audit.md` and machine-readable `governance-audit.json` under `artifacts/governance/`.

A read-only recheck is available with:

~~~bash
bash scripts/apply-github-governance.sh jarmo164/ForestIQ verify
~~~

A passing audit is the machine-readable live evidence for #456. The command never uses PR admin-bypass merging.


## Branch-protection-only evidence

For #456, capture the protected `main` state without changing repository settings:

~~~bash
bash scripts/capture-main-protection-evidence.sh jarmo164/ForestIQ
~~~

This read-only command exports the live branch-protection JSON and writes Markdown plus machine-readable audit evidence under `artifacts/main-protection/`. It checks required aggregate gates, strict/up-to-date mode, PR protection, admin enforcement, conversation resolution, force-push blocking and branch-deletion blocking.

Use `scripts/apply-github-governance.sh` when settings need to be changed; use this capture command when you only need closure evidence for #456.
