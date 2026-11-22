package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Kamerarechte eines Kontos (GET/PUT /users/{name}/cameras, UserCameras im Web). */
@Serializable
data class UserCamera(val id: String = "", val name: String = "", val live: Boolean = false, val recordings: Boolean = false, val names: Boolean = false, val deleted: Boolean = false)

@Serializable
data class UserCameras(val admin: Boolean = false, val cameras: List<UserCamera>? = null)

@Serializable
data class NasAccount(val name: String = "", val role: String = "", val disabled: Boolean = false)

suspend fun NvrApi.accounts(): List<NasAccount> = access.get("/users")
suspend fun NvrApi.userCameras(name: String): UserCameras = access.get("/users/${seg(name)}/cameras")
suspend fun NvrApi.saveUserCamera(name: String, c: UserCamera): UserCameras = access.call(
    "PUT", "/users/${seg(name)}/cameras",
    buildJsonObject { put("cameras", buildJsonObject { put(c.id, buildJsonObject { put("live", c.live); put("recordings", c.recordings); put("names", c.names) }) }) } as JsonObject,
)

/**
 * Welche Kameras ein Konto live sehen darf und wessen Aufnahmen und
 * Ereignisse (mit Namen und Kennzeichen), wie die Gruppe „Kameras“ auf der
 * Kontoseite des Webs (routes/users/AccountDetail.svelte). Hier als eigene
 * Seite des NVR; die Systemsteuerung kann sie mit [account] direkt öffnen.
 * Jede Änderung wird sofort gespeichert; das NAS prüft die Rechte selbst.
 */
@Composable
fun CameraRightsPage(api: NvrApi, account: String? = null) {
    val ui = LocalComputeUi.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var accounts by remember { mutableStateOf<List<NasAccount>?>(null) }
    var picked by rememberSaveable { mutableStateOf(account.orEmpty()) }
    var cams by remember { mutableStateOf<UserCameras?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (account == null) accounts = runCatching { api.accounts() }.onFailure { error = context.errorText(it) }.getOrNull()
    }
    LaunchedEffect(picked) {
        cams = null
        missing = false
        if (picked.isEmpty()) return@LaunchedEffect
        try {
            val res = api.userCameras(picked)
            missing = res.cameras == null
            cams = if (missing) null else res
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            missing = true
        }
    }
    ScrollPage {
        Text(androidx.compose.ui.res.stringResource(R.string.nvr_rights_section), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(vertical = 6.dp))
        val list = accounts
        if (account == null) {
            if (list == null && error == null) Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted)
            if (list != null) Choice(t("desktop.side.accounts"), listOf("" to "–") + list.filter { !it.disabled }.map { it.name to it.name }, picked, { picked = it })
        }
        error?.let { Problem(it) }
        if (picked.isEmpty()) return@ScrollPage
        if (missing) { Text(t("error.nvr_unavailable"), style = TitanType.rowSub, color = Titan.colors.muted); return@ScrollPage }
        val c = cams ?: run { Text(t("common.loading"), style = TitanType.rowSub, color = Titan.colors.muted); return@ScrollPage }
        val rows = c.cameras.orEmpty().filter { !it.deleted }
        val summary = when {
            c.admin -> t("nvr.rights.adminAll")
            rows.isEmpty() -> t("nvr.rights.noCameras")
            else -> t("nvr.rights.count", "live" to rows.count { it.live }, "recordings" to rows.count { it.recordings }, "total" to rows.size)
        }
        Text(summary, style = TitanType.body, color = Titan.colors.text)
        when {
            c.admin -> Text(t("nvr.rights.adminHint"), style = TitanType.rowSub, color = Titan.colors.muted)
            rows.isEmpty() -> {}
            else -> {
                Text(t("nvr.rights.hint"), style = TitanType.rowSub, color = Titan.colors.muted)
                Text(t("nvr.rights.namesHint"), style = TitanType.rowSub, color = Titan.colors.muted)
                fun save(next: UserCamera) {
                    val name = picked
                    busy = true
                    error = null
                    ui.run(onError = { e -> if (name == picked) error = context.errorText(e); busy = false }) {
                        val res = api.saveUserCamera(name, next)
                        if (name == picked && res.cameras != null) cams = res
                        busy = false
                    }
                }
                for (cam in rows) {
                    Text(cam.name, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(top = 10.dp))
                    Card {
                        SwitchRow(t("nvr.rights.live"), cam.live, { save(cam.copy(live = it)) }, enabled = !busy)
                        SwitchRow(t("nvr.rights.recordings"), cam.recordings, { save(cam.copy(recordings = it)) }, enabled = !busy, divider = true)
                        SwitchRow(t("nvr.rights.names"), cam.recordings && cam.names, { save(cam.copy(names = it)) }, enabled = !busy && cam.recordings, divider = true)
                    }
                }
            }
        }
    }
}
