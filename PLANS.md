# ForestIQ ExecPlans

Use an ExecPlan for changes that span multiple files/layers, alter architecture or schema, require migration/backfill work, or are likely to take more than one focused implementation pass.

## Rules

- The plan is a working document, not ceremony. Update it as facts change.
- Record concrete repository paths, commands, and observable acceptance criteria.
- Do not copy issue text blindly; translate it into implementation steps.
- Keep scope explicit. Note intentionally deferred work.
- Prefer reversible steps and preserve working checkpoints.

## Template

```md
# <Issue / change title>

## Goal
Describe the user-visible or system-visible outcome.

## Scope
In scope:
- ...

Out of scope:
- ...

## Current state
Relevant files/components and what they currently do.

## Design / decisions
- Decision: ...
  Reason: ...

## Implementation steps
1. ...
2. ...
3. ...

## Verification
- [ ] `just backend-test`
- [ ] `just ingest-test`
- [ ] `just frontend-check`
- [ ] `just verify` when cross-layer
- [ ] Manual acceptance check: ...

## Risks / rollback
- Risk: ...
  Mitigation: ...

## Progress
- [ ] Analysis complete
- [ ] Implementation complete
- [ ] Tests complete
- [ ] Diff reviewed
```

## Planning guidance by layer

### Backend
State API/DB contract changes, Flyway migrations, authorization implications, and jOOQ regeneration when relevant.

### Ingest
State provider/source behavior, idempotency strategy, retry/failure semantics, normalization rules, and database ownership.

### Frontend
State affected routes/components, API contract dependencies, loading/error/empty states, and accessibility expectations.

### Cross-layer
Define the contract first, then implement producer/provider changes before consumers where practical. Verify generated API types after backend contract changes.
