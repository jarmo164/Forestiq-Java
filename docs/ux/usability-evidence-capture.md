# Usability evidence capture

Related: #305 and #288

Use the existing session plan in `docs/ux/role-based-usability-baseline-plan.md`.

Copy `docs/ux/usability-session-template.csv` to a private working file and enter one row per task. Use anonymous session IDs only; do not add names, emails, phone numbers, customer identifiers, credentials or production payloads.

Generate the aggregate report:

~~~bash
python scripts/usability_baseline_report.py usability-sessions.csv \
  --output usability-baseline.md \
  --require-sample
~~~

The command fails the sample gate until there are at least five unique sessions and at least three role profiles.

The generated report summarizes completion, time, help, errors and confidence by scenario plus variant metrics. A human still has to:

- link each high-impact finding to an existing/new issue;
- document which variant was selected;
- document the rejected alternative and why;
- verify that the sessions were real and consent/privacy rules were followed.

Do not commit raw session notes if they contain accidental personal information. Only the anonymized aggregate report belongs in GitHub evidence.
