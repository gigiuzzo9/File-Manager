package com.filemanager

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SAF = 1001
    }

    private lateinit var recycler: RecyclerView
    private lateinit var txtPath: TextView
    private lateinit var txtInternalInfo: TextView
    private lateinit var txtSort: TextView
    private lateinit var editSearch: EditText
    private lateinit var prefs: SharedPreferences
    private lateinit var btnPaste: ImageButton

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

    private var clipboardPath: String? = null
    private var clipboardAction: String? = null

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
        txtInternalInfo = findViewById(R.id.txtInternalInfo)
        txtSort = findViewById(R.id.txtSort)
        editSearch = findViewById(R.id.editSearch)
        btnPaste = findViewById(R.id.btnPaste)

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
        findViewById<ImageButton>(R.id.btnViewToggle).setOnClickListener { toggleView() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnPaste.setOnClickListener { pasteFromClipboard() }

        findViewById<LinearLayout>(R.id.storageCard).setOnClickListener {
            activeCategory = null
            searchQuery = ""
            editSearch.setText("")
            loadDirectory(rootInternal, resetCategory = true)
        }

        findViewById<LinearLayout>(R.id.catImages).setOnClickListener { setCategory("images") }
        findViewById<LinearLayout>(R.id.catAudio).setOnClickListener { setCategory("audio") }
        findViewById<LinearLayout>(R.id.catVideo).setOnClickListener { setCategory("video") }
        findViewById<LinearLayout>(R.id.catDocs).setOnClickListener { setCategory("documents") }

        updateStorageInfo()
        updateSortLabel()
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
        updatePasteButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun updatePasteButton() {
        btnPaste.visibility = if (clipboardPath != null) View.VISIBLE else View.GONE
    }

    private fun requestSaf(onGranted: () -> Unit) {
        if (safTreeUri != null) {
            onGranted()
            return
        }
        pendingSafAction = onGranted

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_SAF)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(fallback, REQ_SAF)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAF && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
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
        val parts = rel.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            doc = doc?.findFile(part) ?: return null
        }
        return doc
    }

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
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
            Toast.makeText(this, "Attiva \"Gestisci tutti i file\"", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStoragePermission()) {
            loadDirectory(currentPath)
        }
    }

    private fun updateStorageInfo() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - freeBytes
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
            txtInternalInfo.text = String.format("%.1f GB / %.1f GB", usedGb, totalGb)
        } catch (e: Exception) {
            txtInternalInfo.text = "Info non disponibili"
        }
    }

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
                            lastModified = f.lastModified()
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
            }
        }
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
        val docExts = listOf(
            ".pdf", ".doc", ".docx", ".txt", ".rtf", ".odt",
            ".xls", ".xlsx", ".csv", ".ods",
            ".ppt", ".pptx", ".odp",
            ".zip", ".rar", ".7z", ".tar", ".gz"
        )
        return docExts.any { l.endsWith(it) }
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
            try {
                scanRecursive(File(rootInternal), found, cat, 0)
            } catch (_: Exception) {}

            val sorted = when (sortBy) {
                "size" -> found.sortedByDescending { it.size }
                "date" -> found.sortedByDescending { it.lastModified }
                else -> found.sortedBy { it.name.lowercase() }
            }

            mainHandler.post {
                displayedItems = sorted
                renderList()
            }
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
            if (f.isDirectory) {
                scanRecursive(f, out, cat, depth + 1)
            } else {
                val match = if (cat == "documents") {
                    isDocumentFile(name)
                } else {
                    categoryFor(name) == cat
                }
                if (match) {
                    out.add(
                        FileItem(
                            file = f,
                            name = name,
                            path = f.absolutePath,
                            isDirectory = false,
                            size = f.length(),
                            lastModified = f.lastModified()
                        )
                    )
                }
            }
        }
    }

    private fun renderList() {
        recycler.layoutManager = if (isGrid)
            GridLayoutManager(this, 3)
        else
            LinearLayoutManager(this)

        recycler.adapter = FileAdapter(
            items = displayedItems,
            isGrid = isGrid,
            onClick = { item ->
                if (item.isDirectory) loadDirectory(item.path)
                else openFileWithDefault(item)
            },
            onLongClick = { item ->
                showItemMenu(item)
            }
        )
    }

    private fun openFileWithDefault(item: FileItem) {
        val mimeType = getMimeType(item.name)
        val savedPackage = prefs.getString("app_for_$mimeType", null)

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (savedPackage != null) {
                intent.setPackage(savedPackage)
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    prefs.edit().remove("app_for_$mimeType").apply()
                }
            }

            showAppChooser(intent, mimeType)
        } catch (e: Exception) {
            Toast.makeText(this, "Nessuna app per aprire questo file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppChooser(intent: Intent, mimeType: String) {
        val chooser = Intent.createChooser(intent, "Apri con...")
        val receiverIntent = Intent(this, AppChooserReceiver::class.java).apply {
            putExtra("mime_type", mimeType)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(this, mimeType.hashCode(), receiverIntent, flags)
        chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT, pendingIntent)
        startActivity(chooser)
    }

    private fun openFileWithPicker(item: FileItem) {
        val mimeType = getMimeType(item.name)
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            prefs.edit().remove("app_for_$mimeType").apply()
            showAppChooser(intent, mimeType)
        } catch (e: Exception) {
            Toast.makeText(this, "Nessuna app per aprire questo file", Toast.LENGTH_SHORT).show()
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

    // ---------- FINE PARTE 1 ----------
    private fun showItemMenu(item: FileItem) {
        val options = mutableListOf<String>()
        options.add("Apri")
        if (!item.isDirectory) options.add("Apri con...")
        if (!item.isDirectory) options.add("Condividi")
        options.add("Copia")
        options.add("Taglia")
        if (clipboardPath != null) options.add("Incolla qui")
        options.add("Rinomina")
        options.add("Elimina")
        options.add("Proprietà")

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when (choice) {
                    "Apri" -> if (item.isDirectory) loadDirectory(item.path) else openFileWithDefault(item)
                    "Apri con..." -> openFileWithPicker(item)
                    "Condividi" -> shareFile(item)
                    "Copia" -> {
                        clipboardPath = item.path
                        clipboardAction = "copy"
                        Toast.makeText(this, "Copiato: ${item.name}", Toast.LENGTH_SHORT).show()
                        updatePasteButton()
                    }
                    "Taglia" -> {
                        clipboardPath = item.path
                        clipboardAction = "cut"
                        Toast.makeText(this, "Tagliato: ${item.name}", Toast.LENGTH_SHORT).show()
                        updatePasteButton()
                    }
                    "Incolla qui" -> pasteFromClipboard()
                    "Rinomina" -> renameItem(item)
                    "Elimina" -> deleteItem(item)
                    "Proprietà" -> showItemInfo(item)
                }
            }
            .show()
    }

    private fun shareFile(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", item.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(item.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi con..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pasteFromClipboard() {
        val srcPath = clipboardPath
        if (srcPath == null) {
            Toast.makeText(this, "Niente negli appunti", Toast.LENGTH_SHORT).show()
            return
        }

        val src = File(srcPath)
        if (!src.exists()) {
            AlertDialog.Builder(this)
                .setTitle("Errore Incolla")
                .setMessage("File originale non trovato:\n$srcPath")
                .setPositiveButton("OK", null)
                .show()
            clipboardPath = null
            clipboardAction = null
            updatePasteButton()
            return
        }

        val dst = File(currentPath, src.name)

        if (dst.absolutePath == src.absolutePath) {
            AlertDialog.Builder(this)
                .setTitle("Errore Incolla")
                .setMessage("Origine e destinazione coincidono:\n${src.absolutePath}")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (dst.exists()) {
            AlertDialog.Builder(this)
                .setTitle("Errore Incolla")
                .setMessage("Il file esiste già:\n${dst.absolutePath}")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val action = clipboardAction
        Toast.makeText(this, "Copia in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var ok = false
            var errorMsg = ""

            try {
                if (src.isDirectory) {
                    copyDirectoryRecursive(src, dst)
                    ok = true
                } else {
                    copyFile(src, dst)
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
                errorMsg = e.message ?: e.toString()
            }

            if (ok && action == "cut") {
                try {
                    if (src.isDirectory) src.deleteRecursively() else src.delete()
                } catch (_: Exception) {}
            }

            if (ok) scanPath(dst.absolutePath)

            mainHandler.post {
                if (ok) {
                    Toast.makeText(
                        this,
                        if (action == "cut") "Spostato: ${src.name}" else "Copiato: ${src.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    clipboardPath = null
                    clipboardAction = null
                    updatePasteButton()
                    loadDirectory(currentPath)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Errore Incolla")
                        .setMessage("SRC: ${src.absolutePath}\nDST: ${dst.absolutePath}\n\nErrore:\n$errorMsg")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun copyFile(src: File, dst: File) {
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
            if (f.isDirectory) {
                copyDirectoryRecursive(f, newFile)
            } else {
                copyFile(f, newFile)
            }
        }
    }

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
                    val parent = item.file.parentFile
                    val newFile = File(parent, newName)
                    if (item.file.renameTo(newFile)) {
                        updateInMediaStore(item.path, newFile.absolutePath)
                        scanPath(newFile.absolutePath)
                        Toast.makeText(this, "Rinominato", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                        return@setPositiveButton
                    }
                } catch (_: Exception) {}

                requestSaf {
                    val doc = getSafDocumentFile(item.path)
                    if (doc != null) {
                        try {
                            if (doc.renameTo(newName)) {
                                Toast.makeText(this, "Rinominato (SAF)", Toast.LENGTH_SHORT).show()
                                loadDirectory(currentPath)
                            } else {
                                Toast.makeText(this, "Impossibile rinominare", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Errore SAF: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "File non accessibile via SAF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteItem(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Elimina")
            .setMessage("Eliminare \"${item.name}\"?")
            .setPositiveButton("Elimina") { _, _ ->

                val okFile = try {
                    if (item.isDirectory) deleteRecursively(item.file) else item.file.delete()
                } catch (e: Exception) { false }

                if (okFile) {
                    scanPath(item.path)
                    Toast.makeText(this, "Eliminato", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                    return@setPositiveButton
                }

                requestSaf {
                    val doc = getSafDocumentFile(item.path)
                    if (doc != null) {
                        try {
                            if (doc.delete()) {
                                Toast.makeText(this, "Eliminato (SAF)", Toast.LENGTH_SHORT).show()
                                loadDirectory(currentPath)
                            } else {
                                Toast.makeText(this, "Impossibile eliminare", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Errore SAF: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "File non accessibile via SAF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return file.delete()
            for (child in children) {
                deleteRecursively(child)
            }
        }
        val deleted = file.delete()
        if (deleted) deleteFromMediaStore(file.absolutePath)
        return deleted
    }

    private fun deleteFromMediaStore(path: String) {
        try {
            if (File(path).isDirectory) return
            val uri = getMediaStoreUri(path)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(path)
            contentResolver.delete(uri, selection, selectionArgs)
        } catch (_: Exception) {}
    }

    private fun updateInMediaStore(oldPath: String, newPath: String) {
        try {
            if (File(newPath).isDirectory) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, newPath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, File(newPath).name)
                put(MediaStore.MediaColumns.TITLE, File(newPath).name)
            }
            val uri = getMediaStoreUri(oldPath)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(oldPath)
            contentResolver.update(uri, values, selection, selectionArgs)
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

    private fun showItemInfo(item: FileItem) {
        val info = buildString {
            append("Percorso: ${item.path}\n")
            if (!item.isDirectory) append("Dimensione: ${formatSize(item.size)}\n")
            append("Modificato: ${java.util.Date(item.lastModified)}")
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
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
                        loadDirectory(currentPath)
                        return@setPositiveButton
                    }
                } catch (_: Exception) {}

                requestSaf {
                    val parent = getSafDocumentFile(currentPath)
                    if (parent != null) {
                        try {
                            val newDir = parent.createDirectory(name)
                            if (newDir != null) {
                                Toast.makeText(this, "Cartella creata (SAF)", Toast.LENGTH_SHORT).show()
                                loadDirectory(currentPath)
                            } else {
                                Toast.makeText(this, "Impossibile creare", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Errore SAF: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "Cartella non accessibile via SAF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSortDialog() {
        val options = arrayOf("Nome", "Dimensione", "Data")
        val current = when (sortBy) {
            "size" -> 1
            "date" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Ordina per")
            .setSingleChoiceItems(options, current) { dialog, which ->
                sortBy = when (which) {
                    1 -> "size"
                    2 -> "date"
                    else -> "name"
                }
                prefs.edit().putString("sort_by", sortBy).apply()
                updateSortLabel()
                applyFilters()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateSortLabel() {
        val label = when (sortBy) {
            "size" -> "Dimensione"
            "date" -> "Data"
            else -> "Nome"
        }
        txtSort.text = "Ordina per $label"
    }

    private fun toggleView() {
        isGrid = !isGrid
        prefs.edit().putBoolean("is_grid", isGrid).apply()
        renderList()
    }

    private fun showSettingsDialog() {
        val options = mutableListOf<String>()
        options.add(if (showHidden) "Nascondi file nascosti" else "Mostra file nascosti")
        if (clipboardPath != null) options.add("Incolla qui")
        options.add("Rinnova permesso scrittura")

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        showHidden = !showHidden
                        prefs.edit().putBoolean("show_hidden", showHidden).apply()
                        loadDirectory(currentPath)
                    }
                    1 -> if (clipboardPath != null) pasteFromClipboard()
                    2 -> {
                        safTreeUri = null
                        prefs.edit().remove("saf_tree_uri").apply()
                        requestSaf {
                            Toast.makeText(this, "Permesso rinnovato", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
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

    private fun goBack() {
        if (currentPath == rootInternal) return
        val parent = File(currentPath).parent
        if (parent != null && parent.startsWith(rootInternal)) {
            loadDirectory(parent)
        }
    }

    override fun onBackPressed() {
        if (currentPath != rootInternal) {
            goBack()
        } else {
            super.onBackPressed()
        }
    }
}
