package com.filemanager

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var txtPath: TextView
    private lateinit var txtInternalInfo: TextView
    private lateinit var txtSort: TextView
    private lateinit var editSearch: EditText
    private lateinit var prefs: SharedPreferences

    private val ROOT_INTERNAL = "/storage/emulated/0"
    private var currentPath: String = ROOT_INTERNAL
    private var allItems: List<FileItem> = emptyList()
    private var displayedItems: List<FileItem> = emptyList()

    private var showHidden: Boolean = false
    private var isGrid: Boolean = false
    private var sortBy: String = "name"
    private var searchQuery: String = ""
    private var activeCategory: String? = null

    // Executor per la scansione in background
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("filemanager", MODE_PRIVATE)
        showHidden = prefs.getBoolean("show_hidden", false)
        isGrid = prefs.getBoolean("is_grid", false)
        sortBy = prefs.getString("sort_by", "name") ?: "name"

        recycler = findViewById(R.id.recyclerFiles)
        txtPath = findViewById(R.id.txtPath)
        txtInternalInfo = findViewById(R.id.txtInternalInfo)
        txtSort = findViewById(R.id.txtSort)
        editSearch = findViewById(R.id.editSearch)

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

        findViewById<LinearLayout>(R.id.storageCard).setOnClickListener {
            activeCategory = null
            searchQuery = ""
            editSearch.setText("")
            loadDirectory(ROOT_INTERNAL, resetCategory = true)
        }

        findViewById<LinearLayout>(R.id.catImages).setOnClickListener { setCategory("images") }
        findViewById<LinearLayout>(R.id.catAudio).setOnClickListener { setCategory("audio") }
        findViewById<LinearLayout>(R.id.catVideo).setOnClickListener { setCategory("video") }
        findViewById<LinearLayout>(R.id.catDocs).setOnClickListener { setCategory("documents") }

        updateStorageInfo()
        updateSortLabel()

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
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
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
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
            Toast.makeText(this, "Attiva \"Gestisci tutti i file\"", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
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

    // ---------- MEMORIA ----------
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

    // ---------- LETTURA ----------
    private fun loadDirectory(path: String, resetCategory: Boolean = false) {
        currentPath = path
        txtPath.text = path
        if (resetCategory) {
            activeCategory = null
            searchQuery = ""
        }

        executor.execute {
            val dir = File(path)
            val result = if (!dir.exists() || !dir.isDirectory) {
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
                    Toast.makeText(this, "Cartella non accessibile o permesso negato", Toast.LENGTH_SHORT).show()
                    return@post
                }
                allItems = result
                applyFilters()
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

    // ---------- CATEGORIE (ricorsive, in background) ----------
    private fun setCategory(cat: String) {
        if (activeCategory == cat) {
            activeCategory = null
            loadDirectory(ROOT_INTERNAL, resetCategory = true)
            return
        }

        activeCategory = cat
        txtPath.text = "Filtro: $cat"
        recycler.adapter = FileAdapter(emptyList(), isGrid, {}, {})
        Toast.makeText(this, "Ricerca in corso...", Toast.LENGTH_SHORT).show()

        executor.execute {
            val found = mutableListOf<FileItem>()
            try {
                scanRecursive(File(ROOT_INTERNAL), found, cat, 0)
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
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!showHidden && name.startsWith(".")) continue
            if (f.isDirectory) {
                scanRecursive(f, out, cat, depth + 1)
            } else {
                if (categoryFor(name) == cat) {
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

    // ---------- RENDER ----------
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

    // ---------- APERTURA FILE ----------
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

    // ---------- MENU CONTESTUALE ----------
    private fun showItemMenu(item: FileItem) {
        val options = arrayOf("Apri", "Apri con...", "Rinomina", "Elimina", "Proprietà")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (item.isDirectory) loadDirectory(item.path) else openFileWithDefault(item)
                    1 -> if (!item.isDirectory) openFileWithPicker(item)
                    2 -> renameItem(item)
                    3 -> deleteItem(item)
                    4 -> showItemInfo(item)
                }
            }
            .show()
    }

    private fun renameItem(item: FileItem) {
        val input = EditText(this)
        input.setText(item.name)
        AlertDialog.Builder(this)
            .setTitle("Rinomina")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                try {
                    val newFile = File(item.file.parent, newName)
                    item.file.renameTo(newFile)
                    loadDirectory(currentPath)
                } catch (e: Exception) {
                    Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
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
                try {
                    val ok = if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
                    if (ok) loadDirectory(currentPath)
                    else Toast.makeText(this, "Impossibile eliminare", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
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

    // ---------- AZIONI ----------
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
                    if (newDir.mkdir()) loadDirectory(currentPath)
                    else Toast.makeText(this, "Impossibile creare", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val options = arrayOf(if (showHidden) "Nascondi file nascosti" else "Mostra file nascosti")
        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showHidden = !showHidden
                    prefs.edit().putBoolean("show_hidden", showHidden).apply()
                    loadDirectory(currentPath)
                }
            }
            .show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb
