package app.nullpointr.foxnav.conformance

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Hello
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.LeaveConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Ping
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.Sealed
import app.nullpointr.foxnav.shared.convoy.secureRandomBytes
import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.reports.ReportCreated
import app.nullpointr.foxnav.shared.reports.ReportFeed
import app.nullpointr.foxnav.shared.routing.RoutingTileManifest
import app.nullpointr.foxnav.shared.safety.CameraPack
import app.nullpointr.foxnav.shared.speed.SpeedLimitPack
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.seconds
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError

/** Groups of checks, so a run can leave out what a test setup doesn't have. */
enum class Group(val title: String) {
    DISCOVERY("Discovery (§1)"),
    MAP_SERVICES("Map services (§1)"),
    REALTIME("Realtime (§2)"),
    REPORTS("Reports (§4)"),
}

enum class Outcome { PASS, FAIL, SKIP }

data class CheckResult(val group: Group, val name: String, val outcome: Outcome, val detail: String = "")

/**
 * Checks a server against protocol/SPEC.md: the discovery document, the map services it lists,
 * and the convoy relay, message by message. The realtime checks speak the raw protocol with
 * opaque payloads, so they test the server, not a client. Each convoy check uses a new convoy.
 *
 * Checks that leave something behind for other users, like a report, run only with [writes];
 * they put it on Null Island (0, 0), far from any road, and vote it away afterwards.
 */
class ConformanceSuite(
    private val http: HttpClient,
    serverUrl: String,
    private val groups: Set<Group> = Group.entries.toSet(),
    private val writes: Boolean = false,
) {
    private val base = serverUrl.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
    private val results = ArrayList<CheckResult>()

    suspend fun run(): List<CheckResult> {
        results.clear()
        val document = check(Group.DISCOVERY, "serves the discovery document") { discover() }
        if (document == null) {
            skipRest("needs the discovery document")
            return results.toList()
        }
        discoveryChecks(document)
        mapServiceChecks(document)
        realtimeChecks(document)
        reportChecks(document)
        return results.toList()
    }

    private suspend fun discover(): DiscoveryDocument {
        val url = base + DiscoveryDocument.PATH
        val response = http.get(url)
        expect(response.status.isSuccess()) { "GET $url answered HTTP ${response.status.value}" }
        val document = runCatching { DiscoveryDocument.parse(response.bodyAsText()) }.getOrElse { fail("$url isn't a valid discovery document: ${it.message}") }
        expect(document.version == DiscoveryDocument.CURRENT_VERSION) { "\"foxnav\" is ${document.version}, not ${DiscoveryDocument.CURRENT_VERSION}" }
        expect(document.name.isNotBlank()) { "\"name\" is blank" }
        return document.resolved(url)
    }

    private suspend fun discoveryChecks(document: DiscoveryDocument) {
        check(Group.DISCOVERY, "its map styles load") {
            if (document.styles.isEmpty()) throw Skipped("the server lists no styles")
            for ((theme, url) in document.styles) {
                val response = http.get(url)
                expect(response.status.isSuccess()) { "the $theme style ($url) answered HTTP ${response.status.value}" }
                val style = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrElse { fail("the $theme style isn't JSON") }
                expect(style["layers"] is JsonArray) { "the $theme style has no layers" }
            }
        }
    }

    private suspend fun mapServiceChecks(document: DiscoveryDocument) {
        val region = document.regions.firstOrNull { it in TEST_TRIPS }
        check(Group.MAP_SERVICES, "routes with Valhalla") {
            val routing = document.routing?.takeIf { it.type == "valhalla" } ?: throw Skipped("no Valhalla routing listed")
            val (from, to) = TEST_TRIPS[region]?.route ?: throw Skipped("no test route for regions ${document.regions}")
            val response = http.post(routing.url.trimEnd('/') + "/route") {
                contentType(ContentType.Application.Json)
                setBody("""{"locations":[{"lat":${from.latitude},"lon":${from.longitude}},{"lat":${to.latitude},"lon":${to.longitude}}],"costing":"auto"}""")
            }
            expect(response.status.isSuccess()) { "the route request answered HTTP ${response.status.value}" }
            val length = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["trip"]!!.jsonObject["summary"]!!.jsonObject["length"]!!.jsonPrimitive.doubleOrNull
            }.getOrNull()
            expect(length != null && length > 0) { "the answer has no route length" }
        }
        check(Group.MAP_SERVICES, "serves a camera pack for each region") {
            val datapacks = document.datapacks?.takeIf { Feature.DATA_PACKS in document.features } ?: throw Skipped("no data packs listed")
            if (document.regions.isEmpty()) throw Skipped("the server lists no regions")
            for (code in document.regions) {
                val url = datapacks.trimEnd('/') + "/$code/cameras.json"
                val response = http.get(url)
                expect(response.status.isSuccess()) { "$url answered HTTP ${response.status.value}" }
                val pack = runCatching { CameraPack.parse(response.bodyAsText()) }.getOrElse { fail("$url isn't a valid camera pack: ${it.message}") }
                expect(pack.region == code) { "$url says it's for ${pack.region}" }
                expect(pack.cameras.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.bearings.all { b -> b in 0.0..360.0 } }) { "$url has cameras out of range" }
            }
        }
        check(Group.MAP_SERVICES, "serves valid speed limit packs") {
            val datapacks = document.datapacks?.takeIf { Feature.DATA_PACKS in document.features } ?: throw Skipped("no data packs listed")
            if (document.regions.isEmpty()) throw Skipped("the server lists no regions")
            var found = 0
            for (code in document.regions) {
                val url = datapacks.trimEnd('/') + "/$code/speedlimits.json"
                val response = http.get(url)
                if (response.status == HttpStatusCode.NotFound) continue
                expect(response.status.isSuccess()) { "$url answered HTTP ${response.status.value}" }
                val pack = runCatching { SpeedLimitPack.parse(response.bodyAsText()) }.getOrElse { fail("$url isn't a valid speed limit pack: ${it.message}") }
                expect(pack.region == code) { "$url says it's for ${pack.region}" }
                for (way in pack.ways) for (limit in way.conditional) {
                    // Clients ignore ranges they can't read, so a server shouldn't publish them.
                    expect(limit.maxspeed in 5..200) { "$url has way ${way.way} with a limit of ${limit.maxspeed}" }
                    expect(DAY.matches(limit.from) && DAY.matches(limit.to)) { "$url has way ${way.way} with dates ${limit.from}–${limit.to}" }
                }
                for (way in pack.ways) {
                    way.limit?.let { expect(it in 5..200) { "$url has way ${way.way} with a limit of $it" } }
                    for (section in way.sections) {
                        expect(section.maxspeed in 5..200) { "$url has a section of way ${way.way} with a limit of ${section.maxspeed}" }
                        expect(section.line.size >= 2 && section.line.all { it.size == 2 && it[0] in -180.0..180.0 && it[1] in -90.0..90.0 }) {
                            "$url has a section of way ${way.way} whose line isn't [lon, lat] pairs"
                        }
                    }
                }
                for ((limit, deltas) in pack.fill) {
                    expect(limit.toIntOrNull() in 5..200) { "$url fills ways with a limit of $limit" }
                    expect(deltas.drop(1).all { it > 0 }) { "$url has fill IDs for $limit that aren't sorted and distinct" }
                }
                found++
            }
            if (found == 0) throw Skipped("no region has one")
        }
        check(Group.MAP_SERVICES, "serves routing tiles for the phone") {
            val base = document.routing?.takeIf { it.type == "valhalla" }?.tiles ?: throw Skipped("no routing tiles listed")
            val url = base.trimEnd('/') + "/" + RoutingTileManifest.MANIFEST
            val response = http.get(url)
            if (response.status == HttpStatusCode.NotFound) throw Skipped("none published at $url yet")
            expect(response.status.isSuccess()) { "$url answered HTTP ${response.status.value}" }
            val manifest = runCatching { RoutingTileManifest.parse(response.bodyAsText()) }.getOrElse { fail("$url isn't a valid manifest: ${it.message}") }
            expect(manifest.format == RoutingTileManifest.FORMAT && manifest.engine == "valhalla") { "$url is format ${manifest.format} for ${manifest.engine}" }
            expect(BUILD.matches(manifest.build)) { "$url has build \"${manifest.build}\", which can't be a path segment" }
            expect(manifest.tiles.values.all { entries -> entries.all { it.size == 3 && it.all { n -> n >= 0 } } && entries.zipWithNext().all { (a, b) -> a[0] < b[0] } }) {
                "$url lists tiles that aren't [ID, bytes, download bytes] by ID"
            }
            // One local-road tile where the test trip starts: listed, downloadable, and the tile it says it is.
            val (from, _) = TEST_TRIPS[region]?.route ?: throw Skipped("no test trip for regions ${document.regions}")
            val local = manifest.levels.maxOf { it.level }
            val tile = manifest.tilesIn(from, from).firstOrNull { it.level == local } ?: fail("the tiles don't cover $from")
            val tileUrl = manifest.urlOf(base, tile)
            val download = http.get(tileUrl)
            expect(download.status.isSuccess()) { "$tileUrl answered HTTP ${download.status.value}" }
            val packed = download.bodyAsBytes()
            val bytes = runCatching { GZIPInputStream(packed.inputStream()).readBytes() }.getOrElse { fail("$tileUrl isn't gzipped") }
            val size = manifest.sizeOf(tile)!!
            expect(packed.size.toLong() == size.download && bytes.size.toLong() == size.bytes) {
                "$tileUrl is ${packed.size} bytes, ${bytes.size} unpacked; the manifest says ${size.download} and ${size.bytes}"
            }
            val graphId = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long and ((1L shl 46) - 1)
            expect((graphId and 7L) == tile.level.toLong() && ((graphId shr 3) and ((1L shl 22) - 1)) == tile.id) { "$tileUrl holds another tile" }
        }
        check(Group.MAP_SERVICES, "searches with Photon") {
            val search = document.search?.takeIf { it.type == "photon" } ?: throw Skipped("no Photon search listed")
            val place = TEST_TRIPS[region]?.place ?: throw Skipped("no test place for regions ${document.regions}")
            val response = http.get(search.url.trimEnd('/') + "/api?q=${place.encodeURLParameter()}&limit=1")
            expect(response.status.isSuccess()) { "the search answered HTTP ${response.status.value}" }
            val features = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["features"]!!.jsonArray }.getOrNull()
            expect(!features.isNullOrEmpty()) { "searching for $place found nothing" }
        }
    }

    private suspend fun realtimeChecks(document: DiscoveryDocument) {
        val realtime = document.realtime
        if (realtime == null || Feature.CONVOY !in document.features) {
            if (Group.REALTIME in groups) results += CheckResult(Group.REALTIME, "convoys", Outcome.SKIP, "the server lists no realtime endpoint or no convoy feature")
            return
        }

        realtime(realtime, "answers Hello with Welcome") {
            val client = connect()
            client.send(ClientMessage(hello = Hello(client = "foxnav-conformance")))
            val welcome = client.expect("Welcome") { it.welcome }
            expect(welcome.max_convoy_size > 0) { "Welcome says convoys hold ${welcome.max_convoy_size} members" }
        }
        realtime(realtime, "answers Ping with Pong") {
            val client = connect()
            client.send(ClientMessage(ping = Ping()))
            client.expect("Pong") { it.pong }
        }
        realtime(realtime, "answers an undecodable frame with BAD_REQUEST") {
            val client = connect()
            client.sendBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            client.expectError(ProtocolError.Code.CODE_BAD_REQUEST)
        }
        realtime(realtime, "creates a convoy with its creator as owner") { create() }
        realtime(realtime, "lets members join with the token and tells the others") {
            val convoy = create()
            val (_, seat) = join(convoy)
            expect(seat.member_id != convoy.ownerId) { "the new member got the owner's ID" }
            expect(seat.owner == convoy.ownerId) { "Joined names ${seat.owner} as owner, not the creator ${convoy.ownerId}" }
            expect(seat.join_token == convoy.token) { "Joined doesn't carry the current join token" }
            convoy.owner.expect("MemberJoined for the new member") { message -> message.member_joined?.takeIf { it.member_id == seat.member_id } }
        }
        realtime(realtime, "refuses wrong tokens and unknown convoys") {
            val convoy = create()
            val stranger = connect()
            stranger.send(ClientMessage(join_convoy = JoinConvoy(convoy_id = convoy.id, join_token = "guess".encodeUtf8())))
            stranger.expectError(ProtocolError.Code.CODE_FORBIDDEN)
            stranger.send(ClientMessage(join_convoy = JoinConvoy(convoy_id = secureRandomBytes(16).toByteString(), join_token = convoy.token)))
            stranger.expectError(ProtocolError.Code.CODE_NOT_FOUND)
        }
        realtime(realtime, "relays STATE unchanged, from the right sender, not back to it") {
            val convoy = create()
            val (member, seat) = join(convoy)
            val payload = opaque("state")
            member.publish(Channel.CHANNEL_STATE, payload)
            val relay = convoy.owner.expect("the relayed STATE") { message -> message.relay?.takeIf { it.channel == Channel.CHANNEL_STATE } }
            expect(relay.sender == seat.member_id) { "the relay names ${relay.sender} as sender, not ${seat.member_id}" }
            expect(relay.payload == payload) { "the payload changed on the way" }
            member.expectNone("its own STATE back") { it.relay }
        }
        realtime(realtime, "relays EVENTs") {
            val convoy = create()
            val (member, _) = join(convoy)
            val payload = opaque("event")
            member.publish(Channel.CHANNEL_EVENT, payload)
            val relay = convoy.owner.expect("the relayed EVENT") { message -> message.relay?.takeIf { it.channel == Channel.CHANNEL_EVENT } }
            expect(relay.payload == payload) { "the payload changed on the way" }
        }
        realtime(realtime, "gives later arrivals everyone's latest STATE") {
            val convoy = create()
            convoy.owner.publish(Channel.CHANNEL_STATE, opaque("latest"))
            convoy.owner.expectNone("an error") { it.error }
            val (_, seat) = join(convoy)
            val owner = seat.members.firstOrNull { it.member_id == convoy.ownerId } ?: fail("Joined doesn't list the owner")
            expect(owner.last_state == opaque("latest")) { "Joined doesn't carry the owner's latest STATE" }
        }
        realtime(realtime, "lets a dropped member resume their seat, without telling the others") {
            val convoy = create()
            val (member, seat) = join(convoy)
            member.close()
            val back = connect()
            back.send(ClientMessage(resume = Resume(resume_token = seat.resume_token)))
            val resumed = back.expect("Joined after Resume") { it.joined }
            expect(resumed.member_id == seat.member_id) { "Resume gave member ${resumed.member_id}, not ${seat.member_id}" }
            convoy.owner.expectNone("MemberLeft for a member who only dropped") { it.member_left }
        }
        realtime(realtime, "lets only the owner remove members, and changes the join token") {
            val convoy = create()
            val (member, _) = join(convoy)
            val (target, targetSeat) = join(convoy)
            member.send(ClientMessage(remove_member = RemoveMember(member_id = targetSeat.member_id)))
            member.expectError(ProtocolError.Code.CODE_FORBIDDEN)

            convoy.owner.send(ClientMessage(remove_member = RemoveMember(member_id = targetSeat.member_id)))
            target.expect("MemberLeft with removed set, for the removed member") { message -> message.member_left?.takeIf { it.member_id == targetSeat.member_id && it.removed } }
            member.expect("MemberLeft with removed set, for the others") { message -> message.member_left?.takeIf { it.member_id == targetSeat.member_id && it.removed } }
            val newToken = member.expect("InviteChanged") { it.invite_changed }.join_token
            expect(newToken != convoy.token) { "the join token didn't change" }

            val comeback = connect()
            comeback.send(ClientMessage(join_convoy = JoinConvoy(convoy_id = convoy.id, join_token = convoy.token)))
            comeback.expectError(ProtocolError.Code.CODE_FORBIDDEN)
            join(convoy.copy(token = newToken))
        }
        realtime(realtime, "takes KEY payloads only from the owner, and keeps them for later arrivals") {
            val convoy = create()
            val (member, _) = join(convoy)
            member.publish(Channel.CHANNEL_KEY, opaque("not the owner's"))
            member.expectError(ProtocolError.Code.CODE_FORBIDDEN)

            val rotation = opaque("rotation")
            convoy.owner.publish(Channel.CHANNEL_KEY, rotation)
            member.expect("the relayed KEY payload") { message -> message.relay?.takeIf { it.channel == Channel.CHANNEL_KEY } }
            val (_, late) = join(convoy)
            expect(late.key_history.any { it.payload == rotation && it.sender == convoy.ownerId }) { "Joined.key_history doesn't carry the rotation" }
        }
        realtime(realtime, "tells the others when someone leaves, and hands ownership on") {
            val convoy = create()
            val (member, seat) = join(convoy)
            convoy.owner.send(ClientMessage(leave_convoy = LeaveConvoy()))
            val left = member.expect("MemberLeft for the owner") { message -> message.member_left?.takeIf { it.member_id == convoy.ownerId } }
            expect(!left.removed) { "MemberLeft says the owner was removed" }
            expect(left.new_owner == seat.member_id) { "ownership went to ${left.new_owner}, not the only member left, ${seat.member_id}" }
        }
    }

    private suspend fun reportChecks(document: DiscoveryDocument) {
        val reports = document.reports?.takeIf { Feature.REPORTS in document.features }
        if (reports == null) {
            if (Group.REPORTS in groups) results += CheckResult(Group.REPORTS, "reports", Outcome.SKIP, "the server lists no reports")
            return
        }
        check(Group.REPORTS, "serves the report feed") {
            val response = http.get(reports)
            expect(response.status == HttpStatusCode.OK) { "GET $reports answered HTTP ${response.status.value}" }
            val feed = runCatching { ReportFeed.parse(response.bodyAsText()) }.getOrElse { fail("$reports isn't a valid report feed: ${it.message}") }
            expect(feed.reports.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }) { "the feed has reports out of range" }
            expect(feed.reports.map { it.id }.toSet().size == feed.reports.size) { "the feed has reports with the same ID" }
        }
        check(Group.REPORTS, "answers Not Modified while the feed is unchanged") {
            val etag = http.get(reports).headers[HttpHeaders.ETag] ?: fail("the feed has no ETag")
            val response = http.get(reports) { header(HttpHeaders.IfNoneMatch, etag) }
            expect(response.status == HttpStatusCode.NotModified) { "If-None-Match with the current ETag answered HTTP ${response.status.value}, not 304" }
        }
        check(Group.REPORTS, "refuses reports of unknown types") {
            if (!writes) throw Skipped("writes to the server; run with --write")
            val response = postJson(reports, """{"type":"conformance_test","lat":0.0,"lon":0.0}""")
            expect(response.status == HttpStatusCode.BadRequest) { "an unknown type answered HTTP ${response.status.value}, not 400" }
        }
        check(Group.REPORTS, "takes a report, lists it, and votes it away") {
            if (!writes) throw Skipped("writes to the server; run with --write")
            val created = postJson(reports, """{"type":"hazard","lat":0.0,"lon":0.0,"bearing":90}""")
            expect(created.status == HttpStatusCode.Created) { "the report answered HTTP ${created.status.value}, not 201" }
            val id = runCatching { FoxNavJson.decodeFromString(ReportCreated.serializer(), created.bodyAsText()).id }.getOrElse { fail("the answer to a report has no ID") }
            val listed = ReportFeed.parse(http.get(reports).bodyAsText()).reports.find { it.id == id } ?: fail("the new report isn't in the feed")
            expect(listed.type == "hazard" && listed.lat == 0.0 && listed.lon == 0.0 && listed.bearing == 90.0) { "the feed lists the report as $listed" }
            expect(listed.source == null) { "a driver's report has a source" }
            val votes = reports.trimEnd('/') + "/" + id.encodeURLPathPart() + "/votes"
            val yes = postJson(votes, """{"still_there":true}""")
            expect(yes.status == HttpStatusCode.NoContent) { "a vote answered HTTP ${yes.status.value}, not 204" }
            val confirmed = ReportFeed.parse(http.get(reports).bodyAsText()).reports.find { it.id == id }
            expect(confirmed?.confirmations == 1) { "after one yes, confirmations are ${confirmed?.confirmations}, not 1" }
            repeat(2) { postJson(votes, """{"still_there":false}""") }
            expect(ReportFeed.parse(http.get(reports).bodyAsText()).reports.none { it.id == id }) { "the report is still listed after more noes than yeses" }
            val gone = postJson(votes, """{"still_there":true}""")
            expect(gone.status == HttpStatusCode.NotFound) { "a vote on a removed report answered HTTP ${gone.status.value}, not 404" }
        }
    }

    private suspend fun postJson(url: String, json: String) = http.post(url) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    // --- Plumbing ---

    private class Skipped(message: String) : Exception(message)

    private suspend fun <T> check(group: Group, name: String, block: suspend () -> T): T? {
        if (group !in groups) return null
        return try {
            withTimeout(CHECK_TIMEOUT) { block() }.also { results += CheckResult(group, name, Outcome.PASS) }
        } catch (skipped: Skipped) {
            results += CheckResult(group, name, Outcome.SKIP, skipped.message.orEmpty())
            null
        } catch (failure: Throwable) {
            results += CheckResult(group, name, Outcome.FAIL, failure.message ?: failure::class.simpleName.orEmpty())
            null
        }
    }

    private fun skipRest(reason: String) {
        for (group in groups - Group.DISCOVERY) results += CheckResult(group, "all checks", Outcome.SKIP, reason)
    }

    /** A realtime check with its own connections, all closed afterwards. */
    private suspend fun realtime(url: String, name: String, block: suspend Connections.() -> Any?) {
        val connections = Connections(url)
        try {
            check(Group.REALTIME, name) { connections.block() }
        } finally {
            connections.closeAll()
        }
    }

    private data class Convoy(val id: ByteString, val token: ByteString, val owner: RawClient, val ownerId: Long)

    private inner class Connections(private val url: String) {
        private val open = ArrayList<RawClient>()

        suspend fun connect(): RawClient = RawClient.connect(http, url).also { open += it }

        suspend fun create(): Convoy {
            val owner = connect()
            owner.send(ClientMessage(create_convoy = CreateConvoy()))
            val created = owner.expect("ConvoyCreated") { it.convoy_created }
            expect(created.convoy_id.size == 16) { "the convoy ID has ${created.convoy_id.size} bytes, not 16" }
            expect(created.join_token.size > 0) { "the join token is empty" }
            val seat = owner.expect("Joined after ConvoyCreated") { it.joined }
            expect(seat.member_id != 0L) { "the creator got member ID 0" }
            expect(seat.owner == seat.member_id) { "the creator isn't the convoy's owner" }
            return Convoy(created.convoy_id, created.join_token, owner, seat.member_id)
        }

        suspend fun join(convoy: Convoy): Pair<RawClient, Joined> {
            val client = connect()
            client.send(ClientMessage(join_convoy = JoinConvoy(convoy_id = convoy.id, join_token = convoy.token)))
            return client to client.expect("Joined") { it.joined }
        }

        suspend fun closeAll() {
            for (client in open) runCatching { client.close() }
        }
    }

    private suspend fun RawClient.publish(channel: Channel, payload: Sealed) =
        send(ClientMessage(publish = Publish(channel = channel, payload = payload)))

    private suspend fun RawClient.expectError(code: ProtocolError.Code) {
        val error = expect("Error ${code.name.removePrefix("CODE_")}") { it.error }
        expect(error.code == code) { "got Error ${error.code.name.removePrefix("CODE_")}, not ${code.name.removePrefix("CODE_")}" }
    }

    /** A payload as the server sees them: bytes it can't read. */
    private fun opaque(text: String) = Sealed(key_epoch = 0, nonce = OPAQUE_NONCE, ciphertext = text.encodeUtf8())

    private class TestTrip(val route: Pair<GeoPoint, GeoPoint>, val place: String)

    private companion object {
        val CHECK_TIMEOUT = 20.seconds
        // A build names a directory of tiles (spec §3.3).
        val BUILD = Regex("^[A-Za-z0-9._-]+$")
        val DAY = Regex("""^(\d{4}-)?(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""")
        val OPAQUE_NONCE: ByteString = ByteString.of(*ByteArray(12) { it.toByte() })

        /** A short drive and a place to search for, in each region a server may list. */
        val TEST_TRIPS = mapOf(
            "FI" to TestTrip(GeoPoint(60.1699, 24.9384) to GeoPoint(61.4978, 23.7610), "Tampere"),
            "SE" to TestTrip(GeoPoint(59.3293, 18.0686) to GeoPoint(59.8586, 17.6389), "Uppsala"),
            "NO" to TestTrip(GeoPoint(59.9139, 10.7522) to GeoPoint(59.7440, 10.2045), "Drammen"),
            "DK" to TestTrip(GeoPoint(55.6761, 12.5683) to GeoPoint(55.6415, 12.0803), "Roskilde"),
        )
    }
}
