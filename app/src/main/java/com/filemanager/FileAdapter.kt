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
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta?.text = if (item.isDirectory) "" else formatSize(item.size)

        val iconView = holder.icon
        if (iconView != null) {
            when {
                item.isDirectory -> {
                    iconView.setImageResource(R.drawable.folder)
                }
                item.name.lowercase().endsWith(".zip") -> {
                    iconView.setImageResource(R.drawable.zip)
                }
                else -> {
                    val mime = getMimeType(item.name)
                    val ext = item.name.substringAfterLast('.', "").lowercase()

                    // 1) Immagine reale → miniatura con Glide
                    if (mime.startsWith("image/")) {
                        Glide.with(iconView.context)
                            .load(File(item.path))
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .placeholder(R.drawable.images)
                            .error(R.drawable.images)
                            .into(iconView)
                    }
                    // 2) Video → frame con Glide
                    else if (mime.startsWith("video/")) {
                        Glide.with(iconView.context)
                            .load(File(item.path))
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .placeholder(R.drawable.video)
                            .error(R.drawable.video)
                            .into(iconView)
                    }
                    // 3) Altri file → icona per estensione
                    else {
                        iconView.setImageResource(iconForExtension(ext))
                    }
                }
            }
        }

        // Evidenzia se selezionato
        val isSelected = selectionMode && selectedPaths.contains(item.path)
        if (isSelected) {
            holder.itemView.setBackgroundColor(Color.parseColor("#333B82F6"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Click normale
        holder.itemView.setOnClickListener { onClick(item) }

        // Long click
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size

    /**
     * Mappa estensione → icona personalizzata.
     * Per aggiungere nuove estensioni, basta inserirle qui.
     */
    private fun iconForExtension(ext: String): Int {
        return when (ext) {
            // 1) PDF
            "pdf" -> R.drawable.pdf

            // 2) Word / Writer / testo
            "doc", "docx", "odt", "rtf", "txt" -> R.drawable.doc

            // 3) Excel / Calc / CSV
            "xls", "xlsx", "ods", "csv" -> R.drawable.xls

            // 4) PowerPoint / Impress
            "ppt", "pptx", "odp" -> R.drawable.ppt

            // 5) APK Android
            "apk" -> R.drawable.apk

            // 6) Codice / markup / web
            "xml", "html", "htm", "json", "js", "css",
            "yaml", "yml", "ini", "log",
            "py", "java", "kt", "c", "cpp", "h", "sh", "bat" -> R.drawable.code

            // Audio (icona statica)
            "mp3", "wav", "ogg", "flac", "m4a", "aac" -> R.drawable.audio

            // Default: documenti generici
            else -> R.drawable.documents
        }
    }

    private fun getMimeType(name: String): String {
        val l = name.lowercase()
        return when {
            l.endsWith(".jpg") || l.endsWith(".jpeg") -> "image/jpeg"
            l.endsWith(".png") -> "image/png"
            l.endsWith(".gif") -> "image/gif"
            l.endsWith(".webp") -> "image/webp"
            l.endsWith(".bmp") -> "image/bmp"
            l.endsWith(".mp3") -> "audio/mpeg"
            l.endsWith(".wav") -> "audio/wav"
            l.endsWith(".ogg") -> "audio/ogg"
            l.endsWith(".m4a") -> "audio/mp4"
            l.endsWith(".mp4") -> "video/mp4"
            l.endsWith(".mkv") -> "video/x-matroska"
            l.endsWith(".avi") -> "video/x-msvideo"
            l.endsWith(".mov") -> "video/quicktime"
            l.endsWith(".webm") -> "video/webm"
            else -> "application/octet-stream"
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
