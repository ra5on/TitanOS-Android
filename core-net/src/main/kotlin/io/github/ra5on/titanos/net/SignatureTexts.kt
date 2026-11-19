package io.github.ra5on.titanos.net

/** Die Texte, die der Geräteschlüssel unterschreibt (docs/ANDROID-APP.md, Ablauf 4 und 6). */
object SignatureTexts {
    /** Über den Code, den die App bei claim geschickt hat, genau so wie geschickt. */
    fun pair(code: String): ByteArray = "titanos-pair\n$code".toByteArray(Charsets.UTF_8)

    fun session(user: String, device: String, nonce: String): ByteArray =
        "titanos-app-session\n$user\n$device\n$nonce".toByteArray(Charsets.UTF_8)
}
