#!/usr/bin/env bash
# A skipped Testcontainers suite is not a pass: fail when any JUnit report shows skipped tests or
# when fewer tests ran than the known floor (catches suites that silently stopped being discovered).
set -Eeuo pipefail

minimum="${COURTPULSE_MINIMUM_TESTS:-150}"
summary="$(find . -path ./apps/web/node_modules -prune -o -path '*/build/test-results/test/*.xml' -print0 \
  | xargs -0 grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8; n++} END {print t+0, s+0, f+0, e+0, n+0}')"
read -r tests skipped failures errors suites <<<"${summary}"
if (( suites == 0 )); then
  echo 'No JUnit reports were found; did the test task run?' >&2
  exit 1
fi
echo "tests=${tests} skipped=${skipped} failures=${failures} errors=${errors} minimum=${minimum}"
if (( skipped > 0 || failures > 0 || errors > 0 || tests < minimum )); then
  echo 'Every backend test must run and pass; container-backed suites may not be skipped.' >&2
  exit 1
fi
