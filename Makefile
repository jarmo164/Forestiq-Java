.PHONY: \
	check-backend check-backend-unit check-backend-integration check-frontend \
	check-ingest check-ingest-unit check-ingest-integration check-metsaregister-integration \
	check-contract check-e2e check-security check-observability check-ci-boundaries check-repository-policy \
	check-production-performance-tooling check-usability-evidence-tooling check-ingestion-cutover-tooling \
	check-metsaregister-evidence-tooling check-geospatial-evidence-tooling check-owner-evidence-tooling \
	check-cleanup-preflight-tooling check-rik-metsis-evidence-tooling check-ci-baseline-tooling \
	check-governance-audit-tooling check-runner-registration-tooling check-runner-reboot-tooling \
	check-self-hosted-security-tooling check-production-deploy-contract check-render-blueprint-contract check-security-workflow-contract check-action-pins check-fork-heavy-policy check-gate-policy check-governance-status check-all \
	test-fast test-backend test-backend-integration test-frontend test-ingest test-heavy test-e2e

OPENAPI_URL ?= http://localhost:8080/api/openapi

check-backend:
	cd backend && ./gradlew --no-daemon check
check-backend-unit:
	cd backend && ./gradlew --no-daemon test jacocoTestCoverageVerification
check-backend-integration:
	cd backend && ./gradlew --no-daemon integrationTest
check-frontend:
	cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test:unit:coverage && pnpm build
check-ingest-unit:
	python -m pip install -e "./ingest[test]"
	cd ingest && pytest -m "not integration"
	python -m unittest scripts/test_ingestion_deployment_contract.py scripts/test_verify_production_ingestion.py scripts/test_api_ingest_boundary.py scripts/test_ingestion_runtime_ownership.py
check-ingest: check-ingest-unit
check-ingest-integration:
	@test -n "$(TEST_POSTGIS_URL)" || (echo "TEST_POSTGIS_URL is required" && exit 1)
	cd ingest && TEST_POSTGIS_URL="$(TEST_POSTGIS_URL)" pytest -m integration
check-metsaregister-integration:
	@test -n "$(TEST_POSTGIS_URL)" || (echo "TEST_POSTGIS_URL is required" && exit 1)
	python -m pip install -r scripts/metsaregister/requirements-dev.txt
	TEST_POSTGIS_URL="$(TEST_POSTGIS_URL)" python -m pytest -q scripts/metsaregister/tests
check-contract:
	cd frontend && OPENAPI_URL=$(OPENAPI_URL) pnpm verify:api-contract && pnpm typecheck
check-e2e:
	docker compose config
	cd frontend && pnpm exec playwright install chromium && pnpm test:e2e:playwright
check-security:
	cd frontend && pnpm audit --audit-level high
	@if command -v pip-audit >/dev/null 2>&1; then \
		if [ -f ingest/requirements.txt ]; then pip-audit -r ingest/requirements.txt; \
		elif [ -f ingest/pyproject.toml ]; then python -m pip install ./ingest && pip-audit; \
		else echo "No Python ingestion dependency manifest found; skipping pip-audit."; fi; \
	else echo "pip-audit is not installed; install it with: python -m pip install pip-audit"; exit 1; fi
check-observability:
	python scripts/validate-observability-config.py
check-ci-boundaries:
	python scripts/ci/validate-runner-boundaries.py
check-repository-policy:
	python scripts/validate-repository-policy.py
check-production-performance-tooling:
	python -m unittest scripts/test_production_performance_report.py
check-usability-evidence-tooling:
	python -m unittest scripts/test_usability_baseline_report.py
check-ingestion-cutover-tooling:
	python -m unittest scripts/test_ingestion_cutover_bundle.py
check-metsaregister-evidence-tooling:
	python -m unittest scripts/test_build_metsaregister_cutover_evidence.py
check-geospatial-evidence-tooling:
	python -m unittest scripts/test_build_geospatial_cutover_evidence.py
check-owner-evidence-tooling:
	python -m unittest scripts/test_build_owner_flows_cutover_evidence.py
check-cleanup-preflight-tooling:
	python -m unittest scripts/test_validate_legacy_ingestion_cleanup.py
check-rik-metsis-evidence-tooling:
	python -m unittest scripts/test_build_rik_metsis_cutover_evidence.py
check-ci-baseline-tooling:
	python -m unittest scripts/ci/test_main_ci_baseline_report.py
check-governance-audit-tooling:
	python -m unittest scripts/test_github_repository_governance_audit.py scripts/test_github_governance_apply_contract.py scripts/test_github_branch_protection_audit.py
check-runner-registration-tooling:
	python -m unittest scripts/test_github_runner_registration_audit.py scripts/test_runner_registration_capture_contract.py
check-runner-reboot-tooling:
	python -m unittest scripts/ci/test_verify_runner_reboot.py scripts/ci/test_capture_runner_reboot_contract.py
check-self-hosted-security-tooling:
	python -m unittest scripts/ci/test_validate_self_hosted_security.py scripts/ci/test_runner_environment_security.py

check-production-deploy-contract:
	python scripts/validate-production-deploy-contract.py
	python -m unittest scripts/test_controlled_production_deploy.py scripts/release/test_release_supply_chain.py scripts/release/test_migration_policy.py scripts/release/test_production_release_verification.py scripts/release/test_release_manifest.py

check-render-blueprint-contract:
	python scripts/validate_render_blueprint_contract.py
	python -m unittest scripts/test_render_blueprint_contract.py

check-security-workflow-contract:
	python scripts/validate-security-workflow.py

check-action-pins:
	python scripts/validate-github-action-pins.py

check-fork-heavy-policy:
	python scripts/ci/validate-fork-heavy-policy.py

check-gate-policy:
	python -m unittest scripts/ci/test_validate_gate_results.py

check-governance-status:
	python scripts/validate_ci_cd_governance_status.py
	python -m unittest scripts/test_ci_cd_governance_status.py

test-backend: check-backend-unit
test-backend-integration: check-backend-integration
test-frontend: check-frontend
test-ingest: check-ingest-unit
test-e2e: check-e2e
test-fast: check-ci-boundaries check-repository-policy check-production-performance-tooling check-usability-evidence-tooling check-ingestion-cutover-tooling check-metsaregister-evidence-tooling check-geospatial-evidence-tooling check-owner-evidence-tooling check-cleanup-preflight-tooling check-rik-metsis-evidence-tooling check-ci-baseline-tooling check-governance-audit-tooling check-runner-registration-tooling check-runner-reboot-tooling check-self-hosted-security-tooling check-production-deploy-contract check-render-blueprint-contract check-security-workflow-contract check-action-pins check-fork-heavy-policy check-gate-policy check-governance-status check-backend-unit check-frontend check-ingest-unit check-observability
test-heavy: check-backend-integration check-ingest-integration check-metsaregister-integration check-contract check-e2e
check-all: test-fast test-heavy check-security
