#!/usr/bin/env bash
set -Eeuo pipefail

install_dir="${RUNNER_INSTALL_DIR:-$HOME/actions-runner}"
action="${1:-install}"

cd "$install_dir"

if [[ ! -f .runner || ! -x ./svc.sh ]]; then
  echo "Runner is not configured at $install_dir. Run scripts/install-self-hosted-runner.sh first." >&2
  exit 1
fi

case "$action" in
  install)
    ./svc.sh install || true
    ./svc.sh start
    ./svc.sh status
    ;;
  start)
    ./svc.sh start
    ./svc.sh status
    ;;
  stop)
    ./svc.sh stop
    ;;
  restart)
    ./svc.sh stop || true
    ./svc.sh start
    ./svc.sh status
    ;;
  status)
    ./svc.sh status
    ;;
  logs)
    diag_dir="$install_dir/_diag"
    if [[ ! -d "$diag_dir" ]]; then
      echo "No runner diagnostic directory found at $diag_dir." >&2
      exit 1
    fi
    latest="$(find "$diag_dir" -type f -name 'Runner_*.log' -print0 | xargs -0 ls -t 2>/dev/null | head -n 1 || true)"
    if [[ -z "$latest" ]]; then
      latest="$(find "$diag_dir" -type f -print0 | xargs -0 ls -t 2>/dev/null | head -n 1 || true)"
    fi
    [[ -n "$latest" ]] || { echo "No runner logs found." >&2; exit 1; }
    echo "Showing: $latest"
    tail -n 200 "$latest"
    ;;
  uninstall)
    ./svc.sh stop || true
    ./svc.sh uninstall
    ;;
  *)
    echo "Usage: $0 {install|start|stop|restart|status|logs|uninstall}" >&2
    exit 2
    ;;
esac
