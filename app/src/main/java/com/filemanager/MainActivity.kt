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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageEventListener
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

    private var storageEventListener: StorageEventListener? = null

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

        // Registra listener per mount/unmount volumi (USB, SD)
        if (storageEventListener == null) {
            try {
                val sm = getSystemService(STORAGE_SERVICE) as StorageManager
                storageEventListener = object : StorageEventListener() {
                    override fun onStorageStateChanged(
                        path: String?,
                        oldState: String?,
                        newState: String?
                    ) {
                        mainHandler.postDelayed({
                            updateStorageCards()
                            if (currentPath.startsWith("/storage/") &&
                                currentPath != rootInternal &&
                                !File(currentPath).exists()
                            ) {
                                loadDirectory(rootInternal, resetCategory = true)
                            }
                        }, 1000)
                    }

                    override fun onVolumeStateChanged(vol: StorageVolume?, oldState: Int, newState: Int) {
                        mainHandler.postDelayed({
                            updateStorageCards()
                        }, 1000)
                    }
                }
                sm.registerListener(storageEventListener)
            } catch (_: Exception) {}
        }

        if (hasStoragePermission()) {
            loadDirectory(currentPath)

            // Al primo avvio, dopo MANAGE_EXTERNAL_STORAGE, chiedi anche il SAF tree
            if (safTreeUri == null && !prefs.getBoolean("saf_requested", false)) {
                prefs.edit().putBoolean("saf_requested", true).apply()
                requestSaf {
                    Toast.makeText(this, "Permesso completo concesso", Toast.LENGTH_SHORT).show()
                    updateStorageCards()
                    loadDirectory(currentPath)
                }
            }
        }
        updateStorageCards()
        updatePasteButton()
    }

    override fun onPause() {
        super.onPause()
        try {
            storageEventListener?.let {
                val sm = getSystemService(STORAGE_SERVICE) as StorageManager
                sm.unregisterListener(it)
            }
        } catch (_: Exception) {}
        storageEventListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    // ---------- ICONA TOGGLE VISTA ----------

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

    // ---------- SELEZIONE MULTIPLA ----------

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

    // ---------- ELIMINA SELEZIONATI ----------

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
                    var deleted = 0
                    for (path in pathsToDelete) {
                        try {
                            val f = File(path)

                            var ok = if (f.isDirectory) deleteRecursively(f) else f.delete()

                            if (!ok) {
                                val doc = getSafDocumentFile(path)
                                if (doc != null) {
                                    ok = try {
                                        if (doc.isDirectory) deleteDocumentRecursive(doc) else doc.delete()
                                    } catch (_: Exception) { false }
                                }
                            }

                            if (ok) deleted++
                        } catch (_: Exception) {}
                    }

                    val finalDeleted = deleted
                    mainHandler.post {
                        Toast.makeText(this, "Eliminati $finalDeleted file", Toast.LENGTH_SHORT).show()
                        exitSelectionMode()
                        loadDirectory(pathAtStart)
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursively(child)
                }
            }
        }
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteDocumentRecursive(doc: DocumentFile): Boolean {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) {
                deleteDocumentRecursive(child)
            }
        }
        return doc.delete()
    }

    // ---------- COPIA (NON esce dalla selezione) ----------

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

    // ---------- MENU ⋮ DELLA SELEZIONE ----------

    private fun showSelectionMoreMenu() {
        if (selectedPaths.isEmpty()) return

        val popup = PopupMenu(this, btnSelMore)
        val count = selectedPaths.size

        val allVisibleSelected = displayedItems.isNotEmpty() &&
                displayedItems.all { selectedPaths.contains(it.path) }

        if (allVisibleSelected) {
            popup.menu.add(0, 20, 0, "Deseleziona tutto")
        } else {
            popup.menu.add(0, 20, 0, "Seleziona tutto")
        }

        if (count == 1) {
            val item = getSingleSelectedItem()
            if (item != null && !item.isDirectory) {
                popup.menu.add(0, 10, 1, "Apri")
                popup.menu.add(0, 11, 2, "Apri con...")
            }
        }

        popup.menu.add(0, 1, 3, "Taglia")
        popup.menu.add(0, 2, 4, "Condividi")
        popup.menu.add(0, 3, 5, "Comprimi in ZIP")

        if (count == 1) {
            val path = selectedPaths.first()
            val item = allItems.find { it.path == path }
            if (item != null) {
                popup.menu.add(0, 4, 6, "Rinomina")
                if (!item.isDirectory && item.name.lowercase().endsWith(".zip")) {
                    popup.menu.add(0, 5, 7, "Decomprimi")
                }
                popup.menu.add(0, 6, 8, "Proprietà")
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

    // ---------- COMPRIMI MULTI ----------

    private fun comprimiZipSelezioneMultipla() {
        if (selectedPaths.isEmpty()) return

        val pathsToZip = selectedPaths.toList()
        val firstPath = pathsToZip.first()
        val firstName = File(firstPath).name
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
            var errorMsg = ""

            try {
                ok = comprimiZipMultiViaSaf(pathsToZip, finalZipName)
                if (!ok) errorMsg = "SAF: compressione fallita"
            } catch (e: Exception) {
                ok = false
                errorMsg = "SAF: ${e.message}"
            }

            if (!ok) {
                val zipFile = File(currentPath, finalZipName)
                var addedCount = 0
                try {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        for (path in pathsToZip) {
                            val f = File(path)
                            if (!f.exists()) continue
                            if (f.isDirectory) {
                                addDirectoryToZip(f, f.name, zos)
                            } else {
                                addFileToZip(f, f.name, zos)
                            }
                            addedCount++
                        }
                    }
                    ok = addedCount > 0
                    if (!ok) errorMsg += " | File: nessun file aggiunto"
                } catch (e: Exception) {
                    ok = false
                    errorMsg += " | File: ${e.message}"
                    try { zipFile.delete() } catch (_: Exception) {}
                }
            }

            if (ok) {
                val zip = File(currentPath, finalZipName)
                scanPath(zip.absolutePath)
            }

            mainHandler.post {
                if (ok) {
                    Toast.makeText(this, "Creato: $finalZipName", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Compressione")
                        .setMessage("Errore:\n$errorMsg")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun comprimiZipMultiViaSaf(pathsToZip: List<String>, zipName: String): Boolean {
        return try {
            val parentDir = File(currentPath)
            val parentDoc = getSafDocumentFile(parentDir.absolutePath) ?: return false

            val newZipDoc = parentDoc.createFile("application/zip", zipName) ?: return false
            val outputStream = contentResolver.openOutputStream(newZipDoc.uri) ?: return false

            ZipOutputStream(outputStream).use { zos ->
                for (path in pathsToZip) {
                    val f = File(path)
                    if (!f.exists()) continue
                    if (f.isDirectory) {
                        addDirectoryToZip(f, f.name, zos)
                    } else {
                        addFileToZip(f, f.name, zos)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- CARD STORAGE ----------

    private fun updateStorageCards() {
        storageRow.removeAllViews()

        val volumes = mutableListOf<StorageVolumeInfo>()

        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - freeBytes
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, usedBytes, totalBytes))
        } catch (e: Exception) {
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, 0L, 0L))
        }

        try {
            val storageManager = getSystemService(STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in storageManager.storageVolumes) {
                    if (vol.isPrimary) continue
                    if (vol.isRemovable) {
                        val path = getVolumePath(vol)
                        if (path != null && File(path).exists()) {
                            try {
                                val stat = StatFs(path)
                                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                                val usedBytes = totalBytes - freeBytes
                                val label = vol.getDescription(this) ?: "Storage esterno"
                                volumes.add(StorageVolumeInfo(label, path, usedBytes, totalBytes))
                            } catch (_: Exception) {}
                        } else {
                            // Volume rilevato ma senza path accessibile: aggiungi comunque la card
                            // (utile su Android 11+ dove il path della USB è nascosto)
                            try {
                                val label = vol.getDescription(this) ?: "Storage esterno"
                                if (vol.state == Environment.MEDIA_MOUNTED) {
                                    volumes.add(StorageVolumeInfo(label, "", 0L, 0L))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        for ((index, vol) in volumes.withIndex()) {
            val card = createStorageCard(vol)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.marginStart = (8 * resources.displayMetrics.density).toInt()
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
        } catch (e: Exception) { null }
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
        if (vol.totalBytes > 0) {
            val usedGb = vol.usedBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = vol.totalBytes / (1024.0 * 1024.0 * 1024.0)
            info.text = String.format("%.1f GB / %.1f GB", usedGb, totalGb)
        } else {
            info.text = "Info non disponibili"
        }
        info.setTextColor(Color.parseColor("#CCCCCC"))
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        info.setPadding(0, (2 * density).toInt(), 0, 0)
        card.addView(info)

        card.setOnClickListener {
            if (vol.path == rootInternal || vol.path.isEmpty()) {
                activeCategory = null
                searchQuery = ""
                editSearch.setText("")
                if (vol.path.isEmpty()) {
                    // Volume senza path (USB su Android 11+): chiedi SAF
                    requestSafForPath(rootInternal)
                } else {
                    loadDirectory(rootInternal, resetCategory = true)
                }
            } else {
                tryAccessExternalVolume(vol.path)
            }
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
                Toast.makeText(this, "Serve il permesso per accedere a questo volume", Toast.LENGTH_LONG).show()
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

    // ---------- PERMESSI ----------

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Toast.makeText(this, "Attiva \"Gestisci tutti i file\"", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStoragePermission()) loadDirectory(currentPath)
    }

    // ---------- SAF ----------

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

    // ---------- LETTURA DIRECTORY ----------

    private fun loadDirectory(path: String, resetCategory: Boolean = false) {
        currentPath = path
        txtPath.text = path
        if (resetCategory) {
            activeCategory = null
            searchQuery = ""
        }

        executor.execute {
            val dir = File(path)
            val result: List<FileItem>? = if (!dir.exists() || !dir.isDirectory) {
                null
            } else {
                val files = dir.listFiles()
                if (files == null) null else {
                    val filtered = if (showHidden) files.toList() else files.filter { !it.name.startsWith(".") }
                    filtered.map { f ->
                        FileItem(
                            file = f,
                            name = f.name,
                            path = f.absolutePath,
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
                val q = searchQuery.lowercase()
                try {
                    searchRecursive(File(rootInternal), q, results, 0)
                } catch (_: Exception) {}
                list = results
            } else {
                list = list.filter { it.name.lowercase().contains(searchQuery) }
            }
        }

        if (activeCategory != null) {
            list = list.filter { item ->
                !item.isDirectory && categoryFor(item.name) == activeCategory
            }
        }

        list = when (sortBy) {
            "size" -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.size })
            "date" -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.lastModified })
            else -> list.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

        displayedItems = list
        renderList()
    }

    private fun searchRecursive(dir: File, query: String, out: MutableList<FileItem>, depth: Int) {
        if (depth > 8) return
        val dirName = dir.name
        if (dirName == "Android" || dirName == ".trash" || dirName == ".thumbnails") return

        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!showHidden && name.startsWith(".")) continue
            if (name.lowercase().contains(query)) {
                val parentRelPath = try {
                    dir.absolutePath.removePrefix(rootInternal).trimStart('/')
                } catch (_: Exception) { "" }
                val displayPath = if (parentRelPath.isEmpty()) "Memoria interna" else parentRelPath
                out.add(
                    FileItem(f, name, f.absolutePath, f.isDirectory,
                        if (f.isFile) f.length() else 0L, f.lastModified(), 0, displayPath)
                )
            }
            if (f.isDirectory) searchRecursive(f, query, out, depth + 1)
        }
    }

    private fun categoryFor(name: String): String {
        val l = name.lowercase()
        return when {
            l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") ||
            l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".bmp") -> "images"
            l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".ogg") ||
            l.endsWith(".flac") || l.endsWith(".m4a") || l.endsWith(".aac") -> "audio"
            l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi") ||
            l.endsWith(".mov") || l.endsWith(".webm") || l.endsWith(".3gp") -> "video"
            else -> "documents"
        }
    }

    private fun isDocumentFile(name: String): Boolean {
        val l = name.lowercase()
        return listOf(".pdf",".doc",".docx",".txt",".rtf",".odt",".xls",".xlsx",".csv",".ods",".ppt",".pptx",".odp",".zip",".rar",".7z",".tar",".gz").any { l.endsWith(it) }
    }

    private fun setCategory(cat: String) {
        if (activeCategory == cat) {
            activeCategory = null
            loadDirectory(rootInternal, resetCategory = true)
            return
        }

        activeCategory = cat
        txtPath.text = "Filtro: $cat"
        Toast.makeText(this, "Ricerca in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            val found = mutableListOf<FileItem>()
            try { scanRecursive(File(rootInternal), found, cat, 0) } catch (_: Exception) {}
            val sorted = when (sortBy) {
                "size" -> found.sortedByDescending { it.size }
                "date" -> found.sortedByDescending { it.lastModified }
                else -> found.sortedBy { it.name.lowercase() }
            }
            mainHandler.post { displayedItems = sorted; renderList() }
        }
    }

    private fun scanRecursive(dir: File, out: MutableList<FileItem>, cat: String, depth: Int) {
        if (depth > 8) return
        val dirName = dir.name
        if (dirName == "Android" || dirName == ".trash" || dirName == ".thumbnails") return

        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!showHidden && name.startsWith(".")) continue
            if (f.isDirectory) scanRecursive(f, out, cat, depth + 1)
            else {
                val match = if (cat == "documents") isDocumentFile(name) else categoryFor(name) == cat
                if (match) {
                    out.add(FileItem(f, name, f.absolutePath, false, f.length(), f.lastModified(), 0))
                }
            }
        }
    }

    private fun renderList() {
        val firstVisible = try {
            when (val lm = recycler.layoutManager) {
                is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                else -> 0
            }
        } catch (_: Exception) { 0 }

        recycler.layoutManager = if (isGrid) GridLayoutManager(this, 4) else LinearLayoutManager(this)

        recycler.adapter = FileAdapter(
            items = displayedItems,
            isGrid = isGrid,
            selectionMode = selectionMode,
            selectedPaths = selectedPaths,
            onClick = { item ->
                if (selectionMode) toggleSelection(item)
                else {
                    if (item.isDirectory) loadDirectory(item.path) else openFileWithDefault(item)
                }
            },
            onLongClick = { item ->
                if (selectionMode) toggleSelection(item) else enterSelectionMode(item)
            }
        )

        if (firstVisible > 0 && displayedItems.isNotEmpty()) {
            recycler.scrollToPosition(firstVisible)
        }
    }

    // ---------- APERTURA FILE ----------

    private fun getCategoryKey(mimeType: String, fileName: String): String {
        return when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty()) "ext_$ext" else "document"
            }
        }
    }

    private fun openFileWithDefault(item: FileItem) {
        if (item.name.lowercase().endsWith(".apk")) { installApk(item); return }
        val mimeType = getMimeType(item.name)
        val categoryKey = getCategoryKey(mimeType, item.name)
        val savedPackage = prefs.getString("app_for_$categoryKey", null)
        if (savedPackage != null && tryOpenWithPackage(item, savedPackage, mimeType)) return
        showCustomAppPicker(item, mimeType, categoryKey)
    }

    private fun installApk(item: FileItem) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Permesso richiesto")
                    .setMessage("Per installare APK, devi autorizzare il File Manager.")
                    .setPositiveButton("Apri impostazioni") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("", uri)
            }
            for (ri in packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryOpenWithPackage(item: FileItem, pkgName: String, mimeType: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                setPackage(pkgName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("", uri)
            }
            for (ri in packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent); true
        } catch (_: Exception) { false }
    }

    private fun showCustomAppPicker(item: FileItem, mimeType: String, categoryKey: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val filtered = packageManager.queryIntentActivities(probe, 0)
                .filter { it.activityInfo.packageName != packageName }

            if (filtered.isEmpty()) {
                Toast.makeText(this, "Nessuna app per aprire questo file", Toast.LENGTH_LONG).show(); return
            }
            if (filtered.size == 1) {
                val app = filtered.first()
                prefs.edit().putString("app_for_$categoryKey", app.activityInfo.packageName).apply()
                openWithResolveInfo(item, app, uri, mimeType); return
            }
            val density = resources.displayMetrics.density
            val adapter = object : BaseAdapter() {
                override fun getCount() = filtered.size
                override fun getItem(position: Int) = filtered[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val view = convertView ?: layoutInflater.inflate(android.R.layout.activity_list_item, parent, false)
                    val app = filtered[position]
                    val iconView = view.findViewById<ImageView>(android.R.id.icon)
                    val textView = view.findViewById<TextView>(android.R.id.text1)
                    val params = iconView.layoutParams
                    params.width = (64 * density).toInt(); params.height = (64 * density).toInt()
                    iconView.layoutParams = params
                    iconView.setImageDrawable(app.loadIcon(packageManager))
                    textView.text = app.loadLabel(packageManager).toString()
                    textView.textSize = 20f
                    textView.setPadding((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt())
                    return view
                }
            }
            AlertDialog.Builder(this)
                .setTitle("Apri con...")
                .setAdapter(adapter) { _, which ->
                    val app = filtered[which]
                    prefs.edit().putString("app_for_$categoryKey", app.activityInfo.packageName).apply()
                    openWithResolveInfo(item, app, uri, mimeType)
                }
                .setNegativeButton("Annulla", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWithResolveInfo(item: FileItem, app: ResolveInfo, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                setComponent(ComponentName(app.activityInfo.packageName, app.activityInfo.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("", uri)
            }
            for (ri in packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openFileWithPicker(item: FileItem) {
        val mimeType = getMimeType(item.name)
        val categoryKey = getCategoryKey(mimeType, item.name)
        prefs.edit().remove("app_for_$categoryKey").apply()
        showCustomAppPicker(item, mimeType, categoryKey)
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
            l.endsWith(".pdf") -> "application/pdf"
            l.endsWith(".zip") -> "application/zip"
            l.endsWith(".rar") -> "application/x-rar-compressed"
            l.endsWith(".txt") -> "text/plain"
            l.endsWith(".html") || l.endsWith(".htm") -> "text/html"
            l.endsWith(".json") -> "application/json"
            l.endsWith(".xml") -> "text/xml"
            l.endsWith(".doc") -> "application/msword"
            l.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            l.endsWith(".xls") -> "application/vnd.ms-excel"
            l.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            l.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
            l.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            l.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    // ---------- CONDIVIDI ----------

    private fun shareFile(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(item.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("", uri)
            }
            startActivity(Intent.createChooser(intent, "Condividi con..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- COPIA/INCOLLA ----------

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

        Toast.makeText(this, if (action == "cut") "Spostamento in corso..." else "Copia in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var copied = 0
            var errorMsg = ""

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

                if (action == "cut") {
                    try {
                        renamed = src.renameTo(dst)
                        ok = renamed
                    } catch (_: Exception) {}
                }

                if (!ok) {
                    try {
                        if (src.isDirectory) copyDirectoryRecursive(src, dst)
                        else copyFile(src, dst)
                        ok = true
                    } catch (e: Exception) {
                        errorMsg += "\n${src.name}: ${e.message}"
                    }
                }

                if (!ok) {
                    try { ok = copyViaSaf(src, dst) } catch (_: Exception) {}
                }

                if (ok) {
                    copied++
                    scanPath(dst.absolutePath)

                    if (action == "cut" && !renamed && dst.absolutePath != src.absolutePath && src.exists()) {
                        try {
                            var delOk = if (src.isDirectory) deleteRecursively(src) else src.delete()
                            if (!delOk) {
                                val doc = getSafDocumentFile(src.absolutePath)
                                if (doc != null) {
                                    delOk = if (doc.isDirectory) deleteDocumentRecursive(doc) else doc.delete()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val finalCopied = copied
            val finalErr = errorMsg
            val wasCut = action == "cut"

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
                    loadDirectory(currentPath)
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

    private fun copyViaSaf(src: File, dst: File): Boolean {
        return try {
            val parentDir = dst.parentFile ?: return false
            val parentDoc = getSafDocumentFile(parentDir.absolutePath) ?: return false

            if (src.isDirectory) {
                val newDir = parentDoc.createDirectory(src.name) ?: return false
                copyDirViaSaf(src, newDir)
                true
            } else {
                val mimeType = getMimeType(src.name)
                val newFile = parentDoc.createFile(mimeType, src.name) ?: return false
                contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    FileInputStream(src).use { input ->
                        val buffer = ByteArray(8192)
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

    private fun copyDirViaSaf(src: File, dstDoc: DocumentFile) {
        val files = src.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                val newDir = dstDoc.createDirectory(f.name) ?: continue
                copyDirViaSaf(f, newDir)
            } else {
                val mimeType = getMimeType(f.name)
                val newFile = dstDoc.createFile(mimeType, f.name) ?: continue
                try {
                    contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        FileInputStream(f).use { input ->
                            val buffer = ByteArray(8192)
                            var length: Int
                            while (input.read(buffer).also { length = it } > 0) {
                                output.write(buffer, 0, length)
                            }
                            output.flush()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buffer = ByteArray(8192)
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
        if (!dst.exists()) dst.mkdirs()
        val files = src.listFiles() ?: return
        for (f in files) {
            val newFile = File(dst, f.name)
            if (f.isDirectory) copyDirectoryRecursive(f, newFile) else copyFile(f, newFile)
        }
    }

    // ---------- COMPRIMI SINGOLO ----------

    private fun comprimiZip(item: FileItem) {
        val zipName = if (item.isDirectory) "${item.name}.zip" else item.name.substringBeforeLast(".") + ".zip"
        if (File(currentPath, zipName).exists()) {
            Toast.makeText(this, "Esiste già: $zipName", Toast.LENGTH_LONG).show(); return
        }
        Toast.makeText(this, "Compressione in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var ok = false
            var errorMsg = ""

            try {
                ok = comprimiZipViaSaf(item, zipName)
                if (!ok) errorMsg = "SAF: compressione fallita"
            } catch (e: Exception) { ok = false; errorMsg = "SAF: ${e.message}" }

            if (!ok) {
                val zipFile = File(currentPath, zipName)
                try {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        if (item.isDirectory) addDirectoryToZip(item.file, item.file.name, zos)
                        else addFileToZip(item.file, item.file.name, zos)
                    }
                    ok = true; errorMsg = ""
                } catch (e: Exception) {
                    ok = false; errorMsg += " | File: ${e.message}"
                    try { zipFile.delete() } catch (_: Exception) {}
                }
            }

            if (ok) scanPath(File(currentPath, zipName).absolutePath)

            mainHandler.post {
                if (ok) {
                    Toast.makeText(this, "Creato: $zipName", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Compressione")
                        .setMessage("File: ${item.name}\n\nErrore:\n$errorMsg")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun comprimiZipViaSaf(item: FileItem, zipName: String): Boolean {
        return try {
            val parentDir = File(currentPath)
            val parentDoc = getSafDocumentFile(parentDir.absolutePath) ?: return false
            val newZipDoc = parentDoc.createFile("application/zip", zipName) ?: return false
            val outputStream = contentResolver.openOutputStream(newZipDoc.uri) ?: return false
            ZipOutputStream(outputStream).use { zos ->
                if (item.isDirectory) addDirectoryToZip(item.file, item.file.name, zos)
                else addFileToZip(item.file, item.file.name, zos)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun addDirectoryToZip(dir: File, basePath: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entryName = "$basePath/${f.name}"
            if (f.isDirectory) addDirectoryToZip(f, entryName, zos) else addFileToZip(f, entryName, zos)
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            zos.putNextEntry(ZipEntry(entryName))
            val buffer = ByteArray(8192)
            var length: Int
            while (fis.read(buffer).also { length = it } > 0) {
                zos.write(buffer, 0, length)
            }
            zos.closeEntry()
        }
    }

    // ---------- DECOMPRIMI ----------

    private fun decomprimiZip(item: FileItem) {
        if (!item.name.lowercase().endsWith(".zip")) {
            Toast.makeText(this, "Non è un file ZIP", Toast.LENGTH_SHORT).show(); return
        }
        val zipSource = item.file
        if (!zipSource.exists()) {
            Toast.makeText(this, "File non trovato: ${item.name}", Toast.LENGTH_LONG).show(); return
        }
        val baseName = item.name.substringBeforeLast(".")
        Toast.makeText(this, "Decompressione in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var filesExtracted = 0
            var errorMsg = ""
            try {
                val parentDoc = getSafDocumentFile(currentPath)
                val extractDir = File(currentPath, baseName)
                if (!extractDir.exists()) extractDir.mkdirs()

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
                            var written = false
                            try {
                                FileOutputStream(outFile).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var length: Int
                                    while (zis.read(buffer).also { length = it } > 0) {
                                        fos.write(buffer, 0, length)
                                    }
                                    fos.flush()
                                }
                                written = true
                            } catch (_: Exception) {}
                            if (!written && parentDoc != null) written = tryWriteViaSaf(parentDoc, entryName, zis)
                            if (written) filesExtracted++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) { errorMsg = e.message ?: e.toString() }

            if (filesExtracted > 0) scanPath(File(currentPath, baseName).absolutePath)

            val extracted = filesExtracted
            val err = errorMsg
            mainHandler.post {
                if (extracted > 0) {
                    Toast.makeText(this, "Estratti $extracted file in: $baseName/", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Decompressione")
                        .setMessage("File: ${item.name}\n\nNessun file estratto.\n$err")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun tryWriteViaSaf(parentDoc: DocumentFile, entryName: String, zis: ZipInputStream): Boolean {
        return try {
            val parts = entryName.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return false
            val fileName = parts.last()
            val folderParts = if (parts.size > 1) parts.dropLast(1) else emptyList()
            var currentDoc = parentDoc
            for (part in folderParts) {
                var next = currentDoc.findFile(part)
                if (next == null) next = currentDoc.createDirectory(part)
                if (next == null) return false
                currentDoc = next
            }
            val newFile = currentDoc.createFile("application/octet-stream", fileName) ?: return false
            val outputStream = contentResolver.openOutputStream(newFile.uri) ?: return false
            outputStream.use { fos ->
                val buffer = ByteArray(8192)
                var length: Int
                while (zis.read(buffer).also { length = it } > 0) {
                    fos.write(buffer, 0, length)
                }
                fos.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    // ---------- RINOMINA ----------

    private fun renameItem(item: FileItem) {
        val input = EditText(this)
        input.setText(item.name)
        AlertDialog.Builder(this)
            .setTitle("Rinomina")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton
                try {
                    val newFile = File(item.file.parentFile, newName)
                    if (item.file.renameTo(newFile)) {
                        updateInMediaStore(item.path, newFile.absolutePath)
                        scanPath(newFile.absolutePath)
                        Toast.makeText(this, "Rinominato", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath); return@setPositiveButton
                    }
                } catch (_: Exception) {}
                requestSaf {
                    val doc = getSafDocumentFile(item.path)
                    if (doc != null && doc.renameTo(newName)) {
                        Toast.makeText(this, "Rinominato (SAF)", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else Toast.makeText(this, "Impossibile rinominare", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    // ---------- MEDIASTORE ----------

    private fun updateInMediaStore(oldPath: String, newPath: String) {
        try {
            if (File(newPath).isDirectory) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, newPath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, File(newPath).name)
                put(MediaStore.MediaColumns.TITLE, File(newPath).name)
            }
            contentResolver.update(getMediaStoreUri(oldPath), values, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(oldPath))
        } catch (_: Exception) {}
    }

    private fun getMediaStoreUri(path: String): Uri {
        val l = path.lowercase()
        return when {
            l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") ||
            l.endsWith(".gif") || l.endsWith(".webp") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi") ||
            l.endsWith(".mov") || l.endsWith(".webm") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".ogg") ||
            l.endsWith(".flac") || l.endsWith(".m4a") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
    }

    private fun scanPath(path: String) {
        try {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(File(path))
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ---------- ALTRO ----------

    private fun showItemInfo(item: FileItem) {
        val info = buildString {
            append("Percorso: ${item.path}\n")
            if (!item.isDirectory) append("Dimensione: ${formatSize(item.size)}\n")
            append("Modificato: ${java.util.Date(item.lastModified)}")
        }
        AlertDialog.Builder(this)
            .setTitle(item.name).setMessage(info)
            .setPositiveButton("OK", null).show()
    }

    private fun createFolder() {
        val input = EditText(this)
        input.hint = "Nome cartella"
        AlertDialog.Builder(this)
            .setTitle("Nuova cartella")
            .setView(input)
            .setPositiveButton("Crea") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                try {
                    val newDir = File(currentPath, name)
                    if (newDir.mkdir()) {
                        scanPath(newDir.absolutePath)
                        Toast.makeText(this, "Cartella creata", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath); return@setPositiveButton
                    }
                } catch (_: Exception) {}
                requestSaf {
                    val parent = getSafDocumentFile(currentPath)
                    if (parent != null && parent.createDirectory(name) != null) {
                        Toast.makeText(this, "Cartella creata (SAF)", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else Toast.makeText(this, "Impossibile creare", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun showSortDialog() {
        val options = arrayOf("Nome", "Dimensione", "Data")
        val current = when (sortBy) { "size" -> 1; "date" -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("Ordina per")
            .setSingleChoiceItems(options, current) { dialog, which ->
                sortBy = when (which) { 1 -> "size"; 2 -> "date"; else -> "name" }
                prefs.edit().putString("sort_by", sortBy).apply()
                updateSortLabel()
                applyFilters()
                dialog.dismiss()
            }.show()
    }

    private fun updateSortLabel() {
        val label = when (sortBy) { "size" -> "Dimensione"; "date" -> "Data"; else -> "Nome" }
        txtSort.text = "Ordina per $label"
    }

    private fun toggleView() {
        isGrid = !isGrid
        prefs.edit().putBoolean("is_grid", isGrid).apply()
        updateViewToggleIcon()
        renderList()
    }

    private fun showSettingsDialog() {
        val options = mutableListOf<String>()
        options.add("Aggiorna cartella")
        options.add(if (showHidden) "Nascondi file nascosti" else "Mostra file nascosti")
        options.add("Rinnova permesso scrittura")

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { Toast.makeText(this, "Aggiornamento...", Toast.LENGTH_SHORT).show(); loadDirectory(currentPath) }
                    1 -> {
                        showHidden = !showHidden
                        prefs.edit().putBoolean("show_hidden", showHidden).apply()
                        loadDirectory(currentPath)
                    }
                    2 -> {
                        safTreeUri = null
                        prefs.edit().remove("saf_tree_uri").apply()
                        requestSaf { Toast.makeText(this, "Permesso rinnovato", Toast.LENGTH_SHORT).show() }
                    }
                }
            }.show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.1f GB", mb / 1024.0)
    }

    private fun goBack() {
        if (selectionMode) { exitSelectionMode(); return }
        if (currentPath == rootInternal) return
        val parent = File(currentPath).parent
        if (parent != null && parent.startsWith(rootInternal)) loadDirectory(parent)
        else if (currentPath.startsWith("/storage/") || currentPath.startsWith("/mnt/"))
            loadDirectory(rootInternal, resetCategory = true)
    }

    override fun onBackPressed() {
        if (selectionMode) exitSelectionMode()
        else if (currentPath != rootInternal) goBack()
        else super.onBackPressed()
    }

    data class StorageVolumeInfo(
        val label: String,
        val path: String,
        val usedBytes: Long,
        val totalBytes: Long
    )
}
