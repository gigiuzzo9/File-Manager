package com.filemanager.app;

import android.os.Environment;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

@CapacitorPlugin(name = "FileReader")
public class FileReaderPlugin extends Plugin {

    private static final String TAG = "FileReaderPlugin";

    @PluginMethod()
    public void isAllFilesAccessGranted(PluginCall call) {
        JSObject ret = new JSObject();
        boolean granted = Environment.isExternalStorageManager();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod()
    public void readDir(PluginCall call) {
        String path = call.getString("path", "/storage/emulated/0/");

        try {
            File dir = new File(path);
            if (!dir.exists()) {
                call.reject("Directory does not exist: " + path);
                return;
            }
            if (!dir.isDirectory()) {
                call.reject("Path is not a directory: " + path);
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                call.reject("Cannot list files in: " + path);
                return;
            }

            JSArray list = new JSArray();
            for (File f : files) {
                JSObject item = new JSObject();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath());
                item.put("isDirectory", f.isDirectory());
                item.put("isFile", f.isFile());
                item.put("size", f.length());
                item.put("mtime", f.lastModified());
                list.put(item);
            }

            JSObject ret = new JSObject();
            ret.put("files", list);
            ret.put("path", path);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "readDir error: " + e.getMessage());
            call.reject(e.getMessage());
        }
    }
}
