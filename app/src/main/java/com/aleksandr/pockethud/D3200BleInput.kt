package com.aleksandr.pockethud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class D3200BleInput(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPcm: (ByteArray) -> Boolean,
    private val onClosed: () -> Unit
) {
    private val thread = HandlerThread("PocketHUD-D3200").apply { start() }
    private val handler = Handler(thread.looper)
    private val parser = D3200Protocol.Parser()
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var discovering = false
    private var subscribed = false
    private var finished = false
    private var shuttingDown = false
    private var timeout: Runnable? = null
    private var writeTimeout: Runnable? = null
    private val writes = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var decoder: D3200OpusDecoder? = null
    private var received = 0L
    private var decoded = 0L
    private var queued = 0L
    private var nextTelemetry = 0L
    private var awaitingPcmUntil = 0L
    private val drain = object : Runnable {
        override fun run() {
            safely {
                decoder?.drain()
                if (decoded == 0L && SystemClock.elapsedRealtime() >= awaitingPcmUntil) {
                    error("MediaCodec не выдаёт PCM")
                }
                handler.postDelayed(this, 20)
            }
        }
    }

    private val session = D3200Session(::enqueue, ::report, { _, ms ->
        setDeadline(ms) { sessionTimeout() }
    }, ::decode, ::finish)

    fun start() = post {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "Включи Bluetooth для Soundcore Work" }
        val le = checkNotNull(adapter.bluetoothLeScanner) { "Сканирование BLE недоступно" }
        scanner = le
        report("Поиск Soundcore Work")
        le.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        setDeadline(15000) { error("Soundcore Work не найден по BLE") }
    }

    fun stop() = post { shutdown() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = post {
            if (scanner == null) return@post
            val record = result.scanRecord ?: return@post
            if (record.serviceUuids.orEmpty().none { it.uuid == SERVICE } &&
                record.serviceData.orEmpty().keys.none { it.uuid == SERVICE }) return@post
            stopScan()
            report("Подключение GATT")
            gatt = checkNotNull(result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)) {
                "GATT недоступен"
            }
            setDeadline(10000) { error("Истекло ожидание подключения GATT") }
        }

        override fun onScanFailed(errorCode: Int) = post {
            if (scanner != null) error("Сканирование BLE завершилось с кодом $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) = post {
            if (link !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                report("GATT отключён, код=$status")
                finish()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                report("GATT подключён")
                if (link.requestMtu(247)) setDeadline(5000) { discover(link) }
                else discover(link)
            }
        }

        override fun onMtuChanged(link: BluetoothGatt, value: Int, status: Int) = post {
            if (link !== gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value.coerceIn(23, 517)
            if (!discovering) discover(link)
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) = post {
            if (link !== gatt) return@post
            check(status == BluetoothGatt.GATT_SUCCESS) { "Ошибка обнаружения GATT, код=$status" }
            val service = checkNotNull(link.getService(SERVICE)) { "Сервис D3200 отсутствует в GATT" }
            val tx = checkNotNull(service.getCharacteristic(WRITE)) { "Характеристика 7777 отсутствует" }
            check(tx.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) { "7777 не принимает запись" }
            writer = tx
            val rx = checkNotNull(service.getCharacteristic(NOTIFY)) { "Характеристика 8888 отсутствует" }
            val cccd = checkNotNull(rx.getDescriptor(CCCD)) { "CCCD отсутствует" }
            check(link.setCharacteristicNotification(rx, true)) { "Не удалось включить уведомления D3200" }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                link.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                link.writeDescriptor(cccd)
            }
            check(started) { "Не удалось записать CCCD" }
            setDeadline(5000) { error("Истекло ожидание CCCD") }
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post {
            if (link !== gatt || descriptor.uuid != CCCD || subscribed) return@post
            check(status == BluetoothGatt.GATT_SUCCESS) { "Уведомления D3200 отклонены, код=$status" }
            subscribed = true
            report("Уведомления активны, MTU=$mtu")
            session.begin()
        }

        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = post {
            if (link !== gatt || characteristic.uuid != WRITE || !writeInFlight) return@post
            writeTimeout?.let(handler::removeCallbacks)
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Ошибка записи GATT, код=$status")
                finish()
            } else writeNext()
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == NOTIFY) notification(link, value.copyOf())
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NOTIFY) characteristic.value?.let { notification(link, it.copyOf()) }
        }
    }

    private fun notification(link: BluetoothGatt, value: ByteArray) = post {
        if (link === gatt && subscribed) {
            for (frame in parser.append(value)) session.receive(frame)
        }
    }

    private fun discover(link: BluetoothGatt) {
        discovering = true
        report("Сервисы GATT, MTU=$mtu")
        check(link.discoverServices()) { "Не удалось запустить поиск сервисов GATT" }
        setDeadline(8000) { error("Истекло ожидание сервисов GATT") }
    }

    private fun enqueue(frame: ByteArray) {
        val maximum = mtu - 3
        for (offset in frame.indices step maximum) {
            writes.add(frame.copyOfRange(offset, minOf(frame.size, offset + maximum)))
        }
        writeNext()
    }

    private fun writeNext() {
        if (writeInFlight || writes.isEmpty() || finished) return
        val link = checkNotNull(gatt)
        val characteristic = checkNotNull(writer)
        val value = writes.removeFirst()
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            link.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            link.writeCharacteristic(characteristic)
        }
        if (!started) {
            report("Android отклонил запись в GATT")
            finish()
            return
        }
        writeInFlight = true
        val timer = Runnable {
            report("Истекло ожидание записи GATT")
            finish()
        }
        writeTimeout = timer
        handler.postDelayed(timer, 3000)
    }

    private fun decode(packet: ByteArray) {
        if (shuttingDown) return
        received++
        val current = decoder ?: D3200OpusDecoder { pcm ->
            decoded++
            if (onPcm(pcm)) queued++
            val now = SystemClock.elapsedRealtime()
            if (now >= nextTelemetry) {
                nextTelemetry = now + 5000
                report("PCM16LE 16 кГц mono · Opus=$received, PCM=$decoded, в очереди=$queued")
            }
        }.also {
            decoder = it
            awaitingPcmUntil = SystemClock.elapsedRealtime() + 6000
            handler.post(drain)
            report("Декодирование Opus")
        }
        current.decode(packet)
    }

    private fun stopScan() {
        val active = scanner ?: return
        scanner = null
        active.stopScan(scanCallback)
    }

    private fun setDeadline(ms: Long, action: () -> Unit) {
        timeout?.let(handler::removeCallbacks)
        val next = Runnable { safely(action) }
        timeout = next
        handler.postDelayed(next, ms)
    }

    private fun sessionTimeout() = session.timeout()

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        handler.removeCallbacks(drain)
        session.stop()
    }

    private fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        session.discard()
        runCatching { stopScan() }.onFailure { Log.w(TAG, "Не удалось остановить сканирование") }
        runCatching { decoder?.close() }.onFailure { Log.w(TAG, "Не удалось освободить декодер") }
        val link = gatt
        gatt = null
        runCatching { link?.disconnect() }.onFailure { Log.w(TAG, "Не удалось отключить GATT") }
        runCatching { link?.close() }.onFailure { Log.w(TAG, "Не удалось закрыть GATT") }
        Log.i(TAG, "GATT закрыт · Opus=$received, PCM=$decoded, в очереди=$queued")
        onClosed()
        thread.quitSafely()
    }

    private fun post(action: () -> Unit) {
        handler.post { safely(action) }
    }

    private fun safely(action: () -> Unit) {
        if (finished) return
        try {
            action()
        } catch (failure: Exception) {
            // Platform exception messages can contain device identifiers. Protocol errors are ours.
            val detail = when (failure) {
                is SecurityException -> "Нет разрешения Bluetooth"
                is android.media.MediaCodec.CodecException -> "Ошибка MediaCodec (${failure.errorCode})"
                is java.security.GeneralSecurityException -> "Криптографическая проверка D3200 не пройдена"
                is IllegalArgumentException, is IllegalStateException -> failure.message ?: failure.javaClass.simpleName
                else -> failure.javaClass.simpleName
            }
            report(detail)
            if (shuttingDown) finish()
            else runCatching { shutdown() }.onFailure { finish() }
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        if (!shuttingDown) onStatus(message)
    }

    companion object {
        private const val TAG = "PocketHUD-D3200"
        private val SERVICE = UUID.fromString("020cf5da-0000-1000-8000-00805f9b34fb")
        private val WRITE = UUID.fromString("00007777-0000-1000-8000-00805f9b34fb")
        private val NOTIFY = UUID.fromString("00008888-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
