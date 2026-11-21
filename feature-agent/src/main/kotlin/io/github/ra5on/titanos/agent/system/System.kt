package io.github.ra5on.titanos.agent.system

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Notification
import android.app.Person
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.Attachment
import io.github.ra5on.titanos.agent.core.Tr
import java.io.File

/**
 * Was die Teile des Systems (Assistent, Notizen, Mitteilungen) von der App
 * brauchen: das Modell des gekoppelten NAS. Die Application der Agent-App
 * (und später die TitanOS-App) liefert es; ohne Kopplung null.
 */
interface AgentHost {
    fun agentModel(): AgentModel?
    /** Öffnet die App bei einem Chat (null: neuer Chat). */
    fun openAgent(context: Context, chatId: String?)
    /** Adresse und Sitzung für Seiten der Weboberfläche in der App (Office ohne TitanOS-App). */
    fun webSession(): io.github.ra5on.titanos.agent.ui.WebSession? = null
}

fun Context.agentHost(): AgentHost? = applicationContext as? AgentHost

/** Was der Assistent vom Bildschirm sehen darf: nie, jedes Mal fragen (ab Werk), immer. Nur an das eigene NAS. */
object AssistScreen {
    const val NEVER = "never"
    const val ASK = "ask"
    const val ALWAYS = "always"
    private const val KEY = "assist.screen"
    fun get(context: Context): String = context.getSharedPreferences("agent-device", Context.MODE_PRIVATE).getString(KEY, ASK) ?: ASK
    fun set(context: Context, v: String) { context.getSharedPreferences("agent-device", Context.MODE_PRIVATE).edit().putString(KEY, v).apply() }
}

/** Was der Assistent zuletzt vom Bildschirm bekam (nur im Speicher, nur für das nächste Blatt). */
object AssistContent {
    @Volatile var text: String = ""
    @Volatile var screenshot: Bitmap? = null
    @Volatile var app: String = ""
    fun clear() { text = ""; screenshot = null; app = "" }

    /** Der sichtbare Text einer Seite, aus der AssistStructure (Texte und Beschreibungen der Ansichten). */
    fun textOf(structure: AssistStructure): String {
        val out = StringBuilder()
        fun walk(n: AssistStructure.ViewNode) {
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && out.length < 8000) out.append(t).append('\n')
            else n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && out.length < 8000 }?.let { out.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
        return out.toString().trim()
    }
}

/** Der Assistenten-Dienst (Rolle „Digitaler Assistent“). */
class AgentAssistService : VoiceInteractionService()

class AgentAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentAssistSession(this)
}

/**
 * Eine Sitzung des Assistenten: nimmt (wenn erlaubt) Text und Bild des
 * Bildschirms entgegen und öffnet das Assistenten-Blatt mit Spracheingabe.
 */
class AgentAssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistContent.clear()
        launched = false
        // Ohne Bildschirminhalt sofort; sonst warten, bis Text und Bild da sind (höchstens 0,8 s).
        if (AssistScreen.get(context) == AssistScreen.NEVER) launch()
        else android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ launch() }, 800)
    }

    override fun onHandleAssist(state: AssistState) {
        if (AssistScreen.get(context) != AssistScreen.NEVER) {
            state.assistStructure?.let { s ->
                AssistContent.text = AssistContent.textOf(s)
                AssistContent.app = s.activityComponent?.packageName.orEmpty()
            }
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (AssistScreen.get(context) != AssistScreen.NEVER) AssistContent.screenshot = screenshot
        launch()
    }

    private var launched = false
    private fun launch() {
        if (launched) return
        launched = true
        val intent = Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startAssistantActivity(intent) }.onFailure { runCatching { context.startActivity(intent) } }
        finish()
    }
}

/** Eine Spracherkennung muss ein Assistent nennen; diese verweist auf die Erkennung des Handys bzw. des NAS im Blatt. */
class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Das Assistenten-Blatt (über der laufenden App, mit sofortiger Spracheingabe). */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = agentHost()?.agentModel()
        setContent { AssistSheet(this, model, onClose = { finish() }, onOpen = { id -> agentHost()?.openAgent(this, id); finish() }) }
    }
}

/** Die Notizen als Standard-Notizen-App (CREATE_NOTE, auch am Sperrbildschirm). */
class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = agentHost()?.agentModel()
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        setContent { NotesRole(this, model, locked, onClose = { finish() }) }
    }
}

/** Der Bildschirm als Anhang (nur wenn erlaubt), als PNG im Cache. */
fun screenshotAttachment(context: Context, bitmap: Bitmap): Attachment? = runCatching {
    val f = File(context.cacheDir, "bildschirm-${System.currentTimeMillis()}.png")
    f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    Attachment(f.name, f, "image/png")
}.getOrNull()

// ---------- Mitteilungen mit MessagingStyle (Android Auto) ----------

object AnswerNotifier {
    private const val CHANNEL = "agent-answers"
    const val EXTRA_CHAT = "chat"
    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "io.github.ra5on.titanos.agent.REPLY"
    const val ACTION_READ = "io.github.ra5on.titanos.agent.READ"
    private const val PREF = "notify.answers"

    fun enabled(context: Context) = context.getSharedPreferences("agent-device", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences("agent-device", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply() }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context, tr: Tr) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr.t("nav.agent"), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /**
     * Eine Antwort als Nachricht: MessagingStyle mit Frage und Antwort, „Antworten“
     * (RemoteInput, ohne Oberfläche – geht im Auto per Sprache) und „Gelesen“.
     */
    fun post(context: Context, tr: Tr, chatId: String, title: String, question: String?, answer: String, open: PendingIntent?) {
        if (!enabled(context) || !allowed(context)) return
        channel(context, tr)
        val me = Person.Builder().setName(tr.t("agent.you").let { if (it == "agent.you") (if (tr.de) "Ich" else "Me") else it }).build()
        val agent = Person.Builder().setName(tr.t("nav.agent")).setBot(true).setKey("agent").build()
        val style = Notification.MessagingStyle(me).setConversationTitle(title.ifEmpty { tr.t("nav.agent") }).setGroupConversation(false)
        val now = System.currentTimeMillis()
        if (!question.isNullOrBlank()) style.addMessage(Notification.MessagingStyle.Message(question, now - 1000, me))
        style.addMessage(Notification.MessagingStyle.Message(io.github.ra5on.titanos.agent.voice.speakable(answer).take(1500), now, agent))
        val code = chatId.hashCode()
        val reply = PendingIntent.getBroadcast(
            context, code, Intent(context, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_CHAT, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val read = PendingIntent.getBroadcast(
            context, code + 1, Intent(context, ReplyReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_CHAT, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(tr.t("agent.inputShort")).build()
        val replyAction = Notification.Action.Builder(null, if (tr.de) "Antworten" else "Reply", reply)
            .addRemoteInput(input).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(true).build()
        val readAction = Notification.Action.Builder(null, if (tr.de) "Gelesen" else "Mark as read", read)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShortcutId(null)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(replyAction)
            .addAction(readAction)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(code, n)
    }

    fun cancel(context: Context, chatId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(chatId.hashCode())
    }
}

/** „Antworten“ und „Gelesen“ aus der Mitteilung (auch aus Android Auto). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra(AnswerNotifier.EXTRA_CHAT) ?: return
        val model = context.agentHost()?.agentModel() ?: return
        when (intent.action) {
            AnswerNotifier.ACTION_READ -> { model.seen(chat, true); AnswerNotifier.cancel(context, chat) }
            AnswerNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AnswerNotifier.KEY_REPLY)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return
                AnswerNotifier.cancel(context, chat)
                model.replyTo(chat, text)
            }
        }
    }
}
