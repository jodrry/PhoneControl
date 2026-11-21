package com.example.phonecontrol.agent

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.phonecontrol.agent.databinding.ActivityAgentBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentBinding
    private lateinit var projectionManager: MediaProjectionManager
    private var pendingStartAfterNotification = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                AgentService.start(this, result.resultCode, data)
                toast(getString(R.string.toast_starting))
            } catch (t: Throwable) {
                toast(getString(R.string.toast_service_start_failed) + ": ${t.message}")
            }
        } else {
            toast(getString(R.string.toast_projection_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast(getString(R.string.notification_rationale))
        updatePermissionLabels()
        if (pendingStartAfterNotification) {
            pendingStartAfterNotification = false
            // A foreground service can still run without the notification
            // permission, so do not block screen capture on a denial.
            launchScreenCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.startButton.setOnClickListener { requestStart() }
        binding.stopButton.setOnClickListener { AgentService.stop(this) }
        binding.accessibilityTag.setOnClickListener { openAccessibilitySettings() }
        binding.notificationTag.setOnClickListener { requestNotificationPermission() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentState.state.collect { render(it) }
            }
        }

        showOnboardingIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionLabels()
    }

    private fun requestStart() {
        val state = AgentState.state.value
        if (state.isSharing || state.isPreparing) {
            toast(getString(R.string.toast_already_sharing))
            return
        }
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.toast_accessibility_missing))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterNotification = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingStartAfterNotification = false
            launchScreenCapture()
        }
    }

    private fun launchScreenCapture() {
        try {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            toast(getString(R.string.toast_service_start_failed) + ": ${t.message}")
        }
    }

    private fun requestNotificationPermission() {
        pendingStartAfterNotification = false
        if (hasNotificationPermission()) {
            toast(getString(R.string.notification_already_granted))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toast(getString(R.string.notification_already_granted))
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            toast(t.message ?: getString(R.string.toast_service_start_failed))
        }
    }

    private fun render(state: AgentUiState) {
        val (statusTextRes, statusColorRes) = when {
            state.error != null -> R.string.status_error to R.color.danger
            state.isSharing -> R.string.status_sharing to R.color.success
            state.isPreparing -> R.string.status_preparing to R.color.warning
            else -> R.string.status_idle to R.color.text_secondary
        }
        val statusColor = ContextCompat.getColor(this, statusColorRes)
        binding.statusText.setText(statusTextRes)
        binding.statusText.setTextColor(statusColor)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)

        binding.ipText.text = state.localIp.ifBlank { "--" }
        binding.portText.text = state.port.toString()
        binding.connectionsText.text = state.connections.toString()

        binding.screenText.text = getString(
            R.string.metric_screen,
            if (state.screenWidth > 0 && state.screenHeight > 0) {
                getString(R.string.value_screen, state.screenWidth, state.screenHeight)
            } else {
                getString(R.string.value_unknown)
            }
        )
        binding.captureText.text = getString(
            R.string.metric_capture,
            if (state.captureWidth > 0 && state.captureHeight > 0) {
                getString(R.string.value_capture, state.captureWidth, state.captureHeight)
            } else {
                getString(R.string.value_unknown)
            }
        )
        binding.mdnsText.text = getString(
            R.string.metric_mdns,
            when (state.mdnsState) {
                MdnsState.IDLE -> getString(R.string.value_not_running)
                MdnsState.ADVERTISING -> getString(R.string.value_advertising)
                MdnsState.FAILED -> getString(R.string.value_mdns_failed)
            }
        )

        val accessibilityOn = state.accessibilityConnected
        binding.accessibilityTag.setText(
            if (accessibilityOn) R.string.tag_accessibility_on else R.string.tag_accessibility_pending
        )
        styleTag(binding.accessibilityTag, accessibilityOn, useWarning = false)

        val notificationOn = hasNotificationPermission()
        binding.notificationTag.setText(
            if (notificationOn) R.string.tag_notification_on else R.string.tag_notification_pending
        )
        styleTag(binding.notificationTag, notificationOn, useWarning = true)

        binding.errorText.visibility = if (state.error != null) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.errorText.text = state.error?.let { getString(R.string.error_prefix, it) }

        binding.startButton.isEnabled = !state.isPreparing && !state.isSharing
        binding.stopButton.isEnabled = state.isPreparing || state.isSharing
    }

    /** 已就绪显示绿色胶囊，否则按轻重用橙色（可授权）或红色（缺关键服务）。 */
    private fun styleTag(tag: TextView, ready: Boolean, useWarning: Boolean) {
        val backgroundRes = when {
            ready -> R.drawable.bg_tag_on
            useWarning -> R.drawable.bg_tag_warn
            else -> R.drawable.bg_tag_off
        }
        val colorRes = when {
            ready -> R.color.success
            useWarning -> R.color.warning
            else -> R.color.danger
        }
        tag.setBackgroundResource(backgroundRes)
        tag.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updatePermissionLabels() {
        if (::binding.isInitialized) {
            render(AgentState.state.value)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, RemoteAccessibilityService::class.java)
        return enabled.split(':').any { flatten ->
            ComponentName.unflattenFromString(flatten) == expected
        }
    }

    private fun showOnboardingIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_message)
            .setPositiveButton(R.string.onboarding_positive) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.onboarding_negative, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "agent_prefs"
        const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
    }
}
