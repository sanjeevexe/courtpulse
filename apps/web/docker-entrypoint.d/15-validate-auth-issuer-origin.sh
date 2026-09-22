#!/bin/sh
set -eu

issuer_origin="${COURTPULSE_AUTH_ISSUER_ORIGIN:-}"

if [ -z "${issuer_origin}" ]; then
    exit 0
fi

if ! printf '%s\n' "${issuer_origin}" | grep -Eq '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$|^http://(127\.0\.0\.1|localhost)(:[0-9]{1,5})?$'; then
    echo 'COURTPULSE_AUTH_ISSUER_ORIGIN must be an HTTPS origin or an HTTP loopback origin, without a path' >&2
    exit 1
fi

port="$(printf '%s\n' "${issuer_origin}" | sed -n 's#^.*:\([0-9][0-9]*\)$#\1#p')"
if [ -n "${port}" ] && { [ "${port}" -lt 1 ] || [ "${port}" -gt 65535 ]; }; then
    echo 'COURTPULSE_AUTH_ISSUER_ORIGIN contains an invalid port' >&2
    exit 1
fi
