package io.github.ra5on.titanos.pim.con

import io.github.ra5on.titanos.pim.ConFixtures
import io.github.ra5on.titanos.pim.FakeNas
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsApiTest {
    @Test
    fun listPrefsOverviewDuplicates() = runBlocking {
        FakeNas(ConFixtures.routes).use { nas ->
            val api = ContactsApi(nas.connection())
            assertEquals(18, api.list().size)
            assertEquals("c11.vcf", api.prefs().me)
            assertEquals(2, api.overview().groups.size)
            assertEquals(listOf("c16.vcf", "c2.vcf"), api.duplicates().single().ids)
            assertTrue(vcardOf(api.contact("c16.vcf")).contains("TEL;TYPE=CELL:+49 170 1234567"))
        }
    }
}
