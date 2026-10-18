package com.k9drone.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Controller for KY UFO / FLOW_WIFI drones.
 * Protocol reverse-engineered from official KY UFO app.
 *
 * Control: UDP 7099
 * Video:   rtsp://IP:7070/webcam
 */
class DroneController(
    private val droneIp: String = "192.168.1.1",
    private val controlPort: Int = 7099
) {
    companion object {
        private const val TAG = "DroneController"
        private const val CONTROL_RATE_HZ = 20
        private const val HEARTBEAT_RATE_HZ = 1
    }

    // Stick values 0..255, center = 128
    @Volatile var roll: Int = 128
    @Volatile var pitch: Int = 128
    @Volatile var throttle: Int = 128
    @Volatile var yaw: Int = 128

    // Command byte
    @Volatile var cmd: Int = 0x00

    // Speed mode (client-side only, 0=30%, 1=60%, 2=100%)
    @Volatile var speedMode: Int = 1

    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var controlThread: Thread? = null
    private var heartbeatThread: Thread? = null

    private val address: InetAddress by lazy {
        InetAddress.getByName(droneIp)
    }

    fun start() {
        if (running.getAndSet(true)) return
        try {
            socket = DatagramSocket().apply {
                soTimeout = 1000
                broadcast = true
            }
            startControlLoop()
            startHeartbeatLoop()
            Log.i(TAG, "Controller started → $droneIp:$controlPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        controlThread?.join(500)
        heartbeatThread?.join(500)
        socket?.close()
        socket = null
        Log.i(TAG, "Controller stopped")
    }

    // --- High-level commands ---

    fun takeoff() {
        sendCommandBurst(0x01)
    }

    fun land() {
        sendCommandBurst(0x01) // same byte toggles in many firmwares
    }

    fun emergencyStop() {
        sendCommandBurst(0x02)
    }

    fun calibrateGyro() {
        sendCommandBurst(0x03)
    }

    fun toggleHeadless() {
        sendCommandBurst(0x04)
    }

    fun flip() {
        sendCommandBurst(0x05)
    }

    /** Switch camera: 1 = front, 2 = bottom */
    fun switchCamera(front: Boolean) {
        val packet = byteArrayOf(
            0x06.toByte(),
            if (front) 0x01 else 0x02
        )
        sendRaw(packet)
        Log.i(TAG, "Camera switch → ${if (front) "front" else "bottom"}")
    }

    /**
     * Probe for tilt / servo commands.
     * Many firmwares hide tilt in undocumented bytes or special cmd values.
     * We try several common patterns.
     */
    fun probeTilt(up: Boolean) {
        // Pattern A: special command byte
        val cmdA = if (up) 0x0A else 0x0B
        sendCommandBurst(cmdA)

        // Pattern B: extra byte after speed (some firmwares)
        val packetB = buildPacket().also {
            it[8] = if (up) 0x40 else 0xC0.toByte() // try non-zero speed byte as tilt
        }
        sendRaw(packetB)

        // Pattern C: 0x07 / 0x08 style (seen in some cooingdv variants)
        val packetC = byteArrayOf(
            0x07.toByte(),
            if (up) 0x01 else 0x02
        )
        sendRaw(packetC)

        Log.i(TAG, "Tilt probe → ${if (up) "UP" else "DOWN"}")
    }

    // Continuous tilt while held (for slider)
    fun setTiltContinuous(value: Int) {
        // value -100..+100
        // Try embedding in unused bytes
        val packet = buildPacket()
        packet[9] = (value + 128).coerceIn(0, 255).toByte()
        sendRaw(packet)
    }

    // --- Internal ---

    private fun startControlLoop() {
        controlThread = thread(name = "DroneControl", isDaemon = true) {
            val intervalMs = 1000L / CONTROL_RATE_HZ
            while (running.get()) {
                try {
                    val packet = buildPacket()
                    sendRaw(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Control send error: ${e.message}")
                }
                Thread.sleep(intervalMs)
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatThread = thread(name = "DroneHeartbeat", isDaemon = true) {
            val intervalMs = 1000L / HEARTBEAT_RATE_HZ
            while (running.get()) {
                try {
                    // Heartbeat: 0x03 0x66 0x10 0x00 0x00 0x00 0x00 0x00 0x99
                    val hb = byteArrayOf(
                        0x03, 0x66, 0x10,
                        0x00, 0x00, 0x00, 0x00, 0x00,
                        0x99.toByte()
                    )
                    sendRaw(hb)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
                Thread.sleep(intervalMs)
            }
        }
    }

    private fun buildPacket(): ByteArray {
        // 21 bytes: [0x03, 0x66, 0x14, roll, pitch, throttle, yaw, cmd, speed, 0x00×10, xor, 0x99]
        val packet = ByteArray(21)
        packet[0] = 0x03
        packet[1] = 0x66
        packet[2] = 0x14
        packet[3] = roll.toByte()
        packet[4] = pitch.toByte()
        packet[5] = throttle.toByte()
        packet[6] = yaw.toByte()
        packet[7] = cmd.toByte()
        packet[8] = when (speedMode) {
            0 -> 0x01 // slow
            1 -> 0x02 // medium
            else -> 0x03 // fast
        }.toByte()

        // bytes 9..18 = 0x00
        for (i in 9..18) packet[i] = 0x00

        // XOR checksum of bytes 3..8 (or sometimes 3..18) – try common variants
        var xor = 0
        for (i in 3..8) xor = xor xor (packet[i].toInt() and 0xFF)
        packet[19] = xor.toByte()
        packet[20] = 0x99.toByte()

        return packet
    }

    private fun sendCommandBurst(command: Int, count: Int = 15) {
        val oldCmd = cmd
        cmd = command
        // Send several packets quickly so the drone notices
        thread {
            repeat(count) {
                sendRaw(buildPacket())
                Thread.sleep(20)
            }
            cmd = 0x00 // clear after burst
        }
    }

    private fun connect() {
    bindToWifi()
    updateStatus("Поиск дрона…")

    fun setSticks(r: Int, p: Int, t: Int, y: Int) {
        roll = r.coerceIn(0, 255)
        pitch = p.coerceIn(0, 255)
        throttle = t.coerceIn(0, 255)
        yaw = y.coerceIn(0, 255)
    }

    fun centerSticks() {
        roll = 128
        pitch = 128
        throttle = 128
        yaw = 128
    }
}
