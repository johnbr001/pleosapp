package com.ys.pleosapp.shared.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ys.pleosapp.shared.R
import com.ys.pleosapp.shared.drive.DriveEvent
import com.ys.pleosapp.shared.drive.DriveEventDetector
import com.ys.pleosapp.shared.drive.DriveSnapshot
import com.ys.pleosapp.shared.drive.NavState
import com.ys.pleosapp.shared.narration.Narration
import com.ys.pleosapp.shared.narration.NarrationRule
import com.ys.pleosapp.shared.narration.NarrationRuleEngine
import com.ys.pleosapp.shared.narration.NarrationRulesXml
import com.ys.pleosapp.shared.narration.Severity
import com.ys.pleosapp.shared.pleos.Narrator
import com.ys.pleosapp.shared.pleos.PleosDriveMonitor
import com.ys.pleosapp.shared.util.CastLog
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Process-wide owner of the commentary pipeline, independent of which
 * template is on screen: Pleos monitor -> detector -> rule engine -> narrator.
 *
 * The car host owns the foreground and pushes template apps aside for the
 * navigator while driving, so the [com.ys.pleosapp.shared.MyCarAppScreen]
 * is only an observer here. [CastService] keeps this director (and the
 * process) alive with an ongoing notification; the voice and the caption
 * therefore survive the navigation app taking the screen.
 */
object CastDirector {
    fun interface UiListener {
        fun onUpdate(headline: String, status: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<UiListener>()
    private val detector = DriveEventDetector()
    private var engine: NarrationRuleEngine? = null
    private var monitor: PleosDriveMonitor? = null
    private var narrator: Narrator? = null
    private var appContext: Context? = null
    // All detector/engine access runs on bg: the 1 Hz live ticker and the
    // demo lap must never touch them from different threads at once.
    private val monitorListener = PleosDriveMonitor.Listener { snapshot ->
        bg.execute { onSnapshot(snapshot) }
    }

    @Volatile private var running = false
    @Volatile var headline: String = ""
        private set
    @Volatile var status: String = ""
        private set
    @Volatile var muted: Boolean = false
        private set
    @Volatile var lastCall: Narration? = null
        private set

    fun addListener(listener: UiListener) = listeners.add(listener)
    fun removeListener(listener: UiListener) = listeners.remove(listener)

    /** Idempotent: safe to call from both the screen and the service. */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        appContext = app
        CastLog.init(app)
        if (headline.isEmpty()) {
            headline = app.getString(R.string.cast_welcome)
            status = "Connecting to vehicle..."
        }
        if (engine == null) {
            val rules = loadRules(app)
            CastLog.i("rules loaded: ${rules.size}")
            engine = NarrationRuleEngine(rules)
        }
        if (narrator == null) narrator = Narrator(app).also { it.start() }
        if (monitor == null) {
            monitor = PleosDriveMonitor(app).also {
                it.addListener(monitorListener)
                it.start()
            }
        }
        CastLog.i("director started")
        publish()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        monitor?.let {
            it.removeListener(monitorListener)
            it.stop()
        }
        narrator?.stop()
        CastLog.i("director stopped")
    }

    fun setMuted(mute: Boolean) {
        muted = mute
        narrator?.muted = mute
        val app = appContext
        if (mute) {
            narrator?.stop()
            if (app != null) headline = "🔇 ${app.getString(R.string.cast_muted)}"
            CastLog.i("muted")
        } else {
            headline = lastCall?.let { decorate(it) }
                ?: app?.getString(R.string.cast_welcome).orEmpty()
            CastLog.i("unmuted")
        }
        publish()
    }

    fun replay() {
        lastCall?.let { narrator?.speak(it.text) }
    }

    /** Parked demo: replays the signature scenario through the real pipeline. */
    fun demoLap() {
        bg.execute {
            runCatching {
                runDemo()
            }.onFailure { CastLog.e("demo lap failed", it) }
        }
    }

    private fun runDemo() {
        detector.reset()
        engine?.reset()
        var t = System.currentTimeMillis()
        fun feed(speed: Float, bearing: Float?, nav: NavState = NavState()) {
            t += 1_000L
            onSnapshot(DriveSnapshot(t, speed, bearing, true, nav))
            Thread.sleep(1_400L)
        }
        CastLog.i("demo lap started")
        feed(0f, 90f) // parked...
        feed(22f, 90f) // ...and we're GREEN GREEN GREEN!
        feed(60f, 90f)
        feed(62f, 122f) // OH! A sudden lane change!
        feed(61f, 155f) // second sharp move (cooldown holds the call back)
        feed(63f, 188f) // third strike: "let's calm down!"
        feed(64f, 188f)
        feed(34f, 188f) // hard brake
        feed(105f, 188f) // speeding warning
        feed(55f, 188f, NavState(guidanceActive = true, distanceToTurnM = 250))
        feed(20f, 185f, NavState(guidanceActive = true, arrived = true))
    }

    private fun onSnapshot(snapshot: DriveSnapshot) {
        val eng = engine ?: return
        val events = detector.update(snapshot)
        status = formatStatus(snapshot)
        val narration = eng.pick(
            events, snapshot.speedKmh, detector.repeatedSharpCount, snapshot.timestampMs
        )
        if (narration != null) {
            lastCall = narration
            if (!muted) headline = decorate(narration)
            CastLog.i("call [${narration.severity}/${narration.ruleId}]: ${narration.text}")
            narrator?.speak(narration.text)
        }
        publish()
    }

    private fun publish() {
        val h = headline
        val s = status
        mainHandler.post { listeners.forEach { it.onUpdate(h, s) } }
    }

    private fun loadRules(app: Context): List<NarrationRule> {
        val parsed = runCatching {
            app.resources.openRawResource(R.raw.narration_rules).use {
                NarrationRulesXml.parse(it)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (parsed == null) CastLog.w("rules XML missing, using built-in fallback")
        return parsed ?: fallbackRules()
    }

    /** Minimal built-in set so the show goes on even if the XML is missing. */
    private fun fallbackRules(): List<NarrationRule> = listOf(
        NarrationRule(
            id = "sharp_lane_change", event = DriveEvent.SUDDEN_LANE_CHANGE,
            minSpeedKmh = 30f, severity = Severity.FUN,
            lines = listOf("OH! A sudden lane change! What a MOVE!")
        ),
        NarrationRule(
            id = "too_much", event = DriveEvent.REPEATED_SHARP_MOVES,
            minRepeatCount = 3, severity = Severity.WARNING,
            lines = listOf("Whoa, too many sharp moves! Let's calm down!")
        )
    )

    private fun decorate(narration: Narration): String = when (narration.severity) {
        Severity.FUN -> "🎙️ ${narration.text}"
        Severity.INFO -> "📻 ${narration.text}"
        Severity.WARNING -> "⚠️ ${narration.text}"
    }

    private fun formatStatus(s: DriveSnapshot): String {
        val parts = mutableListOf("🚗 ${s.speedKmh.toInt()} km/h")
        s.bearingDeg?.let { parts += "🧭 ${it.toInt()}°" }
        val nav = s.nav
        parts += when {
            nav.arrived -> "📍 arrived!"
            nav.guidanceActive && nav.distanceToTurnM != null ->
                "📍 turn in ${nav.distanceToTurnM} m"
            nav.guidanceActive -> "📍 navigating…"
            else -> "📍 nav idle"
        }
        if (s.doorsClosed == false) parts += "🚪 door open!"
        return parts.joinToString(" • ")
    }
}
