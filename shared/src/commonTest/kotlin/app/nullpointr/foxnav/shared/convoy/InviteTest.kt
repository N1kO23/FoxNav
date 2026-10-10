package app.nullpointr.foxnav.shared.convoy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {
    private val invite = Invite("http://192.168.3.202:8080", secureRandomBytes(16), secureRandomBytes(16), ConvoyCrypto.newKey())

    @Test
    fun roundTripsThroughALink() {
        val link = invite.toLink()
        assertTrue(link.startsWith("foxnav://join#v=0&s=http%3A%2F%2F192.168.3.202%3A8080&c="), link)
        assertEquals(invite, Invite.parse(link))
    }

    @Test
    fun theKeyStaysInTheFragment() {
        val link = invite.toLink("https://foxnav.example/join")
        assertTrue(link.substringBefore('#') == "https://foxnav.example/join", link)
        assertEquals(invite, Invite.parse(link))
    }

    @Test
    fun carriesTheKeyEpochAfterARotation() {
        val rotated = Invite(invite.server, invite.convoyId, invite.joinToken, ConvoyCrypto.newKey(), keyEpoch = 3)
        assertTrue(rotated.toLink().endsWith("&e=3"))
        assertEquals(rotated, Invite.parse(rotated.toLink()))
        assertEquals(0, Invite.parse(invite.toLink())!!.keyEpoch)
        assertNull(Invite.parse(rotated.toLink().replace("&e=3", "&e=-1")))
    }

    @Test
    fun rejectsBrokenLinks() {
        assertNull(Invite.parse("foxnav://join"))
        assertNull(Invite.parse("https://example.org/#nothing=here"))
        assertNull(Invite.parse(invite.toLink().replace("v=0", "v=9")))
        assertNull(Invite.parse(invite.toLink().substringBefore("&k=")))
        assertNull(Invite.parse(invite.toLink().replace(Regex("&c=[^&]+"), "&c=AAAA")))
    }

    @Test
    fun neverPrintsTheKey() {
        assertEquals("Invite(server=http://192.168.3.202:8080)", invite.toString())
    }
}
