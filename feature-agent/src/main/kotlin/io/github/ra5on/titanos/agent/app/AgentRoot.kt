package io.github.ra5on.titanos.agent.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.AskRequest
import io.github.ra5on.titanos.agent.chat.ChatState
import io.github.ra5on.titanos.agent.chat.DevicePrefs
import io.github.ra5on.titanos.agent.core.Look
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.agent.voice.Speech
import java.util.Locale

/** Was das Gerät sich merkt (Arbeitsweise), in den SharedPreferences der App. */
class SharedDevicePrefs(context: Context) : DevicePrefs {
    private val p = context.getSharedPreferences("agent-device", Context.MODE_PRIVATE)
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/**
 * Die Agent-App nach der Kopplung. [ask] ist ein Wunsch von außen (Teilen,
 * Widget); er füllt nur das Eingabefeld eines neuen Chats, gesendet wird erst
 * nach Tippen.
 */
@Composable
fun AgentRoot(
    model: AgentModel,
    ask: AskRequest?,
    onAskTaken: () -> Unit,
    open: String? = null,
    onOpenTaken: () -> Unit = {},
    versionName: String,
    hostname: String,
    user: String,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val tr = remember { Tr.load(context) }
    val scope = rememberCoroutineScope()
    val nav = remember { AgentNav() }
    val speech = remember { Speech(context.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { speech.release() } }
    LaunchedEffect(Unit) {
        model.look = Look.load(context)
        model.onLook = { Look.save(context, it) }
        io.github.ra5on.titanos.agent.core.Appearance.load(context)
        speech.tr = tr
        speech.model = model
    }
    speech.rate = model.look.rate

    // Mikrofon: Berechtigung fragen; Erkennung von Android, wenn das NAS nicht zuhört.
    val afterMic = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> afterMic[0]?.invoke(ok) }
    speech.askMic = { then ->
        if (speech.hasMic()) then(true) else { afterMic[0] = then; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val hear = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        speech.heardFromDevice(model, r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    val prompt = tr.t("agent.voiceStart")
    speech.launchRecognizer = {
        runCatching {
            hear.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (tr.de) "de-DE" else Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt),
            )
        }
    }

    // Bildschirm an, solange der Agent arbeitet (Gerät: „Bildschirm an lassen“).
    val working = model.look.keepAwake && (model.busy || model.chats.any { it.state == ChatState.RUNNING })
    DisposableEffect(working) {
        val window = (context as? android.app.Activity)?.window
        if (working) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Ein Wunsch von außen: neuer Chat, Text ins Feld, Bild als Anhang; nie von selbst senden.
    LaunchedEffect(ask) {
        val a = ask ?: return@LaunchedEffect
        nav.show(AgentView.Chat)
        nav.layer = null
        model.newChat(null, asFresh = true)
        if (a.text.isNotEmpty()) model.draft = a.text
        a.attachments.forEach(model::addAttachment)
        if (a.failed) model.say(tr.t("agent.attachFailed"))
        onAskTaken()
        if (a.voice) speech.micDown(model)
    }

    // Aus dem Assistenten oder einer Mitteilung: diesen Chat zeigen.
    LaunchedEffect(open) {
        val id = open ?: return@LaunchedEffect
        nav.layer = null
        nav.show(AgentView.Chat)
        if (id.isEmpty()) model.newChat(null, asFresh = true) else model.open(id)
        onOpenTaken()
    }

    // Eine Antwort, während der Chat nicht zu sehen ist: als Mitteilung (MessagingStyle, auch Android Auto).
    // Angemeldet nur, solange diese Oberfläche da ist (1.01: vorher blieb die Anmeldung einer beendeten
    // Activity hängen und meldete auch Antworten im offenen Chat). „Zu sehen“ ist dieselbe Regel wie die
    // Meldung ans NAS (AgentVisible, 1.03); meldet das NAS dieselbe Antwort, bleibt es bei einer (AgentNotices).
    DisposableEffect(model) {
        val off = model.onAnswer { e ->
            val chat = model.current
            if (chat != null && !AgentVisible.shown(chat.id)) {
                val q = chat.entries.lastOrNull { it.role == "user" }?.content
                val intent = android.app.PendingIntent.getActivity(
                    context, chat.id.hashCode(),
                    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: android.content.Intent()).putExtra("chat", chat.id),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                io.github.ra5on.titanos.agent.system.AnswerNotifier.post(context, tr, chat.id, chat.title, q, e.content.orEmpty(), intent)
            }
        }
        onDispose { off() }
    }

    // Beim Öffnen: was die Einstellung „Beim Öffnen“ sagt (letzter Chat oder das Menü).
    val started = remember { booleanArrayOf(false) }
    LaunchedEffect(model.listsReady) {
        if (!model.listsReady || started[0]) return@LaunchedEffect
        started[0] = true
        if (ask != null || model.current != null) return@LaunchedEffect
        when (model.startView()) {
            "last" -> model.lastChat?.let { model.open(it) }
            "overview" -> nav.menuOpen = true
        }
    }

    // Thema und Akzent des Kontos (wie im Web), je Gerät überschreibbar.
    LaunchedEffect(model.listsReady) {
        if (model.listsReady) runCatching { io.github.ra5on.titanos.agent.core.Appearance.fromAccount(model.api.getJson("preferences") as kotlinx.serialization.json.JsonObject) }
    }
    io.github.ra5on.titanos.agent.core.AgentTheme(model.look) {
        AgentShell(model, nav, AppHooks(versionName, hostname, onUnpair, user), speech, tr)
    }
}
