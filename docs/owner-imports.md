# Owner onboarding imports

Owner onboarding is available to `ORG_ADMIN` and `CRM_MANAGER` users from the Owners page. The workflow is intentionally split into inspect, preview and commit phases so uploaded organization data is never written before the user has reviewed the mapping and validation result.

## Trust boundary

The authenticated `OrganizationContext` is the only authority for `organization_id`. Uploaded CSV/XLSX columns cannot map to `organizationId`; the import mapping allow-list contains only:

- `displayName`
- `personalOrRegistryCode`
- `ownerType`
- `email`
- `phone`
- `address`
- `info`

If a file contains an organization/tenant column it is treated as ordinary unused input unless the user maps other supported fields. An attempt to submit an unsupported mapping key is rejected with HTTP 400.

## File limits and parsing

- Formats: `.csv` and `.xlsx`.
- Maximum file size: 10 MB.
- Maximum non-empty data rows: 10,000.
- Maximum columns: 100.
- CSV supports comma/semicolon delimiters, quoted delimiters, escaped quotes and quoted newlines.
- XLSX uses the first worksheet and never evaluates formulas. A formula cell causes validation failure instead of importing a potentially stale calculated value.
- Header names must be non-empty and unique case-insensitively.

## Workflow

1. **Inspect** reads headers and a five-row sample and proposes a best-effort mapping. There are no database writes.
2. **Preview / dry-run** applies the explicit mapping, validates every row, calculates the SHA-256 of the file, and classifies each row as `READY`, `DUPLICATE` or `INVALID`. There are no database writes.
3. **Commit** requires the same file and the SHA-256 returned by preview. The server repeats parsing and validation instead of trusting client-side preview data.
4. Only `READY` rows are inserted. Existing owners and invalid rows are skipped and included in the batch counts/error report.
5. Every created owner gets a `SYSTEM` activity identifying the import batch and follows the importing user, matching the normal manual-create behavior.
6. The committed batch is persisted in `outbox_event` as an audit-only `OWNER_IMPORT_BATCH / OWNER_IMPORT_COMMITTED` event with `published_at` set. Its JSON payload contains the organization, subject, filename, SHA-256, mapping, counts, created owner IDs and rejected-row details.

The database unique constraint on `(organization_id, personal_or_registry_code)` remains the final race-condition backstop. If data changes after preview and the commit collides with that constraint, the transaction rolls back and the caller must preview again.

## Error reports

Preview shows row-level errors in the UI and can download the complete preview error CSV. Committed batches remain visible in import history and expose `GET /api/v1/owners/imports/batches/{batchId}/errors.csv` for duplicate/invalid row reporting.

## Recovery and revert strategy

A technical failure during commit is handled by transaction rollback: owners, activities, followings and the batch audit are committed together or not at all.

A **wrongly mapped but successfully committed batch must not be blindly deleted** because owner deletion can cascade into real downstream CRM data. Recovery uses the `createdOwnerIds` stored in the batch audit:

1. Stop further work on the affected batch owners and record the batch ID in the incident/change ticket.
2. Retrieve the batch audit and its exact `createdOwnerIds` from `outbox_event` within the active organization.
3. For every created owner, verify there are no downstream parcels, deal cases, inheritance cases, documents, reminders, status transitions, user-authored activities or other business records created after import.
4. If every owner is still untouched except for the import `SYSTEM` activity/following, an administrator may remove those owner IDs in one organization-scoped maintenance transaction and retain/export the batch audit before deletion.
5. If any downstream business activity exists, do **not** cascade-delete the owner. Correct/merge the owner data through normal CRM operations and keep the original batch audit as provenance.
6. Re-run the corrected file through inspect → preview → commit; never edit the historical batch payload or historical offer/activity data.

There is deliberately no one-click production revert endpoint: safe revert depends on verifying that no legitimate downstream data would be cascaded away.
