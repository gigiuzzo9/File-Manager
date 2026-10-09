<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permessi legacy per Android ≤ 12 -->
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />

    <!-- Permesso speciale per Android 11+: accesso completo a tutti i file.
         Da solo, senza i permessi READ_MEDIA_*, evita il conflitto che su
         alcune ROM (Huawei/EMUI) blocca File.renameTo() e File.delete(). -->
    <uses-permission
        android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />

    <!-- Installazione APK -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:requestLegacyExternalStorage="true"
        android:hardwareAccelerated="true"
        android:theme="@style/AppTheme"
        tools:targetApi="q">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize|density">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Visualizzatore immagini -->
        <activity
            android:name=".ImageViewerActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize|density"
            android:theme="@style/AppTheme" />

        <!-- Player video/audio -->
        <activity
            android:name=".VideoPlayerActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize|density"
            android:theme="@style/AppTheme" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <receiver
            android:name=".AppChooserReceiver"
            android:exported="false" />

    </application>

</manifest>
