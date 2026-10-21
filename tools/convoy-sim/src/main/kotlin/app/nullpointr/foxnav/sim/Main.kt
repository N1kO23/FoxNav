package app.nullpointr.foxnav.sim

import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.shared.convoy.ConvoyEvent
import app.nullpointr.foxnav.shared.convoy.ConvoySession
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.shared.convoy.memberState
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.geo.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

private const val USAGE = """Usage: convoy-sim (--create --server URL | --invite-file FILE) [options]

Bots that join a FoxNav convoy and drive one behind the other, for testing convoys.

  --server URL        A FoxNav server, as typed in the app. Needed with --create and
                      defaults to the invite's server otherwise.
  --create            Start a new convoy, with the first bot as its owner.
  --invite-out FILE   With --create: write the invite link to FILE instead of printing it.
  --invite-file FILE  Join the convoy whose invite link is in FILE. A file keeps the
                      convoy's key out of your shell history.
  --bots N            How many bots (default 3).
  --from LAT,LON      Drive the server's route from here (default: central Helsinki)...
  --to LAT,LON        ...to here (default: Leppävaara, Espoo), and back.
  --gpx FILE          Or drive the track in a GPX file, there and back.
  --kmh N             Cruising speed (default 60); each bot varies it a little.
  --spacing M         Metres between bots (default 150).
  --chatty            Send a random quick message every minute or so.
  --roles             The bot in front leads and the one at the back sweeps up (LEAD, TAIL).
  --music "TITLE/ARTIST"  The bot in front shares that it's playing this song.
  --minutes N         Leave after N minutes (default: run until Ctrl+C).
"""

data class Options(
    val server: String? = null,
    val create: Boolean = false,
    val inviteOut: File? = null,
    val inviteFile: File? = null,
    val bots: Int = 3,
    val from: GeoPoint = GeoPoint(60.1699, 24.9384),
    val to: GeoPoint = GeoPoint(60.2189, 24.8137),
    val gpx: File? = null,
    val kmh: Double = 60.0,
    val spacingM: Double = 150.0,
    val chatty: Boolean = false,
    val roles: Boolean = false,
    val music: NowPlaying? = null,
    val minutes: Double? = null,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var options = Options()
            val queue = ArrayDeque(args.toList())
            fun value(flag: String) = queue.removeFirstOrNull() ?: throw IllegalArgumentException("$flag needs a value")
            fun point(flag: String) = value(flag).split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 }
                ?.let { (latitude, longitude) -> GeoPoint(latitude, longitude) } ?: throw IllegalArgumentException("$flag takes LAT,LON")
            fun number(flag: String) = value(flag).toDoubleOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag takes a positive number")
            while (queue.isNotEmpty()) {
                when (val flag = queue.removeFirst()) {
                    "--server" -> options = options.copy(server = value(flag))
                    "--create" -> options = options.copy(create = true)
                    "--invite-out" -> options = options.copy(inviteOut = File(value(flag)))
                    "--invite-file" -> options = options.copy(inviteFile = File(value(flag)))
                    "--bots" -> options = options.copy(bots = number(flag).toInt().coerceIn(1, 50))
                    "--from" -> options = options.copy(from = point(flag))
                    "--to" -> options = options.copy(to = point(flag))
                    "--gpx" -> options = options.copy(gpx = File(value(flag)))
                    "--kmh" -> options = options.copy(kmh = number(flag))
                    "--spacing" -> options = options.copy(spacingM = number(flag))
                    "--chatty" -> options = options.copy(chatty = true)
                    "--roles" -> options = options.copy(roles = true)
                    "--music" -> options = options.copy(
                        music = value(flag).split('/', limit = 2).let { NowPlaying(title = it[0].trim(), artist = it.getOrElse(1) { "" }.trim(), app = "convoy-sim") },
                    )
                    "--minutes" -> options = options.copy(minutes = number(flag))
                    "--help", "-h" -> throw IllegalArgumentException("")
                    else -> throw IllegalArgumentException("Unknown option $flag")
                }
            }
            require(options.create != (options.inviteFile != null)) { "Use either --create or --invite-file" }
            require(!options.create || options.server != null) { "--create needs --server" }
            return options
        }
    }
}

fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        if (!e.message.isNullOrEmpty()) System.err.println(e.message + "\n")
        System.err.print(USAGE)
        exitProcess(2)
    }
    try {
        runBlocking(Dispatchers.Default) { Simulation(options).run() }
    } catch (e: SimulationError) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

class SimulationError(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw SimulationError(message)

class Simulation(private val options: Options) {
    private val http = HttpClient(CIO) { install(WebSockets) }

    suspend fun run() = coroutineScope {
        val joining = options.inviteFile?.let { file ->
            if (!file.isFile) fail("No such file: $file")
            Invite.parse(file.readText().trim()) ?: fail("$file holds no FoxNav invite link")
        }
        val serverUrl = normalizeServerUrl(options.server ?: joining!!.server)
        val document = discover(serverUrl)
        val realtime = document.realtime?.takeIf { Feature.CONVOY in document.features } ?: fail("${document.name} doesn't host convoys")
        val track = options.gpx?.let(Track::fromGpx) ?: Track(route(document, options.from, options.to))
        log("${document.name}: driving a ${"%.1f".format(track.length / 1000)} km track there and back at about ${options.kmh.roundToInt()} km/h")

        val bots = ArrayList<Bot>()
        fun newBot(start: ConvoySession.Start): Bot {
            val session = ConvoySession(http, realtime, start, this, System::currentTimeMillis, clientName = "FoxNav convoy-sim")
            // The first bot leads: it starts furthest along the track.
            // The first bot starts furthest along: it's in front.
            val index = bots.size
            val role = when {
                !options.roles -> Profile.Role.ROLE_UNSPECIFIED
                index == 0 -> Profile.Role.ROLE_LEAD
                index == options.bots - 1 -> Profile.Role.ROLE_TAIL
                else -> Profile.Role.ROLE_UNSPECIFIED
            }
            return Bot(index, session, track, startDistance = (options.bots - 1 - index) * options.spacingM, options.kmh, role, options.music.takeIf { index == 0 })
                .also { bots += it }
        }

        val invite = joining ?: run {
            val owner = newBot(ConvoySession.Start.Create(serverUrl))
            owner.start(this, report = true)
            val created = withTimeoutOrNull(30_000) { owner.session.invite.filterNotNull().first() } ?: fail("Couldn't create a convoy on $serverUrl")
            val link = created.toLink()
            val out = options.inviteOut
            if (out != null) {
                out.writeText(link + "\n")
                log("Created a convoy. Its invite link is in $out (it carries the convoy's key: share it only with testers).")
            } else {
                log("Created a convoy. Invite link (it carries the convoy's key: share it only with testers):\n$link")
            }
            created
        }
        while (bots.size < options.bots) newBot(ConvoySession.Start.Join(invite)).start(this, report = bots.size == 1 && joining != null)

        val extras = buildList {
            add(launch { reportOthers(bots) })
            if (options.chatty) add(launch { chat(bots) })
        }
        val shutdown = Thread { runBlocking { leaveAll(bots) } }
        Runtime.getRuntime().addShutdownHook(shutdown)

        val minutes = options.minutes
        if (minutes != null) {
            delay((minutes * 60_000).toLong())
            log("Time's up: the bots leave the convoy")
            Runtime.getRuntime().removeShutdownHook(shutdown)
            extras.forEach { it.cancel() }
            leaveAll(bots)
            bots.forEach { it.stop() }
            http.close()
        } else {
            extras.joinAll()
        }
    }

    private suspend fun leaveAll(bots: List<Bot>) {
        withTimeoutOrNull(5_000) { coroutineScope { bots.forEach { bot -> launch { bot.session.leave() } } } }
    }

    /** Every 15 s, what the first bot sees of the humans in the convoy: proof their positions arrive and decrypt. */
    private suspend fun reportOthers(bots: List<Bot>) {
        while (true) {
            delay(15_000)
            val live = bots.filter { it.session.status.value is ConvoyStatus.Live }
            val observer = live.firstOrNull() ?: continue
            val botIds = bots.mapNotNull { it.memberId }.toSet()
            val others = observer.session.members.value.values.filter { it.id !in botIds }
            val leader = bots.first().pose?.point?.let { " · ${bots.first().name} leads at %.5f, %.5f".format(it.latitude, it.longitude) }.orEmpty()
            log("${live.size}/${bots.size} bots in the convoy$leader")
            if (others.isEmpty()) {
                log("  nobody else yet")
                continue
            }
            val now = System.currentTimeMillis()
            for (member in others) {
                val name = member.profile?.display_name?.ifBlank { null } ?: "(no name yet)"
                val fix = member.fix
                val heard = member.lastHeardMs?.let { "${(now - it) / 1000} s ago" } ?: "never"
                val heading = member.route?.waypoints?.lastOrNull()?.let { ", heading to ${it.name.ifBlank { "a shared destination" }}" }.orEmpty()
                if (fix == null) {
                    log("  $name: no position yet (heard $heard)$heading")
                } else {
                    val gap = observer.pose?.point?.distanceTo(fix.point)?.let { "%.1f km".format(it / 1000) } ?: "?"
                    log("  $name: ${(fix.speedMps * 3.6).roundToInt()} km/h, $gap from ${observer.name}, heard $heard$heading")
                }
            }
        }
    }

    private suspend fun chat(bots: List<Bot>) {
        val kinds = listOf(
            QuickMessage.Kind.KIND_POLICE_AHEAD,
            QuickMessage.Kind.KIND_HAZARD_AHEAD,
            QuickMessage.Kind.KIND_SLOW_DOWN,
            QuickMessage.Kind.KIND_FUEL,
            QuickMessage.Kind.KIND_BREAK,
            QuickMessage.Kind.KIND_ALL_GOOD,
        )
        while (true) {
            delay(Random.nextLong(45_000, 90_000))
            val bot = bots.filter { it.memberId != null }.randomOrNull() ?: continue
            val kind = kinds.random()
            bot.session.sendQuickMessage(kind)
            log("${bot.name} sent ${kind.name.removePrefix("KIND_").lowercase().replace('_', ' ')}")
        }
    }

    private suspend fun discover(serverUrl: String): DiscoveryDocument {
        val documentUrl = serverUrl + DiscoveryDocument.PATH
        val response = runCatching { http.get(documentUrl) }.getOrElse { fail("Couldn't reach $serverUrl: ${it.message}") }
        if (!response.status.isSuccess()) fail("HTTP ${response.status.value} from $documentUrl")
        return DiscoveryDocument.parse(response.bodyAsText()).resolved(documentUrl)
    }

    /** A driving route from the server's Valhalla. */
    private suspend fun route(document: DiscoveryDocument, from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val routing = document.routing?.takeIf { it.type == "valhalla" } ?: fail("${document.name} has no Valhalla routing; use --gpx")
        val request = """{"locations":[{"lat":${from.latitude},"lon":${from.longitude}},{"lat":${to.latitude},"lon":${to.longitude}}],"costing":"auto"}"""
        val response = http.post(routing.url.trimEnd('/') + "/route") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) fail("No route from ${document.name}: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
        val legs = Json.parseToJsonElement(response.bodyAsText()).jsonObject["trip"]?.jsonObject?.get("legs")?.jsonArray ?: fail("Unexpected routing response")
        return legs.flatMap { leg -> decodePolyline(leg.jsonObject.getValue("shape").jsonPrimitive.content) }
    }
}

/** One bot: a convoy member driving along [track]. */
class Bot(
    index: Int,
    val session: ConvoySession,
    private val track: Track,
    startDistance: Double,
    private val kmh: Double,
    role: Profile.Role = Profile.Role.ROLE_UNSPECIFIED,
    private val music: NowPlaying? = null,
) {
    val name: String = NAMES[index % NAMES.size] + if (index >= NAMES.size) " ${index / NAMES.size + 1}" else ""
    private val profile = Profile(display_name = name, car = CARS[index % CARS.size].copy(color_rgb = PALETTE[index % PALETTE.size]), role = role)
    private var distance = startDistance
    private var job: Job? = null

    @Volatile
    var memberId: Long? = null
        private set

    @Volatile
    var pose: Pose? = null
        private set

    fun start(scope: CoroutineScope, report: Boolean) {
        job = scope.launch {
            launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { event -> if (report) log(describe(event)) }
            }
            launch {
                session.members.collect { members ->
                    for ((id, member) in members) member.profile?.display_name?.ifBlank { null }?.let { names[id] = it }
                }
            }
            launch {
                session.status.collect { status ->
                    when (status) {
                        is ConvoyStatus.Live -> {
                            if (memberId != status.memberId) log("$name is in the convoy")
                            memberId = status.memberId
                        }
                        is ConvoyStatus.Reconnecting -> if (status.attempt == 1) log("$name lost the connection, reconnecting")
                        is ConvoyStatus.Ended -> log("$name: convoy over (${status.reason.name.lowercase().replace('_', ' ')})")
                        ConvoyStatus.Connecting -> Unit
                    }
                }
            }
            session.start()
            // Speeds drift a little, out of step between bots, so the gaps between them breathe.
            val phase = Random.nextDouble(0.0, 2 * PI)
            val periodS = Random.nextDouble(40.0, 80.0)
            var last = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val speed = kmh / 3.6 * (1 + 0.08 * sin(2 * PI * now / 1000.0 / periodS + phase))
                distance += speed * (now - last) / 1000.0
                last = now
                val current = track.poseAt(distance)
                pose = current
                session.updateState(memberState(profile, Fix(current.point, speed, current.headingDeg, now), nowPlaying = music))
                delay(1_000)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
    }

    private fun describe(event: ConvoyEvent): String = when (event) {
        is ConvoyEvent.MemberJoined -> "Someone joined the convoy"
        is ConvoyEvent.MemberLeft -> "${nameOf(event.memberId)} ${if (event.removed) "was removed" else "left"}"
        is ConvoyEvent.QuickMessageReceived -> "${nameOf(event.from)} sent ${event.kind.name.removePrefix("KIND_").lowercase().replace('_', ' ')}"
        is ConvoyEvent.RegroupPinDropped -> "${nameOf(event.from)} set a regroup point at ${"%.5f, %.5f".format(event.point.latitude, event.point.longitude)}"
        is ConvoyEvent.Refused -> "The server refused something: ${event.message}"
    }

    // Kept after people leave, so "Aki left" still has a name.
    private val names = ConcurrentHashMap<Long, String>()

    private fun nameOf(memberId: Long) = names[memberId] ?: "A member"

    private companion object {
        val NAMES = listOf("Aki", "Mika", "Sanna", "Jussi", "Noora", "Ville", "Elina", "Tero", "Kaisa", "Lauri")
        val CARS = listOf(
            Car(make = "Toyota", model = "GR86"),
            Car(make = "Nissan", model = "Silvia S15"),
            Car(make = "Mazda", model = "MX-5"),
            Car(make = "Subaru", model = "BRZ"),
            Car(make = "Honda", model = "Civic Type R"),
            Car(make = "Volvo", model = "240"),
        )

        // The app's convoy palette (design/tokens.json).
        val PALETTE = listOf(0x4C8DFF, 0x3DDC97, 0xFF5FA2, 0xB79BFF, 0xA8E05F)
    }
}

private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

/** A line of output, with the time, so long runs can be read back. */
private fun log(line: String) {
    val time = java.time.LocalTime.now().format(clockFormat)
    println(line.lines().joinToString("\n") { "$time  $it" })
}

/** What the user typed into a base URL: a bare domain means HTTPS. */
fun normalizeServerUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().trimEnd('/')
    return if ("://" in trimmed) trimmed else "https://$trimmed"
}
