package com.a11yauditor.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.a11yauditor.app.AuditorAccessibilityService.Companion.ServerStatus
import com.a11yauditor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedPackage: String? = null
    // Held in a field: SharedPreferences keeps only a weak reference. Keeps the buttons live when the dashboard starts or stops auditing.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        selectedPackage = prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null)
        binding.manualPackageInput.setText(selectedPackage ?: "")

        val installedApps = loadInstalledApps(packageManager, packageName)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = AppListAdapter(installedApps) { app ->
            selectedPackage = app.packageName
            binding.manualPackageInput.setText(app.packageName)
        }

        binding.manualPackageInput.setOnEditorActionListener { _, actionId, event ->
            val committed = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (committed) {
                selectedPackage = binding.manualPackageInput.text.toString().trim().ifBlank { null }
            }
            committed
        }

        binding.enableServiceButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.openDashboardButton.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl())))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
            }
        }

        binding.copyUrlButton.setOnClickListener { copyDashboardUrl() }

        binding.startStopButton.setOnClickListener { toggleAuditing() }

        observeIssueCount()
        observeConnectionState()
    }

    override fun onStart() {
        super.onStart()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStop() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun prefs() = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)

    private fun dashboardUrl() =
        "${AuditorAccessibilityService.DASHBOARD_URL}?t=${DashboardToken.getOrCreate(prefs())}"

    private fun copyDashboardUrl() {
        val clip = ClipData.newPlainText(getString(R.string.label_clip_dashboard_url), dashboardUrl())
        // The URL carries the token, so keep it out of clipboard previews.
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.toast_url_copied, Toast.LENGTH_LONG).show()
    }

    private fun toggleAuditing() {
        val prefs = prefs()
        val currentlyAuditing = prefs.getBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)

        if (currentlyAuditing) {
            prefs.edit()
                .putBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)
                .apply()
        } else {
            val pkg = selectedPackage
            if (pkg.isNullOrBlank()) {
                binding.statusText.text = "Pick a target app first"
                return
            }
            if (!isAccessibilityServiceEnabled()) {
                binding.statusText.text = getString(R.string.status_service_disabled)
                return
            }
            AuditorAccessibilityService.sessionIssueCount.value = 0
            prefs.edit()
                .putString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, pkg)
                .putBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, true)
                .apply()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        val auditing = prefs.getBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)
        val serviceEnabled = isAccessibilityServiceEnabled()

        binding.statusText.text = when {
            !serviceEnabled -> getString(R.string.status_service_disabled)
            selectedPackage == null -> getString(R.string.status_no_target)
            else -> getString(R.string.status_service_enabled)
        }
        binding.startStopButton.text = getString(if (auditing) R.string.btn_stop else R.string.btn_start)
        binding.startStopButton.isEnabled = serviceEnabled
    }

    private fun observeIssueCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.sessionIssueCount.collect { count ->
                    binding.issueCountText.text = getString(R.string.label_issue_count, count)
                }
            }
        }
    }

    // Live, not just refreshed on resume/click, so the status follows the service starting or stopping.
    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.serverStatus.collect { status ->
                    val running = status == ServerStatus.Running
                    binding.connectionStatusText.text = getString(
                        when (status) {
                            ServerStatus.Running -> R.string.label_dashboard_running
                            ServerStatus.Failed -> R.string.label_dashboard_failed
                            ServerStatus.Stopped -> R.string.label_dashboard_off
                        }
                    )
                    binding.connectionStatusText.setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (running) R.color.status_connected else R.color.status_disconnected
                        )
                    )
                    binding.openDashboardButton.isEnabled = running
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AuditorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
