# rooteros - HRF / Root boardgame server (good-game) + static frontend, in one image.
# Runtime = a Java 17 JRE + the prebuilt fat JAR + the static web assets.
# The HSQLDB database lives on the /data volume so games persist across restarts.
FROM eclipse-temurin:17-jre

# curl is only used by the container HEALTHCHECK below
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# --- backend: self-contained good-game server (regenerate with deploy/build-jar.ps1) ---
COPY deploy/good-game.jar  /app/good-game.jar
COPY deploy/entrypoint.sh  /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

# --- static web root, served by good-game under /hrf/* ---
COPY haunt-roll-fail/index.html   /app/haunt-roll-fail/index.html
COPY haunt-roll-fail/webp2        /app/haunt-roll-fail/webp2
COPY haunt-roll-fail/fonts        /app/haunt-roll-fail/fonts
COPY haunt-roll-fail/background.png haunt-roll-fail/omen.png haunt-roll-fail/external-link.png haunt-roll-fail/question-mark.png  /app/haunt-roll-fail/
# the linked Scala.js game
COPY haunt-roll-fail/target/scala-2.13/hrf-fastopt.js  /app/haunt-roll-fail/target/scala-2.13/hrf-fastopt.js

# How players reach the server - used for player/spectator links AND the asset Referer
# gate. Override in docker-compose.yml, e.g. http://192.168.1.50:7070 or https://games.example.com
ENV PUBLIC_URL="http://localhost:7070"
ENV PORT="7070"

EXPOSE 7070
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/play" >/dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
