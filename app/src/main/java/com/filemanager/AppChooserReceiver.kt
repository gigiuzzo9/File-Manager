package com.filemanager

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AppChooserReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component: ComponentName? = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        val mimeType = intent.getStringExtra("mime_type")
        if (component != null && mimeType != null) {
            val prefs = context.getSharedPreferences("filemanager", Context.MODE_PRIVATE)
            prefs.edit().putString("app_for_$mimeType", component.packageName).apply()
        }
    }
}
