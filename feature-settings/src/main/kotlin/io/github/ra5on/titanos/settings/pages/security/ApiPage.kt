package io.github.ra5on.titanos.settings.pages.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.Loading
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Die Namen der Berechtigungen wie `labels` in routes/settings/api. */
internal val SCOPE_LABELS = mapOf(
    "system:read" to "api.scope.systemRead",
    "system:write" to "api.scope.systemWrite",
    "files:read" to "api.scope.filesRead",
    "files:write" to "api.scope.filesWrite",
    "docker:read" to "api.scope.dockerRead",
    "docker:write" to "api.scope.dockerWrite",
    "vms:read" to "api.scope.vmsRead",
    "vms:write" to "api.scope.vmsWrite",
    "mcp" to "api.scope.mcp",
)

/** Die Berechtigungen, mit denen ein neues MCP-Token beginnt (lib/mcp.ts). */
internal fun mcpTokenScopes(admin: Boolean): List<String> = listOf("mcp", "system:read", "files:read") + if (admin) listOf("docker:read", "vms:read") else emptyList()

/**
 * API und CLI (routes/settings/api): die eigenen Tokens mit Berechtigungen,
 * anlegen (Passwort und Code wie im Web, das neue Token nur einmal mit
 * Kopieren), widerrufen; Verweise auf OpenAPI und den MCP-Server. Anlegen und
 * Widerrufen hinter dem Fingerabdruck „Kontosicherheit“. Aus dem MCP-Server
 * kommt die Seite mit dem Reiter `mcp` und öffnet das Anlegen mit MCP.
 */
@Composable
fun ApiPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLoad { env.api.obj("account/tokens") }
    val session = rememberLoad { env.api.obj("session") }
    val mcp = rememberLoad { runCatching { env.api.obj("system/mcp") }.getOrDefault(JsonObject(emptyMap())) }
    val run = rememberRunner()
    val origin = remember { env.api.origin() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run.run {
            val bytes = env.api.bytes("openapi.json")
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
    }
    val twoFactor = session.value.o("user").bool("twoFactor")
    fun startCreate(scopes: List<String>, label: String = "") {
        val all = state.value?.strings("scopes").orEmpty()
        showCreate(ov, env, tr, context, scope, all, scopes, label, twoFactor, endpoint = mcpEndpoint(origin, mcp.value.str("path"))) { state.reload() }
    }
    LaunchedEffect(state.value != null, a.tab) {
        if (state.value != null && a.tab == "mcp") {
            a.tab = null
            startCreate(mcpTokenScopes(env.admin), "Claude")
        }
    }

    PageIntro(t("api.intro"))
    Buttons { SmallButton(t("api.create"), { startCreate(listOf("system:read")) }, kind = ButtonKind.Primary, icon = WebIcons["plus"]) }
    Loading(state) { res ->
        val tokens = res.objs("tokens")
        if (tokens.isEmpty()) {
            EmptyState(t("api.empty"), "")
        } else {
            SettingGroup(t("api.mine")) {
                for (token in tokens) {
                    val scopes = token.strings("scopes").joinToString(" · ") { tr.t(SCOPE_LABELS[it] ?: it) }
                    val whenText = tr.t("api.created") + " " + Fmt.date(tr, Fmt.instant(token.str("created"))) +
                        if (token.str("lastUsed").isNotEmpty()) " · " + tr.t("api.lastUsed") + " " + Fmt.dateTime(tr, token.str("lastUsed")) else ""
                    SettingRow(token.str("name"), desc = "$scopes\n$whenText", icon = WebIcons["key"], control = {
                        SmallButton(t("api.revoke"), {
                            ov.confirm(tr.t("api.revoke"), tr.t("api.revokeConfirm", "name" to token.str("name")), action = tr.t("api.revoke"), cancel = tr.t("common.cancel")) {
                                run.run(guard = GuardAction.AccountSecurity) {
                                    env.api.delete("account/tokens/" + java.net.URLEncoder.encode(token.str("id"), "UTF-8").replace("+", "%20"))
                                    state.reload()
                                }
                            }
                        }, kind = ButtonKind.Danger, enabled = !run.running)
                    })
                }
            }
        }
    }
    RunnerState(run)
    SettingGroup(t("api.documentation")) {
        SettingRow(t("api.openapiTitle"), desc = t("api.cliHint"), icon = WebIcons["fileText"], control = {
            SmallButton(t("api.openapi"), { save.launch("titanos-openapi.json") }, icon = WebIcons["download"])
        })
        SettingRow(t("mcp.title"), desc = t("api.mcpMoved"), icon = WebIcons["sparkle"], control = {
            SmallButton(t("mcp.pageTitle"), { a.openPage("mcp") })
        })
        Muted(t("api.security"))
    }
}

/** Dialog „Token erstellen“: Bezeichnung, Berechtigungen, Passwort (und Code); danach das neue Token einmal. */
private fun showCreate(
    ov: Overlays,
    env: SettingsEnv,
    tr: Tr,
    context: android.content.Context,
    scope: CoroutineScope,
    all: List<String>,
    start: List<String>,
    label: String,
    twoFactor: Boolean,
    endpoint: String,
    reload: () -> Unit,
) {
    val name = mutableStateOf(label)
    val selected = mutableStateOf(start)
    val password = mutableStateOf("")
    val code = mutableStateOf("")
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val offered = all.filter { env.admin || it.startsWith("files:") || it == "system:read" || it == "mcp" }
    ov.show { close ->
        FormDialog(
            tr.t("api.create"),
            onDismiss = close,
            locked = busy.value,
            actions = listOf(
                FormAction(tr.t("api.create"), ButtonKind.Primary, enabled = !busy.value && name.value.isNotBlank() && selected.value.isNotEmpty() && reconfirmComplete(password.value, code.value, twoFactor)) {
                    Guard.require(context, GuardAction.AccountSecurity) {
                        busy.value = true
                        error.value = null
                        scope.launch {
                            try {
                                val res = env.api.post("account/tokens", jobj("name" to name.value, "scopes" to selected.value, "password" to password.value, "code" to code.value.trim())).o()
                                val chosen = selected.value
                                password.value = ""
                                code.value = ""
                                close()
                                reload()
                                showSecret(ov, tr, context, res.str("secret"), chosen, endpoint)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error.value = tr.errorOf(e)
                                if (e.apiCode == "invalid_code") code.value = ""
                            } finally {
                                busy.value = false
                            }
                        }
                    }
                },
                FormAction(tr.t("common.cancel"), enabled = !busy.value) { close() },
            ),
        ) {
            LabeledField(tr.t("api.name"), name.value, { if (it.length <= 80) name.value = it }, enabled = !busy.value)
            androidx.compose.material3.Text(tr.t("api.scopes"), style = io.github.ra5on.titanos.ui.theme.TitanType.rowSub, color = io.github.ra5on.titanos.ui.theme.Titan.colors.text)
            Column(Modifier.fillMaxWidth()) {
                for (s in offered) {
                    CheckRow(s in selected.value, { on -> selected.value = if (on) selected.value + s else selected.value - s }, tr.t(SCOPE_LABELS[s] ?: s), enabled = !busy.value)
                }
            }
            Muted(tr.t("api.scopeHint"))
            ReconfirmFields(password.value, { password.value = it }, code.value, { code.value = it }, twoFactor, enabled = !busy.value)
            error.value?.let { Banner(it, tone = Tone.Error) }
        }
    }
}

/** Das neue Token – nur jetzt, mit Kopieren; mit MCP auch der fertige Befehl für Claude Code. */
private fun showSecret(ov: Overlays, tr: Tr, context: android.content.Context, secret: String, scopes: List<String>, endpoint: String) {
    val copied: MutableState<String> = mutableStateOf("")
    ov.show { close ->
        FormDialog(
            tr.t("api.createdTitle"),
            onDismiss = close,
            text = tr.t("api.once"),
            actions = listOf(FormAction(tr.t("common.close"), ButtonKind.Primary) { close() }),
        ) {
            androidx.compose.material3.Text(tr.t("api.token"), style = io.github.ra5on.titanos.ui.theme.TitanType.rowSub, color = io.github.ra5on.titanos.ui.theme.Titan.colors.text)
            CodeBlock(secret)
            SmallButton(tr.t(if (copied.value == "token") "api.copied" else "api.copy"), { copyText(context, secret, sensitive = true); copied.value = "token" }, icon = WebIcons["copy"])
            if ("mcp" in scopes) {
                val cmd = claudeCodeCommand(endpoint, secret)
                androidx.compose.material3.Text(tr.t("mcp.withToken"), style = io.github.ra5on.titanos.ui.theme.TitanType.rowSub, color = io.github.ra5on.titanos.ui.theme.Titan.colors.text)
                CodeBlock(cmd)
                SmallButton(tr.t(if (copied.value == "cmd") "mcp.copied" else "mcp.copy"), { copyText(context, cmd, sensitive = true); copied.value = "cmd" }, icon = WebIcons["copy"])
            }
        }
    }
}
