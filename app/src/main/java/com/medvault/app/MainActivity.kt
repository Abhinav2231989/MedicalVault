package com.medvault.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val REQUEST_SIGN_IN = 100
        private const val REQUEST_RECORD_AUDIO = 101
        private const val SHARED_FOLDER_NAME = "MedVault"
        private const val DB_FILE_NAME = "medvault_db.json"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Request microphone permission
        checkMicrophonePermission()
        
        webView = findViewById(R.id.webView)
        setupWebView()
        setupGoogleSignIn()
        
        webView.loadUrl("file:///android_asset/medical-history.html")
    }
    
    private fun checkMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Microphone permission granted - Voice input enabled!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Microphone permission denied - Voice input will not work", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }
        
        webView.addJavascriptInterface(StorageInterface(this), "AndroidStorage")
        webView.addJavascriptInterface(DriveInterface(), "GoogleDrive")
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request?.grant(request.resources)
                }
            }
        }
    }
    
    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        // Check if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            initializeDriveService(account)
        }
    }
    
    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("MedVault")
            .build()
        
        runOnUiThread {
            webView.evaluateJavascript("if(window.onDriveReady) window.onDriveReady(true);", null)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            handleSignInResult(task)
        }
    }
    
    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult()
            initializeDriveService(account)
            Toast.makeText(this, "Google Drive connected", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                webView.evaluateJavascript("if(window.onDriveReady) window.onDriveReady(false);", null)
            }
        }
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    // Local Storage Interface
    inner class StorageInterface(private val context: Context) {
        
        @JavascriptInterface
        fun setItem(key: String, value: String): String {
            return try {
                val prefs = context.getSharedPreferences("MedVaultStorage", Context.MODE_PRIVATE)
                prefs.edit().putString(key, value).apply()
                """{"success": true, "key": "$key"}"""
            } catch (e: Exception) {
                """{"success": false, "error": "${e.message}"}"""
            }
        }
        
        @JavascriptInterface
        fun getItem(key: String): String? {
            return try {
                val prefs = context.getSharedPreferences("MedVaultStorage", Context.MODE_PRIVATE)
                prefs.getString(key, null)
            } catch (e: Exception) {
                null
            }
        }
        
        @JavascriptInterface
        fun removeItem(key: String): String {
            return try {
                val prefs = context.getSharedPreferences("MedVaultStorage", Context.MODE_PRIVATE)
                prefs.edit().remove(key).apply()
                """{"success": true, "key": "$key"}"""
            } catch (e: Exception) {
                """{"success": false, "error": "${e.message}"}"""
            }
        }
        
        @JavascriptInterface
        fun clear(): String {
            return try {
                val prefs = context.getSharedPreferences("MedVaultStorage", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                """{"success": true}"""
            } catch (e: Exception) {
                """{"success": false, "error": "${e.message}"}"""
            }
        }
    }
    
    // Google Drive Interface
    inner class DriveInterface {
        
        @JavascriptInterface
        fun signIn() {
            runOnUiThread {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, REQUEST_SIGN_IN)
            }
        }
        
        @JavascriptInterface
        fun isSignedIn(): Boolean {
            return driveService != null
        }
        
        @JavascriptInterface
        fun syncToCloud(dataJson: String) {
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        uploadToGoogleDrive(dataJson)
                    }
                    
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onSyncComplete) window.onSyncComplete(true, 'Upload successful');",
                            null
                        )
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onSyncComplete) window.onSyncComplete(false, '${e.message}');",
                            null
                        )
                    }
                }
            }
        }
        
        @JavascriptInterface
        fun syncFromCloud() {
            scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        downloadFromGoogleDrive()
                    }
                    
                    runOnUiThread {
                        if (data != null) {
                            webView.evaluateJavascript(
                                "if(window.onDataReceived) window.onDataReceived('$data');",
                                null
                            )
                        } else {
                            webView.evaluateJavascript(
                                "if(window.onSyncComplete) window.onSyncComplete(false, 'No data found');",
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onSyncComplete) window.onSyncComplete(false, '${e.message}');",
                            null
                        )
                    }
                }
            }
        }
        
        private fun uploadToGoogleDrive(dataJson: String): String {
            val drive = driveService ?: throw Exception("Not signed in to Google Drive")
            
            // Find or create MedVault folder
            val folderId = findOrCreateFolder(drive)
            
            // Find existing file or create new
            val fileId = findFileInFolder(drive, folderId, DB_FILE_NAME)
            
            val fileMetadata = File().apply {
                name = DB_FILE_NAME
                if (fileId == null) {
                    parents = listOf(folderId)
                }
            }
            
            val contentStream = dataJson.byteInputStream()
            
            if (fileId != null) {
                // Update existing file
                drive.files().update(fileId, fileMetadata, 
                    com.google.api.client.http.InputStreamContent("application/json", contentStream))
                    .execute()
            } else {
                // Create new file
                drive.files().create(fileMetadata,
                    com.google.api.client.http.InputStreamContent("application/json", contentStream))
                    .setFields("id")
                    .execute()
            }
            
            return "Upload successful"
        }
        
        private fun downloadFromGoogleDrive(): String? {
            val drive = driveService ?: throw Exception("Not signed in to Google Drive")
            
            val folderId = findOrCreateFolder(drive)
            val fileId = findFileInFolder(drive, folderId, DB_FILE_NAME) ?: return null
            
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            
            return outputStream.toString("UTF-8")
        }
        
        private fun findOrCreateFolder(drive: Drive): String {
            // Search for existing folder
            val result = drive.files().list()
                .setQ("mimeType='application/vnd.google-apps.folder' and name='$SHARED_FOLDER_NAME' and trashed=false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            return if (result.files.isNotEmpty()) {
                result.files[0].id
            } else {
                // Create new folder
                val folderMetadata = File().apply {
                    name = SHARED_FOLDER_NAME
                    mimeType = "application/vnd.google-apps.folder"
                }
                
                val folder = drive.files().create(folderMetadata)
                    .setFields("id")
                    .execute()
                
                folder.id
            }
        }
        
        private fun findFileInFolder(drive: Drive, folderId: String, fileName: String): String? {
            val result = drive.files().list()
                .setQ("'$folderId' in parents and name='$fileName' and trashed=false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            return if (result.files.isNotEmpty()) {
                result.files[0].id
            } else {
                null
            }
        }
    }
}
