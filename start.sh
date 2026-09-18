#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

load_env() {
  set -a
  # shellcheck disable=SC1090
  source "$1"
  set +a
}

if [[ -f .env ]]; then
  load_env .env
elif [[ -f elastic-start-local/.env ]]; then
  load_env elastic-start-local/.env
fi

if [[ -n "${ES_LOCAL_URL:-}" || -n "${ES_LOCAL_PASSWORD:-}" ]]; then
  echo "Using Elasticsearch ${ES_LOCAL_URL:-http://localhost:9200/}"
fi

exec mvn compile exec:java "$@"
open http://localhost:7171
