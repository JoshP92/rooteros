package hrf.gg

import slick.jdbc.HsqldbProfile.api._
import slick.jdbc.HsqldbProfile.api.DBIO.seq

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer

import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

object GoodGame {
    case class User(name : String, secret : String, id : String)

    class Users(tag : Tag) extends Table[User](tag, "Users") {
        def name = column[String]("name")
        def secret = column[String]("secret")
        def id = column[String]("id", O.PrimaryKey)
        def * = (name, secret, id).mapTo[User]
    }

    val users = TableQuery[Users]


    case class Journal(name : String, public : Boolean, status : String, message : String, id : String)

    class Journals(tag : Tag) extends Table[Journal](tag, "Journals") {
        def name = column[String]("name")
        def public = column[Boolean]("public")
        def status = column[String]("status")
        def message = column[String]("message")
        def id = column[String]("id", O.PrimaryKey)
        def * = (name, public, status, message, id).mapTo[Journal]
    }

    val journals = TableQuery[Journals]


    case class Entry(journalId : String, index : Int, userId : String, text : String)

    class Entries(tag : Tag) extends Table[Entry](tag, "Entries") {
        def journalId = column[String]("journalId")
        def index = column[Int]("index")
        def userId = column[String]("userId")
        def text = column[String]("text")
        def * = (journalId, index, userId, text).mapTo[Entry]
        def pk = primaryKey("Entries" + "Key", (journalId, index))
        def journal = foreignKey("Entries" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("Entries" + "Users", userId, users)(_.id)
    }

    val entries = TableQuery[Entries]


    case class AccessRight(journalId : String, userId : String, right : String)

    class AccessRights(tag : Tag) extends Table[AccessRight](tag, "AccessRights") {
        def journalId = column[String]("journalId")
        def userId = column[String]("userId")
        def right = column[String]("right")
        def * = (journalId, userId, right).mapTo[AccessRight]
        def pk = primaryKey("AccessRights" + "Key", (journalId, userId, right))
        def journal = foreignKey("AccessRights" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("AccessRights" + "Users", userId, users)(_.id)
    }

    val accessRights = TableQuery[AccessRights]


    case class Play(journalId : String, userId : String, secret : String)

    class Plays(tag : Tag) extends Table[Play](tag, "Plays") {
        def journalId = column[String]("journalId")
        def userId = column[String]("userId")
        def secret = column[String]("secret")
        def * = (journalId, userId, secret).mapTo[Play]
        def journal = foreignKey("Play" + "Journals", journalId, journals)(_.id)
        def user = foreignKey("Play" + "Users", userId, users)(_.id)
    }

    val plays = TableQuery[Plays]


    // ---- Account layer (durable device identity that groups many per-seat Users) ----
    // A single person installs the PWA once and gets one Account; each game they join still
    // creates its own capability User/Play seat, but GameSeat rows tie those seats back to the
    // Account so the dashboard and push notifications can find "all of this person's games".

    case class Account(id : String, secret : String, name : String, email : String, created : Long)

    class Accounts(tag : Tag) extends Table[Account](tag, "Accounts") {
        def id = column[String]("id", O.PrimaryKey)
        def secret = column[String]("secret")
        def name = column[String]("name")
        def email = column[String]("email")
        def created = column[Long]("created")
        def * = (id, secret, name, email, created).mapTo[Account]
    }

    val accounts = TableQuery[Accounts]


    // One row per (account, game seat). Carries everything the dashboard needs to deep-link back
    // into the game (meta + playSecret build /play/<meta>/<playSecret>) plus the userId used to
    // decide whose turn it is. Deduped in code on (accountId, journalId).

    case class GameSeat(accountId : String, userId : String, journalId : String, meta : String, playSecret : String, updated : Long)

    class GameSeats(tag : Tag) extends Table[GameSeat](tag, "GameSeats") {
        def accountId = column[String]("accountId")
        def userId = column[String]("userId")
        def journalId = column[String]("journalId")
        def meta = column[String]("meta")
        def playSecret = column[String]("playSecret")
        def updated = column[Long]("updated")
        def * = (accountId, userId, journalId, meta, playSecret, updated).mapTo[GameSeat]
    }

    val gameSeats = TableQuery[GameSeats]


    // Web Push subscription endpoints for an account (one account can have several devices/browsers).
    // Deduped in code on endpoint.

    case class PushSub(accountId : String, endpoint : String, p256dh : String, auth : String, created : Long)

    class PushSubs(tag : Tag) extends Table[PushSub](tag, "PushSubs") {
        def accountId = column[String]("accountId")
        def endpoint = column[String]("endpoint")
        def p256dh = column[String]("p256dh")
        def auth = column[String]("auth")
        def created = column[Long]("created")
        def * = (accountId, endpoint, p256dh, auth, created).mapTo[PushSub]
    }

    val pushSubs = TableQuery[PushSubs]


    // Whose turn it is in a journal, as reported by clients via /waiting-for. journalId is the PK
    // so the latest report wins. `waiting` is a space-separated list of userIds the game is
    // currently asking (covers off-turn reactions like Ambush, where several seats may be asked).

    case class JournalTurn(journalId : String, waiting : String, updated : Long)

    class JournalTurns(tag : Tag) extends Table[JournalTurn](tag, "JournalTurns") {
        def journalId = column[String]("journalId", O.PrimaryKey)
        def waiting = column[String]("waiting")
        def updated = column[Long]("updated")
        def * = (journalId, waiting, updated).mapTo[JournalTurn]
    }

    val journalTurns = TableQuery[JournalTurns]


    // Live game status reported by the client (POST /game-status): `waiting` = space-separated
    // userIds currently asked (drives the push diff), `status` = a small JSON scoreboard
    // {"players":[{faction,player,vp,turn}]} shown on the dashboard's game-details view.

    case class JournalStatus(journalId : String, waiting : String, status : String, updated : Long)

    class JournalStatuses(tag : Tag) extends Table[JournalStatus](tag, "JournalStatuses") {
        def journalId = column[String]("journalId", O.PrimaryKey)
        def waiting = column[String]("waiting")
        def status = column[String]("status")
        def updated = column[Long]("updated")
        def * = (journalId, waiting, status, updated).mapTo[JournalStatus]
    }

    val journalStatuses = TableQuery[JournalStatuses]



    // Web Push (VAPID) sender. A static singleton, initialized once from the VAPID_* env vars.
    // If the keys are absent/blank it stays disabled and sendPush no-ops, so the server runs fine
    // without push configured. See deploy/entrypoint.sh (sources /data/vapid.env).
    object WebPush {
        import java.math.BigInteger
        import java.net.http.{HttpClient, HttpRequest, HttpResponse}
        import java.security.KeyFactory
        import java.security.interfaces.ECPrivateKey
        import java.security.spec.{ECParameterSpec, ECPrivateKeySpec, ECGenParameterSpec}
        import java.util.Base64
        import java.util.concurrent.TimeUnit
        import com.zerodeplibs.webpush.{PushSubscription, VAPIDKeyPair, VAPIDKeyPairs}
        import com.zerodeplibs.webpush.key.{PrivateKeySources, PublicKeySources}
        import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer

        private val b64url = Base64.getUrlDecoder

        // Read once at class-load. Absent/blank env => enabled == false => sendPush no-ops.
        private val subject = sys.env.get("VAPID_SUBJECT").map(_.trim).filter(_.nonEmpty)
        private val pubB64  = sys.env.get("VAPID_PUBLIC").map(_.trim).filter(_.nonEmpty)
        private val privB64 = sys.env.get("VAPID_PRIVATE").map(_.trim).filter(_.nonEmpty)

        private val httpClient = HttpClient.newHttpClient()

        // Build the VAPIDKeyPair once. Public key is the 65-byte uncompressed point (direct factory).
        // Private key is stored as the raw 32-byte scalar, so rebuild an ECPrivateKey on P-256 —
        // there is no raw-scalar factory; ofPKCS8Bytes would reject the bare scalar.
        private val keyPair : Option[VAPIDKeyPair] =
            try {
                for {
                    pub  <- pubB64
                    priv <- privB64
                    _    <- subject
                } yield {
                    val publicBytes = b64url.decode(pub)                 // 65 bytes, 0x04-prefixed
                    val d = new BigInteger(1, b64url.decode(priv))       // 32-byte unsigned scalar
                    val ap = java.security.AlgorithmParameters.getInstance("EC")
                    ap.init(new ECGenParameterSpec("secp256r1"))
                    val ecSpec = ap.getParameterSpec(classOf[ECParameterSpec])
                    val ecPriv = KeyFactory.getInstance("EC")
                        .generatePrivate(new ECPrivateKeySpec(d, ecSpec)).asInstanceOf[ECPrivateKey]
                    VAPIDKeyPairs.of(
                        PrivateKeySources.ofECPrivateKey(ecPriv),
                        PublicKeySources.ofUncompressedBytes(publicBytes))
                }
            }
            catch { case e : Throwable => println("[rooteros] VAPID key init FAILED: " + e.getMessage) ; None }

        val enabled : Boolean = keyPair.isDefined

        println(if (enabled) "[rooteros] Web Push enabled (VAPID keys present)."
                else          "[rooteros] Web Push DISABLED (VAPID keys absent).")

        // Force object initialization (and the log line above) from main at boot.
        def init() : Unit = ()

        // Send one push. Returns the push-service HTTP status (201 = accepted); 404/410 => caller
        // deletes the sub. Returns 0 when push is disabled or the send throws (nothing to delete).
        def sendPush(endpoint : String, p256dh : String, auth : String, jsonPayload : String) : Int =
            keyPair match {
                case None => 0
                case Some(kp) =>
                    try {
                        val sub = new PushSubscription()
                        sub.setEndpoint(endpoint)
                        val keys = new PushSubscription.Keys()
                        keys.setP256dh(p256dh)   // browser base64url strings, passed straight through
                        keys.setAuth(auth)
                        sub.setKeys(keys)
                        val request : HttpRequest = StandardHttpClientRequestPreparer.getBuilder()
                            .pushSubscription(sub)
                            .vapidJWTExpiresAfter(15, TimeUnit.MINUTES)
                            .vapidJWTSubject(subject.get)
                            .pushMessage(jsonPayload)
                            .ttl(12, TimeUnit.HOURS)
                            .urgencyLow()
                            .build(kp)
                            .toRequest()
                        val resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                        resp.statusCode()
                    }
                    catch { case e : Throwable => println("[rooteros] push send failed: " + e.getMessage) ; 0 }
            }
    }


    def main(args : Array[String]) {
        if (args.size != 6) {
            println("gg <create|run> <directory> <database> <url> <cdn> <port>")
            return
        }

        val mode = args(0)
        val database = args(1)
        val directory = args(2)
        val url = args(3)
        val cdn = args(4)
        val port = args(5).toInt

        def readFile(path : String) = {
            import java.nio.charset.StandardCharsets._
            import java.nio.file.{Files, Paths}

            new String(Files.readAllBytes(Paths.get(path)), UTF_8)
        }

        implicit class Ascii(val s : String) {
            def ascii = s.filter(c => c >= 32 && c < 128)
            def asciiplus = s.filter(c => (c >= 32 && c < 128) || (c > 158 && c < 256 && c.isLetter))
            def safe = ascii.filter(_ != '<').filter(_ != '>').filter(_ != '"').filter(_ != '\\')
            def safeplus = asciiplus.filter(_ != '<').filter(_ != '>').filter(_ != '"').filter(_ != '\\')
        }

        def newSecret(n : Int) = {
            val random = new scala.util.Random()

            0.until(n).map(_ => "abcdefghijklmnopqrstuvwxyz".charAt(random.nextInt(26))).mkString("")
        }

        val db = Database.forURL("jdbc:hsqldb:file:" + database + ";hsqldb.cache_rows=10000;hsqldb.nio_data_file=false", driver="org.hsqldb.jdbcDriver")

        object execute {
            import scala.concurrent.Await
            import scala.concurrent.duration.Duration

            def apply[E <: Effect](actions : DBIOAction[_, NoStream, E]*) = Await.result(db.run(DBIO.seq(actions : _*).withPinnedSession), Duration.Inf)
            def apply[R](action : DBIOAction[R, NoStream, Effect.Read]) : R = Await.result(db.run(action.withPinnedSession), Duration.Inf)
        }

        if (mode == "create") {
            execute(users.schema.create, journals.schema.create, entries.schema.create, accessRights.schema.create, plays.schema.create)
            execute(accounts.schema.create, gameSeats.schema.create, pushSubs.schema.create, journalTurns.schema.create)
            println("Created database.")
            return
        }

        if (mode != "run") {
            println("Unknown mode.")
            return
        }

        // Idempotent boot migration: the data volume persists across deploys, so `create` mode never
        // runs again on an existing DB. Create the account/push tables here if they are missing.
        // HSQLDB has no portable CREATE TABLE IF NOT EXISTS through Slick, so we attempt each create
        // and swallow the "object name already exists" error on subsequent boots.
        def ensure(name : String)(create : => Unit) : Unit =
            try { create ; println("Migrated: created table " + name) }
            catch { case e : Throwable => /* already present */ }

        ensure("Accounts")     { execute(accounts.schema.create) }
        ensure("GameSeats")    { execute(gameSeats.schema.create) }
        ensure("PushSubs")     { execute(pushSubs.schema.create) }
        ensure("JournalTurns") { execute(journalTurns.schema.create) }
        ensure("JournalStatuses") { execute(journalStatuses.schema.create) }

        // Persistence hardening. HSQLDB keeps changes in a write-ahead .log and only merges them into
        // the durable .script on CHECKPOINT/SHUTDOWN. An abrupt container stop (docker restart's
        // SIGKILL) can leave recent schema/rows only in the .log, which isn't always replayed cleanly
        // — that's how the account tables got lost once. So: (1) checkpoint right after the migration
        // to push the new-table DDL into .script immediately, and (2) shut the DB down cleanly on
        // SIGTERM so everything is flushed before the JVM exits.
        try { execute(sqlu"CHECKPOINT") } catch { case e : Throwable => println("[rooteros] checkpoint warning: " + e.getMessage) }
        Runtime.getRuntime.addShutdownHook(new Thread(() => {
            try { execute(sqlu"SHUTDOWN") ; println("[rooteros] database checkpointed and shut down cleanly.") }
            catch { case e : Throwable => println("[rooteros] shutdown checkpoint skipped: " + e.getMessage) }
        }))

        WebPush.init()   // logs whether Web Push is enabled (VAPID_* env present) or disabled

        implicit val system = ActorSystem()
        implicit val executionContext = system.dispatcher

        def hasRight[R, E <: Effect with Effect.Read](userId : String, userSecret : String, journalId : String, right : String)(then : => DBIOAction[R, NoStream, E]) : DBIOAction[R, NoStream, E] = {
            users.filter(_.id === userId).filter(_.secret === userSecret).result.head.flatMap { _ =>
                accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === right).result.head.flatMap { _ =>
                    then
                }
            }
        }

        def index = readFile(directory + "/index.html")

        def html(s : String) = complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s))
        def plain(s : String) = complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, s))
        def redir(s : String) = redirect(s, StatusCodes.SeeOther)

        // Fallback for missing image assets: return a 1x1 transparent PNG (200) instead of a 404,
        // so the client's Cache.add() never rejects and the asset preloader can't hang forever
        // on a single missing/unfinished asset.
        val transparentPng = java.util.Base64.getDecoder.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

        def imageFallback = extractUnmatchedPath { p =>
            val s = p.toString.toLowerCase
            if (s.endsWith(".webp") || s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".gif") || s.endsWith(".svg"))
                complete(HttpEntity(ContentType(MediaTypes.`image/png`), transparentPng))
            else
                complete(StatusCodes.NotFound, "")
        }

        // Minimal JSON string escaper for the dashboard endpoint (no JSON lib on the classpath).
        def js(s : String) = "\"" + s.flatMap {
            case '"'  => "\\\""
            case '\\' => "\\\\"
            case '\n' => "\\n"
            case '\r' => "\\r"
            case '\t' => "\\t"
            case c if c < ' ' => "\\u%04x".format(c.toInt)
            case c => c.toString
        } + "\""

        // Keep only characters that are safe inside a URL path segment / identifier. metas, play
        // secrets and userIds are all drawn from this set, so this is lossless for valid input and
        // strips anything that could break out into a path or into the JSON we emit.
        def urlsafe(s : String) = s.filter(c => c.isLetterOrDigit || c == '-' || c == '_' || c == '.')

        // Verify an account's bearer secret; throws NoSuchElementException (caught → 403) if invalid.
        def checkAccount(accountId : String, accountSecret : String) : Unit =
            execute(accounts.filter(_.id === accountId).filter(_.secret === accountSecret).result.head)

        // Push "it's your turn" to every account behind a newly-asked userId, via GameSeats -> PushSubs.
        // Fire-and-forget on the Akka dispatcher (the route returns immediately); dead subscriptions
        // (404/410) are pruned. Payload keys match pwa/sw.js: {title, body, url, tag}.
        def notifyTurn(journalId : String, userIds : Set[String], iconId : Option[String] = None) : Unit = Future {
            // The game name is the notification title (so each game is instantly distinguishable),
            // "Your turn to play" is the body, and the acting faction's glyph is the icon when known.
            val title = execute(journals.filter(_.id === journalId).map(_.name).result).headOption.filter(_.nonEmpty).getOrElse("RooterOS")
            val iconField = iconId.filter(_.nonEmpty).map(i => "\"icon\":" + js("/faction-icon/" + i) + ",").getOrElse("")
            val payload =
                "{" +
                    "\"title\":" + js(title)              + "," +
                    "\"body\":"  + js("Your turn to play") + "," +
                    iconField +
                    "\"url\":"   + js("/games")    + "," +
                    "\"tag\":"   + js(journalId)   +
                "}"
            val accountIds = execute(
                gameSeats.filter(_.journalId === journalId).filter(_.userId.inSet(userIds)).map(_.accountId).result
            ).toSet
            accountIds.foreach { accountId =>
                execute(pushSubs.filter(_.accountId === accountId).result).foreach { sub =>
                    val code = WebPush.sendPush(sub.endpoint, sub.p256dh, sub.auth, payload)
                    if (code == 404 || code == 410)
                        execute(pushSubs.filter(_.endpoint === sub.endpoint).delete)
                }
            }
        }.recover { case e : Throwable => println("[rooteros] notifyTurn failed: " + e.getMessage) }

        val manifestCT = ContentType(MediaType.applicationWithFixedCharset("manifest+json", HttpCharsets.`UTF-8`, "webmanifest"))

        val route = cors() {
            (pathPrefix("hrf")) {
                // Cache the (rarely-changing) art/font/js assets for a day so the browser stops
                // re-fetching every one on each load, and stop content-type sniffing (nosniff).
                respondWithHeaders(
                    `Cache-Control`(CacheDirectives.public, CacheDirectives.`max-age`(86400)),
                    RawHeader("X-Content-Type-Options", "nosniff")
                ) {
                    optionalHeaderValueByName("Referer") { referer =>
                        // Serve when there is no Referer (browsers omit it for some
                        // early fetch()/script/CSS requests) or when it comes from the
                        // configured host or localhost; still block cross-site hotlinking.
                        if (referer.forall(r => r.startsWith(url) || r.startsWith("http://localhost") || r.startsWith("http://127.0.0.1")))
                            getFromDirectory(directory) ~ imageFallback
                        else
                            complete(StatusCodes.NotFound, "")
                    }
                }
            } ~
            (get & path("")) {
                redir("/play")
            } ~
            (get & path("play" / "")) {
                redir("/play")
            } ~
            (get & path("play")) {
                html(index
                    .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                    .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                    .replace("data-meta=\"" + "\"", "data-meta=\"" + "" + "\"")
                )
            } ~
            (get & path("play" / Segment / "")) { meta =>
                redir("/play/" + meta)
            } ~
            (get & path("play" / Segment)) { meta =>
                html(index
                    .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                    .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                    .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                )
            } ~
            (get & path("play" / Segment / Segments)) { (meta, secret) =>
                if (secret.length == 1 && secret(0).length == 16) {
                    val (user, play) = execute(plays.filter(_.secret === secret(0)).flatMap { play =>
                        users.filter(_.id === play.userId).map((_, play))
                    }.result.head)

                    html(index
                        .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                        .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                        .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                        .replace("data-user=\"" + "\"", "data-user=\"" + user.id + "\"")
                        .replace("data-secret=\"" + "\"", "data-secret=\"" + user.secret + "\"")
                        .replace("data-lobby=\"" + "\"", "data-lobby=\"" + play.journalId + "\"")
                    )
                }
                else
                    html(index
                        .replace("<base href=\"\" />", "<base href=\"" + cdn + "\"/>")
                        .replace("data-server=\"" + "\"", "data-server=\"" + url + "\"")
                        .replace("data-meta=\"" + "\"", "data-meta=\"" + meta + "\"")
                    )
            } ~
            // ---- PWA / dashboard static files (served at the root origin, outside /hrf/) ----
            (get & path("manifest.webmanifest")) {
                complete(HttpEntity(manifestCT, readFile(directory + "/pwa/manifest.webmanifest")))
            } ~
            (get & path("sw.js")) {
                getFromFile(directory + "/pwa/sw.js")
            } ~
            (get & path("games")) {
                html(readFile(directory + "/pwa/games.html"))
            } ~
            (get & path("icon-192.png")) {
                getFromFile(directory + "/pwa/icon-192.png")
            } ~
            (get & path("icon-512.png")) {
                getFromFile(directory + "/pwa/icon-512.png")
            } ~
            (get & path("icon-maskable.png")) {
                getFromFile(directory + "/pwa/icon-maskable.png")
            } ~
            // Faction glyph icons for the dashboard. Given a glyph id (e.g. "mc-glyph"), search the
            // per-category faction image dirs so the dashboard doesn't need to know the category.
            (get & path("faction-icon" / Segment)) { glyphId =>
                val id = glyphId.filter(c => c.isLetterOrDigit || c == '-' || c == '_')
                val base = new java.io.File(directory + "/webp2/root/images/faction")
                val file = Option(base.listFiles).toList.flatten.filter(_.isDirectory)
                    .map(d => new java.io.File(d, id + ".webp")).find(_.exists)
                file match {
                    case Some(f) =>
                        respondWithHeaders(`Cache-Control`(CacheDirectives.public, CacheDirectives.`max-age`(604800))) { getFromFile(f) }
                    case None => complete(HttpEntity(ContentType(MediaTypes.`image/png`), transparentPng))
                }
            } ~
            (post & path("new-user")) {
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(32).trim.safeplus
                        val user = User(name, newSecret(16), newSecret(16))
                        execute(users += user)
                        plain(user.id + "\n" + user.secret)
                    }
                }
            } ~
            (post & path("new-journal" / Segment / Segment)) { case (userId, userSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(128).trim.safeplus
                        val id = newSecret(16)
                        execute(users.filter(_.id === userId).filter(_.secret === userSecret).map(_.id).result.head.flatMap { userId =>
                            seq(
                                journals += Journal(name, false, "", "", id),
                                accessRights += AccessRight(id, userId, "full"),
                                accessRights += AccessRight(id, userId, "read"),
                                accessRights += AccessRight(id, userId, "append")
                            )
                        })
                        plain(id)
                    }
                }
            } ~
            (post & path("grant-read" / Segment / Segment / Segment / Segment)) { case (userId, userSecret, journalId, anotherUser) =>
                execute(hasRight(userId, userSecret, journalId, "full") {
                    users.filter(_.id === anotherUser).result.head.flatMap { _ =>
                        accessRights += AccessRight(journalId, anotherUser, "read")
                    }
                })
                plain("")
            } ~
            (post & path("grant-read-append" / Segment / Segment / Segment / Segment)) { case (userId, userSecret, journalId, anotherUser) =>
                execute(hasRight(userId, userSecret, journalId, "full") {
                    users.filter(_.id === anotherUser).result.head.flatMap { _ =>
                        accessRights ++= List(AccessRight(journalId, anotherUser, "read"), AccessRight(journalId, anotherUser, "append"))
                    }
                })
                plain("")
            } ~
            (post & path("new-play" / Segment / Segment / Segment)) { case (userId, userSecret, journalId) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(32).trim.safeplus
                        val secret = newSecret(16)
                        val user = User(name, newSecret(16), newSecret(16))
                        execute(hasRight(userId, userSecret, journalId, "full") {
                            seq(
                                users += user,
                                accessRights += AccessRight(journalId, user.id, "read"),
                                accessRights += AccessRight(journalId, user.id, "append"),
                                plays += Play(journalId, user.id, secret)
                            )
                        })
                        plain(user.id + "\n" + secret)
                    }
                }
            } ~
            (get & path("read" / Segment / Segment / Segment / IntNumber)) { (userId, userSecret, journalId, from) =>
                val log = execute(hasRight(userId, userSecret, journalId, "read") {
                    entries.filter(_.journalId === journalId).filter(_.index >= from).map(_.text).result
                })
                plain(log.mkString("\n"))
            } ~
            (post & path("append" / Segment / Segment / Segment / IntNumber)) { (userId, userSecret, journalId, from) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val ss = body.split('\n').toList.map(_.asciiplus)

                        try {
                            execute(hasRight(userId, userSecret, journalId, "append") {
                                entries ++= 0.until(ss.size).map(n => Entry(journalId, from + n, userId, ss(n)))
                            })
                            complete(StatusCodes.Accepted)
                        }
                        catch {
                            case e : java.sql.SQLIntegrityConstraintViolationException => complete(StatusCodes.Conflict)
                        }
                    }
                }
            } ~
            // ===================== Account / dashboard / push layer =====================
            // Create a durable account for a freshly installed PWA. Body: display name (optional).
            (post & path("new-account")) {
                decodeRequest {
                    entity(as[String]) { body =>
                        val name = body.take(32).trim.safeplus
                        val account = Account(newSecret(16), newSecret(16), name, "", System.currentTimeMillis())
                        execute(accounts += account)
                        plain(account.id + "\n" + account.secret)
                    }
                }
            } ~
            // Tie a game seat (an existing capability User/Play) to an account so it shows on the
            // dashboard. Body: userId\nuserSecret\njournalId\nmeta\nplaySecret . Deduped per journal.
            (post & path("register-game" / Segment / Segment)) { case (accountId, accountSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val ls = body.split('\n').toList
                        val userId = urlsafe(ls.lift(0).getOrElse("").trim)
                        val userSecret = ls.lift(1).getOrElse("").trim
                        val journalId = urlsafe(ls.lift(2).getOrElse("").trim)
                        val meta = urlsafe(ls.lift(3).getOrElse("").trim)
                        val playSecret = urlsafe(ls.lift(4).getOrElse("").trim)
                        try {
                            checkAccount(accountId, accountSecret)
                            // the seat's own credentials must be valid and have read access to the game
                            execute(users.filter(_.id === userId).filter(_.secret === userSecret).result.head)
                            execute(accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === "read").result.head)
                            execute(gameSeats.filter(_.accountId === accountId).filter(_.journalId === journalId).delete)
                            execute(gameSeats += GameSeat(accountId, userId, journalId, meta, playSecret, System.currentTimeMillis()))
                            complete(StatusCodes.Accepted)
                        }
                        catch { case e : Throwable => complete(StatusCodes.Forbidden, "") }
                    }
                }
            } ~
            // Add a game to this account straight from a shared play link (iOS-friendly: links open
            // in Safari, not the installed app, so the user pastes the link into the dashboard). The
            // play secret alone resolves the seat, so no per-seat credentials are needed here.
            // Body: meta\nplaySecret
            (post & path("add-game" / Segment / Segment)) { case (accountId, accountSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val ls = body.split('\n').toList
                        val meta = urlsafe(ls.lift(0).getOrElse("").trim)
                        val playSecret = urlsafe(ls.lift(1).getOrElse("").trim)
                        try {
                            checkAccount(accountId, accountSecret)
                            val play = execute(plays.filter(_.secret === playSecret).result.head)
                            execute(gameSeats.filter(_.accountId === accountId).filter(_.journalId === play.journalId).delete)
                            execute(gameSeats += GameSeat(accountId, play.userId, play.journalId, meta, playSecret, System.currentTimeMillis()))
                            complete(StatusCodes.Accepted)
                        }
                        catch { case e : Throwable => complete(StatusCodes.NotFound, "") }
                    }
                }
            } ~
            // Send a test push to all of this account's subscriptions, so the user can verify the
            // whole pipeline (subscribe -> server send -> sw.js notification) works on their device.
            (post & path("test-push" / Segment / Segment)) { case (accountId, accountSecret) =>
                try {
                    checkAccount(accountId, accountSecret)
                    if (!WebPush.enabled)
                        complete(StatusCodes.ServiceUnavailable, "push disabled on server")
                    else {
                        val payload =
                            "{" +
                                "\"title\":" + js("RooterOS") + "," +
                                "\"body\":"  + js("Test notification — push is working.") + "," +
                                "\"url\":"   + js("/games") + "," +
                                "\"tag\":"   + js("rooteros-test") +
                            "}"
                        val subs = execute(pushSubs.filter(_.accountId === accountId).result)
                        println("[rooteros] test-push account " + accountId.take(6) + ": " + subs.size + " subscription(s)")
                        Future {
                            subs.foreach { sub =>
                                val code = WebPush.sendPush(sub.endpoint, sub.p256dh, sub.auth, payload)
                                println("[rooteros] test-push send -> HTTP " + code)
                                if (code == 404 || code == 410) execute(pushSubs.filter(_.endpoint === sub.endpoint).delete)
                            }
                        }.recover { case e : Throwable => println("[rooteros] test-push failed: " + e.getMessage) }
                        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, subs.size + " subscription(s) on this account"))
                    }
                }
                catch { case e : Throwable => complete(StatusCodes.Forbidden, "") }
            } ~
            // Dashboard data: every game seat tied to this account, with the game title
            // (Journal.name) and whose-turn info. Returns a small JSON array.
            (get & path("my-games" / Segment / Segment)) { case (accountId, accountSecret) =>
                try {
                    checkAccount(accountId, accountSecret)
                    val seats = execute(gameSeats.filter(_.accountId === accountId).result).toList
                    val myUserIds = seats.map(_.userId).toSet
                    val items = seats.map { s =>
                        val title = execute(journals.filter(_.id === s.journalId).map(_.name).result).headOption.getOrElse("")
                        val st = execute(journalStatuses.filter(_.journalId === s.journalId).result).headOption
                        val waiting = st.map(_.waiting).getOrElse("")
                        val status = st.map(_.status).getOrElse("")
                        val waitingSet = waiting.split(' ').filter(_.nonEmpty).toSet
                        val yourTurn = waitingSet.exists(myUserIds.contains)
                        val updated = st.map(_.updated).getOrElse(s.updated)
                        "{" +
                            "\"journalId\":"  + js(s.journalId)  + "," +
                            "\"userId\":"     + js(s.userId)     + "," +
                            "\"title\":"      + js(title)        + "," +
                            "\"meta\":"       + js(s.meta)       + "," +
                            "\"playSecret\":" + js(s.playSecret) + "," +
                            "\"waiting\":"    + js(waiting)      + "," +
                            "\"yourTurn\":"   + yourTurn         + "," +
                            "\"status\":"     + js(status)       + "," +
                            "\"updated\":"    + updated          +
                        "}"
                    }
                    complete(HttpEntity(ContentTypes.`application/json`, items.mkString("[", ",", "]")))
                }
                catch { case e : Throwable => complete(StatusCodes.Forbidden, "[]") }
            } ~
            // Store/refresh a Web Push subscription for this account. Body: endpoint\np256dh\nauth
            (post & path("push-subscribe" / Segment / Segment)) { case (accountId, accountSecret) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val ls = body.split('\n').toList
                        val endpoint = ls.lift(0).getOrElse("").trim
                        val p256dh = ls.lift(1).getOrElse("").trim
                        val auth = ls.lift(2).getOrElse("").trim
                        try {
                            checkAccount(accountId, accountSecret)
                            if (endpoint.nonEmpty) {
                                execute(pushSubs.filter(_.endpoint === endpoint).delete)
                                execute(pushSubs += PushSub(accountId, endpoint, p256dh, auth, System.currentTimeMillis()))
                            }
                            complete(StatusCodes.Accepted)
                        }
                        catch { case e : Throwable => complete(StatusCodes.Forbidden, "") }
                    }
                }
            } ~
            // A client reports whose turn it is in a journal (space-separated userIds the game is
            // currently asking — covers off-turn reactions like Ambush). Any seat with append rights
            // may report. Body: userIds (space separated).
            (post & path("waiting-for" / Segment / Segment / Segment)) { case (userId, userSecret, journalId) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val waiting = body.split('\n').headOption.getOrElse("").trim.split(' ').map(urlsafe).filter(_.nonEmpty).mkString(" ")
                        try {
                            execute(users.filter(_.id === userId).filter(_.secret === userSecret).result.head)
                            execute(accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === "append").result.head)

                            val prev = execute(journalTurns.filter(_.journalId === journalId).result).headOption.map(_.waiting).getOrElse("")
                            val prevSet = prev.split(' ').filter(_.nonEmpty).toSet
                            val nowSet = waiting.split(' ').filter(_.nonEmpty).toSet
                            val newlyAsked = nowSet.diff(prevSet)

                            execute(journalTurns.filter(_.journalId === journalId).delete)
                            execute(journalTurns += JournalTurn(journalId, waiting, System.currentTimeMillis()))

                            // Only notify the seats that are being asked NOW that weren't a moment ago —
                            // covers off-turn reactions (Ambush etc.), not just the main turn holder.
                            if (WebPush.enabled && newlyAsked.nonEmpty)
                                notifyTurn(journalId, newlyAsked)

                            complete(StatusCodes.Accepted)
                        }
                        catch { case e : Throwable => complete(StatusCodes.Forbidden, "") }
                    }
                }
            } ~
            // Richer whose-turn + scoreboard report from the game client. Body: first line is the
            // space-separated waiting userIds (drives the push diff, exactly like /waiting-for); the
            // rest is a small JSON scoreboard stored verbatim for the dashboard's details view.
            (post & path("game-status" / Segment / Segment / Segment)) { case (userId, userSecret, journalId) =>
                decodeRequest {
                    entity(as[String]) { body =>
                        val nl = body.indexOf('\n')
                        val waiting = (if (nl >= 0) body.substring(0, nl) else body).trim.split(' ').map(urlsafe).filter(_.nonEmpty).mkString(" ")
                        val status = (if (nl >= 0) body.substring(nl + 1) else "").trim.take(20000)
                        try {
                            execute(users.filter(_.id === userId).filter(_.secret === userSecret).result.head)
                            execute(accessRights.filter(_.journalId === journalId).filter(_.userId === userId).filter(_.right === "append").result.head)

                            println("[rooteros] game-status journal " + journalId.take(6) + " waiting=[" + waiting + "] statusLen=" + status.length)

                            val prev = execute(journalStatuses.filter(_.journalId === journalId).result).headOption.map(_.waiting).getOrElse("")
                            val prevSet = prev.split(' ').filter(_.nonEmpty).toSet
                            val nowSet = waiting.split(' ').filter(_.nonEmpty).toSet
                            val newlyAsked = nowSet.diff(prevSet)

                            execute(journalStatuses.filter(_.journalId === journalId).delete)
                            execute(journalStatuses += JournalStatus(journalId, waiting, status, System.currentTimeMillis()))

                            // Glyph of the faction whose turn it is, for the notification icon.
                            val turnIcon = """"turn":true[^}]*?"icon":"([^"]*)"""".r.findFirstMatchIn(status).map(_.group(1)).filter(_.nonEmpty)

                            if (WebPush.enabled && newlyAsked.nonEmpty)
                                notifyTurn(journalId, newlyAsked, turnIcon)

                            complete(StatusCodes.Accepted)
                        }
                        catch { case e : Throwable => complete(StatusCodes.Forbidden, "") }
                    }
                }
            }
        }
// create a user database and a journal database
        implicit val materializer = ActorMaterializer()
        import akka.stream.scaladsl._  
        import akka.http.scaladsl.util._
        import akka.http.scaladsl.model.StatusCodes
        import akka.http.scaladsl.server.Directives._
        import akka.http.scaladsl.server.Route
        import akka.http.scaladsl.settings.ServerSettings
        import akka.http.scaladsl.Http
        import hrf.gg.Ssl
        import scala.util.Using
        import scala.concurrent.Future
        import akka.http.scaladsl.model.headers.Location
    






        val settings = ServerSettings("").withRemoteAddressAttribute(true)

        var server = Http().newServerAt("0.0.0.0", port).withSettings(settings)

        val keyFile = new java.io.File("certificate.pkcs12")

        if (keyFile.exists()) {
            val hcc = Ssl.serverHttpsContext(keyFile, "")

            server = server.enableHttps(hcc)
        }

        // gzip/deflate responses (esp. the ~40MB JS) so first load is usable over the internet
        val bindingFuture = server.bind(encodeResponse { route })

        println("Started server.")

        if (port != 80 && keyFile.exists()) {
            val redirroute = get {
                redirect(url, StatusCodes.MovedPermanently)
            }

            Http().newServerAt("0.0.0.0", 80).bind(redirroute)

            println("Started redirect server.")
        }

        while (true)
            Thread.sleep(1000)

        bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
    }
}
