package com.a11yauditor.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.a11yauditor.app.databinding.ItemAppBinding

data class InstalledAppInfo(val appName: String, val packageName: String, val icon: Drawable)

/** Launchable user-installed apps other than this one, one per package, sorted by label. Updated system apps stay hidden too. */
fun launchableApps(pm: PackageManager, ownPackage: String): List<ActivityInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .asSequence()
        .map { it.activityInfo }
        .filter { it.packageName != ownPackage && it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .distinctBy { it.packageName }
        .sortedBy { it.loadLabel(pm).toString().lowercase() }
        .toList()
}

fun loadInstalledApps(pm: PackageManager, ownPackage: String): List<InstalledAppInfo> =
    launchableApps(pm, ownPackage).map { InstalledAppInfo(it.loadLabel(pm).toString(), it.packageName, it.loadIcon(pm)) }

class AppListAdapter(
    private val apps: List<InstalledAppInfo>,
    private val onSelect: (InstalledAppInfo) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var selectedPackage: String? = null

    class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.binding.appIcon.setImageDrawable(app.icon)
        holder.binding.appNameText.text = app.appName
        holder.binding.packageNameText.text = app.packageName
        holder.binding.root.isSelected = app.packageName == selectedPackage
        holder.binding.root.setOnClickListener {
            val previousIndex = apps.indexOfFirst { it.packageName == selectedPackage }
            selectedPackage = app.packageName
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
            notifyItemChanged(position)
            onSelect(app)
        }
    }

    override fun getItemCount() = apps.size
}
