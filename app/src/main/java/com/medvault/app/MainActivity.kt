package com.medvault.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.*
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
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    private var isSignedIn = false
    
    // File chooser support
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1
    private val PERMISSION_REQUEST_CODE = 100
    
    companion object {
        private const val RC_SIGN_IN = 9001
        private const val MEDVAULT_FOLDER = "MedVault_Data"
        private const val DATA_FILE = "patient_records.json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request necessary permissions
        requestPermissions()

        // Setup Google Sign In
        setupGoogleSignIn()

        // Setup WebView
        setupWebView()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Enable media features
            mediaPlaybackRequiresUserGesture = false
            
            // Enable caching
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // Enable zoom
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // Add JavaScript interface for Android storage
        webView.addJavascriptInterface(AndroidStorage(), "AndroidStorage")
        
        // Add JavaScript interface for Google Drive
        webView.addJavascriptInterface(GoogleDriveInterface(), "GoogleDrive")

        // Set WebView client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Check if user is already signed in
                val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (account != null) {
                    initializeDriveService(account)
                    notifyDriveReady(true)
                } else {
                    notifyDriveReady(false)
                }
            }
        }

        // Set WebChrome client for permissions and file chooser
        webView.webChromeClient = object : WebChromeClient() {
            // Handle permission requests (microphone, camera, etc.)
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // Handle file chooser for Android 5.0+
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing file chooser
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "image/*",
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    startActivityForResult(
                        Intent.createChooser(intent, "Select File"),
                        FILE_CHOOSER_REQUEST_CODE
                    )
                    return true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                    return false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("WebView", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }
        }

        // Load the HTML file
        webView.loadUrl("file:///android_asset/medical-history.html")
    }

    // Handle file chooser result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RC_SIGN_IN -> {
                val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
                handleSignInResult(task)
            }
            FILE_CHOOSER_REQUEST_CODE -> {
                if (filePathCallback == null) return

                val results = if (resultCode == Activity.RESULT_OK) {
                    if (data?.clipData != null) {
                        // Multiple files selected
                        val count = data.clipData!!.itemCount
                        Array(count) { i ->
                            data.clipData!!.getItemAt(i).uri
                        }
                    } else if (data?.data != null) {
                        // Single file selected
                        arrayOf(data.data!!)
                    } else {
                        null
                    }
                } else {
                    null
                }

                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }
        }
    }

    inner class GoogleDriveInterface {
        @JavascriptInterface
        fun isSignedIn(): Boolean {
            return isSignedIn
        }

        @JavascriptInterface
        fun signIn() {
            runOnUiThread {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        @JavascriptInterface
        fun syncToCloud(dataJson: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    uploadToGoogleDrive(dataJson)
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(true, "Data uploaded to Google Drive")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "Upload failed: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun syncFromCloud() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val data = downloadFromGoogleDrive()
                    withContext(Dispatchers.Main) {
                        if (data != null) {
                            notifyDataReceived(data)
                        } else {
                            notifySyncComplete(false, "No data found")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "Download failed: ${e.message}")
                    }
                }
            }
        }
    }

    inner class AndroidStorage {
        @JavascriptInterface
        fun setItem(key: String, value: String) {
            getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .edit()
                .putString(key, value)
                .apply()
        }

        @JavascriptInterface
        fun getItem(key: String): String? {
            return getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .getString(key, null)
        }

        @JavascriptInterface
        fun removeItem(key: String) {
            getSharedPreferences("MedVaultStorage", MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply()
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(Exception::class.java)
            initializeDriveService(account)
            isSignedIn = true
            notifyDriveReady(true)
        } catch (e: Exception) {
            isSignedIn = false
            notifyDriveReady(false)
            Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory(),
            credential
        )
            .setApplicationName("MedVault")
            .build()
    }

    private suspend fun uploadToGoogleDrive(dataJson: String) = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")

        val folderId = getOrCreateFolder(service)
        val existingFileId = findFile(service, folderId, DATA_FILE)

        val fileMetadata = File().apply {
            name = DATA_FILE
            parents = listOf(folderId)
        }

        val content = com.google.api.client.http.ByteArrayContent(
            "application/json",
            dataJson.toByteArray()
        )

        if (existingFileId != null) {
            service.files().update(existingFileId, fileMetadata, content).execute()
        } else {
            service.files().create(fileMetadata, content)
                .setFields("id")
                .execute()
        }
    }

    private suspend fun downloadFromGoogleDrive(): String? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null

        val folderId = getOrCreateFolder(service)
        val fileId = findFile(service, folderId, DATA_FILE) ?: return@withContext null

        val outputStream = ByteArrayOutputStream()
        service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        outputStream.toString("UTF-8")
    }

    private fun getOrCreateFolder(service: Drive): String {
        val result = service.files().list()
            .setQ("name='$MEDVAULT_FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return if (result.files.isEmpty()) {
            val folderMetadata = File().apply {
                name = MEDVAULT_FOLDER
                mimeType = "application/vnd.google-apps.folder"
            }
            service.files().create(folderMetadata)
                .setFields("id")
                .execute()
                .id
        } else {
            result.files[0].id
        }
    }

    private fun findFile(service: Drive, folderId: String, fileName: String): String? {
        val result = service.files().list()
            .setQ("name='$fileName' and '$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        return if (result.files.isNotEmpty()) result.files[0].id else null
    }

    private fun notifyDriveReady(isReady: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("if(typeof onDriveReady === 'function') onDriveReady($isReady);", null)
        }
    }

    private fun notifySyncComplete(success: Boolean, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onSyncComplete === 'function') onSyncComplete($success, '${message.replace("'", "\\'")}');",
                null
            )
        }
    }

    private fun notifyDataReceived(data: String) {
        runOnUiThread {
            val escapedData = data.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript(
                "if(typeof onDataReceived === 'function') onDataReceived('$escapedData');",
                null
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
