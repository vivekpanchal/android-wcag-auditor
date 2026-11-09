package com.a11yauditor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.a11yauditor.app.databinding.ItemIssueBinding
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** One list row in the overlay panel or the Issues screen. */
data class IssueRow(
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val element: String,
    val description: String,
    val fix: String?,
    val bounds: Bounds?,
    val shotFile: String? = null,
    val count: Int = 1,
    /** Set on the first row of each screen group. */
    val header: String? = null,
)

/** Plain text of a stored suggestedFix, with the detected framework's snippet first. */
internal fun parseFix(json: String?): String? = try {
    json?.let { JSONObject(it) }?.let { o ->
        val snippets = listOf("Views" to o.optString("views"), "Compose" to o.optString("compose"))
            .filter { it.second.isNotBlank() }
            .sortedBy { it.first.lowercase() != o.optString("framework") }
            .map { "${it.first}:\n${it.second}" }
        (listOf(o.optString("summary")).filter { it.isNotBlank() } + snippets).joinToString("\n\n").ifBlank { null }
    }
} catch (e: JSONException) {
    null
}

fun AuditIssue.toRow() = IssueRow(
    severity, wcagSc, wcagLevel, elementDescription, description,
    parseFix(ApiJson.suggestedFix(fix, framework)),
    bounds?.let { Bounds(it.left, it.top, it.width, it.height) },
)

/**
 * Rows repeat on every rescan because there is no dedup until Phase 3, so identical findings
 * collapse into one row with a count. Display only: stored rows are untouched.
 */
fun groupStored(issues: List<StoredIssue>): List<IssueRow> =
    issues.groupBy { it.packageName to (it.screen ?: "Unknown screen") }.flatMap { (key, onScreen) ->
        onScreen.groupBy { Triple(it.elementDescription, it.wcagSc, it.description) }.values
            .map { same ->
                val i = same.lastOrNull { it.screenshotFile != null } ?: same.last()
                IssueRow(
                    i.severity, i.wcagSc, i.wcagLevel, i.elementDescription, i.description,
                    parseFix(i.suggestedFix), i.bounds, i.screenshotFile, same.size,
                )
            }
            .sortedBy { it.severity != "serious" }
            .mapIndexed { index, row -> if (index == 0) row.copy(header = "${key.second} · ${key.first}") else row }
    }

/** Screenshot scaled down with the flagged element outlined. */
internal fun highlightedShot(file: File, bounds: Bounds?): Bitmap? {
    val scale = 3
    val bitmap = BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply { inSampleSize = scale; inMutable = true },
    ) ?: return null
    if (bounds != null) {
        val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
        Canvas(bitmap).drawRect(
            bounds.x / scale.toFloat(), bounds.y / scale.toFloat(),
            (bounds.x + bounds.width) / scale.toFloat(), (bounds.y + bounds.height) / scale.toFloat(),
            paint,
        )
    }
    return bitmap
}

class IssueListAdapter(
    private val onExpand: (IssueRow) -> Unit = {},
    private val loadShot: ((IssueRow, ImageView) -> Unit)? = null,
) : RecyclerView.Adapter<IssueListAdapter.ViewHolder>() {

    private var rows = emptyList<IssueRow>()
    private var expanded = RecyclerView.NO_POSITION

    class ViewHolder(val binding: ItemIssueBinding) : RecyclerView.ViewHolder(binding.root)

    @android.annotation.SuppressLint("NotifyDataSetChanged") // the whole list is replaced
    fun submit(list: List<IssueRow>) {
        rows = list
        expanded = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemIssueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val isExpanded = position == expanded
        val b = holder.binding
        val context = b.root.context
        b.groupHeader.isVisible = row.header != null
        b.groupHeader.text = row.header
        b.severityText.text = context.getString(
            if (row.count > 1) R.string.issue_meta_count else R.string.issue_meta,
            row.severity.replaceFirstChar { it.uppercase() }, row.wcagSc, row.wcagLevel, row.count,
        )
        b.severityText.setTextColor(
            ContextCompat.getColor(
                context,
                if (row.severity == "serious") R.color.severity_serious else R.color.severity_moderate,
            ),
        )
        b.elementText.text = row.element
        b.descriptionText.text = row.description
        b.fixText.text = row.fix
        b.fixText.isVisible = isExpanded && row.fix != null
        b.shot.tag = null
        b.shot.isVisible = false
        b.shot.contentDescription = context.getString(R.string.desc_issue_shot, row.element)
        if (isExpanded && row.shotFile != null) loadShot?.invoke(row, b.shot)
        ViewCompat.setStateDescription(
            b.row, context.getString(if (isExpanded) R.string.state_expanded else R.string.state_collapsed),
        )
        b.row.setOnClickListener {
            val tapped = holder.bindingAdapterPosition
            if (tapped == RecyclerView.NO_POSITION) return@setOnClickListener
            val previous = expanded
            expanded = if (tapped == previous) RecyclerView.NO_POSITION else tapped
            if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
            if (expanded != RecyclerView.NO_POSITION) notifyItemChanged(expanded)
            onExpand(rows[tapped])
        }
    }

    override fun getItemCount() = rows.size
}
