#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LABEL="${BENCHMARK_LABEL:-final-gates-$(date +%Y%m%d-%H%M%S)}"
OUT_DIR=".run/performance/${LABEL}"
mkdir -p "$OUT_DIR"
STATS_PID=""

cleanup() {
  if [[ -n "$STATS_PID" ]]; then
    kill "$STATS_PID" >/dev/null 2>&1 || true
    wait "$STATS_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

host_snapshot() {
  local phase="$1"
  {
    echo "phase=$phase"
    echo "captured=$(date -Iseconds)"
    echo "docker_mem_total_bytes=$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
    if command -v powershell.exe >/dev/null 2>&1; then
      powershell.exe -NoProfile -Command \
        '$o=Get-CimInstance Win32_OperatingSystem; "total_visible_kb=$($o.TotalVisibleMemorySize)"; "free_physical_kb=$($o.FreePhysicalMemory)"; "total_virtual_kb=$($o.TotalVirtualMemorySize)"; "free_virtual_kb=$($o.FreeVirtualMemory)"' \
        | tr -d '\r'
    elif command -v free >/dev/null 2>&1; then
      free -k
    fi
  } > "$OUT_DIR/host-${phase}.txt"
}

host_snapshot before
(
  echo 'timestamp,name,cpu_percent,mem_usage,mem_percent,block_io,pids'
  while true; do
    ts="$(date -Iseconds)"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.BlockIO}}|{{.PIDs}}' 2>/dev/null \
      | awk -F'|' -v ts="$ts" 'BEGIN{OFS=","} /^forestiq-/ {print ts,$1,$2,$3,$4,$5,$6}'
    sleep 1
  done
) > "$OUT_DIR/docker-stats.csv" &
STATS_PID=$!

BENCHMARK_LABEL="$LABEL" bash scripts/performance-final-gates.sh

kill "$STATS_PID" >/dev/null 2>&1 || true
wait "$STATS_PID" >/dev/null 2>&1 || true
STATS_PID=""
host_snapshot after

awk -F, '
  NR==1 {next}
  {
    cpu=$3; gsub(/%/,"",cpu); mem=$5; gsub(/%/,"",mem);
    if(cpu+0>maxcpu[$2]+0) maxcpu[$2]=cpu+0;
    if(mem+0>maxmem[$2]+0) maxmem[$2]=mem+0;
    lastusage[$2]=$4; lastblock[$2]=$6; samples[$2]++;
  }
  END {
    print "container\tsamples\tmax_cpu_percent\tmax_mem_percent\tlast_mem_usage\tlast_block_io";
    for(name in samples) printf "%s\t%d\t%.2f\t%.2f\t%s\t%s\n",name,samples[name],maxcpu[name],maxmem[name],lastusage[name],lastblock[name];
  }
' "$OUT_DIR/docker-stats.csv" | sort > "$OUT_DIR/resource-summary.tsv"

{
  echo
  echo "Resource summary"
  cat "$OUT_DIR/resource-summary.tsv"
  echo
  echo "Host before"
  cat "$OUT_DIR/host-before.txt"
  echo
  echo "Host after"
  cat "$OUT_DIR/host-after.txt"
} >> "$OUT_DIR/result.txt"

cat "$OUT_DIR/resource-summary.tsv"
echo "Complete final-gate artifacts: $OUT_DIR"
