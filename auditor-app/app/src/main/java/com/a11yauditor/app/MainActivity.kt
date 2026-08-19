package com.a11yauditor.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

data class InstalledAppInfo(val appName: String, val packageName: String)

class MainActivity : ComponentActivity() {

    private val controlSync = ControlSync()

    // Hoisted to the Activity (not `remember`ed inside the composable) so
    // non-Compose callers below — onResume, toggleAuditing, the issue-count
    // collector — can read/write the same state Compose is observing.
    private var selectedPackage by mutableStateOf<String?>(null)
    private var manualPackageText by mutableStateOf("")
    private var statusMessage by mutableStateOf("")
    private var startStopLabel by mutableStateOf("")
    private var startStopEnabled by mutableStateOf(false)
    private var issueCountLabel by mutableStateOf("")
    private var installedApps by mutableStateOf<List<InstalledAppInfo>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        selectedPackage = prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null)
        manualPackageText = selectedPackage ?: ""
        installedApps = loadInstalledApps()
        issueCountLabel = getString(R.string.label_issue_count, AuditorAccessibilityService.sessionIssueCount.value)

        setContent {
            MaterialTheme {
                Surface {
                    AuditorScreen(
                        statusMessage = statusMessage,
                        apps = installedApps,
                        selectedPackage = selectedPackage,
                        manualPackageText = manualPackageText,
                        onManualPackageTextChange = { manualPackageText = it },
                        onManualPackageCommit = {
                            selectedPackage = manualPackageText.trim().ifBlank { null }
                        },
                        onAppSelected = { app ->
                            selectedPackage = app.packageName
                            manualPackageText = app.packageName
                        },
                        onEnableServiceClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        startStopLabel = startStopLabel,
                        startStopEnabled = startStopEnabled,
                        onStartStopClick = { toggleAuditing() },
                        issueCountLabel = issueCountLabel,
                    )
                }
            }
        }

        observeIssueCount()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Re-sent on every resume, not just the cold-start onCreate: the app
        // list push is fire-and-forget, so if it missed (server not up yet,
        // transient network blip) the dashboard's picker would otherwise stay
        // empty until the Activity was destroyed and recreated.
        installedApps = loadInstalledApps()
        controlSync.pushAppList(installedApps)
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
                statusMessage = "Pick a target app first"
                return
            }
            if (!isAccessibilityServiceEnabled()) {
                statusMessage = getString(R.string.status_service_disabled)
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

        statusMessage = when {
            !serviceEnabled -> getString(R.string.status_service_disabled)
            selectedPackage == null -> getString(R.string.status_no_target)
            else -> getString(R.string.status_service_enabled)
        }
        startStopLabel = getString(if (auditing) R.string.btn_stop else R.string.btn_start)
        startStopEnabled = serviceEnabled
    }

    private fun observeIssueCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuditorAccessibilityService.sessionIssueCount.collect { count ->
                    issueCountLabel = getString(R.string.label_issue_count, count)
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

@Composable
private fun AuditorScreen(
    statusMessage: String,
    apps: List<InstalledAppInfo>,
    selectedPackage: String?,
    manualPackageText: String,
    onManualPackageTextChange: (String) -> Unit,
    onManualPackageCommit: () -> Unit,
    onAppSelected: (InstalledAppInfo) -> Unit,
    onEnableServiceClick: () -> Unit,
    startStopLabel: String,
    startStopEnabled: Boolean,
    onStartStopClick: () -> Unit,
    issueCountLabel: String,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(statusMessage, fontWeight = FontWeight.Bold, fontSize = 14.sp)

        Button(
            onClick = onEnableServiceClick,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.btn_enable_service))
        }

        Text("Select target app", modifier = Modifier.padding(top = 20.dp))

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName == selectedPackage,
                    onClick = { onAppSelected(app) },
                )
            }
        }

        OutlinedTextField(
            value = manualPackageText,
            onValueChange = onManualPackageTextChange,
            label = { Text(stringResource(R.string.hint_manual_package)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onManualPackageCommit() }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Text(issueCountLabel, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))

        Button(
            onClick = onStartStopClick,
            enabled = startStopEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(startStopLabel)
        }
    }
}

@Composable
private fun AppRow(app: InstalledAppInfo, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(12.dp),
    ) {
        Text(app.appName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(app.packageName, fontSize = 12.sp, color = Color(0xFF888888))
    }
}
