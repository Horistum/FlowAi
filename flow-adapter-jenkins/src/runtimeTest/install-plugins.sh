#!/bin/sh
set -eu

# Identical public metadata requests intermittently return HTTP 403 in CI.
# Retry only that observed transport failure, never a runtime test or verdict.
log=$(mktemp)
trap 'rm -f "$log"' 0
attempt=1
while :; do
    if jenkins-plugin-cli "$@" >"$log" 2>&1; then
        cat "$log"
        exit 0
    else
        status=$?
    fi
    cat "$log" >&2
    if [ "$attempt" -ge 3 ] || ! grep -Eq \
        'Unable to retrieve JSON from https://updates\.jenkins\.io/(update-center\.json(\?[^ :]+)?|plugin-versions\.json): status code: 403, reason phrase: Forbidden' "$log"; then
        exit "$status"
    fi
    delay=$((attempt * 5))
    echo "Retrying Jenkins metadata download after HTTP 403 in ${delay}s (attempt $((attempt + 1))/3)" >&2
    sleep "$delay"
    attempt=$((attempt + 1))
done
