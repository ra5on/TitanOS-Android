package io.github.ra5on.titanos.nvr

import android.content.Context
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SocketEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Gegensprechen mit einer Kamera wie web/src/lib/nvr/talk.ts: titand reicht
 * die WebRTC-Signale von go2rtc für den Strom „<id>_twoway“ durch
 * (docs/NVR.md „Gegensprechen“): {"type":"webrtc/offer","value":sdp} →
 * {"type":"webrtc/answer",…}, "webrtc/candidate" in beide Richtungen. Der
 * Ton selbst geht direkt zum NAS (Port 8555). Das Mikrofon bleibt stumm bis
 * [speak] (true) – Drücken zum Sprechen.
 */
class Talk private constructor(
    private val socket: NasSocket,
    private val pc: PeerConnection,
    private val track: AudioTrack,
    private val onClose: () -> Unit,
) {
    @Volatile
    private var closed = false

    /** Das Mikrofon geht nur raus, solange [on] true ist. */
    fun speak(on: Boolean) {
        if (!closed) track.setEnabled(on)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { track.setEnabled(false) }
        socket.close()
        runCatching { pc.close() }
        runCatching { pc.dispose() }
        onClose()
    }

    companion object {
        /** Grund, mit dem das NAS die Verbindung schloss → Problem in Worten (Schlüssel nvr.talk.<problem>). */
        fun closeProblem(reason: String): String = when (reason) {
            "nvr_camera_not_found" -> "camera_gone"
            "nvr_not_running" -> "not_running"
            "nvr_no_picture" -> "no_picture"
            "nvr_unavailable" -> "unavailable"
            "logout" -> "logout"
            "forbidden" -> "forbidden"
            else -> "failed"
        }

        @Volatile
        private var factory: PeerConnectionFactory? = null
        private var users = 0

        @Synchronized
        private fun factory(context: Context): PeerConnectionFactory {
            users++
            factory?.let { return it }
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            return PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also { factory = it }
        }

        @Synchronized
        private fun release() {
            users--
            if (users <= 0) {
                users = 0
                factory?.dispose()
                factory = null
            }
        }

        /**
         * Öffnet Mikrofon und Verbindung; kehrt zurück, sobald die Kamera hören
         * kann. Wirft [TalkProblem]. [onLost] meldet ein späteres Ende mit Grund.
         */
        suspend fun open(context: Context, access: ComputeAccess, camera: String, onLost: (String) -> Unit): Talk {
            val f = factory(context)
            var released = false
            fun releaseOnce() {
                if (!released) {
                    released = true
                    release()
                }
            }
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val source = f.createAudioSource(constraints)
            val track = f.createAudioTrack("talk-mic", source)
            track.setEnabled(false)
            val answered = CompletableDeferred<Unit>()
            var talk: Talk? = null
            lateinit var socket: NasSocket
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    socket.sendText(buildJsonObject { put("type", "webrtc/candidate"); put("value", candidate.sdp) }.toString())
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        socket.sendText(buildJsonObject { put("type", "webrtc/candidate"); put("value", "") }.toString())
                    }
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                        val t = talk
                        if (t != null && !t.closed) { t.close(); onLost("failed") } else answered.completeExceptionally(TalkProblem("failed"))
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            }) ?: run { track.dispose(); source.dispose(); releaseOnce(); throw TalkProblem("unsupported") }
            pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("talk")))

            socket = access.socket("/nvr/cameras/${seg(camera)}/talk", object : SocketEvents {
                override fun onOpen() {
                    pc.createOffer(object : SdpAdapter() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(object : SdpAdapter() {
                                override fun onSetSuccess() {
                                    socket.sendText(buildJsonObject { put("type", "webrtc/offer"); put("value", desc.description) }.toString())
                                }

                                override fun onSetFailure(error: String?) = answered.completeExceptionally(TalkProblem("failed")).let {}
                            }, desc)
                        }

                        override fun onCreateFailure(error: String?) = answered.completeExceptionally(TalkProblem("failed")).let {}
                    }, MediaConstraints())
                }

                override fun onText(text: String) {
                    val msg = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                    val type = msg["type"]?.jsonPrimitive?.contentOrNull
                    val value = msg["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    when {
                        type == "webrtc/answer" && value.isNotEmpty() -> pc.setRemoteDescription(object : SdpAdapter() {
                            override fun onSetSuccess() = answered.complete(Unit).let {}
                            override fun onSetFailure(error: String?) = answered.completeExceptionally(TalkProblem("failed")).let {}
                        }, SessionDescription(SessionDescription.Type.ANSWER, value))
                        type == "webrtc/candidate" && value.isNotEmpty() -> pc.addIceCandidate(IceCandidate("0", 0, value))
                        msg["t"]?.jsonPrimitive?.contentOrNull in listOf("error", "bye") || type == "error" -> answered.completeExceptionally(TalkProblem("failed"))
                    }
                }

                override fun onClosed(opened: Boolean, code: Int, reason: String) {
                    val problem = closeProblem(reason)
                    val t = talk
                    if (t != null) {
                        if (!t.closed) { t.close(); onLost(problem) }
                    } else {
                        answered.completeExceptionally(TalkProblem(problem))
                    }
                }
            })
            try {
                withTimeout(15_000) { answered.await() }
            } catch (e: Exception) {
                socket.close()
                runCatching { pc.close(); pc.dispose() }
                runCatching { source.dispose() }
                releaseOnce()
                throw e as? TalkProblem ?: TalkProblem("failed")
            }
            return Talk(socket, pc, track) { runCatching { source.dispose() }; releaseOnce() }.also { talk = it }
        }
    }
}

/** Warum Sprechen nicht geht (Schlüssel nvr.talk.<problem> der Web-Texte). */
class TalkProblem(val problem: String) : Exception(problem)

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

@Suppress("unused")
private val keepTrackKind = MediaStreamTrack.AUDIO_TRACK_KIND
