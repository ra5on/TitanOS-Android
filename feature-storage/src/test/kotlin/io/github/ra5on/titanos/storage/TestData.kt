package io.github.ra5on.titanos.storage

import io.github.ra5on.titanos.admin.testing.FakeNas

/** Nachgebaute Antworten des NAS für den Speicher (nur Testdaten, keine echten Platten). */
internal fun res(name: String): String =
    requireNotNull(FakeNas::class.java.getResourceAsStream("/storage/$name")) { name }.bufferedReader().readText()

internal fun FakeNas.storageFixtures(): FakeNas {
    on("GET", "storage", res("storage.json"))
    on("GET", "maintenance", res("maintenance.json"))
    on("GET", "preferences", res("preferences.json"))
    on("GET", "shares", res("shares.json"))
    on("GET", "vms", res("vms.json"))
    on("GET", "docker", res("docker.json"))
    on("GET", "hardware/disks", res("hardware-disks.json"))
    return this
}
