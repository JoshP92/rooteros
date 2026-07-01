#!/bin/sh
# Creates the HSQLDB database on first run, then starts the good-game server.
set -e

PORT="${PORT:-7070}"
PUBLIC_URL="${PUBLIC_URL:-http://localhost:${PORT}}"
DB="/data/good-game-database"
WEBROOT="/app/haunt-roll-fail"
JAR="/app/good-game.jar"

# Optional Web Push (VAPID) secrets, dropped into the persistent /data volume at deploy time
# (never baked into the image or docker-compose env). Absent => the server runs with push disabled.
if [ -f /data/vapid.env ]; then
    echo "[rooteros] Loading VAPID keys from /data/vapid.env"
    . /data/vapid.env
    export VAPID_PUBLIC VAPID_PRIVATE VAPID_SUBJECT
fi

if [ ! -f "${DB}.script" ]; then
    echo "[rooteros] First run - creating database at ${DB}"
    java -jar "$JAR" create "$DB" "$WEBROOT" "$PUBLIC_URL" "${PUBLIC_URL}/hrf/" "$PORT"
fi

echo "[rooteros] Serving ${PUBLIC_URL}  (container listens on 0.0.0.0:${PORT})"
exec java -jar "$JAR" run "$DB" "$WEBROOT" "$PUBLIC_URL" "${PUBLIC_URL}/hrf/" "$PORT"
