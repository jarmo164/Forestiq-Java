# Issue #557 — workflow orchestration boundary

## Goal
Keep `WorkflowService` as the application use-case orchestrator while moving persistence and cross-domain responsibilities behind typed contact-policy, owner, deal, identity and work-item/reminder APIs. Preserve the existing HTTP behavior and timeline semantics.

## Scope
In scope:
- isolate contact outcome persistence behind a workflow-owned policy port;
- add a reminder-owned workflow work-item API for open-item lookup, creation, queue reads and complete/postpone/delegate commands;
- add a deal-owned workflow API for deal linkage, timeline event append and the single deal-history projection;
- expose owner workflow history through `OwnerCollaborationPort`;
- remove duplicate workflow helper services once orchestration is centralized;
- add characterization and ArchUnit/boundary tests.

Out of scope:
- public HTTP route or payload changes;
- Python provider ingestion;
- schema changes or Flyway migrations;
- unrelated reminder/deal/owner refactors.

## Current state
- `WorkflowService` executes SQL against contact outcomes, reminders, reminder events, owner activity and deal tables.
- `DealHistoryService` duplicates the deal-history filtering/cursor/projection already present in `WorkflowService`.
- `ContactWorkflowService` and `OwnerOpenActivityService` contain reminder SQL outside the reminder module.
- Identity, owner collaboration and deal lifecycle boundaries from #553–#555 are available on `main`.

## Design / decisions
- Decision: `WorkflowService` has no `DSLContext` dependency.
  Reason: it should coordinate typed application contracts, not own persistence.
- Decision: reminder/work-item persistence is owned by `ee.forestiq.reminder.workflow`.
  Reason: reminder and reminder-event writes must have one module owner.
- Decision: workflow-related deal lookup/event/history is owned by `ee.forestiq.deal.workflow`.
  Reason: deal-case/event persistence remains deal-owned and deal history has one projection implementation.
- Decision: owner-history query is added to `OwnerCollaborationPort` and implemented through the owner repository.
  Reason: workflow must not query `owner_activity` directly.
- Decision: existing activity deal inference moves into `WorkflowService`.
  Reason: contact creation is one use case and no longer needs a SQL-aware wrapper service.

## Implementation steps
1. Add contact outcome policy port/adapter.
2. Add reminder workflow work-item port/adapter.
3. Add deal workflow port/adapter and canonical deal-history query.
4. Extend owner collaboration history query.
5. Rewrite `WorkflowService` as orchestration only and simplify `WorkflowController`.
6. Remove duplicate SQL-aware workflow helper services.
7. Add characterization, foreign-DML and ArchUnit tests.
8. Run backend verification and review the diff.

## Verification
- [ ] `./gradlew test --no-daemon`
- [ ] `./gradlew integrationTest --no-daemon`
- [ ] `git diff --check`
- [ ] `WorkflowService` contains no direct foreign persistence SQL.
- [ ] Deal history has one cursor/filter/projection implementation.
- [ ] Contact + next activity, existing activity/deal link, next-step requirement, complete/postpone/delegate and scope behavior are covered.

## Risks / rollback
- Risk: moving SQL may subtly change filtering, cursor order or audit text.
  Mitigation: copy the current SQL semantics into the owning adapters and characterize the orchestration.
- Risk: contact existing-activity deal inference may regress when removing `ContactWorkflowService`.
  Mitigation: add a focused characterization test for inferred deal linkage and mismatch rejection.

## Progress
- [x] Analysis complete
- [ ] Implementation complete
- [ ] Tests complete
- [ ] Diff reviewed
