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

        val adapter = ImagePagerAdapter(paths) { toggleUi() }
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startIndex, false)

        txtName.text = File(paths[startIndex]).name

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                txtName.text = File(paths[position]).name
            }
        })
    }

    /**
     * Mostra o nasconde nome file e pulsante X
     */
    private fun toggleUi() {
        uiVisible = !uiVisible
        val alpha = if (uiVisible) 1f else 0f
        txtName.animate().alpha(alpha).setDuration(200).start()
        btnClose.animate().alpha(alpha).setDuration(200).start()
        txtName.isClickable = uiVisible
        btnClose.isClickable = uiVisible
    }

    private class ImagePagerAdapter(
        val paths: List<String>,
        val onTap: () -> Unit
    ) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: PhotoView = view.findViewById(R.id.photoView)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_page, parent, false)
            val vh = VH(view)

            // Gesture detector per il tap singolo (PhotoView intercetta il doppio tap)
            val gestureDetector = GestureDetector(parent.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTap()
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean = true
            })

            vh.photoView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false // lascia passare l'evento al PhotoView per zoom/pan
            }

            return vh
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
