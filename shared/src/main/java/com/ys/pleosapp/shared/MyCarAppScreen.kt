package com.ys.pleosapp.shared

import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.OnRequestPermissionsListener
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/** Displays the AAOS vehicle speed without requiring android.car at compile time. */
class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var car: Any? = null
    private var propertyManager: Any? = null
    private var speedCallback: Any? = null
    private var speedText = "Connecting to vehicle..."

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (hasSpeedPermission()) connectToVehicle()
                    else showStatus("Speed permission required. Select Grant permission while parked.")
                }
                Lifecycle.Event.ON_STOP -> disconnectFromVehicle()
                else -> Unit
            }
        })
    }

    private fun connectToVehicle() {
        try {
            val carClass = Class.forName("android.car.Car")
            car = carClass.getMethod("createCar", android.content.Context::class.java)
                .invoke(null, carContext)
            val connectedCar = car ?: throw IllegalStateException("Car service unavailable")
            val manager = carClass.getMethod("getCarManager", String::class.java)
                .invoke(connectedCar, "property")
                ?: throw IllegalStateException("Vehicle property service unavailable")
            propertyManager = manager
            subscribeToSpeed(manager)
            readInitialSpeed(manager)
        } catch (_: SecurityException) {
            showStatus("Speed permission is not granted")
        } catch (error: Exception) {
            val cause = generateSequence(error as Throwable?) { it.cause }.last()
            val detail = cause.message?.takeIf { it.isNotBlank() }
            showStatus(
                if (detail == null) "Vehicle connection failed: ${cause.javaClass.simpleName}"
                else "Vehicle connection failed: ${cause.javaClass.simpleName}: $detail"
            )
        }
    }

    private fun disconnectFromVehicle() {
        unsubscribeFromSpeed()
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
    }

    override fun onGetTemplate(): Template {
        val builder = MessageTemplate.Builder(speedText)
            .setHeaderAction(Action.APP_ICON)
            .setTitle("Current speed")
        if (!hasSpeedPermission()) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Grant permission")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        carContext.requestPermissions(
                            listOf(SPEED_PERMISSION),
                            OnRequestPermissionsListener { granted, _ ->
                                if (SPEED_PERMISSION in granted) connectToVehicle()
                                else showStatus("Speed permission was denied")
                            }
                        )
                    })
                    .build()
            )
        }
        return builder.build()
    }

    private fun hasSpeedPermission(): Boolean =
        carContext.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun subscribeToSpeed(manager: Any) {
        val managerClass = Class.forName("android.car.hardware.property.CarPropertyManager")
        val callbackClass = Class.forName(
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
        )
        val propertyId = PERF_VEHICLE_SPEED
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { proxy, method, args ->
            when (method.name) {
                // Java collections may call these Object methods while registering the proxy.
                // A null return for hashCode() gets unboxed to int and crashes with an NPE.
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "VehicleSpeedCallback"
                "onChangeEvent" -> {
                    val value = args?.firstOrNull()
                    val id = value?.javaClass?.getMethod("getPropertyId")?.invoke(value) as? Int
                    if (id == propertyId) {
                        val speed = value.javaClass.getMethod("getValue").invoke(value) as? Number
                        if (speed != null) showSpeed(speed.toFloat())
                    }
                }
                "onErrorEvent" -> showStatus("Vehicle speed is unavailable")
                else -> null
            }
        }
        speedCallback = callback
        val register = managerClass.methods.first {
            it.name == "registerCallback" && it.parameterTypes.size == 3
        }
        register.invoke(manager, callback, propertyId, NORMAL_SENSOR_RATE)
    }

    private fun readInitialSpeed(manager: Any) {
        ioExecutor.execute {
            try {
                val managerClass = manager.javaClass
                val getProperty = managerClass.methods.firstOrNull {
                    it.name == "getProperty" && it.parameterTypes.size == 3
                }
                val speed = runCatching {
                    if (getProperty == null) throw NoSuchMethodException("getProperty")
                    val value = getProperty.invoke(
                        manager,
                        Float::class.javaObjectType,
                        PERF_VEHICLE_SPEED,
                        0
                    )
                    value?.javaClass?.getMethod("getValue")?.invoke(value) as? Number
                }.getOrNull() ?: run {
                    val getFloat = managerClass.getMethod(
                        "getFloatProperty",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    getFloat.invoke(manager, PERF_VEHICLE_SPEED, 0) as? Number
                }
                if (speed != null) showSpeed(speed.toFloat())
                else showStatus("Waiting for vehicle speed...")
            } catch (_: SecurityException) {
                showStatus("Speed permission is not granted")
            } catch (error: Exception) {
                // Keep the actual failure visible while diagnosing device/API differences.
                val cause = error.cause ?: error
                showStatus("Unable to read vehicle speed: ${cause.javaClass.simpleName}")
            }
        }
    }

    private fun unsubscribeFromSpeed() {
        val manager = propertyManager
        val callback = speedCallback
        if (manager != null && callback != null) {
            runCatching {
                val unregister = manager.javaClass.methods.first {
                    it.name == "unregisterCallback" && it.parameterTypes.size == 2
                }
                unregister.invoke(manager, callback, PERF_VEHICLE_SPEED)
            }
        }
        propertyManager = null
        speedCallback = null
    }

    private fun showSpeed(metersPerSecond: Float) {
        mainHandler.post {
            speedText = "${(metersPerSecond * 3.6f).toInt()} km/h"
            invalidate()
        }
    }

    private fun showStatus(message: String) {
        mainHandler.post {
            speedText = message
            invalidate()
        }
    }

    private companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        // VehiclePropertyIds.PERF_VEHICLE_SPEED, in meters per second.
        const val PERF_VEHICLE_SPEED = 0x11600207
        const val NORMAL_SENSOR_RATE = 1.0f
    }
}
