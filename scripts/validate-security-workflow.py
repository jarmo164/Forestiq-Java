#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(text, needle, message):
    if needle not in text:
        raise SystemExit("security workflow contract failed: " + message)


def main():
    sec = (ROOT / ".github/workflows/security.yml").read_text(encoding="utf-8")
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    gradle = (ROOT / "backend/build.gradle.kts").read_text(encoding="utf-8")
    constraints = (ROOT / "ingest/security-constraints.txt").read_text(encoding="utf-8")

    header = sec.split("permissions:", 1)[0]
    require(header, "workflow_call:", "Security workflow must be reusable from required CI")
    require(header, 'cron: "23 4 * * 1"', "weekly security schedule is missing")
    if "pull_request:" in header:
        raise SystemExit("security workflow contract failed: direct PR trigger duplicates the required CI security gate")
    if "push:" in header:
        raise SystemExit("security workflow contract failed: direct main push duplicates the required CI security gate")

    if sec.split("jobs:", 1)[0].find("security-events: write") >= 0:
        raise SystemExit("security workflow contract failed: top-level security-events write is forbidden")

    require(sec, "security-events: write", "CodeQL job must be able to upload scanning results")
    require(sec, "dependencyCheckAnalyze", "backend dependency audit task is not invoked")
    require(sec, "pip_audit --local", "Python dependency audit is not invoked")
    require(sec, "dependency-check-report.sarif", "dependency SARIF artifact is not retained")
    require(sec, "github.event.pull_request.head.repo.full_name != github.repository", "fork trust boundary is missing")
    require(sec, "ubuntu-latest", "untrusted fork security runner is missing")
    require(sec, '["self-hosted","macOS","ARM64","forestiq","heavy-ci"]', "trusted security runner is missing")

    require(ci, "security-full:", "full Security workflow must be called from required CI")
    require(ci, "- security-full", "fast-quality-gate must depend on full security")
    require(gradle, 'id("org.owasp.dependencycheck") version "12.2.2"', "OWASP plugin is not pinned")
    require(gradle, "failBuildOnCVSS = 7.0f", "backend high severity gate is missing")
    require(gradle, 'formats = listOf("HTML", "SARIF")', "backend SARIF output is missing")
    require(constraints, "httpx==", "httpx security constraint is missing")
    require(constraints, "psycopg[binary,pool]==", "psycopg security constraint is missing")
    print("Security canonical gate contract is valid.")


if __name__ == "__main__":
    main()
