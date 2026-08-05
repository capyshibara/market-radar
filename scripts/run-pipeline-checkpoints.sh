#!/usr/bin/env bash
# One build, one database backup, one app process, then one stage at a time.
#
# This script never deletes the database and never runs an AI stage in STUB mode.
# A stage marked STOP by /pipeline/checkpoint.json prevents the next stage. WARN
# is retained and logged but does not discard information or block human review.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PORT="${SERVER_PORT:-8081}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${PORT}}"
JAR="${JAR:-target/market-radar-0.1.0-MVP.jar}"
RUN_ID="$(date '+%Y%m%d-%H%M%S')"
OUT_DIR="data/run-artifacts/${RUN_ID}"
BACKUP_DIR="data/backups/${RUN_ID}-pipeline-checkpoint"
RUN_SCOUT="${RUN_SCOUT:-0}"
RESTART_APP="${RESTART_APP:-true}"
REGENERATE_LEGACY_PRODUCT="${REGENERATE_LEGACY_PRODUCT:-0}"
POLL_SECONDS="${POLL_SECONDS:-20}"
STAGE_TIMEOUT_SECONDS="${STAGE_TIMEOUT_SECONDS:-18000}"

WRITER_BASE_URL="${WRITER_BASE_URL:-https://api.openai.com/v1}"
WRITER_MODEL="${WRITER_MODEL:-gpt-5-mini}"
CLASSIFIER_BASE_URL="${CLASSIFIER_BASE_URL:-https://api.deepseek.com}"
CLASSIFIER_MODEL="${CLASSIFIER_MODEL:-deepseek-chat}"
VERIFIER_BASE_URL="${VERIFIER_BASE_URL:-https://api.deepseek.com}"
VERIFIER_MODEL="${VERIFIER_MODEL:-deepseek-chat}"
HOME_COMPANY="${HOME_COMPANY:-Techcom Life}"

mkdir -p "$OUT_DIR" "$BACKUP_DIR"

for key in WRITER_API_KEY CLASSIFIER_API_KEY VERIFIER_API_KEY; do
  if [ -z "${!key:-}" ]; then
    echo "STOP: $key is missing. Export all three real-provider keys in this terminal."
    exit 2
  fi
done
if [ ! -f "$JAR" ]; then
  echo "STOP: $JAR is missing. Build once with: mvn -q -DskipTests package"
  exit 2
fi

is_market_radar_running() {
  curl -fsS "$BASE_URL/pipeline/status.json" >/dev/null 2>&1
}

stop_existing_app() {
  if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then return; fi
  if ! is_market_radar_running; then
    echo "STOP: port $PORT is occupied by a process that is not this Market Radar app."
    exit 2
  fi
  if [ "$RESTART_APP" != "true" ]; then
    echo "STOP: Market Radar is already running. Set RESTART_APP=true so the exported keys"
    echo "      are inherited by a fresh process; runtime keys are never read from disk."
    exit 2
  fi
  local pid
  pid="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN | head -1)"
  echo "Stopping existing Market Radar PID $pid before the H2 backup..."
  kill "$pid"
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then return; fi
    sleep 1
  done
  echo "STOP: app did not release port $PORT after 15 seconds."
  exit 2
}

backup_database() {
  if lsof data/marketradar.mv.db >/dev/null 2>&1; then
    echo "STOP: H2 database is still open; refusing an unsafe live-file backup."
    exit 2
  fi
  cp -p data/marketradar.mv.db "$BACKUP_DIR/marketradar-before-pipeline.mv.db"
  if [ -f data/marketradar.trace.db ]; then
    cp -p data/marketradar.trace.db "$BACKUP_DIR/marketradar-before-pipeline.trace.db"
  fi
  shasum -a 256 "$BACKUP_DIR/marketradar-before-pipeline.mv.db" \
    > "$BACKUP_DIR/SHA256.txt"
  echo "Database backup: $BACKUP_DIR"
}

start_app() {
  nohup java -jar "$JAR" \
    --server.port="$PORT" \
    --marketradar.home-company="$HOME_COMPANY" \
    --marketradar.llm.base-url="$WRITER_BASE_URL" \
    --marketradar.llm.model="$WRITER_MODEL" \
    --marketradar.classifier.base-url="$CLASSIFIER_BASE_URL" \
    --marketradar.classifier.model="$CLASSIFIER_MODEL" \
    --marketradar.verifier.base-url="$VERIFIER_BASE_URL" \
    --marketradar.verifier.model="$VERIFIER_MODEL" \
    > "$OUT_DIR/app.log" 2>&1 &
  echo "$!" > "$OUT_DIR/app.pid"
  for _ in $(seq 1 120); do
    if is_market_radar_running; then
      echo "Market Radar started on $BASE_URL (PID $(< "$OUT_DIR/app.pid"))."
      return
    fi
    if ! kill -0 "$(< "$OUT_DIR/app.pid")" 2>/dev/null; then
      echo "STOP: app exited during startup. See $OUT_DIR/app.log"
      tail -80 "$OUT_DIR/app.log"
      exit 2
    fi
    sleep 1
  done
  echo "STOP: app did not become ready in 120 seconds. See $OUT_DIR/app.log"
  exit 2
}

stage_label() {
  case "$1" in
    ingest) echo "Scout" ;;
    classify) echo "Librarian + Router" ;;
    extract) echo "Researcher + Connector" ;;
    interpret) echo "Analyst + Fact-checker (Gate L1)" ;;
    verify) echo "Independent Verifier (Gate L2)" ;;
    *) echo "$1" ;;
  esac
}

checkpoint_decision() {
  local file="$1" stage="$2"
  python3 -c 'import json,sys
d=json.load(open(sys.argv[1], encoding="utf-8"))
row=next(x for x in d["checkpoints"] if x["stage"] == sys.argv[2])
print(row["decision"])
print(row["message"])' "$file" "$stage"
}

run_stage() {
  local stage="$1" artifact_name="${2:-$1}" pass_note="${3:-}"
  local endpoint="${4:-/pipeline/run/$stage}" allow_waiting="${5:-false}"
  local label start now payload state completed total checkpoint decision message
  label="$(stage_label "$stage")"
  if [ -n "$pass_note" ]; then label="$label — $pass_note"; fi
  echo
  echo "=== $label [$stage] ==="
  curl -fsS -X POST -o /dev/null "$BASE_URL$endpoint"
  start="$(date +%s)"
  while true; do
    payload="$(curl -fsS "$BASE_URL/pipeline/status.json")"
    printf '%s' "$payload" > "$OUT_DIR/status-${artifact_name}.json"
    state="$(printf '%s' "$payload" | python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]]["state"])' "$stage")"
    completed="$(printf '%s' "$payload" | python3 -c 'import json,sys; x=json.load(sys.stdin)[sys.argv[1]]; print(x.get("completed"))' "$stage")"
    total="$(printf '%s' "$payload" | python3 -c 'import json,sys; x=json.load(sys.stdin)[sys.argv[1]]; print(x.get("total"))' "$stage")"
    echo "$(date '+%H:%M:%S') $label: $state ($completed/$total)"
    case "$state" in
      SUCCESS) break ;;
      FAILED)
        echo "STOP: $label failed. See $OUT_DIR/status-${artifact_name}.json and app.log"
        exit 3 ;;
    esac
    now="$(date +%s)"
    if [ $((now - start)) -ge "$STAGE_TIMEOUT_SECONDS" ]; then
      echo "STOP: $label exceeded ${STAGE_TIMEOUT_SECONDS}s; app is left running for diagnosis."
      exit 3
    fi
    sleep "$POLL_SECONDS"
  done

  checkpoint="$OUT_DIR/checkpoint-after-${artifact_name}.json"
  curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$checkpoint"
  {
    IFS= read -r decision
    IFS= read -r message
  } < <(checkpoint_decision "$checkpoint" "$stage")
  echo "Checkpoint $label: $decision — $message"
  if [ "$decision" = "STOP" ]; then
    echo "STOP: checkpoint is $decision; next stage was not started. Evidence already written remains in the database."
    exit 4
  fi
  if [ "$decision" = "WAITING" ] && [ "$allow_waiting" != "true" ]; then
    echo "STOP: checkpoint is $decision; next stage was not started. Evidence already written remains in the database."
    exit 4
  fi
}

checkpoint_value() {
  local file="$1" expression="$2"
  python3 -c 'import json,sys
d=json.load(open(sys.argv[1], encoding="utf-8"))
v=d
for key in sys.argv[2].split("."):
    v=v[key]
print(v)' "$file" "$expression"
}

run_researcher_until_saturated() {
  local action=0 checkpoint recommendation artifact endpoint
  while true; do
    checkpoint="$OUT_DIR/checkpoint-researcher-control.json"
    curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$checkpoint"
    recommendation="$(checkpoint_value "$checkpoint" evidence.researchRecommendation)"
    case "$recommendation" in
      READY_FOR_ANALYST)
        echo "Researcher hand-off certified: $(checkpoint_value "$checkpoint" evidence.researchRecommendationMessage)"
        return
        ;;
      RUN_NEXT_BATCH)
        endpoint="/pipeline/run/extract"
        ;;
      RUN_DEFERRED_AUDIT|AUDIT_FOUND_ADDITIONAL_VALUE)
        endpoint="/pipeline/run/extract-audit"
        ;;
      *)
        echo "STOP: Researcher requires operator review — $recommendation"
        echo "      $(checkpoint_value "$checkpoint" evidence.researchRecommendationMessage)"
        exit 4
        ;;
    esac
    action=$((action + 1))
    if [ "$action" -gt 50 ]; then
      echo "STOP: Researcher exceeded the 50-action emergency orchestration firebreak."
      exit 4
    fi
    artifact="researcher-$(printf '%02d' "$action")"
    curl -fsS "$BASE_URL/pipeline/extraction/plan" > "$OUT_DIR/${artifact}-main-plan.txt"
    curl -fsS "$BASE_URL/pipeline/extraction/audit-plan" > "$OUT_DIR/${artifact}-audit-plan.txt"
    run_stage extract "$artifact" "$recommendation" "$endpoint" true
    curl -fsS "$BASE_URL/pipeline/researcher-curation.json" \
      > "$OUT_DIR/${artifact}-value-ledger.json"
  done
}

run_analyst_document_batches() {
  local action=0 checkpoint decision artifact before_docs after_docs
  while true; do
    checkpoint="$OUT_DIR/checkpoint-analyst-control.json"
    curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$checkpoint"
    decision="$(python3 -c 'import json,sys
d=json.load(open(sys.argv[1], encoding="utf-8"))
print(next(row["decision"] for row in d["checkpoints"] if row["stage"] == "interpret"))' "$checkpoint")"
    if [ "$decision" = "PASS" ] || [ "$decision" = "WARN" ]; then return; fi
    if [ "$decision" = "STOP" ]; then
      echo "STOP: Analyst checkpoint is STOP before the next bounded action."
      exit 4
    fi
    before_docs="$(checkpoint_value "$checkpoint" analysis.activeClaimDocuments)"
    action=$((action + 1))
    if [ "$action" -gt 50 ]; then
      echo "STOP: Analyst exceeded the 50-action emergency orchestration firebreak."
      exit 4
    fi
    artifact="analyst-document-$(printf '%02d' "$action")"
    curl -fsS "$BASE_URL/pipeline/analysis/plan" > "$OUT_DIR/${artifact}-plan.txt"
    run_stage interpret "$artifact" "document batch $action" "/pipeline/run/interpret" true
    checkpoint="$OUT_DIR/checkpoint-analyst-after-$(printf '%02d' "$action").json"
    curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$checkpoint"
    after_docs="$(checkpoint_value "$checkpoint" analysis.activeClaimDocuments)"
    if [ "$after_docs" -le "$before_docs" ]; then
      echo "STOP: Analyst made no document-coverage progress ($before_docs -> $after_docs)."
      echo "      Inspect ${artifact}-status.json/output before spending on another identical batch."
      exit 4
    fi
  done
}

stop_existing_app
backup_database
start_app

curl -fsS "$BASE_URL/pipeline/reprocess/preflight.json?backupConfirmed=true" \
  > "$OUT_DIR/preflight.json"
if [ "$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["ready"]).lower())' "$OUT_DIR/preflight.json")" != "true" ]; then
  echo "STOP: preflight blockers remain; no AI stage was started."
  python3 -m json.tool "$OUT_DIR/preflight.json"
  exit 2
fi

curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$OUT_DIR/checkpoint-before.json"
curl -fsS "$BASE_URL/pipeline/classification/plan" > "$OUT_DIR/classification-plan.txt"

if [ "$RUN_SCOUT" = "1" ]; then run_stage ingest; fi
run_stage classify
run_researcher_until_saturated
# Two-pass analytical contract:
#  1) document observations/implications -> independent verification;
#  2) now-verified inputs -> cross-source narratives/deep dives -> verification again.
# A single pass produces accurate fragments but cannot yet produce a connected story.
run_analyst_document_batches
run_stage verify verify-document "document claims"
run_stage interpret interpret-synthesis "cross-source synthesis"
run_stage verify verify-synthesis "cross-source synthesis"

# The CFO/Strategy release publishes the BI report directly from curated,
# verified lanes below. The older Product desk is feature-flagged off by
# default, so calling its endpoint would correctly return 404 and must not
# abort a Strategy run. It remains an explicit compatibility option for a
# future Product-department deployment.
if [ "$REGENERATE_LEGACY_PRODUCT" = "1" ]; then
  curl -fsS -X POST "$BASE_URL/report/product/regenerate-all" \
    > "$OUT_DIR/product-regeneration.json"
  python3 - "$OUT_DIR/product-regeneration.json" <<'PY'
import json, sys

rows = json.load(open(sys.argv[1], encoding="utf-8"))
for row in rows:
    cadence = row.get("cadence", "UNKNOWN")
    status = row.get("status", "UNKNOWN")
    detail = row.get("detail")
    print(f"Product edition {cadence}: {status}" + (f" — {detail}" if detail else ""))
if rows and all(row.get("status") == "GENERATION_FAILED" for row in rows):
    print("WARN: all Product editions failed generation; exports below preserve diagnostics/current-news views.")
PY
fi

for cadence in weekly monthly quarterly; do
  for lang in vi en; do
    curl -fsS "$BASE_URL/report/bi?cadence=$cadence&lang=$lang" \
      > "$OUT_DIR/bi-${cadence}-${lang}.html"
    curl -fsS "$BASE_URL/report/bi.pdf?cadence=$cadence&lang=$lang" \
      > "$OUT_DIR/bi-${cadence}-${lang}.pdf"
    curl -fsS "$BASE_URL/report/bi.docx?cadence=$cadence&lang=$lang" \
      > "$OUT_DIR/bi-${cadence}-${lang}.docx"
    curl -fsS "$BASE_URL/report/bi.pptx?cadence=$cadence&lang=$lang" \
      > "$OUT_DIR/bi-${cadence}-${lang}.pptx"
  done
done

curl -fsS "$BASE_URL/pipeline/checkpoint.json" > "$OUT_DIR/checkpoint-final.json"
echo
echo "Pipeline stages finished without a systemic STOP."
echo "Artifacts: $OUT_DIR"
echo "The app remains running on $BASE_URL. Human Editor items remain at $BASE_URL/review."
