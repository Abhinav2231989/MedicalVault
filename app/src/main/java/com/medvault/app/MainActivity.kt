package com.medvault.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.MediaStore
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
import java.io.File as JavaFile
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
                val uri = request?.url ?: return false
                if (uri.scheme == "tel") {
                    openDialer(uri)
                    return true
                }
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

    private fun openDialer(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open phone dialer", Toast.LENGTH_SHORT).show()
        }
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

        @JavascriptInterface
        fun exportPdf(fileName: String, payloadJson: String) {
            runOnUiThread {
                exportPrescriptionPdf(fileName, payloadJson)
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

    private data class PdfOutput(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val isPendingMediaStoreItem: Boolean
    )

    private fun exportPrescriptionPdf(fileName: String, payloadJson: String) {
        val safeName = buildPdfFileName(fileName)
        val output = try {
            createPdfOutput(safeName)
        } catch (e: Exception) {
            notifyPdfExportComplete(false, "Could not create PDF file: ${e.message}")
            Toast.makeText(this@MainActivity, "Could not create PDF file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val payload = JSONObject(payloadJson)
            val document = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 42f
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            var y = margin

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(10, 25, 41)
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 100, 100)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(95, 95, 95)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25, 25, 25)
                textSize = 12f
            }
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(65, 65, 65)
                textSize = 10.5f
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 210, 210)
                strokeWidth = 1f
            }

            fun newPage() {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }

            fun ensureSpace(height: Float) {
                if (y + height > pageHeight - margin) newPage()
            }

            fun drawWrappedText(text: String, x: Float, paint: Paint, maxWidth: Float, lineHeight: Float) {
                val words = text.replace("\r", "").split(Regex("\\s+"))
                var line = ""
                if (words.isEmpty()) {
                    ensureSpace(lineHeight)
                    canvas.drawText("", x, y, paint)
                    y += lineHeight
                    return
                }
                words.forEach { word ->
                    val candidate = if (line.isBlank()) word else "$line $word"
                    if (paint.measureText(candidate) <= maxWidth) {
                        line = candidate
                    } else {
                        ensureSpace(lineHeight)
                        canvas.drawText(line, x, y, paint)
                        y += lineHeight
                        line = word
                    }
                }
                if (line.isNotBlank()) {
                    ensureSpace(lineHeight)
                    canvas.drawText(line, x, y, paint)
                    y += lineHeight
                }
            }

            fun drawField(label: String, value: String, x: Float, width: Float) {
                ensureSpace(34f)
                canvas.drawText(label.uppercase(Locale.US), x, y, labelPaint)
                y += 15f
                drawWrappedText(value.ifBlank { "N/A" }, x, bodyPaint, width, 15f)
            }

            fun drawSection(title: String) {
                ensureSpace(32f)
                y += 8f
                canvas.drawText(title.uppercase(Locale.US), margin, y, headingPaint)
                y += 8f
                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
                y += 18f
            }

            canvas.drawText("Prescription", margin, y, titlePaint)
            canvas.drawText("MedVault Patient Record", pageWidth - margin - smallPaint.measureText("MedVault Patient Record"), y, smallPaint)
            y += 22f
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 28f

            val leftWidth = 240f
            val rightX = 320f
            val rowStart = y
            drawField("Patient Name", payload.optString("patientName", "N/A"), margin, leftWidth)
            y = rowStart
            drawField("Date of Visit", payload.optString("dateOfVisit", "N/A"), rightX, 210f)
            y += 8f
            val rowTwoStart = y
            drawField("Phone Number", payload.optString("phoneNumber", "N/A"), margin, leftWidth)
            y = rowTwoStart
            drawField("Patient ID", payload.optString("patientId", "N/A"), rightX, 210f)

            drawSection("Diagnosis")
            drawWrappedText(payload.optString("diagnosis", "N/A"), margin, bodyPaint, pageWidth - (margin * 2), 16f)

            drawSection("Medicines")
            drawWrappedText(payload.optString("medicines", "Not prescribed yet"), margin, bodyPaint, pageWidth - (margin * 2), 16f)

            drawSection("Prescription Details")
            val prescriptions = payload.optJSONArray("prescriptions") ?: JSONArray()
            if (prescriptions.length() == 0) {
                drawWrappedText("No structured prescriptions added", margin, bodyPaint, pageWidth - (margin * 2), 16f)
            } else {
                for (i in 0 until prescriptions.length()) {
                    val item = prescriptions.optJSONObject(i) ?: JSONObject()
                    ensureSpace(46f)
                    canvas.drawText("${i + 1}.", margin, y, bodyPaint)
                    drawWrappedText(item.optString("medicine", "Medicine"), margin + 24f, bodyPaint, pageWidth - margin * 2 - 24f, 16f)
                    val meta = item.optString("meta", "")
                    if (meta.isNotBlank()) drawWrappedText(meta, margin + 24f, smallPaint, pageWidth - margin * 2 - 24f, 14f)
                    val instructions = item.optString("instructions", "")
                    if (instructions.isNotBlank()) drawWrappedText(instructions, margin + 24f, smallPaint, pageWidth - margin * 2 - 24f, 14f)
                    y += 8f
                }
            }

            ensureSpace(72f)
            y += 42f
            canvas.drawLine(pageWidth - margin - 180f, y, pageWidth - margin, y, linePaint)
            y += 15f
            canvas.drawText("Doctor Signature", pageWidth - margin - 145f, y, smallPaint)

            document.finishPage(page)
            document.writeTo(java.io.FileOutputStream(output.descriptor.fileDescriptor))
            document.close()
            output.descriptor.close()
            completePdfOutput(output, true)
            notifyPdfExportComplete(true, "Saved to Downloads/MedVault/$safeName")
            Toast.makeText(this@MainActivity, "PDF saved to Downloads/MedVault", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                output.descriptor.close()
            } catch (_: Exception) {
            }
            completePdfOutput(output, false)
            notifyPdfExportComplete(false, e.message ?: "PDF export failed")
            Toast.makeText(this@MainActivity, "PDF export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPdfOutput(fileName: String): PdfOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MedVault")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Downloads storage is not available")
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: throw Exception("Could not open PDF file")
            return PdfOutput(uri, descriptor, true)
        }

        val directory = JavaFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MedVault")
        if (!directory.exists() && !directory.mkdirs()) {
            throw Exception("Could not create Downloads/MedVault")
        }
        val file = JavaFile(directory, fileName)
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        return PdfOutput(Uri.fromFile(file), descriptor, false)
    }

    private fun completePdfOutput(output: PdfOutput, success: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && output.isPendingMediaStoreItem) {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(output.uri, values, null, null)
            } else {
                contentResolver.delete(output.uri, null, null)
            }
        }
    }

    private fun buildPdfFileName(fileName: String): String {
        val baseName = sanitizeFileName(fileName.removeSuffix(".pdf").ifBlank { "Prescription" })
        return "${baseName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
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

    private fun notifyPdfExportComplete(success: Boolean, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onPdfExportComplete === 'function') onPdfExportComplete($success, ${JSONObject.quote(message)});",
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
