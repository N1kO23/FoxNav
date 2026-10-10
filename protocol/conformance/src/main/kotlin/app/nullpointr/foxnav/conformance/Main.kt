package app.nullpointr.foxnav.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** `foxnav-conformance <server>`: checks a FoxNav server against protocol/SPEC.md. */
fun main(args: Array<String>) {
    val server = args.singleOrNull()
    if (server == null || server.startsWith("-")) {
        System.err.println("Usage: foxnav-conformance <server address>, e.g. maps.example.org or http://localhost:8080")
        exitProcess(2)
    }
    val results = runBlocking {
        HttpClient(CIO) { install(WebSockets) }.use { http -> ConformanceSuite(http, server).run() }
    }
    for ((group, inGroup) in results.groupBy { it.group }) {
        println(group.title)
        for (result in inGroup) {
            val mark = when (result.outcome) {
                Outcome.PASS -> "✓"
                Outcome.FAIL -> "✗"
                Outcome.SKIP -> "–"
            }
            println("  $mark ${result.name}" + if (result.detail.isNotEmpty()) ": ${result.detail}" else "")
        }
    }
    val failed = results.count { it.outcome == Outcome.FAIL }
    val passed = results.count { it.outcome == Outcome.PASS }
    println(if (failed == 0) "\n$passed checks passed." else "\n$failed of ${passed + failed} checks failed.")
    exitProcess(if (failed == 0) 0 else 1)
}
