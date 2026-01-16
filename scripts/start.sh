#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"

if [[ ! -f "$FRONTEND_DIR/dist/index.html" ]]; then
  echo "Frontend build not found. Building..."
  (cd "$FRONTEND_DIR" && npm install && npm run build)
fi

cd "$BACKEND_DIR"
exec gradle run --args="--config ../config.json"
