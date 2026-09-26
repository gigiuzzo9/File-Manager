package com.filemanager

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File
import java.util.Locale

class FileAdapter(
    private val items: List<FileItem>,
    private val isGrid: Boolean,
    private val selectionMode: Boolean,
    private val selectedPaths: Set<String>,
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView? = view.findViewById(R.id.itemIcon)
        val name: TextView = view.findViewById(R.id.itemName)
        val meta: TextView? = view.findViewById(R.id.itemMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutId = if (isGrid) R.layout.item_file_grid else R.layout.item_file
        return VH(LayoutInflater.from(parent.context).inflate(layoutId, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        holder.meta?.text = when {
            item.searchParentPath.isNotEmpty() -> item.searchParentPath
            item.isDirectory -> {
                val n = item.childrenCount
                if (n == 1) "1 file" else "$n files"
            }
            else -> formatSize(item.size)
        }

        val iconView = holder.icon
        if (iconView != null) {
            // Cancella un eventuale caricamento precedente quando la riga viene riciclata.
            Glide.with(iconView).clear(iconView)

            when {
                item.isDirectory -> iconView.setImageResource(R.drawable.folder)
                isZip(item.name) -> iconView.setImageResource(R.drawable.zip)
                isImage(item.name) -> loadPreview(iconView, item, R.drawable.images)
                isVideo(item.name) -> loadPreview(iconView, item, R.drawable.video)
                else -> {
                    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    iconView.setImageResource(iconForExtension(ext))
                }
            }
        }

        val isSelected = selectionMode && selectedPaths.contains(item.path)
        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#333B82F6") else Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.icon?.let { Glide.with(it).clear(it) }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private fun loadPreview(iconView: ImageView, item: FileItem, placeholder: Int) {
        Glide.with(iconView)
            .load(File(item.path))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .centerCrop()
            .placeholder(placeholder)
            .error(placeholder)
            .into(iconView)
    }

    private fun isZip(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true)

    private fun isImage(name: String): Boolean {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> true
            else -> false
        }
    }

    private fun isVideo(name: String): Boolean {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "mkv", "avi", "mov", "webm" -> true
            else -> false
        }
    }

    private fun iconForExtension(ext: String): Int {
        return when (ext) {
            "pdf" -> R.drawable.pdf
            "doc", "docx", "odt", "rtf", "txt" -> R.drawable.doc
            "xls", "xlsx", "ods", "csv" -> R.drawable.xls
            "ppt", "pptx", "odp" -> R.drawable.ppt
            "apk" -> R.drawable.apk
            "xml", "html", "htm", "json", "js", "css",
            "yaml", "yml", "ini", "log",
            "py", "java", "kt", "c", "cpp", "h", "sh", "bat" -> R.drawable.code
            "mp3", "wav", "ogg", "flac", "m4a", "aac" -> R.drawable.audio
            else -> R.drawable.documents
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }
}
