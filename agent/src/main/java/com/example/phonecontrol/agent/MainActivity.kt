package com.example.phonecontrol.agent

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        binding.notificationButton.setOnClickListener { requestNotificationPermission() }

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
        binding.statusText.text = when {
            state.error != null -> getString(R.string.status_error)
            state.isSharing -> getString(R.string.status_sharing)
            state.isPreparing -> getString(R.string.status_preparing)
            else -> getString(R.string.status_idle)
        }
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    state.error != null -> R.color.danger
                    state.isSharing -> R.color.success
                    else -> R.color.warning
                }
            )
        )

        binding.ipText.text = state.localIp.ifBlank { "--" }
        binding.portText.text = state.port.toString()
        binding.connectionsText.text = getString(R.string.value_connections, state.connections)
        binding.screenText.text = if (state.screenWidth > 0 && state.screenHeight > 0) {
            getString(R.string.value_screen, state.screenWidth, state.screenHeight)
        } else {
            getString(R.string.value_unknown)
        }
        binding.captureText.text = if (state.captureWidth > 0 && state.captureHeight > 0) {
            getString(R.string.value_capture, state.captureWidth, state.captureHeight)
        } else {
            getString(R.string.value_unknown)
        }
        binding.accessibilityText.text = if (state.accessibilityConnected) {
            getString(R.string.value_enabled)
        } else {
            getString(R.string.value_disabled)
        }
        binding.accessibilityText.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.accessibilityConnected) R.color.success else R.color.warning
            )
        )

        binding.notificationText.text = if (hasNotificationPermission()) {
            getString(R.string.value_granted)
        } else {
            getString(R.string.value_not_granted)
        }
        binding.notificationText.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasNotificationPermission()) R.color.success else R.color.warning
            )
        )

        binding.mdnsText.text = when (state.mdnsState) {
            MdnsState.IDLE -> getString(R.string.value_not_running)
            MdnsState.ADVERTISING -> getString(R.string.value_advertising)
            MdnsState.FAILED -> getString(R.string.value_mdns_failed)
        }

        binding.errorText.visibility = if (state.error != null) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.errorText.text = state.error?.let { getString(R.string.error_prefix, it) }

        binding.startButton.isEnabled = !state.isPreparing && !state.isSharing
        binding.stopButton.isEnabled = state.isPreparing || state.isSharing
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
