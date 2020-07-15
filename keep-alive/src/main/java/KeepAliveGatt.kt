/*
 * Copyright 2020 JUUL Labs, Inc.
 */

package com.juul.able.keepalive

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.juul.able.Able
import com.juul.able.android.connectGatt
import com.juul.able.device.ConnectGattResult.Failure
import com.juul.able.device.ConnectGattResult.Success
import com.juul.able.gatt.Gatt
import com.juul.able.gatt.GattIo
import com.juul.able.gatt.GattStatus
import com.juul.able.gatt.OnCharacteristicChanged
import com.juul.able.gatt.OnCharacteristicRead
import com.juul.able.gatt.OnCharacteristicWrite
import com.juul.able.gatt.OnDescriptorWrite
import com.juul.able.gatt.OnMtuChanged
import com.juul.able.gatt.OnReadRemoteRssi
import com.juul.able.gatt.WriteType
import com.juul.able.keepalive.Event.Disconnected.Info
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NotReady internal constructor(message: String) : IllegalStateException(message)
class ConnectionRejected internal constructor(cause: Throwable) : IOException(cause)

sealed class Event {
    data class Connected(val gatt: GattIo) : Event()
    data class Disconnected(val info: Info) : Event() {
        /**
         * A set of information provided with the [Event.Disconnected], either immediately after a
         * successful connection or after a failed connection attempt.
         *
         * The [connectionAttempt] property represents which connection attempt iteration over the
         * lifespan of the [KeepAliveGatt] this [Info] event is associated with. The value begins at
         * 1 and increase by 1 for each iteration.
         *
         * @param wasConnected is `true` if event follows an established connection, or `false` if previous connection attempt failed.
         * @param connectionAttempt is the number of connection attempts since creation of [KeepAliveGatt].
         */
        data class Info(
            val wasConnected: Boolean,
            val connectionAttempt: Int
        )
    }
}

suspend fun Event.onConnected(action: suspend (gatt: GattIo) -> Unit) {
    if (this is Event.Connected) action.invoke(gatt)
}

suspend fun Event.onDisconnected(action: suspend (Info) -> Unit) {
    if (this is Event.Disconnected) action.invoke(info)
}

typealias EventHandler = suspend (Event) -> Unit

sealed class State {
    object Connecting : State()
    object Connected : State()
    object Disconnecting : State()
    data class Disconnected(val cause: Throwable? = null) : State() {
        override fun toString() = super.toString()
    }

    data class Cancelled(val cause: Throwable?) : State() {
        override fun toString() = super.toString()
    }

    override fun toString(): String = javaClass.simpleName
}

fun CoroutineScope.keepAliveGatt(
    androidContext: Context,
    bluetoothDevice: BluetoothDevice,
    disconnectTimeoutMillis: Long,
    eventHandler: EventHandler? = null
) = KeepAliveGatt(
    parentCoroutineContext = coroutineContext,
    androidContext = androidContext,
    bluetoothDevice = bluetoothDevice,
    disconnectTimeoutMillis = disconnectTimeoutMillis,
    eventHandler = eventHandler
)

class KeepAliveGatt internal constructor(
    parentCoroutineContext: CoroutineContext,
    androidContext: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val disconnectTimeoutMillis: Long,
    private val eventHandler: EventHandler?
) : GattIo {

    private val applicationContext = androidContext.applicationContext

    private val job = SupervisorJob(parentCoroutineContext[Job]).apply {
        invokeOnCompletion { cause ->
            _state.value = State.Cancelled(cause)
            _onCharacteristicChanged.cancel()
        }
    }
    private val scope = CoroutineScope(parentCoroutineContext + job)

    private val isRunning = AtomicBoolean()

    @Volatile
    private var _gatt: GattIo? = null
    private val gatt: GattIo
        inline get() = _gatt ?: throw NotReady(toString())

    private val _state = MutableStateFlow<State>(State.Disconnected())

    /**
     * Provides a [Flow] of the [KeepAliveGatt]'s [State].
     *
     * The initial [state] is [Disconnected] and will typically transition through the following
     * [State]s after [connect] is called:
     *
     * ```
     *                    connect()
     *                        :
     *  .--------------.      v       .------------.       .-----------.
     *  | Disconnected | ----------> | Connecting | ----> | Connected |
     *  '--------------'             '------------'       '-----------'
     *         ^                                                |
     *         |                                         connection drop
     *         |                                                v
     *         |                                        .---------------.
     *         '----------------------------------------| Disconnecting |
     *                                                  '---------------'
     * ```
     */
    @FlowPreview
    val state: Flow<State> = _state

    private val _onCharacteristicChanged = BroadcastChannel<OnCharacteristicChanged>(BUFFERED)

    private var connectionAttempt = 1

    fun connect(): Boolean {
        check(!job.isCancelled) { "Cannot connect, $this is closed" }
        isRunning.compareAndSet(false, true) || return false

        scope.launch(CoroutineName("KeepAliveGatt@$bluetoothDevice")) {
            while (isActive) {
                val didConnect = establishConnection()
                eventHandler?.invoke(
                    Event.Disconnected(Info(didConnect, connectionAttempt++))
                )
            }
        }.invokeOnCompletion { isRunning.set(false) }
        return true
    }

    suspend fun disconnect() {
        job.children.forEach { it.cancelAndJoin() }
    }

    /**
     * Establishes a connection, suspending until either the attempt at establishing connection
     * fails or an established connection drops.
     *
     * @return `true` if connection was established (then dropped), or `false` if connection attempt failed.
     * @throws ConnectionRejected if the operating system rejects the connection request.
     */
    private suspend fun establishConnection(): Boolean {
        try {
            _state.value = State.Connecting

            val gatt: Gatt = when (val result = bluetoothDevice.connectGatt(applicationContext)) {
                is Success -> result.gatt
                is Failure.Rejected -> throw ConnectionRejected(result.cause)
                is Failure.Connection -> {
                    Able.error { "Failed to connect to device $bluetoothDevice due to ${result.cause}" }
                    return false
                }
            }

            supervisorScope {
                launch {
                    try {
                        coroutineScope {
                            gatt.onCharacteristicChanged
                                .onEach(_onCharacteristicChanged::send)
                                .launchIn(this, start = UNDISPATCHED)
                            _gatt = gatt
                            eventHandler?.invoke(Event.Connected(gatt))
                            _state.value = State.Connected
                        }
                    } finally {
                        _gatt = null
                        _state.value = State.Disconnecting

                        withContext(NonCancellable) {
                            withTimeoutOrNull(disconnectTimeoutMillis) {
                                gatt.disconnect()
                            } ?: Able.warn {
                                "Timed out waiting ${disconnectTimeoutMillis}ms for disconnect"
                            }
                        }
                    }
                }
            }
            _state.value = State.Disconnected()
            return true
        } catch (failure: Exception) {
            _state.value = State.Disconnected(failure)
            throw failure
        }
    }

    @FlowPreview
    override val onCharacteristicChanged: Flow<OnCharacteristicChanged> =
        _onCharacteristicChanged.asFlow()

    override suspend fun discoverServices(): GattStatus = gatt.discoverServices()

    override val services: List<BluetoothGattService> get() = gatt.services
    override fun getService(uuid: UUID): BluetoothGattService? = gatt.getService(uuid)

    override suspend fun requestMtu(mtu: Int): OnMtuChanged = gatt.requestMtu(mtu)

    override suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): OnCharacteristicRead = gatt.readCharacteristic(characteristic)

    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean
    ): Boolean = gatt.setCharacteristicNotification(characteristic, enable)

    override suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: WriteType
    ): OnCharacteristicWrite = gatt.writeCharacteristic(characteristic, value, writeType)

    override suspend fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): OnDescriptorWrite = gatt.writeDescriptor(descriptor, value)

    override suspend fun readRemoteRssi(): OnReadRemoteRssi = gatt.readRemoteRssi()

    override fun toString() =
        "KeepAliveGatt(device=$bluetoothDevice, gatt=$_gatt, state=${_state.value})"
}

private fun <T> Flow<T>.launchIn(
    scope: CoroutineScope,
    start: CoroutineStart = CoroutineStart.DEFAULT
): Job = scope.launch(start = start) {
    collect()
}
