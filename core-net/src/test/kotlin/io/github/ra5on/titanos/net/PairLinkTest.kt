package io.github.ra5on.titanos.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    private val spki = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg"
    private val spki2 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    /** So baut das NAS den Link: url.Values.Encode, Schlüssel sortiert, „!“ und „:“ kodiert. */
    @Test
    fun parsesLinkAsTheNasEncodesIt() {
        val link = "titanos://pair?a=nas.tail1234.ts.net%21$spki&a=192.168.1.20%3A8443%21$spki2&c=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFi&v=1"
        val p = PairLink.parse(link)!!
        assertEquals("Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFi", p.code)
        assertEquals(2, p.addresses.size)
        assertEquals(ServerAddress("nas.tail1234.ts.net", 443), p.addresses[0].address)
        assertEquals(spki, p.addresses[0].spki)
        assertEquals(AddressKind.TAILSCALE, p.addresses[0].address.kind)
        assertEquals(ServerAddress("192.168.1.20", 8443), p.addresses[1].address)
        assertEquals(AddressKind.LAN, p.addresses[1].address.kind)
        assertEquals(spki2, p.addresses[1].spki)
    }

    @Test
    fun acceptsUnencodedBangAndIpv6() {
        val p = PairLink.parse("titanos://pair?v=1&c=abc&a=%5Bfd00%3A%3A2%5D%3A443!$spki")!!
        assertEquals(ServerAddress("fd00::2", 443), p.addresses.single().address)
        assertEquals("[fd00::2]", p.addresses.single().address.server)
    }

    @Test
    fun emptySpkiIsKeptForPublicNames() {
        val p = PairLink.parse("titanos://pair?v=1&c=abc&a=nas.tail1234.ts.net%21")!!
        assertEquals("", p.addresses.single().spki)
    }

    @Test
    fun rejectsWrongInput() {
        assertNull(PairLink.parse("https://pair?v=1&c=abc&a=nas!$spki"))
        assertNull(PairLink.parse("titanos://pair?v=2&c=abc&a=nas!$spki"))
        assertNull(PairLink.parse("titanos://pair?v=1&a=nas!$spki"))
        assertNull(PairLink.parse("titanos://pair?v=1&c=abc"))
        assertNull(PairLink.parse("titanos://pair?v=1&c=a%20b&a=nas!$spki"))
        // Kein Pfad, kein Benutzer in der Adresse; ein kaputter Fingerabdruck verwirft die Adresse.
        assertNull(PairLink.parse("titanos://pair?v=1&c=abc&a=evil.example%2Fx!$spki"))
        assertNull(PairLink.parse("titanos://pair?v=1&c=abc&a=u%40nas!$spki"))
        assertNull(PairLink.parse("titanos://pair?v=1&c=abc&a=nas!nicht-base64"))
        assertNull(PairLink.parse("titanos://pair?v=1&c=abc&a=nas%3A99999!$spki"))
    }

    @Test
    fun duplicateAddressesCountOnce() {
        val p = PairLink.parse("titanos://pair?v=1&c=abc&a=nas!$spki&a=nas:443!$spki")!!
        assertEquals(1, p.addresses.size)
    }
}
