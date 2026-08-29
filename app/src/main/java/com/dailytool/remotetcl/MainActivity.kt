package com.dailytool.remotetcl

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dailytool.remotetcl.databinding.ActivityMainBinding
import com.dailytool.remotetcl.discovery.TvDiscoveryManager
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import com.dailytool.remotetcl.model.TvType
import com.dailytool.remotetcl.protocol.TvRemoteManager
import com.dailytool.remotetcl.ui.DeviceListAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var remoteManager: TvRemoteManager
    private lateinit var discoveryManager: TvDiscoveryManager

    private var scanDialog: Dialog? = null
    private var deviceAdapter: DeviceListAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remoteManager = TvRemoteManager(this)
        discoveryManager = TvDiscoveryManager(this)

        setupSavedTv()
        setupButtons()
    }

    private fun setupSavedTv() {
        val savedDevice = remoteManager.currentDevice
        if (savedDevice != null) {
            updateUiConnected(savedDevice)
            lifecycleScope.launch {
                remoteManager.connect(savedDevice)
            }
        } else {
            updateUiDisconnected()
        }
    }

    private fun updateUiConnected(device: TvDevice) {
        binding.tvDeviceName.text = device.name
        binding.tvDeviceStatus.text = getString(R.string.status_connected, device.ipAddress)
        binding.viewStatusIndicator.setBackgroundResource(R.drawable.bg_badge_connected)
    }

    private fun updateUiDisconnected() {
        binding.tvDeviceName.text = getString(R.string.btn_select_tv)
        binding.tvDeviceStatus.text = getString(R.string.status_disconnected)
        binding.viewStatusIndicator.setBackgroundResource(R.drawable.bg_badge_disconnected)
    }

    private fun setupButtons() {
        // TV Header / Scan triggers
        binding.layoutTvHeader.setOnClickListener { showScanDialog() }
        binding.btnScanTv.setOnClickListener { showScanDialog() }
        binding.btnManualIp.setOnClickListener { showManualIpDialog() }

        // Power
        binding.btnPower.setOnClickListener { sendKey(TvKey.POWER) }

        // Top navigation
        binding.btnBack.setOnClickListener { sendKey(TvKey.BACK) }
        binding.btnHome.setOnClickListener { sendKey(TvKey.HOME) }
        binding.btnMenu.setOnClickListener { sendKey(TvKey.MENU) }

        // D-Pad
        binding.btnDpadUp.setOnClickListener { sendKey(TvKey.DPAD_UP) }
        binding.btnDpadDown.setOnClickListener { sendKey(TvKey.DPAD_DOWN) }
        binding.btnDpadLeft.setOnClickListener { sendKey(TvKey.DPAD_LEFT) }
        binding.btnDpadRight.setOnClickListener { sendKey(TvKey.DPAD_RIGHT) }
        binding.btnDpadOk.setOnClickListener { sendKey(TvKey.DPAD_CENTER) }

        // Volume & Channel & Media
        binding.btnVolUp.setOnClickListener { sendKey(TvKey.VOLUME_UP) }
        binding.btnVolDown.setOnClickListener { sendKey(TvKey.VOLUME_DOWN) }
        binding.btnMute.setOnClickListener { sendKey(TvKey.MUTE) }
        binding.btnChUp.setOnClickListener { sendKey(TvKey.CHANNEL_UP) }
        binding.btnChDown.setOnClickListener { sendKey(TvKey.CHANNEL_DOWN) }
        binding.btnPlayPause.setOnClickListener { sendKey(TvKey.PLAY_PAUSE) }

        // App Shortcuts
        binding.btnNetflix.setOnClickListener { sendKey(TvKey.NETFLIX) }
        binding.btnYoutube.setOnClickListener { sendKey(TvKey.YOUTUBE) }
        binding.btnPrime.setOnClickListener { sendKey(TvKey.PRIME_VIDEO) }
    }

    private fun sendKey(key: TvKey) {
        vibrateFeedback()
        if (remoteManager.currentDevice == null) {
            Toast.makeText(this, "Silakan pilih atau hubungkan TV terlebih dahulu", Toast.LENGTH_SHORT).show()
            showScanDialog()
            return
        }

        lifecycleScope.launch {
            val success = remoteManager.sendKey(key)
            if (!success) {
                // Sinyal gagal terkirim
                // Bisa dipicu jika TV baru bangun tidur atau ganti IP
            }
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            }
        } catch (_: Exception) {}
    }

    private fun showScanDialog() {
        val dialog = Dialog(this)
        scanDialog = dialog
        dialog.setContentView(R.layout.dialog_scan_tv)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rvDevices = dialog.findViewById<RecyclerView>(R.id.rv_devices)
        val progressScan = dialog.findViewById<ProgressBar>(R.id.progress_scan)
        val tvScanHint = dialog.findViewById<TextView>(R.id.tv_scan_hint)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tv_empty_devices)
        val btnRescan = dialog.findViewById<Button>(R.id.btn_dialog_rescan)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_dialog_cancel)

        val adapter = DeviceListAdapter { selectedDevice ->
            onDeviceSelected(selectedDevice)
            dialog.dismiss()
        }
        deviceAdapter = adapter
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = adapter

        btnCancel.setOnClickListener {
            discoveryManager.stopDiscovery()
            dialog.dismiss()
        }

        btnRescan.setOnClickListener {
            startScan(adapter, progressScan, tvScanHint, tvEmpty)
        }

        dialog.setOnDismissListener {
            discoveryManager.stopDiscovery()
        }

        dialog.show()
        startScan(adapter, progressScan, tvScanHint, tvEmpty)
    }

    private fun startScan(
        adapter: DeviceListAdapter,
        progress: ProgressBar,
        hint: TextView,
        emptyView: TextView
    ) {
        progress.visibility = View.VISIBLE
        hint.text = getString(R.string.status_searching)
        emptyView.visibility = View.GONE
        adapter.setDevices(emptyList())

        discoveryManager.startDiscovery(object : TvDiscoveryManager.DiscoveryCallback {
            override fun onDiscoveryStarted() {
                progress.visibility = View.VISIBLE
                hint.text = getString(R.string.status_searching)
            }

            override fun onDeviceFound(device: TvDevice) {
                adapter.addDevice(device)
                emptyView.visibility = View.GONE
            }

            override fun onDiscoveryFinished(devices: List<TvDevice>) {
                progress.visibility = View.GONE
                hint.text = "Selesai memindai (${devices.size} TV ditemukan)"
                if (devices.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun onDeviceSelected(device: TvDevice) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Menghubungkan ke ${device.name}...", Toast.LENGTH_SHORT).show()
            val connected = remoteManager.connect(device)
            if (connected) {
                updateUiConnected(device)
                Toast.makeText(this@MainActivity, "Berhasil terhubung ke ${device.name}", Toast.LENGTH_SHORT).show()
            } else if (device.type == TvType.ANDROID_TV) {
                // Start Pairing Flow for Android TV
                showPinPairingDialog(device)
            } else {
                updateUiConnected(device)
            }
        }
    }

    private fun showPinPairingDialog(device: TvDevice) {
        lifecycleScope.launch {
            val pairingStarted = remoteManager.androidTvProtocol.startPairing(device)
            if (!pairingStarted) {
                Toast.makeText(this@MainActivity, "Gagal memulai pairing dengan TV", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dialog = Dialog(this@MainActivity)
            dialog.setContentView(R.layout.dialog_pin_pair)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val etPin = dialog.findViewById<EditText>(R.id.et_pair_pin)
            val btnConfirm = dialog.findViewById<Button>(R.id.btn_pin_confirm)
            val btnCancel = dialog.findViewById<Button>(R.id.btn_pin_cancel)

            btnCancel.setOnClickListener { dialog.dismiss() }

            btnConfirm.setOnClickListener {
                val pin = etPin.text.toString().trim()
                if (pin.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Masukkan PIN dari layar TV", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    val verified = remoteManager.androidTvProtocol.verifyPin(pin)
                    if (verified) {
                        remoteManager.connect(device)
                        updateUiConnected(device)
                        Toast.makeText(this@MainActivity, "Pairing Berhasil!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, "PIN Salah atau Pairing Kadaluarsa", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            dialog.show()
        }
    }

    private fun showManualIpDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_manual_ip)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etIp = dialog.findViewById<EditText>(R.id.et_ip_address)
        val rgType = dialog.findViewById<RadioGroup>(R.id.rg_tv_type)
        val rbRoku = dialog.findViewById<RadioButton>(R.id.rb_roku_tv)
        val btnConnect = dialog.findViewById<Button>(R.id.btn_ip_connect)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_ip_cancel)

        remoteManager.currentDevice?.let {
            etIp.setText(it.ipAddress)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConnect.setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Masukkan IP Address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tvType = if (rbRoku.isChecked) TvType.ROKU_TV else TvType.ANDROID_TV
            val manualDevice = TvDevice(
                name = "TCL TV ($ip)",
                ipAddress = ip,
                port = if (tvType == TvType.ROKU_TV) 8060 else 6466,
                type = tvType
            )

            onDeviceSelected(manualDevice)
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
        remoteManager.androidTvProtocol.disconnect()
    }
}
