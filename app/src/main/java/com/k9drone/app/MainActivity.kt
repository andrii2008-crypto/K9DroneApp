package com.k9drone.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.k9drone.app.databinding.ActivityMainBinding
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var controller: DroneController? = null

    // Possible IPs used by FLOW / KY UFO drones
    private val possibleIps = listOf(
        "192.168.1.1",
        "192.168.0.1",
        "192.168.4.1",
        "192.168.169.1"
    )
    private var currentIp = "192.168.1.1"
    private var frontCamera = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupJoysticks()
        setupButtons()
        updateStatus("Нажми «Подключить» после подключения к Wi-Fi дрона")
    }

    private fun setupJoysticks() {
        // Left stick: Throttle (Y) + Yaw (X)
        binding.joystickLeft.listener = object : JoystickView.Listener {
            override fun onMove(x: Float, y: Float) {
                val yaw = ((x + 1f) / 2f * 255f).toInt()          // -1..1 → 0..255
                val throttle = ((y + 1f) / 2f * 255f).toInt()
                controller?.let {
                    it.yaw = yaw
                    it.throttle = throttle
                }
            }
            override fun onRelease() {
                controller?.let {
                    it.yaw = 128
                    it.throttle = 128
                }
            }
        }

        // Right stick: Roll (X) + Pitch (Y)
        binding.joystickRight.listener = object : JoystickView.Listener {
            override fun onMove(x: Float, y: Float) {
                val roll = ((x + 1f) / 2f * 255f).toInt()
                val pitch = ((y + 1f) / 2f * 255f).toInt()
                controller?.let {
                    it.roll = roll
                    it.pitch = pitch
                }
            }
            override fun onRelease() {
                controller?.let {
                    it.roll = 128
                    it.pitch = 128
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            if (controller == null) {
                connect()
            } else {
                disconnect()
            }
        }

        binding.btnTakeoff.setOnClickListener {
            controller?.takeoff()
            Toast.makeText(this, "Взлёт", Toast.LENGTH_SHORT).show()
        }

        binding.btnLand.setOnClickListener {
            controller?.land()
            Toast.makeText(this, "Посадка", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmergency.setOnClickListener {
            controller?.emergencyStop()
            Toast.makeText(this, "EMERGENCY STOP", Toast.LENGTH_SHORT).show()
        }

        binding.btnCameraSwitch.setOnClickListener {
            frontCamera = !frontCamera
            controller?.switchCamera(frontCamera)
            Toast.makeText(
                this,
                if (frontCamera) "Передняя камера" else "Нижняя камера",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnCalibrate.setOnClickListener {
            controller?.calibrateGyro()
            Toast.makeText(this, "Калибровка гироскопа", Toast.LENGTH_SHORT).show()
        }

        // Tilt probes
        binding.btnTiltUp.setOnClickListener {
            controller?.probeTilt(up = true)
            Toast.makeText(this, "Наклон ▲ (probe)", Toast.LENGTH_SHORT).show()
        }
        binding.btnTiltDown.setOnClickListener {
            controller?.probeTilt(up = false)
            Toast.makeText(this, "Наклон ▼ (probe)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindToWifi() {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.bindProcessToNetwork(n)
                return
            }
        }
    }

    private fun connect() {
        bindToWifi()
        updateStatus("Поиск дрона…")
        // Try common IPs
        Thread {
            var found: String? = null
            for (ip in possibleIps) {
                try {
                    val addr = InetAddress.getByName(ip)
                    if (addr.isReachable(800)) {
                        found = ip
                        break
                    }
                } catch (_: Exception) {}
            }
            // Fallback to first IP even if ping fails (many drones block ICMP)
            val ip = found ?: possibleIps[0]
            runOnUiThread {
                currentIp = ip
                binding.tvIp.text = ip
                startController(ip)
                startVideo(ip)
            }
        }.start()
    }

    private fun startController(ip: String) {
        controller?.stop()
        controller = DroneController(droneIp = ip).also { it.start() }
        binding.btnConnect.text = "Отключить"
        updateStatus("Управление активно ($ip)")
    }

    private fun startVideo(ip: String) {
        player?.release()
        val url = "rtsp://$ip:7070/webcam"
        Log.i("K9Drone", "Starting video: $url")

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val mediaItem = MediaItem.fromUri(url)
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(false) // try UDP first
                .createMediaSource(mediaItem)
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("K9Drone", "Video error: ${error.message}")
                    updateStatus("Видео: ошибка (${error.errorCodeName})")
                    // Retry with TCP
                    tryTcpVideo(ip)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        updateStatus("Видео OK + управление")
                    }
                }
            })
        }
    }

    private fun tryTcpVideo(ip: String) {
        player?.release()
        val url = "rtsp://$ip:7070/webcam"
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url))
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun disconnect() {
        controller?.stop()
        controller = null
        player?.release()
        player = null
        binding.playerView.player = null
        binding.btnConnect.text = "Подключить"
        updateStatus("Отключено")
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
