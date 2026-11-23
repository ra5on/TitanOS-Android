package io.github.ra5on.titanos.ui

/*
 * Ganze Wörter (Inhaber 21.11.2026), keine Silbentrennung mitten im Wort
 * (Projektregel; automatische Silbentrennung ist verboten, die Prüfung `noTruncatedText`
 * und die Bildtests melden es). Lange Wörter dürfen nur dort umbrechen, wo
 * schon ein Trennzeichen steht: am Bindestrich und in Kennungen.
 */

/**
 * Bindestrich-Wörter im Fließtext („Low-Power-Encodierung“) dürfen am Bindestrich
 * umbrechen: Android bricht sie sonst mitten im Wort. Setzt nach „-“ zwischen zwei
 * Buchstaben einen unsichtbaren Trennpunkt.
 */
fun breakAtHyphens(text: String): String = text.replace(HYPHEN, "-​")

/**
 * Kennungen im Text (Dateinamen wie claude_desktop_config.json, Fingerabdrücke ab:12:cd,
 * Adressen wie nas.tail1234.ts.net:443 oder anna@example.org, OLLAMA_CONTEXT_LENGTH)
 * dürfen nach „_“, „:“, „.“, „/“ und „@“ umbrechen, wenn ein Buchstabe oder eine Ziffer
 * folgt: sonst bricht Android sie mitten im Wort. Ein unsichtbarer Trennpunkt, kein Zeichen mehr.
 */
fun breakIds(text: String): String = text.replace(ID_MARK, "$1​")

/** Beides: Fließtext mit Bindestrich-Wörtern und Kennungen (Betreff, Absender, Antworten des Agenten). */
fun breakWords(text: String): String = breakIds(breakAtHyphens(text))

private val HYPHEN = Regex("(?<=\\p{L})-(?=\\p{L})")
private val ID_MARK = Regex("([_:./@])(?=[\\p{L}\\p{N}])")
