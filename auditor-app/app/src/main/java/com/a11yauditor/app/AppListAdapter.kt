package com.a11yauditor.app

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.a11yauditor.app.databinding.ItemAppBinding

data class InstalledAppInfo(val appName: String, val packageName: String, val icon: Drawable)

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
