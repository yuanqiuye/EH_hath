#!/bin/sh

# set umask accordingly
if [ "${UMASK:-UNSET}" != "UNSET" ]; then
  umask "$UMASK"
fi

set -eu
mkdir -p /hath/data
if [ -n "${HATH_CLIENT_ID:-}" ] && [ -n "${HATH_CLIENT_KEY:-}" ]; then
  printf '%s-%s' "$HATH_CLIENT_ID" "$HATH_CLIENT_KEY" > /hath/data/client_login
elif [ ! -s /hath/data/client_login ]; then
  echo "Set HATH_CLIENT_ID and HATH_CLIENT_KEY, or provide data/client_login" >&2
  exit 1
fi

exec java -jar ./HentaiAtHome.jar         \
    --cache-dir=/hath/cache               \
    --data-dir=/hath/data                 \
    --download-dir=/hath/download         \
    --log-dir=/hath/log                   \
    --temp-dir=/hath/tmp                  \
    --port="${HATH_PORT:-0}"                     \
    --metrics-name="${METRICS_CLIENT_NAME:-}"   \
    --metrics-user="${METRICS_USER_ID:-}"       \
    --metrics-address=0.0.0.0             \
    --metrics-port=9500                   \
    --enable-metrics=true                 \
    --upload-throttle-bytes="${UPLOAD_THROTTLE_BYTES:-0}" \
    --speedtest-throttle-bytes="${SPEEDTEST_THROTTLE_BYTES:-0}"

