#!/usr/bin/env bash

set -euo pipefail

LOCAL_PORT="${LOCAL_PORT:-13100}"
REMOTE_HOST="${REMOTE_HOST:-127.0.0.1}"
REMOTE_PORT="${REMOTE_PORT:-3100}"
SSH_TARGET="${SSH_TARGET:-root@69.165.65.123}"
SSH_KEY="${SSH_KEY:-$HOME/dadaikuai}"

exec ssh -i "${SSH_KEY}" -o ProxyCommand=none -N -L "${LOCAL_PORT}:${REMOTE_HOST}:${REMOTE_PORT}" "${SSH_TARGET}"
