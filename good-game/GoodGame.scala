package hrf.gg

import slick.jdbc.HsqldbProfile.api._
import slick.jdbc.HsqldbProfile.api.DBIO.seq

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

        val manifestCT = ContentType(MediaType.applicationWithFixedCharset("manifest+json", HttpCharsets.`UTF-8`, "webmanifest"))

        val route = cors() {
            (pathPrefix("hrf")) {
                optionalHeaderValueByName("Referer") { referer =>
                    // Serve when there is no Referer (browsers omit it for some
                    // early fetch()/script/CSS requests) or when it comes from the
                    // configured host or localhost; still block cross-site hotlinking.
                    if (referer.forall(r => r.startsWith(url) || r.startsWith("http://localhost") || r.startsWith("http://127.0.0.1")))
                        getFromDirectory(directory) ~ imageFallback
                    else
                        complete(StatusCodes.NotFound, "")
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
            // Dashboard data: every game seat tied to this account, with the game title
            // (Journal.name) and whose-turn info. Returns a small JSON array.
            (get & path("my-games" / Segment / Segment)) { case (accountId, accountSecret) =>
                try {
                    checkAccount(accountId, accountSecret)
                    val seats = execute(gameSeats.filter(_.accountId === accountId).result).toList
                    val myUserIds = seats.map(_.userId).toSet
                    val items = seats.map { s =>
                        val title = execute(journals.filter(_.id === s.journalId).map(_.name).result).headOption.getOrElse("")
                        val turn = execute(journalTurns.filter(_.journalId === s.journalId).result).headOption
                        val waiting = turn.map(_.waiting).getOrElse("")
                        val waitingSet = waiting.split(' ').filter(_.nonEmpty).toSet
                        val yourTurn = waitingSet.exists(myUserIds.contains)
                        val updated = turn.map(_.updated).getOrElse(s.updated)
                        "{" +
                            "\"journalId\":"  + js(s.journalId)  + "," +
                            "\"title\":"      + js(title)        + "," +
                            "\"meta\":"       + js(s.meta)       + "," +
                            "\"playSecret\":" + js(s.playSecret) + "," +
                            "\"waiting\":"    + js(waiting)      + "," +
                            "\"yourTurn\":"   + yourTurn         + "," +
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
                            execute(journalTurns.filter(_.journalId === journalId).delete)
                            execute(journalTurns += JournalTurn(journalId, waiting, System.currentTimeMillis()))
                            // TODO (push checkpoint): diff against the previous `waiting` set and send a
                            // Web Push "it's your turn" to the account(s) behind any newly-asked userId
                            // (look them up via GameSeats → PushSubs).
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
