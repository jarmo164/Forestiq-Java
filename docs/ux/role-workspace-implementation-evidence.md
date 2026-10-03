# Role-based workspace implementation evidence

Epic: #288  
Validation issue: #305

## Implemented contract

The repository implementation for #301–#304 is now protected by a focused route-policy regression suite.

The single declarative route policy in `frontend/lib/workspace-routes.ts` drives:

- role-sensitive navigation visibility;
- direct authenticated URL access;
- role-specific default workspace selection;
- safe internal deep-link restoration.

The authenticated layout keeps recoverable loading/error states, while `AppShell` provides a keyboard skip link, scrollable desktop navigation and focus-visible navigation controls.

## Regression coverage

`frontend/lib/workspace-routes.test.ts` now locks:

- CALLER -> `/sales`;
- EVALUATOR -> `/evaluation-workdesk`;
- manager/admin default behavior;
- direct-route authorization for protected route families;
- admin-only contract configuration;
- rejection of external/protocol-relative/backslash deep-link redirects.

These tests run in the normal frontend unit suite.

## Closure boundary

This commit completes the code-side regression contract for #288. It does **not** replace #305.

The epic should remain open until #305 contains at least five real usability sessions across at least three role profiles, anonymized aggregate metrics and measured variant decisions. Code review cannot manufacture that evidence.
