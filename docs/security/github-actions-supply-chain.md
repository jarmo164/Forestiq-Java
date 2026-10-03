# GitHub Actions supply-chain policy

Related: #500.

All external actions used by ForestIQ workflows are pinned to full 40-character Git commit SHAs. Human-readable release information stays in an inline comment, for example:

~~~yaml
uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
~~~

Local reusable workflows under `./.github/workflows/` are not external dependencies and therefore do not require a SHA.

`scripts/validate-github-action-pins.py` scans every workflow during `make test-fast` and fails on mutable refs such as `@v4`, `@main` or `@0.30.0`.

Dependabot already manages the `github-actions` ecosystem weekly from `.github/dependabot.yml`. With SHA-pinned actions it updates the immutable commit while retaining/updating the human-readable version comment.

When adding a new external action:

1. resolve the intended release/tag to its final commit;
2. pin the full commit SHA;
3. add the release/tag as a comment;
4. keep workflow/job token permissions minimal;
5. let Dependabot propose later pin updates through normal PR review.
