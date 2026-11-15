# Live Drive Cast — code-level flows (developer doc)

Companion to [README.md](../README.md). Class names, method names, and call
order below match the sources under `shared/src/main/java`. Mermaid blocks
render on GitHub / IDE preview.

## 1. Startup and wiring

How the car host, the screen, the director, the monitor, and the keep-alive
service connect. `CastDirector.start()` is `synchronized` and idempotent:
every entry point calls it, only the first call binds anything.

```mermaid
sequenceDiagram
    autonumber
    participant H as Host
    participant Svc as MyCarAppService
    participant Ses as MyCarAppSession
    participant Scr as MyCarAppScreen
    participant D as CastDirector
    participant M as PleosDriveMonitor
    participant N as Narrator
    participant F as CastService
    participant L as CastLog

    H->>Svc: onCreate()
    Svc->>L: init() + installCrashHandler()
    H->>Svc: onCreateSession()
    Svc->>Ses: MyCarAppSession()
    H->>Ses: onCreateScreen(intent)
    Ses->>Scr: MyCarAppScreen(carContext)

    H->>Scr: ON_START → onStart()
    Scr->>D: start(context)
    D->>D: loadRules() (narration_rules.xml or fallbackRules())
    D->>N: Narrator(app).start()
    N->>N: bindPleosTts() → else bindPlatformTts()
    D->>M: PleosDriveMonitor(app) + addListener() + start()
    M->>M: bindAndroidCarSpeed / bindFusedLocation / bindNaviHelper / bindVehicleDoors
    M->>M: 1 Hz ticker → publish() → mainHandler → Listener.onSnapshot()
    Scr->>F: startForegroundService(CastService)
    F->>L: init() + installCrashHandler()
    F->>F: createChannel()
    F->>D: start() (no-op, already running)
    F->>D: addListener(ui)
    F->>F: startForeground(NOTIF_ID, buildNotification(), MEDIA_PLAYBACK)
    Scr->>D: addListener(ui)
    Scr->>Scr: resetRefreshBudget()
    Scr->>Scr: gate → invalidate()
    H->>Scr: getTemplate() → onGetTemplate()
    Scr->>Scr: buildTemplate() + selectActions() + buildAction() × N
```

Notes:

- `PleosDriveMonitor.publish()` runs on the ticker thread and re-posts to
  `mainHandler`; `DriveEventDetector` / `NarrationRuleEngine` are only
  touched on `CastDirector`'s single `bg` executor.
- First render is free: `shouldRefreshTemplate()` returns true when the
  last-rendered snapshot is null.

## 2. Demo lap commentary loop

One tap drives 11 scripted snapshots through the real pipeline. Voice and
the notification fire on every call; the template only refreshes when the
gate allows (changed content, ≥ 5 s since last refresh, ≤ 3 auto-refreshes
per host flow).

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant H as Host
    participant Scr as MyCarAppScreen
    participant D as CastDirector
    participant E as DriveEventDetector
    participant R as NarrationRuleEngine
    participant N as Narrator

    U->>Scr: tap Demo (ParkedOnlyOnClickListener)
    Scr->>Scr: resetRefreshBudget()
    Scr->>D: demoLap() → bg.execute(runDemo)
    loop 11 feeds × ~1.4 s
        D->>D: onSnapshot(snapshot)
        D->>E: update(snapshot) → List~DriveEvent~
        D->>R: pick(events, speedKmh, repeatedSharpCount, nowMs) → Narration?
        alt narration != null
            D->>D: lastCall = it
            D->>D: headline = decorate(it)
            D->>N: speak(text)
        end
        D->>Scr: publish() → UiListener.onUpdate(h, s)
        Scr->>Scr: shouldRefreshTemplate(last…, new…, elapsedMs, autoRefreshesUsed)
        opt gate passes
            Scr->>Scr: snapshot = latest
            Scr->>Scr: autoRefreshesUsed += 1
            Scr->>H: invalidate() → getTemplate() → onGetTemplate()
        end
    end
```

## 3. Narrator speak / TTS fallback chain

`Narrator.speak()` never throws and never blocks a caller thread: everything
runs on `mainHandler`. When no engine is ready it retries every 500 ms up to
`TTS_NOT_READY_MAX_RETRIES` (40 ≈ 20 s), then drops the line with an error
log. `muted` short-circuits before any of this.

```mermaid
sequenceDiagram
    autonumber
    participant D as CastDirector
    participant N as Narrator
    participant P as Pleos TTS SDK
    participant A as Platform TTS
    participant L as CastLog

    D->>N: speak(text)
    alt muted or blank
        N->>L: i("speak suppressed (muted)") / return
    else mainHandler.post
        alt pleosTts != null
            N->>P: speak(text) via reflection
            opt reflection fails
                N->>N: pleosTts = null → fall through
            end
        end
        alt platformReady
            N->>A: speak(text, QUEUE_FLUSH, "cast") → rc
            opt rc == ERROR
                N->>L: w("platform speak returned ERROR (missing voice data?)")
            end
        else not ready and shouldKeepRetryingTts(retries)
            N->>N: bindPlatformTts()
            N->>N: retries += 1
            opt retries == 20
                N->>L: w("platform tts not ready after ~10s, engine may be missing")
            end
            N->>N: postDelayed(500 ms → speak(text))
        else budget spent
            N->>L: e("dropping voice line, no TTS engine became ready")
        end
    end
```

`start()` binding order, for reference:

```mermaid
flowchart TD
    S[Narrator.start] --> B{"bindPleosTts()?"}
    B -- "Class.forName OK, initialize OK" --> P["tts: pleos engine"]
    B -- "missing/unavailable" --> F[bindPlatformTts]
    F --> W{OnInitListener status?}
    W -- SUCCESS --> R["isLanguageAvailable → language = US, tts ready"]
    W -- ERROR --> E["w(init failed + engines list from PackageManager)"]
```
