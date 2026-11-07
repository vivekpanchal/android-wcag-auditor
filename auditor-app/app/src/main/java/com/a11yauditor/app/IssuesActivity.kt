package com.a11yauditor.app

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.a11yauditor.app.databinding.ActivityIssuesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stored issues by screen. Search and export stay in the dashboard. */
class IssuesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIssuesBinding
    private var stored = emptyList<StoredIssue>()
    private val store get() = (application as AuditorApplication).store
    private val adapter = IssueListAdapter(loadShot = ::loadShot)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIssuesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationContentDescription(R.string.desc_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.issueList.layoutManager = LinearLayoutManager(this)
        binding.issueList.adapter = adapter
        binding.severityFilter.setOnCheckedStateChangeListener { _, _ -> show() }
        binding.clearButton.setOnClickListener { confirmClear() }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            stored = withContext(Dispatchers.IO) { store.issues() }
            show()
        }
    }

    private fun show() {
        val severity = when (binding.severityFilter.checkedChipId) {
            R.id.chipSerious -> "serious"
            R.id.chipModerate -> "moderate"
            else -> null
        }
        val rows = groupStored(stored.filter { severity == null || it.severity == severity })
        adapter.submit(rows)
        binding.emptyText.isVisible = rows.isEmpty()
        binding.clearButton.isEnabled = stored.isNotEmpty()
    }

    private fun loadShot(row: IssueRow, view: ImageView) {
        view.tag = row
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                row.shotFile?.let { store.screenshot(it) }?.let { highlightedShot(it, row.bounds) }
            }
            // The holder may have been rebound while decoding.
            if (view.tag === row && bitmap != null) {
                view.setImageBitmap(bitmap)
                view.isVisible = true
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // Through the server when it runs, so open dashboards clear too.
                        val deps = AuditorAccessibilityService.serverDeps
                        if (deps != null) deps.clearIssues() else store.clear()
                    }
                    reload()
                }
            }
            .show()
    }
}
