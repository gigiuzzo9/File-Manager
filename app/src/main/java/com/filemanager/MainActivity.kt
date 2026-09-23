package com.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var txtPath: TextView
    private var currentPath: String = "/storage/emulated/0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerFiles)
        txtPath = findViewById(R.id.txtPath)

        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { loadDirectory(currentPath) }

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

    private fun loadDirectory(path: String) {
        currentPath = path
        txtPath.text = path

        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "Cartella non accessibile", Toast.LENGTH_SHORT).show()
            return
        }

        val files = dir.listFiles()
        if (files == null) {
            Toast.makeText(this, "Permesso negato", Toast.LENGTH_SHORT).show()
            return
        }

        val items = files.map { f ->
            FileItem(
                file = f,
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                lastModified = f.lastModified()
            )
        }.sortedWith(compareByDescending<FileItem> { it.isDirectory }
            .thenBy { it.name.lowercase() })

        recycler.adapter = FileAdapter(items) { item ->
            if (item.isDirectory) {
                loadDirectory(item.path)
            } else {
                Toast.makeText(this, "File: ${item.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goBack() {
        if (currentPath == "/storage/emulated/0") return
        val parent = File(currentPath).parent
        if (parent != null && parent.startsWith("/storage/emulated/0")) {
            loadDirectory(parent)
        }
    }

    override fun onBackPressed() {
        if (currentPath != "/storage/emulated/0") {
            goBack()
        } else {
            super.onBackPressed()
        }
    }
}
