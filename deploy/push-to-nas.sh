#!/usr/bin/env bash
# Push the current rooteros build to the LIVE Synology NAS container WITHOUT a full image
# rebuild. Uses `docker cp` + `docker restart`, which keeps the container's IP stable so the
# DSM reverse proxy keeps pointing at it. Idempotent — safe to re-run.
#
# Usage:   NAS_PW='the-ssh-password' bash deploy/push-to-nas.sh
#
# What it updates in the container (and mirrors into /volume1/docker/rooteros so a future
# rebuild matches): good-game.jar, index.html, and the pwa/ folder (manifest, sw.js, dashboard,
# icons). The restart re-runs the entrypoint in `run` mode, which triggers the idempotent
# migration that creates the new Accounts/GameSeats/PushSubs/JournalTurns tables.
set -euo pipefail

: "${NAS_PW:?set NAS_PW to the NAS SSH/sudo password}"
NAS_HOST="famphnas.synology.me"
NAS_USER="JoshP"
NAS_PORT="5522"
CONTAINER="rooteros"
DOCKER="/usr/local/bin/docker"
STAGE="/tmp/rooteros-update"
SRC_DIR="/volume1/docker/rooteros"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

# --- OpenSSH SSH_ASKPASS setup (no sshpass/plink on this box) ---
ASKDIR="$(mktemp -d)"
trap 'rm -rf "$ASKDIR"' EXIT
printf '%s' "$NAS_PW" > "$ASKDIR/pw"; chmod 600 "$ASKDIR/pw"
printf '#!/bin/sh\ncat "%s/pw"\n' "$ASKDIR" > "$ASKDIR/askpass"; chmod 700 "$ASKDIR/askpass"
export SSH_ASKPASS="$ASKDIR/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0

SSHOPTS=(-T -p "$NAS_PORT" -o StrictHostKeyChecking=accept-new -o PubkeyAuthentication=no -o NumberOfPasswordPrompts=1)
sshx() { ssh "${SSHOPTS[@]}" "$NAS_USER@$NAS_HOST" "$@"; }

# The NAS sshd has no SFTP subsystem (scp fails), so stream files over ssh with cat/tar instead.
echo "[1/6] stage files on NAS ($STAGE)"
sshx "rm -rf $STAGE && mkdir -p $STAGE/pwa"
sshx "cat > $STAGE/good-game.jar" < "$REPO/deploy/good-game.jar"
sshx "cat > $STAGE/index.html"    < "$REPO/haunt-roll-fail/index.html"
tr -d '\r' < "$REPO/deploy/entrypoint.sh" | sshx "cat > $STAGE/entrypoint.sh"   # LF endings for /bin/sh
tar -C "$REPO/haunt-roll-fail/pwa" -cf - . | sshx "tar -C $STAGE/pwa -xf -"
# Optional VAPID keys (gitignored) — deployed into the persistent /data volume so push works.
[ -f "$REPO/deploy/keys/vapid.env" ] && sshx "cat > $STAGE/vapid.env" < "$REPO/deploy/keys/vapid.env" && echo "  (staged vapid.env)"
echo "  staged: $(sshx "ls -1 $STAGE $STAGE/pwa | tr '\n' ' '")"

echo "[2/6] copy into container + mirror into $SRC_DIR"
sshx "bash -s" <<REMOTE
set -e
echo '$NAS_PW' | sudo -S -v 2>/dev/null
sudo $DOCKER cp $STAGE/good-game.jar $CONTAINER:/app/good-game.jar
sudo $DOCKER cp $STAGE/index.html    $CONTAINER:/app/haunt-roll-fail/index.html
sudo $DOCKER cp $STAGE/entrypoint.sh $CONTAINER:/app/entrypoint.sh
sudo $DOCKER exec $CONTAINER chmod +x /app/entrypoint.sh
sudo $DOCKER cp $STAGE/pwa           $CONTAINER:/app/haunt-roll-fail/
if [ -f "$STAGE/vapid.env" ]; then
  sudo $DOCKER cp $STAGE/vapid.env $CONTAINER:/data/vapid.env
  sudo $DOCKER exec $CONTAINER chmod 600 /data/vapid.env 2>/dev/null || true
  echo "  vapid.env -> /data (push will be enabled)"
fi
if [ -d "$SRC_DIR" ]; then
  sudo cp $STAGE/good-game.jar $SRC_DIR/deploy/good-game.jar 2>/dev/null || true
  sudo cp $STAGE/index.html    $SRC_DIR/haunt-roll-fail/index.html 2>/dev/null || true
  sudo mkdir -p $SRC_DIR/haunt-roll-fail/pwa && sudo cp $STAGE/pwa/* $SRC_DIR/haunt-roll-fail/pwa/ 2>/dev/null || true
fi
echo "copied."
REMOTE

echo "[3/6] restart container (preserves IP)"
sshx "bash -s" <<REMOTE
set -e
echo '$NAS_PW' | sudo -S -v 2>/dev/null
sudo $DOCKER restart $CONTAINER
REMOTE

echo "[4/6] wait for boot"
sleep 10

echo "[5/6] server env + migration log"
sshx "bash -s" <<REMOTE
echo '$NAS_PW' | sudo -S -v 2>/dev/null
echo -n "PUBLIC_URL="; sudo $DOCKER exec $CONTAINER printenv PUBLIC_URL 2>/dev/null || echo "(unknown)"
echo "container IP: \$(sudo $DOCKER inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $CONTAINER)"
sudo $DOCKER logs --tail 40 $CONTAINER 2>&1 | grep -iE "Migrat|Started server|Serving|First run|Web Push|Loading VAPID|Exception|ERROR" || echo "(no notable log lines)"
REMOTE

echo "[6/6] verify endpoints from inside the NAS"
sshx "bash -s" <<REMOTE
echo '$NAS_PW' | sudo -S -v 2>/dev/null
for p in /play /games /sw.js /manifest.webmanifest /icon-192.png; do
  code=\$(sudo $DOCKER exec $CONTAINER curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:7070\$p")
  echo "  \$p -> \$code"
done
acct=\$(sudo $DOCKER exec $CONTAINER curl -s -X POST --data-binary "DeployProbe" "http://127.0.0.1:7070/new-account")
echo "  /new-account -> \$(printf '%s' "\$acct" | head -1 | cut -c1-10)... (POST)"
REMOTE

echo "DONE"
