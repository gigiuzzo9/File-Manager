package com.filemanager

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SAF = 1001
    }

    private lateinit var recycler: RecyclerView
    private lateinit var txtPath: TextView
    private lateinit var txtSort: TextView
    private lateinit var editSearch: EditText
    private lateinit var prefs: SharedPreferences
    private lateinit var storageRow: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var categoriesRow: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var txtSelectionCount: TextView
    private lateinit var btnSelPaste: LinearLayout
    private lateinit var btnSelMore: LinearLayout
    private lateinit var btnPaste: ImageButton
    private lateinit var btnViewToggle: ImageButton

    private var fileAdapter: FileAdapter? = null
    private var currentLayoutIsGrid: Boolean? = null

    private val rootInternal: String
        get() = if (File("/storage/emulated/0").exists()) {
            "/storage/emulated/0"
        } else {
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        }

    private var currentPath: String = ""
    private var allItems: List<FileItem> = emptyList()
    private var displayedItems: List<FileItem> = emptyList()

    private var showHidden: Boolean = false
    private var isGrid: Boolean = false
    private var sortBy: String = "name"
    private var searchQuery: String = ""
    private var activeCategory: String? = null

    private val clipboardPaths = mutableListOf<String>()
    private var clipboardAction: String? = null

    private var selectionMode: Boolean = false
    private val selectedPaths = mutableSetOf<String>()

    private var safTreeUri: Uri? = null
    private var pendingSafAction: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("filemanager", MODE_PRIVATE)
        showHidden = prefs.getBoolean("show_hidden", false)
        isGrid = prefs.getBoolean("is_grid", false)
        sortBy = prefs.getString("sort_by", "name") ?: "name"

        currentPath = rootInternal
        safTreeUri = prefs.getString("saf_tree_uri", null)?.let { Uri.parse(it) }

        recycler = findViewById(R.id.recyclerFiles)
        txtPath = findViewById(R.id.txtPath)
        txtSort = findViewById(R.id.txtSort)
        editSearch = findViewById(R.id.editSearch)
        storageRow = findViewById(R.id.storageRow)
        searchBar = findViewById(R.id.searchBar)
        selectionBar = findViewById(R.id.selectionBar)
        categoriesRow = findViewById(R.id.categoriesRow)
        actionsRow = findViewById(R.id.actionsRow)
        txtSelectionCount = findViewById(R.id.txtSelectionCount)
        btnSelPaste = findViewById(R.id.btnSelPaste)
        btnSelMore = findViewById(R.id.btnSelMore)
        btnPaste = findViewById(R.id.btnPaste)
        btnViewToggle = findViewById(R.id.btnViewToggle)

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.lowercase() ?: ""
                applyFilters()
            }
        })

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<ImageButton>(R.id.btnAddFolder).setOnClickListener { createFolder() }
        findViewById<LinearLayout>(R.id.btnSort).setOnClickListener { showSortDialog() }
        btnViewToggle.setOnClickListener { toggleView() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        btnPaste.setOnClickListener { pasteFromClipboard() }

        findViewById<LinearLayout>(R.id.catImages).setOnClickListener { setCategory("images") }
        findViewById<LinearLayout>(R.id.catAudio).setOnClickListener { setCategory("audio") }
        findViewById<LinearLayout>(R.id.catVideo).setOnClickListener { setCategory("video") }
        findViewById<LinearLayout>(R.id.catDocs).setOnClickListener { setCategory("documents") }

        findViewById<ImageButton>(R.id.btnSelectionClose).setOnClickListener { exitSelectionMode() }
        findViewById<LinearLayout>(R.id.btnSelCopy).setOnClickListener { copySelectedFiles("copy") }
        findViewById<LinearLayout>(R.id.btnSelDelete).setOnClickListener { deleteSelectedFiles() }
        btnSelPaste.setOnClickListener { pasteFromClipboard() }
        btnSelMore.setOnClickListener { showSelectionMoreMenu() }

        updateStorageCards()
        updateSortLabel()
        updateViewToggleIcon()
        updatePasteButton()

        if (hasStoragePermission()) {
            loadDirectory(currentPath)
        } else {
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            loadDirectory(currentPath)
        }
        updateStorageCards()
        updatePasteButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun updateViewToggleIcon() {
        btnViewToggle.setImageResource(if (isGrid) R.drawable.grid else R.drawable.list)
        val tintColor = MaterialColors.getColor(
            btnViewToggle,
            com.google.android.material.R.attr.colorOnSurface
        )
        btnViewToggle.setColorFilter(tintColor)
    }

    private fun updatePasteButton() {
        val shouldShow = !selectionMode && clipboardPaths.isNotEmpty()
        btnPaste.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun enterSelectionMode(item: FileItem) {
        selectionMode = true
        selectedPaths.clear()
        selectedPaths.add(item.path)
        updateSelectionUI()
        renderList()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        updateSelectionUI()
        renderList()
    }

    private fun toggleSelection(item: FileItem) {
        if (selectedPaths.contains(item.path)) {
            selectedPaths.remove(item.path)
            if (selectedPaths.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedPaths.add(item.path)
        }
        updateSelectionUI()
        renderList()
    }

    private fun updateSelectionUI() {
        if (selectionMode) {
            searchBar.visibility = View.GONE
            selectionBar.visibility = View.VISIBLE
            categoriesRow.visibility = View.GONE
            storageRow.visibility = View.GONE
            actionsRow.visibility = View.GONE

            val count = selectedPaths.size
            txtSelectionCount.text = if (count == 1) "1 selezionato" else "$count selezionati"

            btnSelPaste.visibility = if (clipboardPaths.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            searchBar.visibility = View.VISIBLE
            selectionBar.visibility = View.GONE
            categoriesRow.visibility = View.VISIBLE
            storageRow.visibility = View.VISIBLE
            actionsRow.visibility = View.VISIBLE

            updatePasteButton()
        }
    }

    // ============================================================
    // === ELIMINA — versione iterativa e più stabile ===
    // ============================================================
    private fun deleteSelectedFiles() {
        if (selectedPaths.isEmpty()) return

        val count = selectedPaths.size
        val pathAtStart = currentPath

        AlertDialog.Builder(this)
            .setTitle("Elimina")
            .setMessage("Eliminare $count file?")
            .setPositiveButton("Elimina") { _, _ ->
                val pathsToDelete = selectedPaths.toList()
                Toast.makeText(this, "Eliminazione in corso...", Toast.LENGTH_SHORT).show()

                executor.execute {
                    val operationStart = System.nanoTime()
                    var directDeleteNanos = 0L
                    var safDeleteNanos = 0L
                    var deleted = 0
                    for (path in pathsToDelete) {
                        try {
                            val f = File(path)

                            val directStart = System.nanoTime()
                            var ok = if (f.isDirectory) deleteRecursively(f) else f.delete()
                            directDeleteNanos += System.nanoTime() - directStart

                            if (!ok) {
                                val safStart = System.nanoTime()
                                val doc = getSafDocumentFile(path)
                                if (doc != null) {
                                    ok = try {
                                        if (doc.isDirectory) deleteDocumentRecursive(doc) else doc.delete()
                                    } catch (_: Exception) { false }
                                }
                                safDeleteNanos += System.nanoTime() - safStart
                            }

                            if (ok) deleted++
                        } catch (_: Exception) {}
                    }

                    val finalDeleted = deleted
                    val operationNanos = System.nanoTime() - operationStart
                    mainHandler.post {
                        Toast.makeText(this, "Eliminati $finalDeleted file", Toast.LENGTH_SHORT).show()
                        exitSelectionMode()
                        loadDirectory(pathAtStart) { refreshNanos ->
                            showDiagnostic(
                                "Diagnostica cancellazione",
                                listOf(
                                    "File.delete()/ricorsivo: ${formatDiagnosticMs(directDeleteNanos)}",
                                    "Fallback SAF: ${formatDiagnosticMs(safDeleteNanos)}",
                                    "Refresh elenco: ${formatDiagnosticMs(refreshNanos)}",
                                    "Operazione filesystem: ${formatDiagnosticMs(operationNanos)}",
                                    "TOTALE fino a elenco aggiornato: ${formatDiagnosticMs(operationNanos + refreshNanos)}"
                                )
                            )
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteRecursively(root: File): Boolean {
        if (!root.exists()) return true

        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)

        var success = true

        while (stack.isNotEmpty()) {
            val (file, visited) = stack.removeLast()

            if (!file.exists()) continue

            if (file.isDirectory && !visited) {
                stack.addLast(file to true)

                val children = try {
                    file.listFiles()
                } catch (_: Exception) {
                    null
                }

                if (children == null) {
                    success = false
                    continue
                }

                for (child in children) {
                    stack.addLast(child to false)
                }
            } else {
                try {
                    if (!file.delete() && file.exists()) {
                        success = false
                    }
                } catch (_: Exception) {
                    success = false
                }
            }
        }

        return success && !root.exists()
    }

    private fun deleteDocumentRecursive(doc: DocumentFile): Boolean {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) {
                deleteDocumentRecursive(child)
            }
        }
        return doc.delete()
    }

    private fun copySelectedFiles(action: String) {
        if (selectedPaths.isEmpty()) return
        clipboardPaths.clear()
        clipboardPaths.addAll(selectedPaths)
        clipboardAction = action
        Toast.makeText(
            this,
            "${selectedPaths.size} file ${if (action == "cut") "tagliati" else "copiati"}",
            Toast.LENGTH_SHORT
        ).show()
        updateSelectionUI()
    }

    // ============================================================
    // === COPIA/INCOLLA — senza scandire le cartelle ===
    // ============================================================
    private fun pasteFromClipboard() {
        if (clipboardPaths.isEmpty()) {
            Toast.makeText(this, "Niente negli appunti", Toast.LENGTH_SHORT).show()
            return
        }

        val srcPaths = clipboardPaths.toList()
        val action = clipboardAction
        val dstDir = File(currentPath)

        val existingSrc = srcPaths.filter { File(it).exists() }
        if (existingSrc.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Errore Incolla")
                .setMessage("Nessun file originale trovato")
                .setPositiveButton("OK", null)
                .show()
            clipboardPaths.clear()
            clipboardAction = null
            exitSelectionMode()
            updatePasteButton()
            return
        }

        Toast.makeText(
            this,
            if (action == "cut") "Spostamento in corso..." else "Copia in corso...",
            Toast.LENGTH_SHORT
        ).show()

        executor.execute {
            val operationStart = System.nanoTime()
            var directCopyNanos = 0L
            var safCopyNanos = 0L
            var cutDeleteNanos = 0L
            var copied = 0
            var errorMsg = ""
            val copiedPaths = ArrayList<String>()

            for (srcPath in existingSrc) {
                val src = File(srcPath)

                var dstName = src.name
                var dst = File(dstDir, dstName)

                if (dst.absolutePath == src.absolutePath || dst.exists()) {
                    dstName = generateUniqueName(dstDir, src.name)
                    dst = File(dstDir, dstName)
                }

                var ok = false
                var renamed = false

                val directStart = System.nanoTime()
                try {
                    if (action == "cut") {
                        renamed = src.renameTo(dst)
                        ok = renamed
                    }
                    if (!ok) {
                        if (src.isDirectory) copyDirectoryRecursive(src, dst)
                        else copyFile(src, dst)
                        ok = true
                    }
                } catch (e: Exception) {
                    errorMsg += "\n${src.name}: ${e.message}"
                    ok = false
                }
                directCopyNanos += System.nanoTime() - directStart

                if (!ok) {
                    val safStart = System.nanoTime()
                    try { ok = copyViaSaf(src, dst) } catch (_: Exception) {}
                    safCopyNanos += System.nanoTime() - safStart
                }

                if (ok) {
                    copied++
                    copiedPaths.add(dst.absolutePath)

                    if (action == "cut" && !renamed && dst.absolutePath != src.absolutePath && src.exists()) {
                        val deleteStart = System.nanoTime()
                        try {
                            if (src.isDirectory) deleteRecursively(src) else src.delete()
                        } catch (_: Exception) {}
                        cutDeleteNanos += System.nanoTime() - deleteStart
                    }
                }
            }

            val scanNanos = 0L
            val finalCopied = copied
            val finalErr = errorMsg
            val wasCut = action == "cut"
            val operationNanos = System.nanoTime() - operationStart

            mainHandler.post {
                if (finalCopied > 0) {
                    Toast.makeText(
                        this,
                        if (wasCut) "Spostati $finalCopied file" else "Copiati $finalCopied file",
                        Toast.LENGTH_SHORT
                    ).show()
                    clipboardPaths.clear()
                    clipboardAction = null
                    exitSelectionMode()
                    updatePasteButton()
                    loadDirectory(currentPath) { refreshNanos ->
                        showDiagnostic(
                            "Diagnostica incolla",
                            listOf(
                                "Copia diretta (File): ${formatDiagnosticMs(directCopyNanos)}",
                                "Copia via SAF (fallback): ${formatDiagnosticMs(safCopyNanos)}",
                                "Cancellazione sorgente (cut): ${formatDiagnosticMs(cutDeleteNanos)}",
                                "Operazione filesystem: ${formatDiagnosticMs(operationNanos)}",
                                "Refresh elenco: ${formatDiagnosticMs(refreshNanos)}",
                                "TOTALE fino a elenco aggiornato: ${formatDiagnosticMs(operationNanos + refreshNanos)}",
                                "MediaStore scan: ${formatDiagnosticMs(scanNanos)}"
                            )
                        )
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Incolla")
                        .setMessage("Nessun file copiato.\n$finalErr")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun generateUniqueName(dir: File, originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName: String
        val extension: String
        if (dotIndex > 0) {
            baseName = originalName.substring(0, dotIndex)
            extension = originalName.substring(dotIndex)
        } else {
            baseName = originalName
            extension = ""
        }

        var counter = 1
        var candidate = "${baseName}_$counter$extension"
        while (File(dir, candidate).exists()) {
            counter++
            candidate = "${baseName}_$counter$extension"
        }
        return candidate
    }

    // ============================================================
    // === COPIA — buffer 128 KB ===
    // ============================================================
    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buffer = ByteArray(128 * 1024)
                var length: Int
                while (input.read(buffer).also { length = it } > 0) {
                    output.write(buffer, 0, length)
                }
                output.flush()
            }
        }
        dst.setLastModified(src.lastModified())
    }

    private fun copyDirectoryRecursive(src: File, dst: File) {
        if (!dst.exists() && !dst.mkdirs() && !dst.exists()) {
            throw java.io.IOException("Impossibile creare ${dst.absolutePath}")
        }
        val files = src.listFiles() ?: throw java.io.IOException("Impossibile leggere ${src.absolutePath}")
        for (f in files) {
            val newFile = File(dst, f.name)
            if (f.isDirectory) copyDirectoryRecursive(f, newFile)
            else copyFile(f, newFile)
        }
    }

    // ============================================================
    // === SAF — fallback ===
    // ============================================================
    private fun copyViaSaf(src: File, dst: File): Boolean {
        return try {
            val parentDir = dst.parentFile ?: return false
            val parentDoc = getSafDocumentFile(parentDir.absolutePath) ?: return false

            if (src.isDirectory) {
                val newDir = parentDoc.createDirectory(dst.name) ?: return false
                copyDirViaSaf(src, newDir)
                true
            } else {
                val mimeType = getMimeType(src.name)
                val newFile = parentDoc.createFile(mimeType, dst.name) ?: return false
                contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    FileInputStream(src).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                        output.flush()
                    }
                } ?: return false
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyDirViaSaf(src: File, dstDoc: DocumentFile): Boolean {
        val files = src.listFiles() ?: return false
        var allOk = true
        for (f in files) {
            if (f.isDirectory) {
                val newDir = dstDoc.createDirectory(f.name)
                if (newDir == null || !copyDirViaSaf(f, newDir)) allOk = false
            } else {
                val mimeType = getMimeType(f.name)
                val newFile = dstDoc.createFile(mimeType, f.name)
                if (newFile == null) { allOk = false; continue }
                try {
                    contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        FileInputStream(f).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var length: Int
                            while (input.read(buffer).also { length = it } > 0) {
                                output.write(buffer, 0, length)
                            }
                            output.flush()
                        }
                    }
                } catch (_: Exception) { allOk = false }
            }
        }
        return allOk
    }

    // ============================================================
    // === MENU ⋮ ===
    // ============================================================
    private fun showSelectionMoreMenu() {
        if (selectedPaths.isEmpty()) return

        val popup = PopupMenu(this, btnSelMore)
        val count = selectedPaths.size

        val allVisibleSelected = displayedItems.isNotEmpty() &&
                displayedItems.all { selectedPaths.contains(it.path) }

        if (allVisibleSelected) {
            popup.menu.add(0, 20, 0, "❌  Deseleziona tutto")
        } else {
            popup.menu.add(0, 20, 0, "✅  Seleziona tutto")
        }

        if (count == 1) {
            val item = getSingleSelectedItem()
            if (item != null && !item.isDirectory) {
                popup.menu.add(0, 10, 1, "📂  Apri")
                popup.menu.add(0, 11, 2, "🔧  Apri con...")
            }
        }

        popup.menu.add(0, 1, 3, "✂️  Taglia")
        popup.menu.add(0, 2, 4, "📤  Condividi")
        popup.menu.add(0, 3, 5, "📦  Comprimi in ZIP")

        if (count == 1) {
            val path = selectedPaths.first()
            val item = allItems.find { it.path == path }
            if (item != null) {
                popup.menu.add(0, 4, 6, "✏️  Rinomina")
                if (!item.isDirectory && item.name.lowercase().endsWith(".zip")) {
                    popup.menu.add(0, 5, 7, "📂  Decomprimi")
                }
                popup.menu.add(0, 6, 8, "ℹ️  Proprietà")
            }
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                20 -> {
                    if (allVisibleSelected) selectedPaths.clear()
                    else {
                        selectedPaths.clear()
                        for (item in displayedItems) selectedPaths.add(item.path)
                    }
                    updateSelectionUI()
                    renderList()
                }
                10 -> {
                    val item = getSingleSelectedItem()
                    if (item != null) { exitSelectionMode(); openFileWithDefault(item) }
                }
                11 -> {
                    val item = getSingleSelectedItem()
                    if (item != null) { exitSelectionMode(); openFileWithPicker(item) }
                }
                1 -> copySelectedFiles("cut")
                2 -> shareSelectedFiles()
                3 -> comprimiZipSelezioneMultipla()
                4 -> {
                    val item = getSingleSelectedItem()
                    if (item != null) { exitSelectionMode(); renameItem(item) }
                }
                5 -> {
                    val item = getSingleSelectedItem()
                    if (item != null) { exitSelectionMode(); decomprimiZip(item) }
                }
                6 -> {
                    val item = getSingleSelectedItem()
                    if (item != null) { exitSelectionMode(); showItemInfo(item) }
                }
            }
            true
        }
        popup.show()
    }

    private fun getSingleSelectedItem(): FileItem? {
        if (selectedPaths.size != 1) return null
        val path = selectedPaths.first()
        return allItems.find { it.path == path }
    }

    private fun shareSelectedFiles() {
        if (selectedPaths.isEmpty()) return
        if (selectedPaths.size == 1) {
            val path = selectedPaths.first()
            val item = allItems.find { it.path == path }
            if (item != null && !item.isDirectory) {
                shareFile(item)
                exitSelectionMode()
            } else if (item != null && item.isDirectory) {
                Toast.makeText(this, "Impossibile condividere una cartella", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val uris = ArrayList<Uri>()
            for (path in selectedPaths) {
                val f = File(path)
                if (f.exists() && f.isFile) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", f)
                    uris.add(uri)
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, "Nessun file da condividere", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi con..."))
            exitSelectionMode()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun comprimiZipSelezioneMultipla() {
        if (selectedPaths.isEmpty()) return

        val pathsToZip = selectedPaths.toList()
        val firstName = File(pathsToZip.first()).name
        val baseName = if (firstName.contains(".")) firstName.substringBeforeLast(".") else firstName

        var zipName = "$baseName.zip"
        var counter = 1
        while (File(currentPath, zipName).exists()) {
            zipName = "${baseName}_$counter.zip"
            counter++
        }
        val finalZipName = zipName

        Toast.makeText(this, "Compressione in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var ok = false
            val zipFile = File(currentPath, finalZipName)
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    for (path in pathsToZip) {
                        val f = File(path)
                        if (!f.exists()) continue
                        if (f.isDirectory) addDirectoryToZip(f, f.name, zos)
                        else addFileToZip(f, f.name, zos)
                    }
                }
                ok = true
            } catch (_: Exception) {
                try { zipFile.delete() } catch (_: Exception) {}
            }

            if (ok) scanPath(zipFile.absolutePath)

            mainHandler.post {
                if (ok) {
                    Toast.makeText(this, "Creato: $finalZipName", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadDirectory(currentPath)
                } else {
                    Toast.makeText(this, "Errore compressione", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun addDirectoryToZip(dir: File, basePath: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entryName = "$basePath/${f.name}"
            if (f.isDirectory) addDirectoryToZip(f, entryName, zos)
            else addFileToZip(f, entryName, zos)
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            val buffer = ByteArray(128 * 1024)
            var length: Int
            while (fis.read(buffer).also { length = it } > 0) {
                zos.write(buffer, 0, length)
            }
            zos.closeEntry()
        }
    }

    private fun decomprimiZip(item: FileItem) {
        if (!item.name.lowercase().endsWith(".zip")) {
            Toast.makeText(this, "Non è un file ZIP", Toast.LENGTH_SHORT).show()
            return
        }
        val zipSource = item.file
        if (!zipSource.exists()) {
            Toast.makeText(this, "File non trovato", Toast.LENGTH_SHORT).show()
            return
        }
        val baseName = item.name.substringBeforeLast(".")
        Toast.makeText(this, "Decompressione in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var filesExtracted = 0
            val extractDir = File(currentPath, baseName)
            if (!extractDir.exists()) extractDir.mkdirs()

            try {
                ZipInputStream(FileInputStream(zipSource)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        val outFile = File(extractDir, entryName)
                        if (!outFile.canonicalPath.startsWith(extractDir.canonicalPath)) {
                            zis.closeEntry(); entry = zis.nextEntry; continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            try {
                                FileOutputStream(outFile).use { fos ->
                                    val buffer = ByteArray(128 * 1024)
                                    var length: Int
                                    while (zis.read(buffer).also { length = it } > 0) {
                                        fos.write(buffer, 0, length)
                                    }
                                }
                                filesExtracted++
                            } catch (_: Exception) {}
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}

            val extracted = filesExtracted
            mainHandler.post {
                if (extracted > 0) {
                    Toast.makeText(this, "Estratti $extracted file", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    Toast.makeText(this, "Nessun file estratto", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStorageCards() {
        storageRow.removeAllViews()
        val volumes = mutableListOf<StorageVolumeInfo>()

        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, totalBytes - freeBytes, totalBytes))
        } catch (_: Exception) {
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, 0L, 0L))
        }

        try {
            val sm = getSystemService(STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    if (vol.isPrimary || !vol.isRemovable) continue
                    val path = getVolumePath(vol) ?: continue
                    if (!File(path).exists()) continue
                    try {
                        val stat = StatFs(path)
                        val totalBytes = stat.blockCountLong * stat.blockSizeLong
                        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                        volumes.add(StorageVolumeInfo(vol.getDescription(this) ?: "Storage esterno", path, totalBytes - freeBytes, totalBytes))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        for ((i, vol) in volumes.withIndex()) {
            val card = createStorageCard(vol)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) params.marginStart = (8 * resources.displayMetrics.density).toInt()
            card.layoutParams = params
            storageRow.addView(card)
        }
    }

    private fun getVolumePath(vol: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uuid = vol.uuid
                if (uuid != null) {
                    val path = "/storage/$uuid"
                    if (File(path).exists()) return path
                }
            } catch (_: Exception) {}
        }
        return try {
            val method = vol.javaClass.getMethod("getPath")
            method.invoke(vol) as? String
        } catch (_: Exception) { null }
    }

    private fun createStorageCard(vol: StorageVolumeInfo): LinearLayout {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        card.isClickable = true
        card.isFocusable = true

        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#2A2A2A"))
        bg.cornerRadius = 12 * density
        card.background = bg

        val title = TextView(this)
        title.text = vol.label
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        card.addView(title)

        val info = TextView(this)
        info.text = if (vol.totalBytes > 0) {
            String.format("%.1f GB / %.1f GB",
                vol.usedBytes / (1024.0 * 1024.0 * 1024.0),
                vol.totalBytes / (1024.0 * 1024.0 * 1024.0))
        } else "Info non disponibili"
        info.setTextColor(Color.parseColor("#CCCCCC"))
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        info.setPadding(0, (2 * density).toInt(), 0, 0)
        card.addView(info)

        card.setOnClickListener {
            if (vol.path == rootInternal) {
                activeCategory = null
                searchQuery = ""
                editSearch.setText("")
                loadDirectory(rootInternal, resetCategory = true)
            } else tryAccessExternalVolume(vol.path)
        }
        return card
    }

    private fun tryAccessExternalVolume(path: String) {
        try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Volume non accessibile", Toast.LENGTH_SHORT).show(); return
            }
            if (dir.listFiles() == null) {
                Toast.makeText(this, "Serve il permesso", Toast.LENGTH_LONG).show()
                requestSafForPath(path); return
            }
            activeCategory = null
            searchQuery = ""
            editSearch.setText("")
            loadDirectory(path, resetCategory = true)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestSafForPath(path: String) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_SAF)
            pendingSafAction = { updateStorageCards(); loadDirectory(path, resetCategory = true) }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore apertura SAF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStoragePermission()) loadDirectory(currentPath)
    }

    private fun requestSaf(onGranted: () -> Unit) {
        if (safTreeUri != null) { onGranted(); return }
        pendingSafAction = onGranted
        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_SAF)
        } catch (_: Exception) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_SAF)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAF && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                safTreeUri = uri
                prefs.edit().putString("saf_tree_uri", uri.toString()).apply()
                Toast.makeText(this, "Permesso concesso", Toast.LENGTH_SHORT).show()
                pendingSafAction?.invoke()
            } catch (e: Exception) {
                Toast.makeText(this, "Errore permesso: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingSafAction = null
    }

    private fun getSafDocumentFile(path: String): DocumentFile? {
        val tree = safTreeUri ?: return null
        val rel = path.removePrefix(rootInternal).trimStart('/')
        var doc = DocumentFile.fromTreeUri(this, tree) ?: return null
        if (rel.isEmpty()) return doc
        for (part in rel.split("/").filter { it.isNotEmpty() }) {
            doc = doc?.findFile(part) ?: return null
        }
        return doc
    }

    // ============================================================
    // === DIAGNOSTICA ===
    // ============================================================
    private fun formatDiagnosticMs(nanos: Long): String {
        return String.format(java.util.Locale.US, "%.2f s", nanos / 1_000_000_000.0)
    }

    private fun showDiagnostic(title: String, lines: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    // ============================================================
    // === LOAD DIRECTORY — conteggio figli in BACKGROUND ===
    // ============================================================
    private fun loadDirectory(
        path: String,
        resetCategory: Boolean = false,
        onComplete: ((Long) -> Unit)? = null
    ) {
        currentPath = path
        txtPath.text = path
        if (resetCategory) {
            activeCategory = null
            searchQuery = ""
        }

        val refreshStart = System.nanoTime()
        executor.execute {
            val dir = File(path)
            val result: List<FileItem>? = if (!dir.exists() || !dir.isDirectory) {
                null
            } else {
                val files = dir.listFiles()
                if (files == null) null
                else {
                    val filtered = if (showHidden) files.toList() else files.filter { !it.name.startsWith(".") }
                    filtered.map { f ->
                        FileItem(
                            file = f, name = f.name, path = f.absolutePath,
                            isDirectory = f.isDirectory,
                            size = if (f.isFile) f.length() else 0L,
                            lastModified = f.lastModified(),
                            childrenCount = 0
                        )
                    }
                }
            }

            mainHandler.post {
                if (result == null) {
                    Toast.makeText(this, "Cartella non accessibile", Toast.LENGTH_SHORT).show()
                    return@post
                }
                allItems = result
                applyFilters()
                updatePasteButton()
                onComplete?.invoke(System.nanoTime() - refreshStart)

                executor.execute {
                    val updated = result.map { item ->
                        if (item.isDirectory) {
                            try {
                                val count = item.file.list()?.let { arr ->
                                    if (showHidden) arr.size else arr.count { !it.startsWith(".") }
                                } ?: 0
                                item.copy(childrenCount = count)
                            } catch (_: Exception) { item }
                        } else item
                    }
                    mainHandler.post {
                        if (currentPath == path) {
                            allItems = updated
                            applyFilters()
                        }
                    }
                }
            }
        }
    }

    private fun applyFilters() {
        var list = allItems.toList()
        if (searchQuery.isNotEmpty()) {
            if (currentPath == rootInternal) {
                val results = mutableListOf<FileItem>()
                try { searchRecursive(File(rootInternal), searchQuery.lowercase(), results, 0) } catch (_: Exception) {}
                list = results
            } else {
                list = list.filter { it.name.lowercase().contains(searchQuery) }
            }
        }
        if (activeCategory != null) {
            list = list.filter { !it.isDirectory && categoryFor(it.name) == activeCategory }
        }
        list = when (sortBy) {
            "size" -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.size })
            "date" -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.lastModified })
            else -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        displayedItems = list
        renderList()
    }

    private fun renderList() {
        val items = displayedItems
        if (fileAdapter == null) {
            fileAdapter = FileAdapter(
                items = items,
                isGrid = isGrid,
                selectionMode = selectionMode,
                selectedPaths = selectedPaths,
                onClick = { item ->
                    if (selectionMode) toggleSelection(item)
                    else if (item.isDirectory) loadDirectory(item.path)
                    else openFileWithDefault(item)
                },
                onLongClick = { item ->
                    if (!selectionMode) {
                        enterSelectionMode(item)
                    } else {
                        toggleSelection(item)
                    }
                }
            )
            recycler.adapter = fileAdapter
        } else {
            fileAdapter?.updateItems(items, selectionMode)
        }
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileItem>, depth: Int) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!showHidden && file.name.startsWith(".")) continue
            val nameLower = file.name.lowercase()
            val match = nameLower.contains(query)
            if (file.isDirectory) {
                if (match) {
                    results.add(
                        FileItem(
                            file = file,
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = true,
                            size = 0L,
                            lastModified = file.lastModified(),
                            childrenCount = 0,
                            searchParentPath = "Cartella"
                        )
                    )
                }
                searchRecursive(file, query, results, depth + 1)
            } else if (match) {
                results.add(
                    FileItem(
                        file = file,
                        name = file.name,
                        path = file.absolutePath,
                        isDirectory = false,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        childrenCount = 0,
                        searchParentPath = "File"
                    )
                )
            }
        }
    }

    private fun categoryFor(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") -> "images"
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".m4a") -> "audio"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".webm") -> "video"
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "documents"
            else -> null
        }
    }

    private fun setCategory(cat: String) {
        activeCategory = if (activeCategory == cat) null else cat
        applyFilters()
    }

    private fun goBack() {
        val parent = File(currentPath).parentFile
        if (parent != null) loadDirectory(parent.absolutePath)
    }

    private fun toggleView() {
        isGrid = !isGrid
        prefs.edit().putBoolean("is_grid", isGrid).apply()
        updateViewToggleIcon()
        renderList()
    }

    private fun showSettingsDialog() {}
    private fun showSortDialog() {}
    private fun updateSortLabel() {}
    private fun hasStoragePermission(): Boolean = true
    private fun requestStoragePermission() {}
    private fun openFileWithDefault(item: FileItem) {}
    private fun openFileWithPicker(item: FileItem) {}
    private fun renameItem(item: FileItem) {}
    private fun shareFile(item: FileItem) {}
    private fun createFolder() {}
    private fun scanPath(path: String) {}
    private fun scanPaths(paths: List<String>) {}
    private fun getMediaStoreUri(path: String): Uri = Uri.EMPTY
}

class StorageVolumeInfo(
    val label: String,
    val path: String,
    val usedBytes: Long,
    val totalBytes: Long
)

