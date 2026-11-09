package com.a11yauditor.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.a11yauditor.app.AuditorAccessibilityService.Companion.ServerStatus
import com.a11yauditor.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The setting may hold `pkg/.Class` or `pkg/full.Class`, so compare parsed names, not strings. */
internal fun isServiceInSetting(setting: String?, service: ComponentName): Boolean =
    setting?.split(':')?.any { ComponentName.unflattenFromString(it) == service } == true

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedPackage: String? = null
    // Held in a field: SharedPreferences keeps only a weak reference. Keeps the buttons live when the dashboard starts or stops auditing.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AuditorAccessibilityService.KEY_TARGET_PACKAGE) loadTarget()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val installedApps = loadInstalledApps(packageManager, packageName)
        binding.appList.layoutManager = LinearLayoutManager(this)
        val appAdapter = AppListAdapter(installedApps) { app ->
            binding.manualPackageInput.setText(app.packageName)
            pickTarget(app.packageName)
        }
        binding.appList.adapter = appAdapter
        binding.appSearchInput.doAfterTextChanged { appAdapter.filter(it.toString()) }

        binding.manualPackageInput.setOnEditorActionListener { _, actionId, event ->
            val committed = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (committed) {
                pickTarget(binding.manualPackageInput.text.toString().trim().ifBlank { null })
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

        binding.viewIssuesButton.setOnClickListener { openIssues() }

        binding.startStopButton.setOnClickListener { toggleAuditing() }

        observeIssueCount()
        observeConnectionState()
        observeServiceBound()
    }

    override fun onStart() {
        super.onStart()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        loadTarget() // the listener is off while stopped, and the dashboard may have set a target
    }

    override fun onStop() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshIssueButton()
    }

    private fun openIssues() = startActivity(Intent(this, IssuesActivity::class.java))

    private suspend fun issueGroups() = withContext(Dispatchers.IO) {
        groupStored((application as AuditorApplication).store.issues()).size
    }

    private fun refreshIssueButton() {
        lifecycleScope.launch { binding.viewIssuesButton.text = getString(R.string.btn_view_issues, issueGroups()) }
    }

    private fun offerIssues() {
        lifecycleScope.launch {
            val groups = issueGroups()
            if (groups == 0) return@launch
            Snackbar.make(binding.root, getString(R.string.snackbar_stopped, groups), Snackbar.LENGTH_LONG)
                .setAnchorView(binding.startStopButton)
                .setAction(R.string.btn_view) { openIssues() }
                .show()
        }
    }

    private fun prefs() = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)

    private fun loadTarget() {
        selectedPackage = prefs().getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null)
        val shown = selectedPackage ?: ""
        if (binding.manualPackageInput.text.toString() != shown) binding.manualPackageInput.setText(shown)
    }

    // Saved at once so the dashboard can start an audit. A running audit keeps its target until Start.
    private fun pickTarget(pkg: String?) {
        selectedPackage = pkg
        val prefs = prefs()
        if (prefs.getBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)) return
        if (prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null) != pkg) {
            prefs.edit().putString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, pkg).apply()
        }
    }

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
            offerIssues()
        } else {
            // The field, not the last Done, so a typed package is used even without pressing Done.
            val pkg = binding.manualPackageInput.text.toString().trim().ifBlank { null }
            selectedPackage = pkg
            if (pkg == null) {
                binding.statusText.text = getString(R.string.status_pick_target)
                return
            }
            if (!serviceReady()) {
                refreshStatus()
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
            !serviceReady() -> getString(R.string.status_service_not_running)
            selectedPackage == null -> getString(R.string.status_no_target)
            else -> getString(R.string.status_service_enabled)
        }
        binding.startStopButton.text = getString(if (auditing) R.string.btn_stop else R.string.btn_start)
        binding.startStopButton.isEnabled = serviceReady() || auditing
        binding.enableServiceButton.isVisible = !serviceReady()
    }

    private fun observeIssueCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.sessionIssueCount.collect { count ->
                    binding.issueCountText.text = getString(R.string.label_issue_count, count)
                    refreshIssueButton()
                }
            }
        }
    }

    private fun observeServiceBound() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.serviceBound.collect { refreshStatus() }
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
                    binding.copyUrlButton.isEnabled = running
                }
            }
        }
    }

    private fun serviceReady() = isAccessibilityServiceEnabled() && AuditorAccessibilityService.serviceBound.value

    private fun isAccessibilityServiceEnabled() = isServiceInSetting(
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        ComponentName(this, AuditorAccessibilityService::class.java),
    )
}
