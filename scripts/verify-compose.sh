#!/usr/bin/env bash
# Runs only on a fresh docker compose project with synthetic test data.
set -euo pipefail
cd "$(dirname "$0")/.."
compose=(docker compose --env-file /dev/null --file docker-compose.yml --project-name careerforge-verify)
export JWT_SECRET
JWT_SECRET=$(openssl rand -base64 32)
export DEV_AUTH_ENABLED=true STRIPE_WEBHOOK_SECRET=disposable-fixture CF_TEST_WEBHOOK_SECRET=disposable-fixture
export COOKIE_SECURE=false APP_ORIGIN=http://localhost:5173
export GEMINI_API_KEY= GOOGLE_CLIENT_ID= STRIPE_SECRET_KEY= STRIPE_PRICE_PRO=
export MAIL_HOST=mailpit MAIL_PORT=1025 MAIL_FROM=noreply@careerforge.local
export DB_PASSWORD
DB_PASSWORD=$(openssl rand -hex 24)
existing=$("${compose[@]}" ps --all --quiet)
if [ -n "$existing" ]; then
  echo "The disposable verification project already has containers. Remove it or use an isolated runner." >&2
  exit 1
fi
cleanup() {
  if [ "$?" -ne 0 ]; then "${compose[@]}" logs --no-color || true; fi
  "${compose[@]}" down --volumes
}
trap cleanup EXIT
"${compose[@]}" config --quiet
"${compose[@]}" up --build --wait --wait-timeout 180
curl --silent --fail http://localhost:5173/actuator/health >/dev/null
python3 tests/integration/smoke.py
cd frontend
npm ci
npm test
npm run build
CF_BROWSER_MATRIX=true npm run test:e2e
