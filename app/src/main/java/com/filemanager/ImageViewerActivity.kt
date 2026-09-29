package com.filemanager

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "image_paths"
        const val EXTRA_INDEX = "image_index"
    }

    private var uiVisible = true
    private lateinit var txtName: TextView
    private lateinit var btnClose: ImageButton

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: run {
            finish()
            return
        }
        if (paths.isEmpty()) {
            finish()
            return
        }

        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, paths.size - 1)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        txtName = findViewById(R.id.txtName)
        btnClose = findViewById(R.id.btnClose)

        btnClose.setOnClickListener { finish() }

        val adapter = ImagePagerAdapter(paths)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startIndex, false)

        txtName.text = File(paths[startIndex]).name

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                txtName.text = File(paths[position]).name
            }
        })

        // Intercetta il tap singolo tramite il ViewPager2,
        // senza toccare il PhotoView (così zoom e swipe restano intatti)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleUi()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        viewPager.getChildAt(0)?.setOnTouchListener { _, event ->
            // Passa SEMPRE l'evento al ViewPager2 (per swipe) e al PhotoView (per zoom)
            // Intercetta solo il tap singolo
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val alpha = if (uiVisible) 1f else 0f
        txtName.animate().alpha(alpha).setDuration(200).start()
        btnClose.animate().alpha(alpha).setDuration(200).start()
        txtName.isClickable = uiVisible
        btnClose.isClickable = uiVisible
    }

    private class ImagePagerAdapter(val paths: List<String>) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: PhotoView = view.findViewById(R.id.photoView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_page, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val path = paths[position]
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    holder.photoView.setImageBitmap(bitmap)
                } else {
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } catch (_: Exception) {
                holder.photoView.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }

        override fun getItemCount() = paths.size
    }
}
