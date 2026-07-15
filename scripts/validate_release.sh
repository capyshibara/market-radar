#!/usr/bin/env bash
# Offline release validation for Market Radar.
#
# This script deliberately does not start Spring Boot, call a model provider,
# access the live pipeline, or mutate application data. Run it from any working
# directory; all paths are resolved relative to this file.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=8081

fail() {
  printf 'RELEASE VALIDATION FAILED: %s\n' "$*" >&2
  exit 1
}

port_is_listening() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
    return
  fi

  # Read-only fallback for environments without lsof. A successful connection
  # means a process is already listening and validation must not continue.
  python3 -c \
    'import socket, sys; s=socket.socket(); s.settimeout(0.25); sys.exit(0 if s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0 else 1)' \
    "$PORT"
}

printf '[preflight] Checking that port %s is not active...\n' "$PORT"
if port_is_listening; then
  fail "port $PORT has a listening process; stop it before running this non-live harness"
fi

for command_name in mvn javac java python3; do
  command -v "$command_name" >/dev/null 2>&1 \
    || fail "required command not found: $command_name"
done

workspace="$(mktemp -d "${TMPDIR:-/tmp}/market-radar-release.XXXXXX")"
classpath_file="$workspace/maven-classpath.txt"
test_classes="$workspace/test-classes"
mkdir -p "$test_classes"

cd "$ROOT"

printf '[build] Packaging application without starting it...\n'
mvn -DskipTests package

printf '[classpath] Resolving Maven dependency classpath...\n'
mvn -q dependency:build-classpath \
  -Dmdep.outputFile="$classpath_file" \
  -Dmdep.pathSeparator=:

[[ -f "$classpath_file" ]] || fail "Maven did not create the dependency classpath file"
maven_classpath="$(tr -d '\r\n' < "$classpath_file")"
runtime_classpath="$ROOT/target/classes:$test_classes"
if [[ -n "$maven_classpath" ]]; then
  runtime_classpath="$runtime_classpath:$maven_classpath"
fi

shopt -s nullglob
test_sources=("$ROOT"/*Test.java)
shopt -u nullglob
(( ${#test_sources[@]} > 0 )) || fail "no root standalone *Test.java files were found"

printf '[standalone] Compiling %d root test files...\n' "${#test_sources[@]}"
for source in "${test_sources[@]}"; do
  printf '  compile %s\n' "$(basename "$source")"
  javac -encoding UTF-8 -cp "$runtime_classpath" -d "$test_classes" "$source"
done

printf '[standalone] Running %d root test classes with assertions enabled...\n' "${#test_sources[@]}"
for source in "${test_sources[@]}"; do
  test_class="$(basename "$source" .java)"
  printf '  run %s\n' "$test_class"
  java -ea -cp "$runtime_classpath" "$test_class"
done

printf '[golden] Enforcing current Product golden targets...\n'
python3 scripts/evaluate_product_golden_set.py --enforce-targets

printf '[negative-control] Requiring the legacy-label baseline to fail targets...\n'
set +e
python3 scripts/evaluate_product_golden_set.py --legacy-baseline --enforce-targets
legacy_status=$?
set -e
case "$legacy_status" in
  1)
    printf '  expected failure observed (exit 1)\n'
    ;;
  0)
    fail "legacy negative control unexpectedly passed; the golden gate is not discriminating"
    ;;
  *)
    fail "legacy negative control had an evaluator/infrastructure error (exit $legacy_status), not the expected target failure"
    ;;
esac

printf '[publication] Running Product publication-quality fixtures...\n'
python3 scripts/evaluate_product_publication_quality.py

printf '\nRELEASE VALIDATION PASSED\n'
printf 'Build, %d standalone tests, current golden targets, legacy negative control, and publication fixtures passed.\n' \
  "${#test_sources[@]}"
printf 'Manual Product-SME acceptance remains required; see docs/evaluation/product-sme-review-checklist.md.\n'
