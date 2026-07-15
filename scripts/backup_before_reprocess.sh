#!/usr/bin/env bash
set -eu

DB_FILE="${1:-data/marketradar.mv.db}"
BACKUP_DIR="${2:-data/backups}"

if lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "REFUSED: Market Radar is listening on port 8081. Stop the app before copying the H2 file."
  exit 2
fi

if [ ! -f "$DB_FILE" ]; then
  echo "REFUSED: database file not found: $DB_FILE"
  exit 2
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/marketradar-before-reprocess-$STAMP.mv.db"
cp -p "$DB_FILE" "$TARGET"

if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$TARGET"
else
  sha256sum "$TARGET"
fi
echo "BACKUP_READY=$TARGET"
