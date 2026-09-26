#!/usr/bin/env bash
set -euo pipefail
# Compatibility entry point for Debug installations.
exec "$(dirname -- "${BASH_SOURCE[0]}")/upload-adb-debug.sh" "$@"
