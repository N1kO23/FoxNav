package app.nullpointr.foxnav.shared.road

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceAttributesTest {
    @Test
    fun requestListsThePointsAndAttributes() {
        val body = TraceAttributes.request(listOf(GeoPoint(60.1, 24.9), GeoPoint(60.2, 24.8)))
        assertTrue("\"shape\":[{\"lat\":60.1,\"lon\":24.9},{\"lat\":60.2,\"lon\":24.8}]" in body, body)
        assertTrue("\"edge.speed_limit\"" in body && "\"matched.edge_index\"" in body, body)
    }

    @Test
    fun currentRoadIsTheEdgeUnderTheNewestPoint() {
        val json = """
            {"edges":[{"speed_limit":30,"names":["Mannerheimintie","E 12"]},{"speed_limit":40,"names":["Korutie"]}],
             "matched_points":[{"edge_index":0,"type":"matched"},{"edge_index":1,"type":"matched"}]}
        """.trimIndent()
        assertEquals(RoadInfo("Korutie", 40), TraceAttributes.parseCurrentRoad(json))
    }

    @Test
    fun theCountryComesFromTheAdminAreaAtTheEdgesEnd() {
        val json = """
            {"admins":[{"country_code":"FI"},{"country_code":"SE"}],
             "edges":[{"names":["E 8"],"end_node":{"admin_index":1}}],
             "matched_points":[{"edge_index":0}]}
        """.trimIndent()
        assertEquals("SE", TraceAttributes.parseCurrentRoad(json)?.countryCode)
    }

    @Test
    fun unknownLimitsAndNamesStayNull() {
        val json = """{"edges":[{"road_class":"service_other"}],"matched_points":[{"edge_index":0}]}"""
        assertEquals(RoadInfo(null, null), TraceAttributes.parseCurrentRoad(json))
    }

    @Test
    fun unmatchedPointsGiveNoRoad() {
        assertNull(TraceAttributes.parseCurrentRoad("""{"edges":[],"matched_points":[{"type":"unmatched"}]}"""))
        assertNull(TraceAttributes.parseCurrentRoad("{}"))
    }
}
