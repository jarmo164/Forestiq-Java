# Role-based usability baseline plan

**Related:** #288, #305  
**Date:** 2026-09-08  
**Status:** Ready for moderated or structured asynchronous sessions

## Purpose

Validate whether the delivered role-based navigation, authentication recovery, keyboard/accessibility patterns and core workflows match real ForestIQ work before a broader UI redesign is prioritized.

This plan deliberately separates **implemented correctness** (#301–#304) from **user validation** (#305). The latter requires real participants and must not be simulated or inferred from code review.

## Participants

Minimum acceptance sample:

- at least 5 sessions total;
- at least 3 role profiles represented;
- target coverage across all four roles where practical:
  - CALLER / helistaja;
  - EVALUATOR / hindaja;
  - SALES_MANAGER / müügijuht;
  - ORG_ADMIN / administraator.

Do not record names, personal identifiers, customer records or production secrets in notes. Use test accounts and non-sensitive fixture data.

## Consent and privacy

Before each session tell the participant:

- the test evaluates the product, not the person;
- participation is voluntary and can be stopped at any time;
- record only task outcomes, timing, errors, assistance and product feedback;
- do not paste real customer/person data into free-text notes;
- screenshots/recordings are optional and require explicit consent;
- published findings are aggregated and anonymized.

Suggested consent statement:

> I understand this session tests ForestIQ usability. I agree that anonymous task metrics and product feedback may be recorded for product improvement. No unnecessary personal or customer data will be retained.

## Environment

Use a stable deployed test environment or a deterministic local E2E environment with seeded accounts for each role.

For each session record:

- environment/build commit;
- browser/device class;
- role;
- session date;
- whether the participant is new, occasional or experienced with ForestIQ.

Do not store bearer tokens, OIDC state values, passwords or raw private API payloads.

## Metrics

For every task record:

- **Success:** completed / completed with help / failed;
- **Time on task:** seconds;
- **Assistance:** none / one prompt / repeated help;
- **Errors:** count of wrong routes, invalid actions or recovery loops;
- **Recovery:** self-recovered / recovered with help / could not recover;
- **Confidence:** 1–5 after task;
- **Comment:** short anonymized observation.

For each flow calculate:

- completion rate;
- median task time;
- help rate;
- error rate;
- recovery rate;
- median confidence.

A high-impact finding requires at least one of:

- repeated observation across participants;
- task failure/blocker;
- high-risk role/security misunderstanding;
- accessibility failure that prevents completion;
- measured variant improvement large enough to justify change.

## Scenario A — Start the workday and recover intended work

**Roles:** all; CALLER is mandatory.

1. Open a valid protected deep link while logged out.
2. Sign in.
3. Verify the allowed deep link is restored.
4. Sign out and sign in without a deep link.
5. Verify role-appropriate default workspace (CALLER should land in Sales/Müük).
6. Attempt one route the role is not allowed to use.

Observe:

- whether the participant understands where they landed;
- whether denied access is clear rather than appearing broken;
- whether navigation labels match role expectations;
- whether work context survives authentication.

Success criteria:

- no participant is stranded on login/loading state;
- allowed deep link recovers without manual navigation;
- denied route has a clear next step;
- role default workspace is understood without explanation.

## Scenario B — Owner search, contact and follow-up

**Primary role:** CALLER; SALES_MANAGER may also run it.

1. Find a seeded owner.
2. Open owner context.
3. Record a contact outcome.
4. Create the next follow-up/reminder.
5. Return to the work queue and locate the next item.

Observe terminology, route switching, visible save/error feedback and whether participant knows what happened after submit.

## Scenario C — Map to qualification

**Roles:** CALLER, EVALUATOR, SALES_MANAGER.

1. Search an address or exact cadastral code.
2. Focus the parcel on the map.
3. Inspect parcel/source freshness information.
4. Continue into the relevant qualification/evaluation action.
5. Return to the map without losing parcel context.

Observe whether map controls, parcel selection and source/freshness indicators are understandable.

## Scenario D — Evaluation decision and conflict recovery

**Primary role:** EVALUATOR.

1. Open an assigned evaluation.
2. Change data required for a decision.
3. Trigger or simulate an optimistic-concurrency conflict using the prepared test fixture.
4. Recover by reloading/refreshing the current server state.
5. Complete the decision.

Observe whether the conflict is understood as recoverable rather than data loss.

## Scenario E — Administration / bulk assignment

**Primary roles:** SALES_MANAGER, ORG_ADMIN.

1. Open the relevant work queue/admin view.
2. Assign or bulk-assign seeded work.
3. Attempt an action that is disabled for a user pending removal or without sufficient permission.
4. Confirm one destructive/reversible action.
5. Undo where the flow supports undo.

Observe permission comprehension, confirmation wording and recovery confidence.

## Keyboard/accessibility pass

At least one session or dedicated pass must use keyboard-only navigation for:

- first Tab -> **Liigu põhisusse** skip link;
- sidebar through all items at 1280×720 or equivalent low-height viewport;
- modal/dialog open, cancel, Escape and focus return;
- form labels/errors/status announcements;
- retry action on recoverable error state.

Record any focus loss, hidden focus, inaccessible icon action or keyboard trap as a blocker.

## Variant comparison: two critical flows

Do not redesign the entire application. Compare only two evidence-sensitive flows.

### Flow 1 — Workday start

**Variant A:** current role-default landing with declarative route/navigation rules.  
**Variant B:** role-default landing plus a compact “continue previous work / primary queue” decision card.

Measure:

- time until first meaningful task action;
- wrong-route count;
- participant confidence.

Keep Variant B only if it measurably improves task start without adding confusion.

### Flow 2 — Map to qualification

**Variant A:** current map search + selected parcel panel.  
**Variant B:** same map with a single explicit next-action block after parcel selection, keeping detailed attributes behind domain APIs.

Measure:

- time from search to correct qualification action;
- wrong-click count;
- whether source/freshness is noticed when relevant.

The selected variant must be based on measured participant results, not preference.

## Session worksheet

Use one row per task:

| Session | Role | Scenario | Success | Seconds | Help | Errors | Recovery | Confidence 1–5 | Observation |
| --- | --- | --- | --- | ---: | --- | ---: | --- | ---: | --- |
| S01 | CALLER | A |  |  |  |  |  |  |  |

Keep raw notes private if they accidentally contain personal detail. Put only anonymized aggregate findings into GitHub.

## Finding format

For every high-impact finding create/update backlog evidence in this format:

```text
Finding: <short behavior/problem>
Roles/sessions: <anonymous counts only>
Evidence: completion/time/error/help/confidence result
Impact: blocker / high / medium / low
Existing issue: #NNN OR new issue required
Decision: keep / change / test another variant
Rejected alternative: <what and why>
```

## Completion gate for #305 and #288

Do not close #305 from code review alone. Close it only when:

1. at least 5 real sessions have been completed;
2. at least 3 role profiles are represented;
3. results are aggregated anonymously;
4. two critical-flow variants have measured comparison evidence;
5. each high-impact finding is linked to an existing or new backlog item;
6. the selected/rejected solution decision is documented.

After that evidence exists, #288 can be closed if no remaining blocker contradicts its role-based, recoverable and accessible workspace success criteria.
