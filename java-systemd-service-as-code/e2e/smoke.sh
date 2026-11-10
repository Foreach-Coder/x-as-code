#!/usr/bin/env bash
# Linux / macOS entry point. The assertions live in smoke.ps1 (single source);
# this wrapper only forwards to PowerShell 7+ (pwsh).
#
#   pwsh -NoProfile -File e2e/smoke.ps1     # direct
#   bash e2e/smoke.sh                       # same thing
#
# Windows: use powershell -NoProfile -ExecutionPolicy Bypass -File e2e\smoke.ps1
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v pwsh >/dev/null 2>&1; then
    echo "pwsh (PowerShell 7+) is required by this wrapper; run e2e/smoke.ps1 directly on Windows." >&2
    exit 2
fi

exec pwsh -NoProfile -File "$here/smoke.ps1"
