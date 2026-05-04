#!/usr/bin/env bash
# Local run for Flight Training Scheduler (port 9000).
# Requires GOOGLE_AI_GEMINI_API_KEY and OPENWEATHERMAP_API_KEY (export or .env — see README).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT}"

if [[ -f "${ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
  set +a
fi

if [[ -z "${GOOGLE_AI_GEMINI_API_KEY:-}" || -z "${OPENWEATHERMAP_API_KEY:-}" ]]; then
  echo "Missing GOOGLE_AI_GEMINI_API_KEY or OPENWEATHERMAP_API_KEY." >&2
  echo "Set them in the environment or copy .env.example to .env — see README → API keys." >&2
  exit 1
fi

# Optional overrides still work via .env or export:
# OPENWEATHER_LOCATION_QUERY, ALLOW_SIMULATED_QUOTA_FALLBACK, RESERVATION_EVALUATION_DEADLINE

# Local dev only: free port 9000 (terminates whatever is bound there—do not use on shared machines).
if command -v lsof >/dev/null 2>&1; then
  lsof -ti:9000 | xargs -r kill -9 || true
fi

echo "Starting akka-dev-cert from ${ROOT} (port 9000)..."
exec mvn compile exec:java
