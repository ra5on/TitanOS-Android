package io.github.ra5on.titanos.admin

import androidx.test.core.app.ApplicationProvider

/** Text des Webs zu einem Schlüssel in der Sprache des Tests. */
internal fun adminTextForTest(key: String): String = ApplicationProvider.getApplicationContext<android.content.Context>().adminText(key)
