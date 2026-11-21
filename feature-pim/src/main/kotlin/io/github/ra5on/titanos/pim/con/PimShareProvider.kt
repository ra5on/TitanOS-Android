package io.github.ra5on.titanos.pim.con

import androidx.core.content.FileProvider

/** Eigene Klasse, damit sich der Eintrag nicht mit dem FileProvider anderer Module vermischt. */
class PimShareProvider : FileProvider()
