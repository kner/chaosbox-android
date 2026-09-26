#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
./run-desktop.sh --check
exec /usr/bin/python3 desktop/install.py
