package io.github.ra5on.titanos.update

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Die Play-Fassung sucht nicht selbst nach Updates und zeigt keinen Hinweis (1.02). */
@RunWith(RobolectricTestRunner::class)
class PlayHasNoUpdateHintTest {
    @Test
    fun nothingInPlay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        UpdateHint.onForeground(context, kotlinx.coroutines.GlobalScope)
        assertEquals(null, UpdateHint.version)
        assertFalse(UpdateHint.banner(context))
    }
}
