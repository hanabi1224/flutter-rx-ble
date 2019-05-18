package com.pycampers.rx_ble

import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.pycampers.plugin_scaffold.catchErrors
import com.pycampers.plugin_scaffold.trySend
import com.pycampers.plugin_scaffold.trySendThrowable
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.util.UUID

interface ConnectInterface {
    fun disconnect(call: MethodCall, result: Result)
    fun getBleConnection(macAddress: String): RxBleConnection
    fun getConnectionState(call: MethodCall, result: Result)
    fun readChar(call: MethodCall, result: Result)
    fun writeChar(call: MethodCall, result: Result)
    fun requestMtu(call: MethodCall, result: Result)
}

class DeviceState {
    var connectDisposable: Disposable? = null
    var stateDisposable: Disposable? = null
    var eventSink: EventSink? = null
    var bleDevice: RxBleDevice? = null
    var bleConnection: RxBleConnection? = null
}

class ConnectMethods(messenger: BinaryMessenger) : ConnectInterface {
    val channel = EventChannel(messenger, "$PKG_NAME/connect")
    val devices = mutableMapOf<String, DeviceState>()

    fun getBleDevice(macAddress: String): RxBleDevice {
        return devices[macAddress]?.bleDevice ?: throw IllegalArgumentException(
            "Device has not been initialized yet. " +
                "You must call \"startScan()\" and wait for " +
                "device to appear in ScanResults before accessing the device."
        )
    }

    fun getDeviceState(macAddress: String) = devices.getOrPut(macAddress) { DeviceState() }

    fun disconnect(macAddress: String) {
        devices[macAddress]?.run {
            connectDisposable?.dispose()
            connectDisposable = null
            stateDisposable?.dispose()
            stateDisposable = null
            eventSink?.endOfStream()
            eventSink = null
        }
    }

    fun connect(macAddress: String, waitForDevice: Boolean, events: EventSink) {
        disconnect(macAddress)

        val device = getBleDevice(macAddress)
        val state = getDeviceState(macAddress)

        state.connectDisposable = device.establishConnection(waitForDevice).subscribe(
            { state.bleConnection = it },
            { trySendThrowable(events, it) }
        )
        state.stateDisposable = device.observeConnectionStateChanges().subscribe { trySend(events) { it.ordinal } }

        state.eventSink = events
    }

    init {
        channel.setStreamHandler(object : StreamHandler {
            override fun onListen(args: Any?, events: EventSink) {
                catchErrors(events) {
                    val map = args as Map<*, *>
                    val macAddress = map["macAddress"] as String
                    val waitForDevice = map["waitForDevice"] as Boolean
                    connect(macAddress, waitForDevice, events)
                }
            }

            override fun onCancel(args: Any?) {
                val map = args as Map<*, *>
                val macAddress = map["macAddress"] as String
                disconnect(macAddress)
            }
        })
    }

    override fun getBleConnection(macAddress: String): RxBleConnection {
        return devices[macAddress]?.bleConnection ?: throw IllegalArgumentException(
            "Connection to device has not been initialized yet. " +
                "You must call \"connect()\" and wait for " +
                "\"BleConnectionState.connected\" before doing any read/write operation."
        )
    }

    override fun disconnect(call: MethodCall, result: Result) {
        val macAddress = call.arguments as String?
        if (macAddress != null) {
            disconnect(macAddress)
        } else {
            for (it in devices.keys) {
                disconnect(it)
            }
        }
        result.success(null)
    }

    override fun getConnectionState(call: MethodCall, result: Result) {
        val macAddress = call.arguments as String
        result.success(getBleDevice(macAddress).connectionState.ordinal)
    }

    fun <T> subscribeAndSendResult(observable: Single<T>, result: Result) {
        observable.run {
            subscribe(
                { trySend(result) { it } },
                { trySendThrowable(result, it) }
            )
        }
    }

    override fun readChar(call: MethodCall, result: Result) {
        val macAddress = call.argument<String>("macAddress")!!
        val uuid = UUID.fromString(call.argument<String>("uuid")!!)
        val connection = getBleConnection(macAddress)
        subscribeAndSendResult(connection.readCharacteristic(uuid), result)
    }

    override fun writeChar(call: MethodCall, result: Result) {
        val macAddress = call.argument<String>("macAddress")!!
        val uuid = UUID.fromString(call.argument<String>("uuid")!!)
        val value = call.argument<ByteArray>("value")!!
        val connection = getBleConnection(macAddress)
        subscribeAndSendResult(connection.writeCharacteristic(uuid, value), result)
    }

    override fun requestMtu(call: MethodCall, result: Result) {
        val macAddress = call.argument<String>("macAddress")!!
        val value = call.argument<Int>("value")!!
        val connection = getBleConnection(macAddress)
        subscribeAndSendResult(connection.requestMtu(value), result)
    }
}