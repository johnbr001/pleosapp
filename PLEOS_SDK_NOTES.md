# Live Drive Cast — Pleos SDK notes

Sports-broadcast narration of the driver's own trip for the Pleos Connect
head unit (AAOS-based, big driver-seat screen).

Pipeline: `PleosDriveMonitor` → `DriveEventDetector` → `NarrationRuleEngine`
(rules in `shared/src/main/res/raw/narration_rules.xml`) → comic caption on
screen + `Narrator` (Pleos TextToSpeech, platform TTS fallback).

## Pleos SDKs used (per https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)

| Concern           | SDK (version)        | Entry points used |
|-------------------|----------------------|-------------------|
| Car status        | Vehicle 2.3.2        | `Vehicle(context)`, `initialize()`, `getDoor()`, `Door.getCurrentDoorPosition(area, onComplete, onFailed)`, `release()` |
| Navigation status | NaviHelper 3.0.5     | `NaviHelper`, `initialize()`, `addListener()`, `NaviHelperEventListener` (`onRouteStarted`, `onDestinationArrived`, …), `removeListener()`, `release()` |
| Driving movement  | FusedLocation 1.1.3  | `FusedLocation`, `isSupported()`, `initialize()`, `registerFusedLocationCallback(FusedLocationListener.onChanged/onError)`, `unregister…`, `release()` |
| Voice             | TextToSpeech 3.3.1   | `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize()`, `addEventListener(EventListener)`, `speak(text)`, `stop()`, `release()` |

Speed additionally uses the AAOS vehicle property (present on Pleos Connect,
which is AAOS-based), following this repo's existing `android.car` pattern.

`Mode.ON_DEVICE` needs no network and no app registration; switch to `HYBRID`
with a registered My Project credential for server voices.

## Binding style

Pleos classes are bound **reflectively** (same convention this repo already
used for `android.car`): every bind is `runCatching`, so the app also runs
where a Pleos service is absent (plain emulator) with safe fallbacks. On a
Pleos Connect head unit with the client libraries, the real SDKs light up.

## Gradle (needs network to Pleos Maven)

`settings.gradle.kts` already declares
`https://nexus-playground.pleos.ai/repository/maven-releases/`.
To package the real client libraries, add to `shared/build.gradle`:

```gradle
dependencies {
    implementation "ai.pleos.playground:Vehicle:2.3.2"
    implementation "ai.pleos.playground:NaviHelper:3.0.5"
    implementation "ai.pleos.playground:TextToSpeech:3.3.1"
    implementation "ai.pleos.playground:FusedLocation:1.1.3"
}
```

Manifest permissions are declared in
`shared/src/main/AndroidManifest.xml`
(`CAR_SPEED`, `pleos.car.permission.FUSED_LOCATION`,
`pleos.car.permission.CAR_DRIVE`). Pleos permissions need App Market
consultation/approval per the Vehicle SDK review guide.

## Staying alive next to the navigator

The host owns the foreground and gives it to the active navigator while
driving — a template app cannot pin or split itself. So the commentary is
owned by `CastDirector` (process-wide), kept alive by the `CastService`
foreground service (`mediaPlayback` type) with an ongoing notification that
carries the latest call plus a Stop action. The template (`MyCarAppScreen`)
is only an observer/controller. Voice and caption therefore continue over
the navigation screen; split-screen itself remains the head unit's choice.

## Run it

1. Install the Pleos Connect Emulator + Virtual Console (see the
   `setup-connect-sdk-emulator` guide) and run the `automotive` module.
2. Grant speed + location permissions when asked.
3. While **parked**, tap "Demo lap" to hear the signature scenario:
   green start → sudden lane change → "let's calm down" warning.
4. Start driving with navigation: our screen yields, but the voice continues
   and the notification shows each call. Stop it from the notification.
5. Edit `narration_rules.xml` to change the show — first matching rule wins,
   per-rule cooldowns stop spam, lines round-robin.

## Tests

`shared/src/test`: `DriveEventDetectorTest` + `NarrationRuleEngineTest` +
`CastActionsTest` (18 tests, JVM-only, no device needed).

## Debugging: logs

The app writes a run log to logcat (tag `LiveDriveCast`) and to
`filesDir/cast.log` (rotated past 256 KB). It records lifecycle, granted
permissions, which Pleos SDKs bound (`pleos … bound` vs `… unavailable`),
every spoken call (`call [SEVERITY/ruleId]: text`), and any caught template
error.

- Live: `adb logcat -s LiveDriveCast:V`
- Save to file: `adb logcat -d > cast-logcat.txt`
- Pull the run log (debuggable build):
  `adb exec-out run-as com.ys.pleosapp cat files/cast.log > cast.log`

Known fixed crash: `MessageTemplate` allows at most 2 body actions — adding
a third throws and the host closes the app. `selectActions()` caps the list
at 2 and `onGetTemplate()` falls back to a bare message if building ever
fails, so a template bug can no longer kill the app.
