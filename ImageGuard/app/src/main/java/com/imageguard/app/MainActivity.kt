package com.imageguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var statusText: TextView
    private lateinit var setupCard: MaterialCardView
    private lateinit var btnStorage: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button
    private lateinit var switchAuto: MaterialSwitch
    private lateinit var thresholdLabel: TextView
    private lateinit var slider: Slider
    private lateinit var scanText: TextView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var btnScan: Button
    private lateinit var btnQuarantine: Button

    private val legacyStorageRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store.get(this)
        GuardService.createChannels(this)

        statusText = findViewById(R.id.statusText)
        setupCard = findViewById(R.id.setupCard)
        btnStorage = findViewById(R.id.btnStorage)
        btnNotifications = findViewById(R.id.btnNotifications)
        btnBattery = findViewById(R.id.btnBattery)
        switchAuto = findViewById(R.id.switchAuto)
        thresholdLabel = findViewById(R.id.thresholdLabel)
        slider = findViewById(R.id.sliderThreshold)
        scanText = findViewById(R.id.scanText)
        scanProgress = findViewById(R.id.scanProgress)
        btnScan = findViewById(R.id.btnScan)
        btnQuarantine = findViewById(R.id.btnQuarantine)

        btnStorage.setOnClickListener { requestStorage() }
        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        switchAuto.isChecked = store.autoProtect
        switchAuto.setOnCheckedChangeListener { button, checked ->
            if (checked && !Permissions.hasStorage(this)) {
                button.isChecked = false
                Toast.makeText(this, R.string.need_storage, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            store.autoProtect = checked
            if (checked) {
                GuardService.start(this)
            } else if (!ScanState.progress.value.running) {
                GuardService.start(this, GuardService.ACTION_STOP)
            }
            refresh()
        }

        slider.value = (store.threshold * 100).roundToInt().coerceIn(30, 90)
            .let { it - it % 5 }.toFloat()
        updateThresholdLabel(slider.value)
        slider.addOnChangeListener { _, value, _ ->
            store.threshold = value / 100f
            updateThresholdLabel(value)
        }

        btnScan.setOnClickListener {
            if (ScanState.progress.value.running) {
                GuardService.start(this, GuardService.ACTION_CANCEL_SCAN)
            } else if (!Permissions.hasStorage(this)) {
                Toast.makeText(this, R.string.need_storage, Toast.LENGTH_SHORT).show()
            } else {
                GuardService.start(this, GuardService.ACTION_FULL_SCAN)
            }
        }
        btnQuarantine.setOnClickListener {
            startActivity(Intent(this, QuarantineActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScanState.progress.collect { renderScan(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Make sure protection is running whenever the app is opened (e.g. after Samsung killed it).
        if (store.autoProtect && Permissions.hasStorage(this)) GuardService.start(this)
    }

    private fun refresh() {
        val storage = Permissions.hasStorage(this)
        val notifications = Permissions.hasNotifications(this)
        val battery = Permissions.ignoresBatteryOptimizations(this)

        btnStorage.visibility = if (storage) View.GONE else View.VISIBLE
        btnNotifications.visibility = if (notifications) View.GONE else View.VISIBLE
        btnBattery.visibility = if (battery) View.GONE else View.VISIBLE
        setupCard.visibility = if (storage && notifications && battery) View.GONE else View.VISIBLE

        statusText.setText(
            when {
                !storage -> R.string.status_needs_setup
                store.autoProtect -> R.string.status_protecting
                else -> R.string.status_off
            }
        )
        btnQuarantine.text = getString(R.string.btn_quarantine, store.quarantined().size)
        renderScan(ScanState.progress.value)
    }

    private fun renderScan(p: ScanProgress) {
        if (p.running) {
            scanProgress.visibility = View.VISIBLE
            scanProgress.max = maxOf(p.total, 1)
            scanProgress.setProgressCompat(p.done, true)
            scanText.text = getString(R.string.scan_running, p.done, p.total, p.removed)
            btnScan.setText(R.string.btn_cancel_scan)
        } else {
            scanProgress.visibility = View.GONE
            btnScan.setText(R.string.btn_scan)
            scanText.text = when {
                p.total > 0 -> getString(R.string.scan_done, p.done, p.removed)
                store.lastFullScan > 0 -> getString(
                    R.string.scan_last,
                    DateUtils.getRelativeTimeSpanString(store.lastFullScan)
                )
                else -> getString(R.string.scan_never)
            }
            if (::btnQuarantine.isInitialized) {
                btnQuarantine.text = getString(R.string.btn_quarantine, store.quarantined().size)
            }
        }
    }

    private fun updateThresholdLabel(value: Float) {
        thresholdLabel.text = getString(R.string.threshold_label, value.roundToInt())
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.storage_settings_hint, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStorageRequest.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    @SuppressLint("BatteryLife") // Sideloaded protection app: staying alive is the whole point.
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
