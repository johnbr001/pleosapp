package com.ys.pleosapp.shared

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.OnRequestPermissionsListener
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ys.pleosapp.shared.cast.CastDirector
import com.ys.pleosapp.shared.cast.CastService
import com.ys.pleosapp.shared.util.CastLog

/**
 * "Live Drive Cast" template: a thin observer of [CastDirector].
 *
 * The host owns the foreground and may replace this screen with the
 * navigator while driving. The commentary itself lives in [CastDirector]
 * (kept alive by [CastService]), so the voice and the notification caption
 * continue regardless of which template is showing.
 */
class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var headline = carContext.getString(R.string.cast_welcome)
    private var statusLine = "Connecting to vehicle..."
    private var muted = false
    private var hasReplay = false

    // Last template actually sent to the host. Every invalidate() produces a
    // new MessageTemplate and the host allows only 5 per flow, so refreshes
    // are coalesced through shouldRefreshTemplate instead of going out on
    // every director tick (speed ticker, demo-lap feeds).
    private var lastRenderedHeadline: String? = null
    private var lastRenderedStatus: String? = null
    private var lastRenderedMuted = false
    private var lastRenderedHasReplay = false
    private var lastRenderedPermissions = false
    private var lastRefreshMs = 0L
    private var autoRefreshesUsed = 0

    /** User gestures start a new host flow, which resets the refresh budget. */
    private fun resetRefreshBudget() {
        autoRefreshesUsed = 0
        lastRefreshMs = 0L
    }

    private val ui = CastDirector.UiListener { h, s ->
        val newMuted = CastDirector.muted
        val newHasReplay = CastDirector.lastCall != null
        val newPermissions = hasPermissions()
        val now = SystemClock.uptimeMillis()
        headline = h
        statusLine = s
        muted = newMuted
        hasReplay = newHasReplay
        if (shouldRefreshTemplate(
                lastRenderedHeadline,
                lastRenderedStatus,
                lastRenderedMuted,
                lastRenderedHasReplay,
                lastRenderedPermissions,
                h,
                s,
                newMuted,
                newHasReplay,
                newPermissions,
                now - lastRefreshMs,
                autoRefreshesUsed
            )
        ) {
            lastRenderedHeadline = h
            lastRenderedStatus = s
            lastRenderedMuted = newMuted
            lastRenderedHasReplay = newHasReplay
            lastRenderedPermissions = newPermissions
            lastRefreshMs = now
            autoRefreshesUsed += 1
            mainHandler.post { invalidate() }
        }
    }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_STOP -> CastDirector.removeListener(ui)
                else -> Unit
            }
        })
    }

    private fun onStart() {
        // Pipeline always runs in-process; the service adds keep-alive.
        CastDirector.start(carContext)
        runCatching {
            carContext.startForegroundService(Intent(carContext, CastService::class.java))
        }.onFailure { CastLog.w("keep-alive service unavailable: ${it.message}") }
        headline = CastDirector.headline.ifEmpty { headline }
        statusLine = CastDirector.status.ifEmpty { statusLine }
        muted = CastDirector.muted
        hasReplay = CastDirector.lastCall != null
        CastDirector.addListener(ui)
        if (!hasPermissions()) {
            val denied = REQUIRED_PERMISSIONS.filter {
                carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            statusLine = "Permissions needed for the full commentary."
            CastLog.w("permissions denied: $denied")
        }
        resetRefreshBudget()
        val now = SystemClock.uptimeMillis()
        if (shouldRefreshTemplate(
                lastRenderedHeadline,
                lastRenderedStatus,
                lastRenderedMuted,
                lastRenderedHasReplay,
                lastRenderedPermissions,
                headline,
                statusLine,
                muted,
                hasReplay,
                hasPermissions(),
                now - lastRefreshMs,
                autoRefreshesUsed
            )
        ) {
            lastRenderedHeadline = headline
            lastRenderedStatus = statusLine
            lastRenderedMuted = muted
            lastRenderedHasReplay = hasReplay
            lastRenderedPermissions = hasPermissions()
            lastRefreshMs = now
            autoRefreshesUsed += 1
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        // Never let a template bug kill the app: fall back to a bare message.
        return runCatching { buildTemplate() }.getOrElse { error ->
            CastLog.e("template failed, showing fallback", error)
            MessageTemplate.Builder("$headline\n\n$statusLine")
                .setHeaderAction(Action.APP_ICON)
                .setTitle("🏁 ${carContext.getString(R.string.cast_title)}")
                .build()
        }
    }

    private fun buildTemplate(): Template {
        val builder = MessageTemplate.Builder("$headline\n\n$statusLine")
            .setHeaderAction(Action.APP_ICON)
            .setTitle("🏁 ${carContext.getString(R.string.cast_title)}")
        // The host allows at most 2 body actions; selectActions enforces it.
        for (action in selectActions(hasPermissions(), hasReplay)) {
            builder.addAction(buildAction(action))
        }
        return builder.build()
    }

    private fun buildAction(action: CastAction): Action = when (action) {
        CastAction.GRANT -> Action.Builder()
            .setTitle(carContext.getString(R.string.grant_permission))
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                carContext.requestPermissions(
                    REQUIRED_PERMISSIONS,
                    // User gesture resets the host flow, so refresh at once
                    // and record it to keep the throttle bookkeeping exact.
                    OnRequestPermissionsListener { _, _ ->
                        resetRefreshBudget()
                        lastRenderedHeadline = headline
                        lastRenderedStatus = statusLine
                        lastRenderedMuted = muted
                        lastRenderedHasReplay = hasReplay
                        lastRenderedPermissions = hasPermissions()
                        lastRefreshMs = SystemClock.uptimeMillis()
                        invalidate()
                    }
                )
            })
            .build()
        CastAction.MUTE -> Action.Builder()
            .setTitle(if (muted) carContext.getString(R.string.action_unmute) else carContext.getString(R.string.action_mute))
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                resetRefreshBudget()
                CastDirector.setMuted(!muted)
            })
            .build()
        CastAction.REPLAY -> Action.Builder()
            .setTitle(carContext.getString(R.string.action_replay))
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                resetRefreshBudget()
                CastDirector.replay()
            })
            .build()
        CastAction.DEMO -> Action.Builder()
            .setTitle(carContext.getString(R.string.action_demo))
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                resetRefreshBudget()
                CastDirector.demoLap()
            })
            .build()
    }

    // ---- Permissions ----

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        carContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        val REQUIRED_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            "pleos.car.permission.FUSED_LOCATION"
        )
    }
}
