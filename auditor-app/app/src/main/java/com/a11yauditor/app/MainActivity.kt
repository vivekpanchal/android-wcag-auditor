package com.a11yauditor.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.a11yauditor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedPackage: String? = null
    private val controlSync = ControlSync()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        selectedPackage = prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null)
        binding.manualPackageInput.setText(selectedPackage ?: "")

        val installedApps = loadInstalledApps()
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = AppListAdapter(installedApps) { app ->
            selectedPackage = app.packageName
            binding.manualPackageInput.setText(app.packageName)
        }
        controlSync.pushAppList(installedApps)

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

        binding.startStopButton.setOnClickListener { toggleAuditing() }

        observeIssueCount()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleAuditing() {
        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        val currentlyAuditing = prefs.getBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)

        if (currentlyAuditing) {
            prefs.edit()
                .putBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false)
                .putLong(AuditorAccessibilityService.KEY_LOCAL_INTENT_AT, System.currentTimeMillis())
                .apply()
            controlSync.pushControl(selectedPackage, auditing = false)
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
                .putLong(AuditorAccessibilityService.KEY_LOCAL_INTENT_AT, System.currentTimeMillis())
                .apply()
            controlSync.pushControl(pkg, auditing = true)
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AuditorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun loadInstalledApps(): List<InstalledAppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { InstalledAppInfo(it.loadLabel(packageManager).toString(), it.packageName) }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }
}
