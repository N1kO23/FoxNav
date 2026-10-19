package app.nullpointr.foxnav.shared.convoy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {
    private val invite = Invite("http://192.168.3.202:8080", secureRandomBytes(16), secureRandomBytes(16), ConvoyFrames.newKey(), Identity.generate().signingKey)

    @Test
    fun roundTripsThroughALink() {
        val link = invite.toLink()
        assertTrue(link.startsWith("foxnav://join#v=0&s=http%3A%2F%2F192.168.3.202%3A8080&c="), link)
        assertTrue("&o=" in link, link)
        assertEquals(invite, Invite.parse(link))
    }

    @Test
    fun theKeyStaysInTheFragment() {
        val link = invite.toLink("https://foxnav.example/join")
        assertTrue(link.substringBefore('#') == "https://foxnav.example/join", link)
        assertEquals(invite, Invite.parse(link))
    }

    @Test
    fun carriesTheKeyEpochAndHandoversOnceThereAreAny() {
        val later = Invite(invite.server, invite.convoyId, invite.joinToken, ConvoyFrames.newKey(), invite.ownerKey, keyEpoch = 3, handovers = 2)
        assertTrue(later.toLink().endsWith("&e=3&h=2"))
        assertEquals(later, Invite.parse(later.toLink()))
        val first = Invite.parse(invite.toLink())!!
        assertEquals(0, first.keyEpoch)
        assertEquals(0, first.handovers)
        assertNull(Invite.parse(later.toLink().replace("&e=3", "&e=-1")))
        assertNull(Invite.parse(later.toLink().replace("&h=2", "&h=x")))
    }

    @Test
    fun readsTheSameLinkAsTheWebPage() {
        // web/test/invite.test.js parses this one too.
        val link = "foxnav://join#v=0&s=http%3A%2F%2F192.168.3.202%3A8080&c=AQIDBAUGBwgJCgsMDQ4PEA&t=ZGVmZ2hpamtsbW5vcHFycw" +
            "&k=yMnKy8zNzs_Q0dLT1NXW19jZ2tvc3d7f4OHi4-Tl5uc&o=BwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSY&e=2&h=1"
        fun range(size: Int, start: Int) = ByteArray(size) { (start + it).toByte() }
        val expected = Invite("http://192.168.3.202:8080", range(16, 1), range(16, 100), range(32, 200), range(32, 7), keyEpoch = 2, handovers = 1)
        assertEquals(expected, Invite.parse(link))
        assertEquals(link, expected.toLink())
    }

    @Test
    fun rejectsBrokenLinks() {
        assertNull(Invite.parse("foxnav://join"))
        assertNull(Invite.parse("https://example.org/#nothing=here"))
        assertNull(Invite.parse(invite.toLink().replace("v=0", "v=9")))
        assertNull(Invite.parse(invite.toLink().substringBefore("&k=")))
        assertNull(Invite.parse(invite.toLink().substringBefore("&o=")), "no owner")
        assertNull(Invite.parse(invite.toLink().replace(Regex("&c=[^&]+"), "&c=AAAA")))
        assertNull(Invite.parse(invite.toLink().replace(Regex("&o=[^&]+"), "&o=AAAA")))
    }

    @Test
    fun neverPrintsTheKey() {
        assertEquals("Invite(server=http://192.168.3.202:8080)", invite.toString())
    }
}
