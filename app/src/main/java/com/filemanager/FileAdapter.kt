package com.filemanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val items: List<FileItem>,
    private val onClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.itemIcon)
        val name: TextView = view.findViewById(R.id.itemName)
        val meta: TextView = view.findViewById(R.id.itemMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        if (item.isDirectory) {
            holder.icon.setImageResource(R.drawable.folder)
            holder.meta.text = ""
        } else {
            holder.icon.setImageResource(iconForFile(item.name))
            holder.meta.text = formatSize(item.size)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    private fun iconForFile(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") ->
                R.drawable.images

            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
            lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".3gp") ->
                R.drawable.video

            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
            lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".aac") ->
                R.drawable.audio

            else -> R.drawable.documents
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }
}
