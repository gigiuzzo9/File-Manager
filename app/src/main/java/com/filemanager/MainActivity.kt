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
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SAF = 1001
        private const val BUFFER_SIZE = 65536
        private const val ZIP_BUFFER_SIZE = 32768
        private val COPY_THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(4, 8)
        private const val PREFS_SAF_MAP = "saf_tree_uris_map"
        private const val PREFS_SAF_INTERNAL_REQUESTED = "saf_requested_internal"
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

    private val safTreeUris = mutableMapOf<String, Uri>()

    private var pendingSafPath: String? = null
    private var pendingSafAction: (() -> Unit)? = null
    private var pendingSafOnDenied: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val heavyExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val storagePollRunnable = object : Runnable {
        private var lastSnapshot: String = ""
        override fun run() {
            try {
                val sm = getSystemService(STORAGE_SERVICE) as StorageManager
                val snapshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    sm.storageVolumes.joinToString("|") {
                        "${it.uuid}:${it.state}:${it.isRemovable}"
                    }
                } else ""

                if (snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    updateStorageCards()

                    if (currentPath.startsWith("/storage/") &&
                        currentPath != rootInternal &&
                        !File(currentPath).exists()
                    ) {
                        loadDirectory(rootInternal, resetCategory = true)
                    }
                }
            } catch (_: Exception) {}

            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("filemanager", MODE_PRIVATE)
        showHidden = prefs.getBoolean("show_hidden", false)
        isGrid = prefs.getBoolean("is_grid", false)
        sortBy = prefs.getString("sort_by", "name") ?: "name"

        currentPath = rootInternal
        loadSafTreeUris()

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
        mainHandler.removeCallbacks(storagePollRunnable)
        mainHandler.post(storagePollRunnable)

        if (hasStoragePermission()) {
            loadDirectory(currentPath)

            if (!hasSafFor(rootInternal) && !prefs.getBoolean(PREFS_SAF_INTERNAL_REQUESTED, false)) {
                prefs.edit().putBoolean(PREFS_SAF_INTERNAL_REQUESTED, true).apply()
                requestSafForPath(rootInternal) {
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
        mainHandler.removeCallbacks(storagePollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        heavyExecutor.shutdown()
    }

    // ============================================================
    // HELPER
    // ============================================================

    private fun isInternalPath(path: String): Boolean {
        return path == rootInternal || path.startsWith("$rootInternal/")
    }

    private fun useFileApi(path: String): Boolean = isInternalPath(path)

    // ============================================================
    // SAF
    // ============================================================

    private fun loadSafTreeUris() {
        safTreeUris.clear()
        try {
            val json = prefs.getString(PREFS_SAF_MAP, null) ?: return
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val path = obj.getString("path")
                val uri = Uri.parse(obj.getString("uri"))
                val hasPerm = try {
                    contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission && it.isWritePermission
                    }
                } catch (_: Exception) { false }
                if (hasPerm) safTreeUris[path] = uri
            }
        } catch (_: Exception) {}
    }

    private fun saveSafTreeUris() {
        try {
            val arr = org.json.JSONArray()
            for ((path, uri) in safTreeUris) {
                val obj = org.json.JSONObject()
                obj.put("path", path)
                obj.put("uri", uri.toString())
                arr.put(obj)
            }
            prefs.edit().putString(PREFS_SAF_MAP, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun registerSafTreeUri(rootPathForVolume: String, uri: Uri) {
        safTreeUris[rootPathForVolume] = uri
        saveSafTreeUris()
    }

    private fun getSafTreeFor(path: String): Uri? {
        var best: Pair<String, Uri>? = null
        for ((basePath, uri) in safTreeUris) {
            if (path == basePath || path.startsWith("$basePath/")) {
                if (best == null || basePath.length > best.first.length) {
                    best = basePath to uri
                }
            }
        }
        return best?.second
    }

    private fun getSafBaseFor(path: String): String? {
        var best: String? = null
        for (basePath in safTreeUris.keys) {
            if (path == basePath || path.startsWith("$basePath/")) {
                if (best == null || basePath.length > best!!.length) best = basePath
            }
        }
        return best
    }

    private fun hasSafFor(path: String): Boolean = getSafTreeFor(path) != null

    private fun getVolumeRootFor(path: String): String {
        if (isInternalPath(path)) return rootInternal
        val parts = path.trimStart('/').split('/')
        if (parts.size >= 2 && parts[0] == "storage") {
            return "/storage/${parts[1]}"
        }
        val f = File(path)
        var cur: File? = f
        while (cur != null && cur.parentFile != null &&
            cur.parentFile!!.absolutePath != "/storage" &&
            cur.parentFile!!.absolutePath != "/"
        ) {
            cur = cur.parentFile
        }
        return cur?.absolutePath ?: path
    }

    private fun requestSafForPath(path: String, onDenied: (() -> Unit)? = null) {
        val volumeRoot = getVolumeRootFor(path)
        pendingSafPath = volumeRoot
        pendingSafOnDenied = onDenied
        pendingSafAction = {
            if (currentPath == volumeRoot || currentPath.startsWith("$volumeRoot/")) {
                loadDirectory(currentPath)
            } else if (currentPath == path || currentPath.startsWith("$path/")) {
                loadDirectory(currentPath)
            }
            updateStorageCards()
            updatePasteButton()
        }

        try {
            val intent = buildOpenDocumentTreeIntent(volumeRoot)
            startActivityForResult(intent, REQ_SAF)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore apertura SAF: ${e.message}", Toast.LENGTH_LONG).show()
            pendingSafPath = null
            pendingSafAction = null
            pendingSafOnDenied = null
        }
    }

    private fun buildOpenDocumentTreeIntent(volumeRoot: String): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val sm = getSystemService(STORAGE_SERVICE) as StorageManager
                val sv: StorageVolume? = sm.getStorageVolume(File(volumeRoot))
                if (sv != null) {
                    val intent = sv.createOpenDocumentTreeIntent()
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    return intent
                }
            } catch (_: Exception) {}
        }

        val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            val initial = if (volumeRoot == rootInternal) {
                Uri.parse("content://com.android.externalstorage.documents/root/primary")
            } else {
                val uuid = volumeRoot.substringAfterLast('/')
                Uri.parse("content://com.android.externalstorage.documents/root/$uuid")
            }
            fallback.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        } catch (_: Exception) {}
        return fallback
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAF) return

        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                pendingSafPath = null
                pendingSafAction = null
                pendingSafOnDenied = null
                return
            }

            val volumeRoot = pendingSafPath ?: rootInternal

            val selectedDocId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) { null }

            val expectedDocId = when {
                volumeRoot == rootInternal -> "primary:"
                else -> {
                    val uuid = volumeRoot.substringAfterLast('/')
                    "$uuid:"
                }
            }

            if (selectedDocId != null && !selectedDocId.equals(expectedDocId, ignoreCase = true)) {
                AlertDialog.Builder(this)
                    .setTitle("Seleziona la root")
                    .setMessage(
                        "Hai selezionato una sottocartella.\n\n" +
                        "Per accedere a TUTTO il volume devi selezionare la cartella " +
                        "più in alto (la root).\n\n" +
                        "Vuoi riprovare?"
                    )
                    .setPositiveButton("Riprova") { _, _ ->
                        pendingSafPath = null
                        pendingSafAction = null
                        pendingSafOnDenied = null
                        requestSafForPath(volumeRoot)
                    }
                    .setNegativeButton("Annulla") { _, _ ->
                        val denied = pendingSafOnDenied
                        pendingSafPath = null
                        pendingSafAction = null
                        pendingSafOnDenied = null
                        denied?.invoke()
                    }
                    .setCancelable(false)
                    .show()
                return
            }

            acceptSafPermission(uri, volumeRoot)
        } else {
            val denied = pendingSafOnDenied
            pendingSafPath = null
            pendingSafAction = null
            pendingSafOnDenied = null
            Toast.makeText(this, "Permesso negato", Toast.LENGTH_SHORT).show()
            denied?.invoke()
        }
    }

    private fun acceptSafPermission(uri: Uri, volumeRoot: String) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            registerSafTreeUri(volumeRoot, uri)
            Toast.makeText(this, "Permesso concesso", Toast.LENGTH_SHORT).show()

            val action = pendingSafAction
            pendingSafPath = null
            pendingSafAction = null
            pendingSafOnDenied = null
            action?.invoke()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore permesso: ${e.message}", Toast.LENGTH_LONG).show()
            pendingSafPath = null
            pendingSafAction = null
            pendingSafOnDenied = null
        }
    }

    private fun openDirectoryWithSafCheck(path: String) {
        if (isInternalPath(path) || hasSafFor(path)) {
            loadDirectory(path)
            return
        }
        requestSafForPath(path) {
            Toast.makeText(this, "Permesso necessario per accedere a questo volume", Toast.LENGTH_LONG).show()
            loadDirectory(rootInternal, resetCategory = true)
        }
    }

    // ============================================================
    // DOCUMENTFILE HELPERS
    // ============================================================

    private fun getSafDocumentFile(path: String): DocumentFile? {
        val basePath = getSafBaseFor(path) ?: return null
        val tree = safTreeUris[basePath] ?: return null
        val rel = path.removePrefix(basePath).trimStart('/')
        var doc = DocumentFile.fromTreeUri(this, tree) ?: return null
        if (rel.isEmpty()) return doc
        for (part in rel.split("/").filter { it.isNotEmpty() }) {
            val next = doc.findFile(part) ?: return null
            doc = next
        }
        return doc
    }

    private fun readSizeFromUri(uri: Uri): Long {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                    val l = c.getLong(idx)
                    if (l > 0L) return l
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun readLastModifiedFromUri(uri: Uri): Long {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                    return c.getLong(idx)
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    // ============================================================
    // LOAD DIRECTORY
    // ============================================================

    private fun loadDirectory(path: String, resetCategory: Boolean = false) {
        currentPath = path
        txtPath.text = path
        if (resetCategory) {
            activeCategory = null
            searchQuery = ""
        }

        executor.execute {
            val result: List<FileItem>? = if (useFileApi(path)) {
                loadViaFileApi(path)
            } else {
                loadViaSaf(path)
            }

            mainHandler.post {
                if (result == null) {
                    Toast.makeText(this, "Cartella non accessibile", Toast.LENGTH_SHORT).show()
                    return@post
                }
                allItems = result
                applyFilters()
                updatePasteButton()
            }
        }
    }

    private fun loadViaFileApi(path: String): List<FileItem>? {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        val filtered = if (showHidden) files.toList() else files.filter { !it.name.startsWith(".") }
        return filtered.map { f ->
            val isDir = f.isDirectory
            FileItem(
                file = f,
                name = f.name,
                path = f.absolutePath,
                isDirectory = isDir,
                size = if (isDir) 0L else f.length(),
                lastModified = f.lastModified(),
                childrenCount = if (isDir) {
                    try {
                        val arr = f.list() ?: emptyArray()
                        if (showHidden) arr.size else arr.count { !it.startsWith(".") }
                    } catch (_: Exception) { 0 }
                } else 0
            )
        }
    }

    private fun loadViaSaf(path: String): List<FileItem>? {
        val doc = getSafDocumentFile(path) ?: return null
        if (!doc.isDirectory) return null

        val files = doc.listFiles()
        val result = mutableListOf<FileItem>()

        for (d in files) {
            val name = d.name ?: continue
            if (!showHidden && name.startsWith(".")) continue

            val isDir = d.isDirectory
            var size = 0L
            var lastMod = 0L

            if (!isDir) {
                size = readSizeFromUri(d.uri)
                if (size <= 0L) {
                    try { size = d.length() } catch (_: Exception) {}
                }
                lastMod = readLastModifiedFromUri(d.uri)
                if (lastMod <= 0L) {
                    try { lastMod = d.lastModified() } catch (_: Exception) {}
                }
            } else {
                try { lastMod = d.lastModified() } catch (_: Exception) {}
            }

            result.add(
                FileItem(
                    file = File(path, name),
                    name = name,
                    path = "$path/$name",
                    isDirectory = isDir,
                    size = size,
                    lastModified = lastMod,
                    childrenCount = 0
                )
            )
        }
        return result
    }

    private fun applyFilters() {
        var list = allItems.toList()

        if (searchQuery.isNotEmpty()) {
            list = list.filter { it.name.lowercase().contains(searchQuery) }
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
            try { scanRecursiveInternal(File(rootInternal), found, cat, 0) } catch (_: Exception) {}
            val sorted = when (sortBy) {
                "size" -> found.sortedByDescending { it.size }
                "date" -> found.sortedByDescending { it.lastModified }
                else -> found.sortedBy { it.name.lowercase() }
            }
            mainHandler.post { displayedItems = sorted; renderList() }
        }
    }

    private fun scanRecursiveInternal(dir: File, out: MutableList<FileItem>, cat: String, depth: Int) {
        if (depth > 8) return
        val dirName = dir.name
        if (dirName == "Android" || dirName == ".trash" || dirName == ".thumbnails") return

        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!showHidden && name.startsWith(".")) continue
            if (f.isDirectory) scanRecursiveInternal(f, out, cat, depth + 1)
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
                    if (item.isDirectory) openDirectoryWithSafCheck(item.path)
                    else openFileWithDefault(item)
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

    // ============================================================
    // SELECTION
    // ============================================================

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
            if (selectedPaths.isEmpty()) { exitSelectionMode(); return }
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
    // DELETE
    // ============================================================

    private fun deleteSelectedFiles() {
        if (selectedPaths.isEmpty()) return
        val count = selectedPaths.size
        val pathsToDelete = selectedPaths.toList()

        AlertDialog.Builder(this)
            .setTitle("Elimina")
            .setMessage("Eliminare $count file?")
            .setPositiveButton("Elimina") { _, _ ->
                allItems = allItems.filter { !selectedPaths.contains(it.path) }
                displayedItems = displayedItems.filter { !selectedPaths.contains(it.path) }
                exitSelectionMode()
                renderList()

                executor.execute {
                    for (path in pathsToDelete) {
                        try {
                            if (useFileApi(path)) {
                                val f = File(path)
                                if (f.exists()) {
                                    val ok = if (f.isDirectory) deleteRecursivelyFast(f) else f.delete()
                                    if (!ok) deleteViaSaf(path)
                                }
                            } else {
                                deleteViaSaf(path)
                            }
                        } catch (_: Exception) {}
                    }
                    mainHandler.post { scanPath(currentPath) }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteRecursivelyFast(file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.walk(file.toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach { p -> try { Files.deleteIfExists(p) } catch (_: Exception) {} }
                !file.exists()
            } else deleteRecursivelyLegacy(file)
        } catch (_: Exception) { deleteRecursivelyLegacy(file) }
    }

    private fun deleteRecursivelyLegacy(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursivelyLegacy(it) }
        }
        return try { file.delete() } catch (_: Exception) { false }
    }

    private fun deleteViaSaf(path: String): Boolean {
        val doc = getSafDocumentFile(path) ?: return false
        return try {
            if (doc.isDirectory) deleteDocumentRecursive(doc) else doc.delete()
        } catch (_: Exception) { false }
    }

    private fun deleteDocumentRecursive(doc: DocumentFile): Boolean {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) deleteDocumentRecursive(child)
        }
        return doc.delete()
    }

    // ============================================================
    // COPY / PASTE
    // ============================================================

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

    private fun pasteFromClipboard() {
        if (clipboardPaths.isEmpty()) {
            Toast.makeText(this, "Niente negli appunti", Toast.LENGTH_SHORT).show()
            return
        }

        val srcPaths = clipboardPaths.toList()
        val action = clipboardAction
        val dstDir = File(currentPath)
        val dstPath = dstDir.absolutePath

        if (useFileApi(dstPath)) {
            pasteInternal(srcPaths, action, dstDir, dstPath)
        } else {
            pasteToSaf(srcPaths, action, dstPath)
        }
    }

    private fun pasteInternal(srcPaths: List<String>, action: String?, dstDir: File, dstPath: String) {
        val existing = srcPaths.filter { File(it).exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "Nessun file originale trovato", Toast.LENGTH_SHORT).show()
            clearClipboard()
            return
        }

        val placeholders = mutableListOf<FileItem>()
        val pending = mutableListOf<Pair<String, File>>()

        for (srcPath in existing) {
            val src = File(srcPath)
            var dstName = src.name
            var dst = File(dstDir, dstName)
            if (dst.absolutePath == src.absolutePath || dst.exists()) {
                dstName = generateUniqueName(dstDir, src.name)
                dst = File(dstDir, dstName)
            }
            placeholders.add(
                FileItem(
                    file = dst, name = dstName, path = dst.absolutePath,
                    isDirectory = src.isDirectory,
                    size = if (src.isFile) src.length() else 0L,
                    lastModified = System.currentTimeMillis(), childrenCount = 0
                )
            )
            pending.add(srcPath to dst)
        }

        allItems = allItems + placeholders
        applyFilters()
        Toast.makeText(this,
            if (action == "cut") "Spostamento in corso..." else "Copia in corso...",
            Toast.LENGTH_SHORT).show()

        val finalAction = action ?: "copy"
        clearClipboard()
        exitSelectionMode()
        updatePasteButton()

        heavyExecutor.execute {
            var copied = 0
            var errorMsg = ""
            val pool = Executors.newFixedThreadPool(COPY_THREADS)

            try {
                for ((index, pair) in pending.withIndex()) {
                    val srcPath = pair.first
                    val src = File(srcPath)
                    val dst = placeholders[index].file
                    var ok = false
                    var renamed = false

                    if (finalAction == "cut") {
                        try { renamed = src.renameTo(dst); ok = renamed } catch (_: Exception) {}
                    }

                    if (!ok) {
                        try {
                            if (src.isDirectory) { copyDirectoryRecursiveParallel(src, dst, pool); ok = true }
                            else { copyFile(src, dst); ok = true }
                        } catch (e: Exception) { errorMsg += "\n${src.name}: ${e.message}" }
                    }

                    if (!ok && !useFileApi(srcPath)) {
                        try {
                            ok = if (src.isDirectory) copyDirectoryViaSaf(src, dst) else copyFileViaSaf(src, dst)
                        } catch (_: Exception) {}
                    }

                    if (ok) {
                        copied++
                        if (finalAction == "cut" && !renamed && dst.absolutePath != src.absolutePath && src.exists()) {
                            try {
                                if (useFileApi(src.absolutePath)) {
                                    if (src.isDirectory) deleteRecursivelyFast(src) else src.delete()
                                } else deleteViaSaf(src.absolutePath)
                            } catch (_: Exception) {}
                        }
                    }
                }
            } finally { pool.shutdown() }

            if (copied > 0) scanPath(dstPath)

            val fCopied = copied
            val fErr = errorMsg
            mainHandler.post {
                if (fCopied > 0) {
                    Toast.makeText(this,
                        if (finalAction == "cut") "Spostati $fCopied file" else "Copiati $fCopied file",
                        Toast.LENGTH_SHORT).show()
                } else if (fErr.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Incolla")
                        .setMessage("Nessun file copiato.\n$fErr")
                        .setPositiveButton("OK", null).show()
                }
                if (currentPath == dstPath) loadDirectory(currentPath)
            }
        }
    }

    private fun pasteToSaf(srcPaths: List<String>, action: String?, dstPath: String) {
        Toast.makeText(this,
            if (action == "cut") "Spostamento in corso..." else "Copia in corso...",
            Toast.LENGTH_SHORT).show()

        val finalAction = action ?: "copy"
        clearClipboard()
        exitSelectionMode()
        updatePasteButton()

        heavyExecutor.execute {
            var copied = 0
            var errorMsg = ""

            val dstParentDoc = getSafDocumentFile(dstPath)
            if (dstParentDoc == null) {
                mainHandler.post {
                    AlertDialog.Builder(this)
                        .setTitle("Errore")
                        .setMessage("Impossibile accedere alla destinazione (SAF)")
                        .setPositiveButton("OK", null).show()
                }
                return@execute
            }

            for (srcPath in srcPaths) {
                try {
                    val srcDoc = getSafDocumentFile(srcPath)
                    val srcFile = File(srcPath)

                    val displayName = srcDoc?.name ?: srcFile.name

                    var targetName = displayName
                    if (dstParentDoc.findFile(targetName) != null) {
                        targetName = generateUniqueNameSaf(dstParentDoc, displayName)
                    }

                    val isDir = srcDoc?.isDirectory ?: srcFile.isDirectory

                    if (isDir) {
                        val newDir = dstParentDoc.createDirectory(targetName)
                        if (newDir != null) {
                            copySafDirRecursive(srcDoc, srcFile, newDir)
                            copied++
                        }
                    } else {
                        val ok = copySafFileTo(srcDoc, srcFile, dstParentDoc, targetName)
                        if (ok) copied++
                    }

                    if (copied > 0 && finalAction == "cut") {
                        try {
                            if (srcDoc != null) deleteDocumentRecursive(srcDoc)
                            else if (srcFile.exists() && useFileApi(srcPath)) {
                                if (srcFile.isDirectory) deleteRecursivelyFast(srcFile) else srcFile.delete()
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    errorMsg += "\n${File(srcPath).name}: ${e.message}"
                }
            }

            val fCopied = copied
            val fErr = errorMsg
            mainHandler.post {
                if (fCopied > 0) {
                    Toast.makeText(this,
                        if (finalAction == "cut") "Spostati $fCopied file" else "Copiati $fCopied file",
                        Toast.LENGTH_SHORT).show()
                    if (currentPath == dstPath) loadDirectory(currentPath)
                } else if (fErr.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Incolla")
                        .setMessage("Nessun file copiato.\n$fErr")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun generateUniqueNameSaf(parent: DocumentFile, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        var candidate = "${base}_$i$ext"
        while (parent.findFile(candidate) != null) {
            i++
            candidate = "${base}_$i$ext"
        }
        return candidate
    }

    private fun copySafFileTo(
        srcDoc: DocumentFile?,
        srcFile: File,
        dstParent: DocumentFile,
        targetName: String
    ): Boolean {
        return try {
            val newDoc: DocumentFile = dstParent.createFile(getMimeType(targetName), targetName) ?: return false
            val out: OutputStream = contentResolver.openOutputStream(newDoc.uri) ?: return false
            val input: InputStream = if (srcDoc != null) {
                contentResolver.openInputStream(srcDoc.uri) ?: return false
            } else {
                FileInputStream(srcFile)
            }
            input.use { ins ->
                out.use { outs ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (ins.read(buf).also { len = it } > 0) outs.write(buf, 0, len)
                    outs.flush()
                }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun copySafDirRecursive(srcDoc: DocumentFile?, srcFile: File, dstDoc: DocumentFile) {
        // 1) Sorgente via SAF
        if (srcDoc != null) {
            val doc: DocumentFile = srcDoc
            val children: Array<DocumentFile> = doc.listFiles()
            for (child in children) {
                val safeChild: DocumentFile = child
                val name: String = safeChild.name ?: continue
                if (safeChild.isDirectory) {
                    val sub: DocumentFile = dstDoc.createDirectory(name) ?: continue
                    copySafDirRecursive(safeChild, File(srcFile, name), sub)
                } else {
                    val out: DocumentFile = dstDoc.createFile(getMimeType(name), name) ?: continue
                    try {
                        val ins: InputStream? = contentResolver.openInputStream(safeChild.uri)
                        if (ins != null) {
                            ins.use { input ->
                                val outs: OutputStream? = contentResolver.openOutputStream(out.uri)
                                if (outs != null) {
                                    outs.use { output ->
                                        val buf = ByteArray(BUFFER_SIZE)
                                        var len: Int
                                        while (input.read(buf).also { len = it } > 0) {
                                            output.write(buf, 0, len)
                                        }
                                        output.flush()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            return
        }

        // 2) Sorgente via File
        val files = srcFile.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                val sub: DocumentFile = dstDoc.createDirectory(f.name) ?: continue
                copySafDirRecursive(null, f, sub)
            } else {
                copySafFileTo(null, f, dstDoc, f.name)
            }
        }
    }

    private fun generateUniqueName(dir: File, originalName: String): String {
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        var c = 1
        var cand = "${base}_$c$ext"
        while (File(dir, cand).exists()) { c++; cand = "${base}_$c$ext" }
        return cand
    }

    private fun clearClipboard() {
        clipboardPaths.clear()
        clipboardAction = null
    }

    private fun copyFileViaSaf(src: File, dst: File): Boolean {
        return try {
            val parentDoc = getSafDocumentFile(dst.parentFile?.absolutePath ?: "") ?: return false
            val newFile: DocumentFile = parentDoc.createFile(getMimeType(src.name), src.name) ?: return false
            contentResolver.openOutputStream(newFile.uri)?.use { out ->
                FileInputStream(src).use { ins ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (ins.read(buf).also { len = it } > 0) out.write(buf, 0, len)
                    out.flush()
                }
            } ?: return false
            true
        } catch (_: Exception) { false }
    }

    private fun copyDirectoryViaSaf(src: File, dst: File): Boolean {
        return try {
            val parentDir = dst.parentFile ?: return false
            val parentDoc = getSafDocumentFile(parentDir.absolutePath) ?: return false
            val newDir: DocumentFile = parentDoc.findFile(dst.name) ?: parentDoc.createDirectory(dst.name) ?: return false
            copySafDirRecursive(null, src, newDir)
            true
        } catch (_: Exception) { false }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        try {
            FileInputStream(src).use { ins ->
                FileOutputStream(dst).use { outs ->
                    val inCh = ins.channel; val outCh = outs.channel
                    var pos = 0L; val size = inCh.size()
                    while (pos < size) pos += inCh.transferTo(pos, size - pos, outCh)
                }
            }
            try { dst.setLastModified(src.lastModified()) } catch (_: Exception) {}
            return
        } catch (_: Exception) {}

        try {
            FileInputStream(src).use { ins ->
                FileOutputStream(dst).use { outs ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (ins.read(buf).also { len = it } > 0) outs.write(buf, 0, len)
                    outs.flush()
                }
            }
            try { dst.setLastModified(src.lastModified()) } catch (_: Exception) {}
            return
        } catch (_: Exception) {}

        throw java.io.IOException("Impossibile copiare: ${src.absolutePath} -> ${dst.absolutePath}")
    }

    private fun copyDirectoryRecursiveParallel(src: File, dst: File, pool: java.util.concurrent.ExecutorService) {
        if (!dst.exists()) {
            if (!dst.mkdirs() && !dst.exists()) throw java.io.IOException("Impossibile creare: ${dst.absolutePath}")
        }
        val files = src.listFiles() ?: throw java.io.IOException("Impossibile leggere: ${src.absolutePath}")
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val futures = mutableListOf<Future<*>>()

        for (f in files) {
            val newFile = File(dst, f.name)
            if (f.isDirectory) {
                futures.add(pool.submit {
                    try { copyDirectoryRecursiveParallel(f, newFile, pool) }
                    catch (e: Exception) { errors.add("${f.name}: ${e.message}") }
                })
            } else {
                futures.add(pool.submit {
                    try { copyFile(f, newFile) }
                    catch (e: Exception) { errors.add("${f.name}: ${e.message}") }
                })
            }
        }

        for (future in futures) { try { future.get() } catch (_: Exception) {} }
        if (errors.isNotEmpty()) throw java.io.IOException("Errori:\n${errors.joinToString("\n")}")
    }

    // ============================================================
    // ZIP
    // ============================================================

    private fun comprimiZipSelezioneMultipla() {
        if (selectedPaths.isEmpty()) return
        val pathsToZip = selectedPaths.toList()
        val firstName = File(pathsToZip.first()).name
        val baseName = if (firstName.contains(".")) firstName.substringBeforeLast(".") else firstName

        var zipName = "$baseName.zip"
        var counter = 1
        while (fileExistsIn(currentPath, zipName)) { zipName = "${baseName}_$counter.zip"; counter++ }

        val finalZipName = zipName
        Toast.makeText(this, "Compressione in corso...", Toast.LENGTH_SHORT).show()

        heavyExecutor.execute {
            var ok = false
            var errorMsg = ""

            try {
                if (useFileApi(currentPath)) {
                    val zipFile = File(currentPath, finalZipName)
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        for (path in pathsToZip) {
                            val f = File(path)
                            if (!f.exists()) continue
                            if (f.isDirectory) addDirectoryToZip(f, f.name, zos)
                            else addFileToZip(f, f.name, zos)
                        }
                    }
                    ok = true
                } else {
                    ok = comprimiZipMultiViaSaf(pathsToZip, finalZipName)
                }
            } catch (e: Exception) {
                ok = false
                errorMsg = e.message ?: ""
            }

            if (ok) scanPath(File(currentPath, finalZipName).absolutePath)

            val fOk = ok; val fErr = errorMsg
            mainHandler.post {
                if (fOk) {
                    Toast.makeText(this, "Creato: $finalZipName", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Compressione")
                        .setMessage("Errore:\n$fErr")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun fileExistsIn(dirPath: String, name: String): Boolean {
        return if (useFileApi(dirPath)) File(dirPath, name).exists()
        else getSafDocumentFile(dirPath)?.findFile(name) != null
    }

    private fun comprimiZipMultiViaSaf(pathsToZip: List<String>, zipName: String): Boolean {
        return try {
            val parentDoc = getSafDocumentFile(currentPath) ?: return false
            val newZipDoc: DocumentFile = parentDoc.createFile("application/zip", zipName) ?: return false
            val outputStream = contentResolver.openOutputStream(newZipDoc.uri) ?: return false
            ZipOutputStream(outputStream).use { zos ->
                for (path in pathsToZip) {
                    val srcDoc = getSafDocumentFile(path)
                    val srcFile = File(path)
                    if (srcDoc != null) {
                        val d: DocumentFile = srcDoc
                        val nm: String = d.name ?: "file"
                        if (d.isDirectory) addSafDirToZip(d, nm, zos)
                        else addSafFileToZip(d, nm, zos)
                    } else if (srcFile.exists()) {
                        if (srcFile.isDirectory) addDirectoryToZip(srcFile, srcFile.name, zos)
                        else addFileToZip(srcFile, srcFile.name, zos)
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun addSafDirToZip(dir: DocumentFile, basePath: String, zos: ZipOutputStream) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val entry = "$basePath/$name"
            if (child.isDirectory) addSafDirToZip(child, entry, zos)
            else addSafFileToZip(child, entry, zos)
        }
    }

    private fun addSafFileToZip(doc: DocumentFile, entryName: String, zos: ZipOutputStream) {
        try {
            val ins = contentResolver.openInputStream(doc.uri)
            if (ins != null) {
                ins.use { input ->
                    zos.putNextEntry(ZipEntry(entryName))
                    val buf = ByteArray(ZIP_BUFFER_SIZE)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) zos.write(buf, 0, len)
                    zos.closeEntry()
                }
            }
        } catch (_: Exception) {}
    }

    private fun addDirectoryToZip(dir: File, basePath: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entry = "$basePath/${f.name}"
            if (f.isDirectory) addDirectoryToZip(f, entry, zos) else addFileToZip(f, entry, zos)
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            zos.putNextEntry(ZipEntry(entryName))
            val buf = ByteArray(ZIP_BUFFER_SIZE)
            var len: Int
            while (fis.read(buf).also { len = it } > 0) zos.write(buf, 0, len)
            zos.closeEntry()
        }
    }

    private fun decomprimiZip(item: FileItem) {
        if (!item.name.lowercase().endsWith(".zip")) {
            Toast.makeText(this, "Non è un file ZIP", Toast.LENGTH_SHORT).show(); return
        }
        val baseName = item.name.substringBeforeLast(".")
        Toast.makeText(this, "Decompressione in corso...", Toast.LENGTH_SHORT).show()

        heavyExecutor.execute {
            var filesExtracted = 0
            var errorMsg = ""

            try {
                val srcDoc = if (useFileApi(item.path)) null else getSafDocumentFile(item.path)
                val zipInput: InputStream = if (srcDoc != null) {
                    val doc: DocumentFile = srcDoc
                    val ins = contentResolver.openInputStream(doc.uri)
                    ins ?: throw Exception("Impossibile aprire ZIP")
                } else {
                    FileInputStream(item.file)
                }

                ZipInputStream(zipInput).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (useFileApi(currentPath)) {
                            val extractDir = File(currentPath, baseName)
                            if (!extractDir.exists()) extractDir.mkdirs()
                            val outFile = File(extractDir, entryName)
                            if (outFile.canonicalPath.startsWith(extractDir.canonicalPath)) {
                                if (entry.isDirectory) outFile.mkdirs()
                                else {
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        FileOutputStream(outFile).use { fos ->
                                            val buf = ByteArray(ZIP_BUFFER_SIZE)
                                            var len: Int
                                            while (zis.read(buf).also { len = it } > 0) fos.write(buf, 0, len)
                                            fos.flush()
                                        }
                                        filesExtracted++
                                    } catch (_: Exception) {}
                                }
                            }
                        } else {
                            val parentDoc = getSafDocumentFile(currentPath)
                            if (parentDoc != null) {
                                val ok = writeEntryToSaf(parentDoc, baseName, entryName, entry.isDirectory, zis)
                                if (ok) filesExtracted++
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) { errorMsg = e.message ?: "" }

            val fEx = filesExtracted; val fErr = errorMsg
            mainHandler.post {
                if (fEx > 0) {
                    Toast.makeText(this, "Estratti $fEx file in: $baseName/", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Decompressione")
                        .setMessage("Nessun file estratto.\n$fErr")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun writeEntryToSaf(
        parentDoc: DocumentFile,
        baseName: String,
        entryName: String,
        isDirectory: Boolean,
        zis: ZipInputStream
    ): Boolean {
        return try {
            var base: DocumentFile? = parentDoc.findFile(baseName)
            if (base == null) base = parentDoc.createDirectory(baseName)
            val safeBase: DocumentFile = base ?: return false

            val parts = entryName.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return false
            val fileName = parts.last()
            val folders = parts.dropLast(1)
            var cur: DocumentFile = safeBase
            for (folder in folders) {
                var next: DocumentFile? = cur.findFile(folder)
                if (next == null) next = cur.createDirectory(folder)
                val safeNext: DocumentFile = next ?: return false
                cur = safeNext
            }
            if (isDirectory) {
                cur.createDirectory(fileName)
                return true
            }
            val newFile: DocumentFile = cur.createFile(getMimeType(fileName), fileName) ?: return false
            val out: OutputStream = contentResolver.openOutputStream(newFile.uri) ?: return false
            out.use { os ->
                val buf = ByteArray(ZIP_BUFFER_SIZE)
                var len: Int
                while (zis.read(buf).also { len = it } > 0) os.write(buf, 0, len)
                os.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    // ============================================================
    // RENAME
    // ============================================================

    private fun renameItem(item: FileItem) {
        val input = EditText(this)
        input.setText(item.name)
        AlertDialog.Builder(this)
            .setTitle("Rinomina")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton

                if (useFileApi(item.path)) {
                    try {
                        val newFile = File(item.file.parentFile, newName)
                        if (item.file.renameTo(newFile)) {
                            updateInMediaStore(item.path, newFile.absolutePath)
                            scanPath(newFile.absolutePath)
                            Toast.makeText(this, "Rinominato", Toast.LENGTH_SHORT).show()
                            loadDirectory(currentPath); return@setPositiveButton
                        }
                    } catch (_: Exception) {}
                }

                val doc = getSafDocumentFile(item.path)
                if (doc != null && doc.renameTo(newName)) {
                    Toast.makeText(this, "Rinominato", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    Toast.makeText(this, "Impossibile rinominare", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun updateInMediaStore(oldPath: String, newPath: String) {
        try {
            if (File(newPath).isDirectory) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, newPath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, File(newPath).name)
            }
            contentResolver.update(getMediaStoreUri(oldPath), values,
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(oldPath))
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
            if (!useFileApi(path)) return
            val f = File(path)
            if (f.isFile) {
                MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
            } else {
                f.listFiles()?.forEach { c ->
                    if (c.isFile) {
                        try {
                            MediaScannerConnection.scanFile(this, arrayOf(c.absolutePath), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ============================================================
    // CREATE FOLDER
    // ============================================================

    private fun createFolder() {
        val input = EditText(this)
        input.hint = "Nome cartella"
        AlertDialog.Builder(this)
            .setTitle("Nuova cartella")
            .setView(input)
            .setPositiveButton("Crea") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton

                if (useFileApi(currentPath)) {
                    try {
                        val newDir = File(currentPath, name)
                        if (newDir.mkdir()) {
                            scanPath(newDir.absolutePath)
                            Toast.makeText(this, "Cartella creata", Toast.LENGTH_SHORT).show()
                            loadDirectory(currentPath); return@setPositiveButton
                        }
                    } catch (_: Exception) {}
                }

                val parent = getSafDocumentFile(currentPath)
                if (parent != null && parent.createDirectory(name) != null) {
                    Toast.makeText(this, "Cartella creata", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                } else {
                    Toast.makeText(this, "Impossibile creare", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    // ============================================================
    // INFO / SHARE / OPEN
    // ============================================================

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

    private fun shareFile(item: FileItem) {
        try {
            if (!item.file.exists()) {
                Toast.makeText(this, "Condivisione USB non supportata senza permesso", Toast.LENGTH_SHORT).show()
                return
            }
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
                    .setNegativeButton("Annulla", null).show()
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
                .setNegativeButton("Annulla", null).show()
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

    // ============================================================
    // MENU SELEZIONE
    // ============================================================

    private fun showSelectionMoreMenu() {
        if (selectedPaths.isEmpty()) return
        val popup = PopupMenu(this, btnSelMore)
        val count = selectedPaths.size
        val allVisibleSelected = displayedItems.isNotEmpty() &&
                displayedItems.all { selectedPaths.contains(it.path) }

        if (allVisibleSelected) popup.menu.add(0, 20, 0, "Deseleziona tutto")
        else popup.menu.add(0, 20, 0, "Seleziona tutto")

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
            val item = getSingleSelectedItem()
            if (item != null) {
                popup.menu.add(0, 4, 6, "Rinomina")
                if (!item.isDirectory && item.name.lowercase().endsWith(".zip")) {
                    popup.menu.add(0, 5, 7, "Decomprimi")
                }
                popup.menu.add(0, 6, 8, "Proprietà")
            }
        }

        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                20 -> {
                    if (allVisibleSelected) selectedPaths.clear()
                    else { selectedPaths.clear(); displayedItems.forEach { selectedPaths.add(it.path) } }
                    updateSelectionUI(); renderList()
                }
                10 -> { val i = getSingleSelectedItem(); if (i != null) { exitSelectionMode(); openFileWithDefault(i) } }
                11 -> { val i = getSingleSelectedItem(); if (i != null) { exitSelectionMode(); openFileWithPicker(i) } }
                1 -> copySelectedFiles("cut")
                2 -> shareSelectedFiles()
                3 -> comprimiZipSelezioneMultipla()
                4 -> { val i = getSingleSelectedItem(); if (i != null) { exitSelectionMode(); renameItem(i) } }
                5 -> { val i = getSingleSelectedItem(); if (i != null) { exitSelectionMode(); decomprimiZip(i) } }
                6 -> { val i = getSingleSelectedItem(); if (i != null) { exitSelectionMode(); showItemInfo(i) } }
            }
            true
        }
        popup.show()
    }

    private fun getSingleSelectedItem(): FileItem? {
        if (selectedPaths.size != 1) return null
        val p = selectedPaths.first()
        return allItems.find { it.path == p }
    }

    private fun shareSelectedFiles() {
        if (selectedPaths.isEmpty()) return
        if (selectedPaths.size == 1) {
            val item = allItems.find { it.path == selectedPaths.first() }
            if (item != null && !item.isDirectory) { shareFile(item); exitSelectionMode() }
            else if (item != null && item.isDirectory) {
                Toast.makeText(this, "Impossibile condividere una cartella", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val uris = ArrayList<Uri>()
            for (path in selectedPaths) {
                val f = File(path)
                if (f.exists() && f.isFile) {
                    uris.add(FileProvider.getUriForFile(this, "$packageName.provider", f))
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, "Nessun file da condividere", Toast.LENGTH_SHORT).show(); return
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

    // ============================================================
    // STORAGE CARDS
    // ============================================================

    private fun updateStorageCards() {
        storageRow.removeAllViews()
        val volumes = mutableListOf<StorageVolumeInfo>()

        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, total - free, total))
        } catch (_: Exception) {
            volumes.add(StorageVolumeInfo("Memoria interna", rootInternal, 0L, 0L))
        }

        try {
            val sm = getSystemService(STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    if (vol.isPrimary || !vol.isRemovable) continue
                    val path = getVolumePath(vol)
                    if (path != null && File(path).exists()) {
                        try {
                            val stat = StatFs(path)
                            val total = stat.blockCountLong * stat.blockSizeLong
                            val free = stat.availableBlocksLong * stat.blockSizeLong
                            val label = vol.getDescription(this) ?: "Storage esterno"
                            volumes.add(StorageVolumeInfo(label, path, total - free, total))
                        } catch (_: Exception) {}
                    } else {
                        try {
                            val label = vol.getDescription(this) ?: "Storage esterno"
                            if (vol.state == Environment.MEDIA_MOUNTED) {
                                volumes.add(StorageVolumeInfo(label, "", 0L, 0L))
                            }
                        } catch (_: Exception) {}
                    }
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
                    val p = "/storage/$uuid"
                    if (File(p).exists()) return p
                }
            } catch (_: Exception) {}
        }
        return try {
            val m = vol.javaClass.getMethod("getPath")
            m.invoke(vol) as? String
        } catch (_: Exception) { null }
    }

    private fun createStorageCard(vol: StorageVolumeInfo): LinearLayout {
        val d = resources.displayMetrics.density
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        card.isClickable = true
        card.isFocusable = true

        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#2A2A2A"))
        bg.cornerRadius = 12 * d
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
        } else info.text = "Info non disponibili"
        info.setTextColor(Color.parseColor("#CCCCCC"))
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        info.setPadding(0, (2 * d).toInt(), 0, 0)
        card.addView(info)

        card.setOnClickListener {
            activeCategory = null
            searchQuery = ""
            editSearch.setText("")
            when {
                vol.path.isEmpty() -> requestSafForPath(rootInternal)
                vol.path == rootInternal -> openDirectoryWithSafCheck(rootInternal)
                else -> openDirectoryWithSafCheck(vol.path)
            }
        }
        return card
    }

    // ============================================================
    // PERMESSI
    // ============================================================

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
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

    // ============================================================
    // NAVIGAZIONE / SETTINGS
    // ============================================================

    private fun goBack() {
        if (selectionMode) { exitSelectionMode(); return }
        if (currentPath == rootInternal) return
        val parent = File(currentPath).parent
        if (parent != null && parent.startsWith(rootInternal)) {
            openDirectoryWithSafCheck(parent)
        } else if (currentPath.startsWith("/storage/") || currentPath.startsWith("/mnt/")) {
            val vroot = getVolumeRootFor(currentPath)
            if (currentPath == vroot) loadDirectory(rootInternal, resetCategory = true)
            else openDirectoryWithSafCheck(parent ?: rootInternal)
        }
    }

    override fun onBackPressed() {
        if (selectionMode) exitSelectionMode()
        else if (currentPath != rootInternal) goBack()
        else super.onBackPressed()
    }

    private fun showSortDialog() {
        val options = arrayOf("Nome", "Dimensione", "Data")
        val current = when (sortBy) { "size" -> 1; "date" -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("Ordina per")
            .setSingleChoiceItems(options, current) { dlg, which ->
                sortBy = when (which) { 1 -> "size"; 2 -> "date"; else -> "name" }
                prefs.edit().putString("sort_by", sortBy).apply()
                updateSortLabel(); applyFilters(); dlg.dismiss()
            }.show()
    }

    private fun updateSortLabel() {
        val label = when (sortBy) { "size" -> "Dimensione"; "date" -> "Data"; else -> "Nome" }
        txtSort.text = "Ordina per $label"
    }

    private fun toggleView() {
        isGrid = !isGrid
        prefs.edit().putBoolean("is_grid", isGrid).apply()
        updateViewToggleIcon(); renderList()
    }

    private fun showSettingsDialog() {
        val options = mutableListOf<String>()
        options.add("Aggiorna cartella")
        options.add(if (showHidden) "Nascondi file nascosti" else "Mostra file nascosti")
        options.add("Rinnova permesso scrittura")
        options.add("Rimuovi tutti i permessi SAF")
        options.add("Richiedi di nuovo permesso memoria interna")

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { loadDirectory(currentPath) }
                    1 -> {
                        showHidden = !showHidden
                        prefs.edit().putBoolean("show_hidden", showHidden).apply()
                        loadDirectory(currentPath)
                    }
                    2 -> {
                        val vroot = getVolumeRootFor(currentPath)
                        safTreeUris.remove(vroot); saveSafTreeUris()
                        requestSafForPath(currentPath) {
                            Toast.makeText(this, "Permesso rinnovato", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        try {
                            for (p in contentResolver.persistedUriPermissions) {
                                contentResolver.releasePersistableUriPermission(
                                    p.uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                        } catch (_: Exception) {}
                        safTreeUris.clear(); saveSafTreeUris()
                        prefs.edit().remove(PREFS_SAF_INTERNAL_REQUESTED).apply()
                        Toast.makeText(this, "Permessi rimossi", Toast.LENGTH_SHORT).show()
                        loadDirectory(rootInternal, resetCategory = true)
                    }
                    4 -> {
                        prefs.edit().remove(PREFS_SAF_INTERNAL_REQUESTED).apply()
                        safTreeUris.remove(rootInternal); saveSafTreeUris()
                        requestSafForPath(rootInternal) {
                            Toast.makeText(this, "Permesso rinnovato", Toast.LENGTH_SHORT).show()
                        }
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

    data class StorageVolumeInfo(
        val label: String, val path: String,
        val usedBytes: Long, val totalBytes: Long
    )
}
