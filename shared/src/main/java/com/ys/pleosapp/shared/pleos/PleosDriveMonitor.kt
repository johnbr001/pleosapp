package com.ys.pleosapp.shared.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ys.pleosapp.shared.drive.DriveSnapshot
import com.ys.pleosapp.shared.drive.NavState
import com.ys.pleosapp.shared.util.CastLog
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fuses Pleos SDK signals into [DriveSnapshot]s on a 1 Hz ticker:
 *
 * - driving movement: speed from the AAOS vehicle property (present on
 *   Pleos Connect, which is AAOS-based) plus bearing/speed from the Pleos
 *   FusedLocation SDK (`FusedLocation.registerFusedLocationCallback`,
 *   `FusedLocationListener.onChanged/onError`);
 * - navigation status: Pleos NaviHelper SDK (`NaviHelper`, `addListener`,
 *   `NaviHelperEventListener.onRouteStarted/onDestinationArrived/...`);
 * - car status: Pleos Vehicle SDK (`Vehicle.initialize`, `getDoor`,
 *   `Door.getCurrentDoorPosition(area, onComplete, onFailed)`).
 *
 * Pleos classes are bound reflectively — the same convention this repo uses
 * for `android.car` — so the app also runs where a Pleos service is absent
 * (plain AAOS emulator): every bind is `runCatching` and missing signals
 * simply stay at their safe defaults. On a Pleos Connect head unit with the
 * Pleos client libraries, the real SDKs light up.
 */
class PleosDriveMonitor(private val appContext: Context) {

    fun interface Listener {
        fun onSnapshot(snapshot: DriveSnapshot)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var ticker: ScheduledFuture<*>? = null
    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var lastSpeedKmh: Float = 0f
    @Volatile private var lastBearingDeg: Float? = null
    @Volatile private var doorsClosed: Boolean? = null
    @Volatile private var navState: NavState = NavState()

    private var androidCar: Any? = null
    private var fusedLocation: Any? = null
    private var fusedListener: Any? = null
    private var naviHelper: Any? = null
    private var naviListener: Any? = null
    private var vehicle: Any? = null

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun start() {
        stop()
        bindAndroidCarSpeed()
        bindFusedLocation()
        bindNaviHelper()
        bindVehicleDoors()
        val sched = Executors.newSingleThreadScheduledExecutor()
        scheduler = sched
        ticker = sched.scheduleAtFixedRate({ publish() }, 0L, 1L, TimeUnit.SECONDS)
    }

    fun stop() {
        ticker?.cancel(true)
        ticker = null
        scheduler?.shutdownNow()
        scheduler = null
        unbindFusedLocation()
        unbindNaviHelper()
        unbindVehicle()
        runCatching { androidCar?.javaClass?.getMethod("disconnect")?.invoke(androidCar) }
        androidCar = null
    }

    private fun publish() {
        val snapshot = DriveSnapshot(
            timestampMs = System.currentTimeMillis(),
            speedKmh = lastSpeedKmh,
            bearingDeg = lastBearingDeg,
            doorsClosed = doorsClosed,
            nav = navState
        )
        mainHandler.post { listeners.forEach { it.onSnapshot(snapshot) } }
    }

    // ---- Speed via AAOS vehicle property (also present on Pleos Connect). ----

    private fun bindAndroidCarSpeed() {
        ioExecutor.execute {
            runCatching {
                val carClass = Class.forName("android.car.Car")
                val car = carClass
                    .getMethod("createCar", Context::class.java)
                    .invoke(null, appContext) ?: return@runCatching
                androidCar = car
                val manager = carClass
                    .getMethod("getCarManager", String::class.java)
                    .invoke(car, "property") ?: return@runCatching
                val managerClass = Class.forName(
                    "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
                )
                val callback = Proxy.newProxyInstance(
                    managerClass.classLoader, arrayOf(managerClass)
                ) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "DriveMonitorSpeedCallback"
                        "onChangeEvent" -> {
                            val value = args?.firstOrNull()
                            val speed = value?.javaClass?.getMethod("getValue")
                                ?.invoke(value) as? Number
                            if (speed != null) {
                                lastSpeedKmh = speed.toFloat() * 3.6f
                            }
                        }
                        else -> null
                    }
                }
                manager.javaClass.methods.first {
                    it.name == "registerCallback" && it.parameterTypes.size == 3
                }.invoke(manager, callback, PERF_VEHICLE_SPEED, 1.0f)
                CastLog.i("speed bound via vehicle property")
            }.onFailure { CastLog.w("vehicle speed unavailable: ${it.message}") }
        }
    }

    // ---- Movement via Pleos FusedLocation SDK. ----

    private fun bindFusedLocation() {
        ioExecutor.execute {
            runCatching {
                val clazz =
                    Class.forName("ai.pleos.playground.location.fused.FusedLocation")
                val listenerIface =
                    Class.forName("ai.pleos.playground.location.fused.FusedLocationListener")
                val instance = runCatching {
                    clazz.getConstructor(Context::class.java).newInstance(appContext)
                }.getOrElse { clazz.getConstructor().newInstance() }
                val supported = runCatching {
                    clazz.getMethod("isSupported").invoke(instance) as? Boolean
                }.getOrDefault(true)
                if (supported == false) return@runCatching
                runCatching { clazz.getMethod("initialize").invoke(instance) }
                val listener = Proxy.newProxyInstance(
                    listenerIface.classLoader, arrayOf(listenerIface)
                ) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "DriveMonitorLocationListener"
                        "onChanged" -> {
                            readLocation(args?.firstOrNull())
                            null
                        }
                        else -> primitiveDefault(method.returnType)
                    }
                }
                clazz.getMethod("registerFusedLocationCallback", listenerIface)
                    .invoke(instance, listener)
                fusedLocation = instance
                fusedListener = listener
                CastLog.i("pleos fused location bound")
            }.onFailure { CastLog.w("fused location unavailable, speed-only mode") }
        }
    }

    private fun readLocation(location: Any?) {
        if (location == null) return
        // LocationData carries latitude/longitude/altitude/bearing per the
        // FusedLocation guide; probe tolerant getter names.
        probeFloat(location, "getBearing", "bearing", "getDirection")?.let {
            lastBearingDeg = it
        }
        probeFloat(location, "getSpeed", "speed", "getVelocity")?.let {
            if (it > 0f) lastSpeedKmh = it * 3.6f
        }
    }

    private fun unbindFusedLocation() {
        val instance = fusedLocation
        val listener = fusedListener
        if (instance != null && listener != null) {
            runCatching {
                instance.javaClass.methods.first {
                    it.name == "unregisterFusedLocationCallback" && it.parameterTypes.size == 1
                }.invoke(instance, listener)
                runCatching { instance.javaClass.getMethod("release").invoke(instance) }
            }
        }
        fusedLocation = null
        fusedListener = null
    }

    // ---- Navigation status via Pleos NaviHelper SDK. ----

    private fun bindNaviHelper() {
        ioExecutor.execute {
            runCatching {
                val clazz = Class.forName("ai.pleos.playground.navihelper.NaviHelper")
                val listenerIface =
                    Class.forName("ai.pleos.playground.navihelper.NaviHelperEventListener")
                val instance = runCatching {
                    clazz.getConstructor(Context::class.java).newInstance(appContext)
                }.getOrElse { clazz.getConstructor().newInstance() }
                runCatching { clazz.getMethod("initialize").invoke(instance) }
                val listener = Proxy.newProxyInstance(
                    listenerIface.classLoader, arrayOf(listenerIface)
                ) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "DriveMonitorNaviListener"
                        "onRouteStarted", "onRoutingCompleted", "onReRouteEnded",
                        "onDestinationChanged", "onWaypointChanged" ->
                            navState = navState.copy(guidanceActive = true)
                        "onRouteCancelled" ->
                            navState = NavState()
                        "onDestinationArrived", "onWayPointArrived" ->
                            navState = navState.copy(arrived = true)
                        "onBoardInfo", "onRouteOptionChanged" -> Unit
                        else -> primitiveDefault(method.returnType)
                    }
                }
                clazz.getMethod("addListener", listenerIface).invoke(instance, listener)
                naviHelper = instance
                naviListener = listener
                CastLog.i("pleos navihelper bound")
            }.onFailure { CastLog.w("navihelper unavailable, nav status idle") }
        }
    }

    private fun unbindNaviHelper() {
        val instance = naviHelper
        val listener = naviListener
        if (instance != null && listener != null) {
            runCatching {
                runCatching {
                    instance.javaClass.getMethod("removeListener", listener.javaClass.interfaces[0])
                        .invoke(instance, listener)
                }
                runCatching { instance.javaClass.getMethod("release").invoke(instance) }
            }
        }
        naviHelper = null
        naviListener = null
    }

    // ---- Car status via Pleos Vehicle SDK (door position probe). ----

    private fun bindVehicleDoors() {
        ioExecutor.execute {
            runCatching {
                val vehicleClass = Class.forName("ai.pleos.playground.vehicle.Vehicle")
                val instance = vehicleClass
                    .getConstructor(Context::class.java).newInstance(appContext)
                runCatching { vehicleClass.getMethod("initialize").invoke(instance) }
                vehicle = instance
                val door = vehicleClass.getMethod("getDoor").invoke(instance) ?: return@runCatching
                // Door.getCurrentDoorPosition(area, onComplete, onFailed).
                val areaClass = Class.forName("ai.pleos.playground.vehicle.api.DoorArea")
                val valueClass = Class.forName("ai.pleos.playground.vehicle.api.DoorValue")
                val row1Left = valueClass.fields.first { it.name == "ROW_1_LEFT" }.get(null)
                val area = areaClass.constructors.first { it.parameterCount == 1 }
                    .newInstance(row1Left)
                val getCurrent = door.javaClass.methods.first {
                    it.name == "getCurrentDoorPosition" && it.parameterTypes.size == 3
                }
                val onComplete = functionProxy(getCurrent.parameterTypes[1]) { args ->
                    // 0 == closed on most door-position enums; anything else => check doors.
                    doorsClosed = (args?.firstOrNull() as? Number)?.toInt() == 0
                    CastLog.i("pleos vehicle bound, doorsClosed=$doorsClosed")
                    null
                }
                val onFailed = functionProxy(getCurrent.parameterTypes[2]) { null }
                getCurrent.invoke(door, area, onComplete, onFailed)
            }.onFailure { CastLog.w("pleos vehicle unavailable: ${it.message}") }
        }
    }

    private fun unbindVehicle() {
        val instance = vehicle ?: return
        vehicle = null
        runCatching { instance.javaClass.getMethod("release").invoke(instance) }
    }

    // ---- Reflection helpers. ----

    private fun functionProxy(iface: Class<*>, body: (Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DriveMonitorCallback"
                else -> body(args) ?: primitiveDefault(method.returnType)
            }
        }

    private fun probeFloat(target: Any, vararg names: String): Float? {
        for (name in names) {
            val value = runCatching {
                target.javaClass.getMethod(name).invoke(target) as? Number
            }.getOrNull()
            if (value != null) return value.toFloat()
        }
        return null
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private companion object {
        // VehiclePropertyIds.PERF_VEHICLE_SPEED, meters per second.
        const val PERF_VEHICLE_SPEED = 0x11600207
    }
}
