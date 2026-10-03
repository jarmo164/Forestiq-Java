# ForestIQ Mac Studio self-hosted runner

ForestIQ heavy CI targets a repository-scoped Apple Silicon runner with these labels:

- `self-hosted`
- `macOS`
- `ARM64`
- `forestiq`
- `heavy-ci`

## Register the runner

Create a short-lived **repository runner registration token** from:

`Settings -> Actions -> Runners -> New self-hosted runner`

On the Mac Studio, from the ForestIQ repository:

~~~bash
RUNNER_TOKEN='<short-lived token>' \
  bash scripts/install-self-hosted-runner.sh
~~~

Optional overrides:

~~~bash
RUNNER_NAME=koodikratt \
RUNNER_INSTALL_DIR="$HOME/actions-runner" \
RUNNER_REPO=jarmo164/ForestIQ \
RUNNER_TOKEN='<token>' \
  bash scripts/install-self-hosted-runner.sh
~~~

The registration token is used only by `config.sh`; the installer does not persist it.

The GitHub runner supplies default `self-hosted`, `macOS` and `ARM64` labels. The installer adds the project-specific `forestiq` and `heavy-ci` labels.

## Install the runner as a service

After registration:

~~~bash
bash scripts/install-self-hosted-runner-service.sh install
~~~

The official GitHub runner `svc.sh` installs a per-user launchd service on macOS. This makes the runner start after reboot/login and lets launchd restart the managed process according to GitHub runner service configuration.

Useful operations:

~~~bash
bash scripts/install-self-hosted-runner-service.sh status
bash scripts/install-self-hosted-runner-service.sh restart
bash scripts/install-self-hosted-runner-service.sh logs
~~~

The `logs` command tails the newest runner diagnostic log from `$HOME/actions-runner/_diag`.

To stop or uninstall only the launchd service:

~~~bash
bash scripts/install-self-hosted-runner-service.sh stop
bash scripts/install-self-hosted-runner-service.sh uninstall
~~~

Uninstalling the service does not remove the repository runner registration.

## Repository scope

Register this runner from the **ForestIQ repository settings**, not as a broadly shared organization runner. That prevents unrelated repositories from targeting the machine.

## Verify registration and reboot recovery

1. Open `Settings -> Actions -> Runners`.
2. Confirm `koodikratt` reports **Online**.
3. Run **Self-hosted runner diagnostics**.
4. Confirm Docker, Compose, Java, Node/pnpm and Python checks pass.
5. Reboot the Mac Studio.
6. Confirm the runner returns **Online** without manually running `run.sh`.
7. Run the diagnostics workflow once more.

The runner service should use a dedicated non-admin CI account when #442 security hardening is applied.

## Remove/re-register

GitHub supplies a short-lived removal token. First stop/uninstall the service, then remove the registration:

~~~bash
bash scripts/install-self-hosted-runner-service.sh uninstall
cd "$HOME/actions-runner"
./config.sh remove --token '<remove-token>'
~~~

Then rerun the registration installer with a new token.


## Acceptance workflow

After #440–#442 have been applied on the Mac Studio, run **Self-hosted runner acceptance** from GitHub Actions.

The workflow executes `scripts/ci/runner-acceptance.sh` on the actual `heavy-ci` runner and fails unless:

- the host is Apple Silicon macOS;
- the runner name/context matches `koodikratt`, macOS and ARM64;
- Git, Docker/Compose, Node, pnpm, Java and Python are available;
- the official runner launchd service reports healthy;
- the CI process runs under a non-admin macOS account;
- ForestIQ scoped cleanup completes successfully.

A Markdown acceptance report is uploaded for 30 days even when the check fails. Run it once before reboot and once after reboot; the post-reboot green run is the evidence needed to close the live runner acceptance portion of #431.


## Verify repository runner registration

Capture and validate repository-scoped runner registration in one command:

~~~bash
bash scripts/capture-runner-registration-evidence.sh jarmo164/ForestIQ
~~~

The command reads only the repository-scoped `/actions/runners` endpoint and writes the raw GitHub response plus a validated Markdown report under `artifacts/runner-registration/`. It does not create, delete or re-register a runner.

The audit fails unless exactly one `koodikratt` runner is registered, it is Online and idle at audit time, and it exposes all required labels: `self-hosted`, `macOS`, `ARM64`, `forestiq` and `heavy-ci`.

This export plus a successful **Self-hosted runner acceptance** workflow run provides the machine-checkable evidence for #440.


## Prove automatic reboot recovery

The acceptance workflow now uploads both Markdown and machine-readable JSON. To prove #441 after a real restart:

1. Run **Self-hosted runner acceptance** and download `acceptance.json` as the before evidence.
2. Reboot the Mac Studio normally.
3. Do not start `run.sh` or the runner manually.
4. Wait for the repository runner to return Online and run **Self-hosted runner acceptance** again.
5. Download the second `acceptance.json`.
6. Capture and verify both workflow runs in one command:

~~~bash
bash scripts/ci/capture_runner_reboot_evidence.sh <before-run-id> <after-run-id>
~~~

The command downloads both acceptance artifacts and writes `reboot-evidence.md` plus machine-readable `reboot-evidence.json` under `artifacts/runner-reboot/`.

The verifier requires two green acceptance runs from the expected Mac runner and proves that the second run happened after a later macOS boot epoch. This distinguishes actual launchd recovery from two runs during the same boot.
