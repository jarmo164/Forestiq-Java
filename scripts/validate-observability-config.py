from __future__ import annotations
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
 'infra/prometheus/prometheus.yml',
 'infra/prometheus/rules/forestiq-alerts.yml',
 'infra/grafana/provisioning/dashboards/forestiq-api-bff-health.json',
 'infra/grafana/provisioning/dashboards/forestiq-ingestion-workers.json',
 'infra/grafana/provisioning/dashboards/forestiq-source-freshness.json',
 'compose.full.yaml',
 'docs/operations/production-slos.md',
 'docs/runbooks/observability-alerts.md',
]
for relative in REQUIRED:
    if not (ROOT / relative).exists():
        raise SystemExit(f'Missing required observability file: {relative}')
for relative in REQUIRED:
    if relative.endswith('.json'):
        data = json.loads((ROOT / relative).read_text(encoding='utf-8'))
        if not data.get('uid') or not data.get('panels'):
            raise SystemExit(f'Invalid Grafana dashboard: {relative}')
prometheus = (ROOT / 'infra/prometheus/prometheus.yml').read_text(encoding='utf-8')
if 'rule_files:' not in prometheus or '/etc/prometheus/rules/*.yml' not in prometheus:
    raise SystemExit('Prometheus rule_files is not configured')
alerts = (ROOT / 'infra/prometheus/rules/forestiq-alerts.yml').read_text(encoding='utf-8')
for alert in ['ForestIQApiReadinessFailure','ForestIQApiHigh5xxRate','ForestIQIntegrationWorkerHeartbeatStale','ForestIQIntegrationQueueBacklog','ForestIQProviderFailures','ForestIQGeospatialFreshnessLag']:
    if alert not in alerts:
        raise SystemExit(f'Missing alert: {alert}')
compose = (ROOT / 'compose.full.yaml').read_text(encoding='utf-8')
if './infra/prometheus/rules:/etc/prometheus/rules:ro' not in compose:
    raise SystemExit('compose.full.yaml does not mount Prometheus rules')
print('Observability configuration validation passed.')
