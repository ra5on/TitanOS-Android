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
    on("GET", "storage/smart", res("smart.json"))
    for (d in listOf("sda", "sdb", "sdi", "nvme0n1", "sdm")) on("GET", "storage/smart/$d", res("smart-$d.json"))
    on("GET", "storage/smart/sdi/history", res("smart-history.json"))
    on("GET", "storage/smart/sda/history", res("smart-history.json"))
    on("GET", "storage/import", res("import.json"))
    on("POST", "storage/import/preview", res("import-preview.json"))
    on("GET", "storage/volumes/Data/usage", res("usage.json"))
    on("GET", "storage/volumes/Fast/snapshots", res("snapshots.json"))
    on("GET", "storage/volumes/Fast/snapshots/nightly-2026-11-19/files*", res("snapshot-files.json"))
    on("POST", "storage/volumes/Fast/snapshots/nightly-2026-11-19/folders/preview", res("folder-preview.json"))
    on("GET", "storage/snapshot-restores", res("restores.json"))
    on("GET", "system/memory", res("memory.json"))
    return this
}

/**
 * Die abgedunkelte Fläche hinter AdminDialog/AdminSheet (core-admin, Dialogs.kt) ist
 * anklickbar (schließt) und hat keinen Namen. Das ist ein Fund in core-admin, nicht in
 * diesem Modul (gemeldet). Nur genau diese Fläche wird übergangen: ein Bedienelement
 * ohne Namen, das das ganze Fenster ([width] × [height] Pixel) bedeckt.
 */
internal fun isCoreBackdrop(finding: String, width: Float, height: Float): Boolean {
    val m = Regex("""Bedienelement ohne Namen bei Rect\.fromLTRB\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)""").find(finding) ?: return false
    val (l, t, r, b) = m.destructured
    return l.toFloat() == 0f && t.toFloat() == 0f && kotlin.math.abs(r.toFloat() - width) <= 2f && kotlin.math.abs(b.toFloat() - height) <= 2f
}
