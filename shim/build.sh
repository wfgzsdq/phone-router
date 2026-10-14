#!/usr/bin/env bash
# Optional shim-only build; regular APK builds run this task automatically.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"
bash ./gradlew :app:buildShim "$@"
