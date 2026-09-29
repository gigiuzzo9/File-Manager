package com.filemanager

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView

class ImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "image_paths"
        const val EXTRA_INDEX = "image_index"
    }

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
        val adapter = ImagePagerAdapter(paths)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startIndex, false)
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
