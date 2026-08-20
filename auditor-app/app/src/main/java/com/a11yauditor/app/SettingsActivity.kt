package com.a11yauditor.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.a11yauditor.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * Manual override for the persistent /ws/device connection (DeviceSocket) —
 * lets you pause reporting/control without disabling the accessibility
 * service entirely. The choice is saved to prefs so it survives the service
 * restarting; when the service is currently running, toggling here also
 * takes effect immediately via AuditorAccessibilityService.instance.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.socketToggleButton.setOnClickListener { toggleSocket() }
        observeConnectionState()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleSocket() {
        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        val currentlyEnabled = prefs.getBoolean(AuditorAccessibilityService.KEY_SOCKET_ENABLED, true)
        val service = AuditorAccessibilityService.instance
        if (currentlyEnabled) {
            if (service != null) {
                service.disconnectDeviceSocket()
            } else {
                prefs.edit().putBoolean(AuditorAccessibilityService.KEY_SOCKET_ENABLED, false).apply()
            }
        } else {
            if (service != null) {
                service.connectDeviceSocket()
            } else {
                prefs.edit().putBoolean(AuditorAccessibilityService.KEY_SOCKET_ENABLED, true).apply()
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(AuditorAccessibilityService.KEY_SOCKET_ENABLED, true)
        val serviceRunning = AuditorAccessibilityService.instance != null

        binding.socketToggleButton.text = getString(
            if (enabled) R.string.btn_disconnect else R.string.btn_connect
        )
        binding.serviceStateText.text = getString(
            if (serviceRunning) R.string.settings_service_running else R.string.settings_service_not_running
        )
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.deviceSocketConnected.collect { connected ->
                    binding.connectionStatusText.text = getString(
                        if (connected) R.string.settings_status_connected else R.string.settings_status_disconnected
                    )
                }
            }
        }
    }
}
