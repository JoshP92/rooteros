#!/bin/sh
# Creates the HSQLDB database on first run, then starts the good-game server.
set -e

PORT="${PORT:-7070}"
PUBLIC_URL="${PUBLIC_URL:-http://localhost:${PORT}}"
DB="/data/good-game-database"
WEBROOT="/app/haunt-roll-fail"
JAR="/app/good-game.jar"

if [ ! -f "${DB}.script" ]; then
    echo "[rooteros] First run - creating database at ${DB}"
    java -jar "$JAR" create "$DB" "$WEBROOT" "$PUBLIC_URL" "${PUBLIC_URL}/hrf/" "$PORT"
fi

echo "[rooteros] Serving ${PUBLIC_URL}  (container listens on 0.0.0.0:${PORT})"
exec java -jar "$JAR" run "$DB" "$WEBROOT" "$PUBLIC_URL" "${PUBLIC_URL}/hrf/" "$PORT"
