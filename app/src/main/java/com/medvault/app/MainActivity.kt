package com.medvault.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    private var isSignedIn = false
    
    // File chooser support
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var selectedReportUris: List<Uri> = emptyList()
    private val FILE_CHOOSER_REQUEST_CODE = 1
    private val PERMISSION_REQUEST_CODE = 100
    
    companion object {
        private const val RC_SIGN_IN = 9001
        private const val MEDVAULT_FOLDER = "MedVault_Data"
        private const val REPORTS_FOLDER = "Reports"
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

                selectedReportUris = results?.toList() ?: emptyList()
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
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Cannot open report link", Toast.LENGTH_SHORT).show()
                }
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
                        notifyDataReceived(data)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notifySyncComplete(false, "Download failed: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun uploadReport(
            requestId: String,
            patientId: String,
            originalName: String,
            mimeType: String,
            base64Content: String
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val report = uploadReportToGoogleDrive(patientId, originalName, mimeType, base64Content)
                    report.put("requestId", requestId)
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(true, report.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report upload failed")
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(false, payload.toString())
                    }
                }
            }
        }

        @JavascriptInterface
        fun getSelectedReportCount(): Int {
            return selectedReportUris.size
        }

        @JavascriptInterface
        fun uploadSelectedReports(requestId: String, patientId: String) {
            val uris = selectedReportUris.toList()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reports = uploadSelectedReportUrisToGoogleDrive(patientId, uris)
                    selectedReportUris = emptyList()
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("reports", reports)
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(true, payload.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report upload failed")
                    withContext(Dispatchers.Main) {
                        notifyReportUploadComplete(false, payload.toString())
                    }
                }
            }
        }

        @JavascriptInterface
        fun deleteReport(requestId: String, fileId: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    deleteReportFromGoogleDrive(fileId)
                    val payload = JSONObject().put("requestId", requestId)
                    withContext(Dispatchers.Main) {
                        notifyReportDeleteComplete(true, payload.toString())
                    }
                } catch (e: Exception) {
                    val payload = JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Report delete failed")
                    withContext(Dispatchers.Main) {
                        notifyReportDeleteComplete(false, payload.toString())
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

        val content = com.google.api.client.http.ByteArrayContent(
            "application/json",
            dataJson.toByteArray()
        )

        if (existingFileId != null) {
            val updateMetadata = File().apply {
                name = DATA_FILE
                mimeType = "application/json"
            }
            service.files().update(existingFileId, updateMetadata, content)
                .setFields("id")
                .execute()
        } else {
            val createMetadata = File().apply {
                name = DATA_FILE
                mimeType = "application/json"
                parents = listOf(folderId)
            }
            service.files().create(createMetadata, content)
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

    private fun getOrCreateFolder(
        service: Drive,
        folderName: String = MEDVAULT_FOLDER,
        parentFolderId: String? = null
    ): String {
        val parentClause = parentFolderId?.let { " and '$it' in parents" } ?: ""
        val result = service.files().list()
            .setQ("name='${escapeDriveQueryValue(folderName)}' and mimeType='application/vnd.google-apps.folder'$parentClause and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return if (result.files.isEmpty()) {
            val folderMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                parentFolderId?.let { parents = listOf(it) }
            }
            service.files().create(folderMetadata)
                .setFields("id")
                .execute()
                .id
        } else {
            result.files[0].id
        }
    }

    private fun escapeDriveQueryValue(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun findFile(service: Drive, folderId: String, fileName: String): String? {
        val result = service.files().list()
            .setQ("name='${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed=false")
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
                "if(typeof onSyncComplete === 'function') onSyncComplete($success, ${JSONObject.quote(message)});",
                null
            )
        }
    }

    private suspend fun uploadReportToGoogleDrive(
        patientId: String,
        originalName: String,
        mimeType: String,
        base64Content: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        val medVaultFolderId = getOrCreateFolder(service)
        val reportsFolderId = getOrCreateFolder(service, REPORTS_FOLDER, medVaultFolderId)
        val bytes = Base64.decode(base64Content, Base64.DEFAULT)
        val uploadedAt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val driveName = buildReportFileName(patientId, originalName, uploadedAt)

        val fileMetadata = File().apply {
            name = driveName
            parents = listOf(reportsFolderId)
        }
        val content = com.google.api.client.http.ByteArrayContent(
            mimeType.ifBlank { "application/octet-stream" },
            bytes
        )

        val uploadedFile = service.files().create(fileMetadata, content)
            .setFields("id, name, webViewLink, webContentLink")
            .execute()

        JSONObject()
            .put("report", JSONObject()
                .put("id", uploadedFile.id)
                .put("name", uploadedFile.name)
                .put("originalName", originalName)
                .put("mimeType", mimeType)
                .put("uploadedAt", uploadedAt)
                .put("webViewLink", uploadedFile.webViewLink ?: "")
                .put("webContentLink", uploadedFile.webContentLink ?: "")
            )
    }

    private suspend fun uploadSelectedReportUrisToGoogleDrive(
        patientId: String,
        uris: List<Uri>
    ): JSONArray = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        val medVaultFolderId = getOrCreateFolder(service)
        val reportsFolderId = getOrCreateFolder(service, REPORTS_FOLDER, medVaultFolderId)
        if (uris.isEmpty()) {
            throw Exception("No report file was selected. Please attach the report again.")
        }
        val reports = JSONArray()

        uris.forEach { uri ->
            val originalName = getDisplayName(uri)
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = readReportBytes(uri, mimeType)
            val uploadedAt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val driveName = buildReportFileName(patientId, originalName, uploadedAt)

            val fileMetadata = File().apply {
                name = driveName
                parents = listOf(reportsFolderId)
            }
            val content = com.google.api.client.http.ByteArrayContent(
                if (mimeType.startsWith("image/")) "image/jpeg" else mimeType,
                bytes
            )
            val uploadedFile = service.files().create(fileMetadata, content)
                .setFields("id, name, webViewLink, webContentLink")
                .execute()

            reports.put(JSONObject()
                .put("id", uploadedFile.id)
                .put("name", uploadedFile.name)
                .put("originalName", originalName)
                .put("mimeType", if (mimeType.startsWith("image/")) "image/jpeg" else mimeType)
                .put("uploadedAt", uploadedAt)
                .put("webViewLink", uploadedFile.webViewLink ?: "")
                .put("webContentLink", uploadedFile.webContentLink ?: "")
            )
        }

        reports
    }

    private fun readReportBytes(uri: Uri, mimeType: String): ByteArray {
        val originalBytes = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw Exception("Could not read selected report")

        if (!mimeType.startsWith("image/") || originalBytes.size <= 700 * 1024) {
            return originalBytes
        }

        return try {
            val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                ?: return originalBytes
            val maxSide = 1600f
            val scale = minOf(1f, maxSide / maxOf(bitmap.width, bitmap.height).toFloat())
            val outputBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxOf(1, (bitmap.width * scale).toInt()),
                    maxOf(1, (bitmap.height * scale).toInt()),
                    true
                )
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            val compressed = output.toByteArray()
            if (compressed.isNotEmpty() && compressed.size < originalBytes.size) compressed else originalBytes
        } catch (e: Exception) {
            originalBytes
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                name = it.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment ?: "Report"
    }

    private suspend fun deleteReportFromGoogleDrive(fileId: String) = withContext(Dispatchers.IO) {
        val service = driveService ?: throw Exception("Drive service not initialized")
        if (fileId.isBlank()) throw Exception("Report file id is missing")
        service.files().delete(fileId).execute()
    }

    private fun buildReportFileName(patientId: String, originalName: String, uploadedAt: String): String {
        val safePatientId = sanitizeFileName(patientId.ifBlank { "Patient" })
        val safeOriginal = sanitizeFileName(originalName.ifBlank { "Report" })
        val dotIndex = safeOriginal.lastIndexOf('.')
        val baseName = if (dotIndex > 0) safeOriginal.substring(0, dotIndex) else safeOriginal
        val extension = if (dotIndex > 0 && dotIndex < safeOriginal.length - 1) safeOriginal.substring(dotIndex) else ""
        return "${safePatientId}_${baseName}_${uploadedAt}${extension}"
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(120)
            .ifBlank { "Report" }
    }

    private fun notifyDataReceived(data: String?) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onDataReceived === 'function') onDataReceived(${JSONObject.quote(data)});",
                null
            )
        }
    }

    private fun notifyReportUploadComplete(success: Boolean, payloadJson: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onReportUploadComplete === 'function') onReportUploadComplete($success, ${JSONObject.quote(payloadJson)});",
                null
            )
        }
    }

    private fun notifyReportDeleteComplete(success: Boolean, payloadJson: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onReportDeleteComplete === 'function') onReportDeleteComplete($success, ${JSONObject.quote(payloadJson)});",
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
