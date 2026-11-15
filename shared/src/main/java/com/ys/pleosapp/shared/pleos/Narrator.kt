package com.ys.pleosapp.shared.pleos

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.ys.pleosapp.shared.util.CastLog
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Speaks commentary lines, preferring the Pleos TextToSpeech SDK and falling
 * back to the platform `android.speech.tts` engine.
 *
 * Pleos usage follows the programming guide: `TextToSpeech(context, Mode)`,
 * `initialize()`, `addEventListener(EventListener)`, `speak(text)`,
 * `stop()`, `release()`. `Mode.ON_DEVICE` needs no network and no app
 * registration, so narration works offline; switch to `HYBRID` (with a
 * registered My Project credential) for server voices.
 */
class Narrator(private val appContext: Context) {

    @Volatile var muted: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pleosTts: Any? = null
    private var pleosListener: Any? = null
    private var platformTts: android.speech.tts.TextToSpeech? = null
    private var platformReady = false
    private var notReadyRetries = 0

    fun start() {
        mainHandler.post {
            if (pleosTts == null && platformTts == null) {
                if (bindPleosTts()) CastLog.i("tts: pleos engine")
                else if (bindPlatformTts()) CastLog.i("tts: platform engine")
                else CastLog.w("tts: no engine available, silent mode")
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (muted) {
            CastLog.i("speak suppressed (muted): $text")
            return
        }
        mainHandler.post {
            val pleos = pleosTts
            if (pleos != null) {
                runCatching {
                    pleos.javaClass.getMethod("speak", String::class.java)
                        .invoke(pleos, text)
                    return@post
                }
                pleosTts = null
            }
            if (platformReady) {
                notReadyRetries = 0
                runCatching {
                    val rc = platformTts?.speak(
                        text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cast"
                    ) ?: android.speech.tts.TextToSpeech.ERROR
                    if (rc == android.speech.tts.TextToSpeech.ERROR) {
                        CastLog.w("platform speak returned ERROR (missing voice data?)")
                    }
                }.onFailure { CastLog.e("platform speak failed", it) }
            } else if (shouldKeepRetryingTts(notReadyRetries)) {
                bindPlatformTts()
                notReadyRetries += 1
                if (notReadyRetries == 20) {
                    CastLog.w("platform tts not ready after ~10s, engine may be missing")
                }
                mainHandler.postDelayed({ speak(text) }, 500L)
            } else {
                CastLog.e("dropping voice line, no TTS engine became ready: $text")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            runCatching {
                pleosTts?.javaClass?.getMethod("stop")?.invoke(pleosTts)
            }
            platformTts?.stop()
        }
    }

    fun release() {
        mainHandler.post {
            val pleos = pleosTts
            pleosTts = null
            if (pleos != null) {
                runCatching {
                    pleosListener?.let {
                        runCatching {
                            pleos.javaClass.methods.first {
                                it.name == "removeEventListener" && it.parameterTypes.size == 1
                            }.invoke(pleos, it)
                        }
                    }
                    pleos.javaClass.getMethod("release").invoke(pleos)
                }
            }
            platformTts?.shutdown()
            platformTts = null
            platformReady = false
        }
    }

    /** Returns true when the Pleos engine bound successfully. */
    private fun bindPleosTts(): Boolean = runCatching {
        val ttsClass = Class.forName("ai.pleos.playground.tts.TextToSpeech")
        val modeClass = Class.forName("ai.pleos.playground.tts.constant.Mode")
        val listenerIface = Class.forName("ai.pleos.playground.tts.listener.EventListener")
        val onDevice = modeClass.enumConstants
            ?.firstOrNull { it.toString() == "ON_DEVICE" }
            ?: error("Pleos TTS Mode.ON_DEVICE not found")
        val instance = ttsClass
            .getConstructor(Context::class.java, modeClass)
            .newInstance(appContext, onDevice)
        val listener = Proxy.newProxyInstance(
            listenerIface.classLoader, arrayOf(listenerIface)
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CastTtsListener"
                else -> null // onReady/onStart/onUpdatedRms/onDone/onStop/onError: no-op.
            }
        }
        ttsClass.getMethod("addEventListener", listenerIface).invoke(instance, listener)
        runCatching { ttsClass.getMethod("initialize").invoke(instance) }
        pleosTts = instance
        pleosListener = listener
        true
    }.getOrDefault(false)

    private fun bindPlatformTts(): Boolean {
        if (platformTts != null) return true
        platformTts = android.speech.tts.TextToSpeech(appContext) { status ->
            platformReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (!platformReady) {
                // Query PackageManager directly: TextToSpeech.engines can be
                // null when binding failed, hiding whether any engine exists.
                val engines = runCatching {
                    appContext.packageManager.queryIntentServices(
                        Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                        0
                    ).joinToString { it.serviceInfo.packageName }
                }.getOrNull()
                CastLog.w("platform tts init failed: $status (engines=[$engines])")
            } else {
                val tts = platformTts
                if (tts != null) {
                    val available = tts.isLanguageAvailable(Locale.US)
                    if (available >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                        tts.language = Locale.US
                    }
                    CastLog.i("platform tts ready (US available=$available)")
                }
            }
        }
        return true
    }
}

/** Max silent 500 ms retries while the platform engine is not ready (~20 s), then drop the line. */
const val TTS_NOT_READY_MAX_RETRIES = 40

fun shouldKeepRetryingTts(notReadyRetries: Int): Boolean =
    notReadyRetries < TTS_NOT_READY_MAX_RETRIES
