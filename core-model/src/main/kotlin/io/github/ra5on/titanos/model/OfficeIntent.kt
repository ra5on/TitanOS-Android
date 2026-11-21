package io.github.ra5on.titanos.model

/**
 * Schnittstelle zwischen den Apps (README › „Schnittstelle für andere Apps“):
 * Die Agent-App bittet die TitanOS-App, eine Datei des NAS im eingebetteten
 * Office-Editor zu öffnen. [EXTRA_PATH] ist der Pfad wie im Dateimanager,
 * „<root>/<pfad>“, z. B. „share/Documents/Brief.docx“. Mit [EXTRA_HREF]
 * statt einer Datei eine Seite der Weboberfläche (z. B. ein Entwurf des
 * Agenten: „/mail/compose?agentDraft=…“).
 */
object OfficeIntent {
    const val ACTION = "io.github.ra5on.titanos.OPEN_OFFICE"
    const val EXTRA_PATH = "path"
    const val EXTRA_HREF = "href"
    const val PACKAGE = "io.github.ra5on.titanos"
}
