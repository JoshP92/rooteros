# Deploying rooteros on a Synology NAS

This runs the **whole game** — the `good-game` server, its database, and the web
frontend — in one Docker container on your Synology. You get full online multiplayer,
with games and accounts that **persist across restarts**.

- **Image:** a Java 17 runtime + the prebuilt `good-game.jar` + the static assets from
  `haunt-roll-fail/`.
- **Database:** HSQLDB on a Docker **named volume** (`rooteros-db`) → survives restarts
  and image rebuilds.
- **Requirements:** DSM 7.2+ with **Container Manager**, ~1 GB free RAM, and this repo's
  files on the NAS.

---

## 1. Put the repo on the NAS

Easiest: on a PC, clone/download this repo, then copy the whole folder to a shared
folder on the NAS (e.g. `docker/rooteros`) with File Station or over SMB. Or, if the NAS
has Git Server / SSH:

```sh
cd /volume1/docker
git clone https://github.com/JoshP92/rooteros.git
```

The folder must contain `docker-compose.yml`, `deploy/`, `haunt-roll-fail/`, and `good-game/`.

## 2. Set how players reach it

Edit **`docker-compose.yml`** → `PUBLIC_URL`:

- **LAN only:** your NAS's LAN IP, e.g. `http://192.168.1.50:7070`
  (DSM → Control Panel → Network → Network Interface).
- **Internet/domain (later):** `https://games.yourname.synology.me` (see §5).

> **Why it matters:** `PUBLIC_URL` must match the address people actually open. It's the
> API endpoint the client uses for online games, and it feeds the server's asset gate.
> LAN-by-IP works out of the box because the server also serves no-Referer and
> localhost/127.0.0.1 requests.

## 3. Build & run in Container Manager

1. **Container Manager** → **Project** → **Create**.
2. **Project name:** `rooteros`. **Path:** the folder from step 1 (the repo root).
3. **Source:** *Use existing docker-compose.yml* → it will pick up `docker-compose.yml`
   at the repo root.
4. **Next / Build.** First build takes a few minutes (pulls the JRE image, copies
   assets). When done, the **rooteros** container shows *running* and then *healthy*.

CLI alternative (SSH):
```sh
cd /volume1/docker/rooteros
sudo docker compose up -d --build
```

## 4. Play on your LAN

From any phone/laptop on the same Wi‑Fi:

```
http://<NAS-LAN-IP>:7070/play
```

Full hotseat, vs‑bots, and online multiplayer between devices on your network.

---

## 5. (Optional) Play over the internet, with HTTPS

Put it behind DSM's reverse proxy with a real certificate — you don't have to expose
port 7070 directly.

1. **DDNS** — Control Panel → External Access → **DDNS** → add a `synology.me` hostname
   (e.g. `yourname.synology.me`).
2. **Certificate** — Control Panel → Security → **Certificate** → add a **Let's Encrypt**
   cert for `games.yourname.synology.me`.
3. **Reverse Proxy** — Control Panel → Login Portal → Advanced → **Reverse Proxy** → Create:
   - Source: `https://games.yourname.synology.me : 443`
   - Destination: `http://localhost : 7070`
   - In *Custom Header* → **Create → WebSocket** (recommended).
4. **Router** — forward external **443** → the NAS.
5. **PUBLIC_URL** — set to `https://games.yourname.synology.me` in `docker-compose.yml`,
   then rebuild (Container Manager → Project → **Build**, or `docker compose up -d --build`).
6. **Firewall** (recommended) — Control Panel → Security → **Firewall**: allow 443,
   restrict the rest.

Game is now at `https://games.yourname.synology.me/play`.

> **Security:** anything internet-facing carries risk. There's no admin panel, but keep
> DSM patched and consider limiting exposure (firewall allow-lists, or a VPN) if you
> don't want it fully public.

---

## Updating the game

- **UI / game-logic changes** (Scala.js frontend): rebuild `hrf-fastopt.js`
  (`sbt fastOptJS` in `haunt-roll-fail/`), commit, `git pull` on the NAS, rebuild the
  Container Manager project.
- **Server changes** (`good-game/*.scala`): on a PC with sbt, run `./deploy/build-jar.ps1`
  to regenerate `deploy/good-game.jar`, commit, `git pull` on the NAS, rebuild.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Assets 404 in the browser | `PUBLIC_URL` doesn't match the URL you're visiting. Fix it in `docker-compose.yml` and rebuild. |
| Port 7070 already used on the NAS | Change the **left** side of `ports:` (e.g. `"8070:7070"`) and use that port. Keep `PORT: "7070"` (internal). |
| Games gone after restart | Ensure the `rooteros-db` volume wasn't removed; the DB lives at `/data` in the container. |
| Container unhealthy | Check logs: Container Manager → Container → **rooteros** → *Details/Log*, or `docker logs rooteros`. |

### Don't want to commit the 31 MB JAR?
The default keeps a prebuilt `deploy/good-game.jar` in the repo (simple, low-RAM builds).
Alternatively, build the JAR **inside** Docker with a multi-stage build (a
`sbtscala/scala-sbt` build stage) so no binary is committed — but that needs ~2 GB RAM to
build on the NAS. Ask and I'll add that Dockerfile variant.
