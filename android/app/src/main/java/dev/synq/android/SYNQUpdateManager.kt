package dev.synq.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateManifest(
    val version: String,
    val versionCode: Int,
    val releaseDate: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
    val certificateFingerprint: String? = null,
)

sealed interface UpdateStatus {
    object Idle : UpdateStatus
    object Checking : UpdateStatus
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateStatus
    data class UpToDate(val version: String, val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus
    data class ReadyToInstall(val apkFile: File, val manifest: UpdateManifest) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

class SYNQUpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null

    companion object {
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/bharadwajsanket/SYNQ/main/release-manifest.json"
    }

    val currentVersionName: String by lazy {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "4.5.4"
        } catch (_: Exception) {
            "4.5.4"
        }
    }

    val currentVersionCode: Long by lazy {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            35L
        }
    }

    fun checkForUpdates(manifestUrl: String = DEFAULT_MANIFEST_URL) {
        if (_status.value is UpdateStatus.Checking || _status.value is UpdateStatus.Downloading) {
            return
        }
        _status.value = UpdateStatus.Checking

        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    fetchManifest(manifestUrl)
                }

                if (manifest.versionCode > currentVersionCode) {
                    _status.value = UpdateStatus.UpdateAvailable(manifest)
                } else {
                    _status.value = UpdateStatus.UpToDate(currentVersionName)
                }
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(e.message ?: "Failed to check for updates")
            }
        }
    }

    fun downloadUpdate(manifest: UpdateManifest) {
        if (_status.value is UpdateStatus.Downloading) return

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _status.value = UpdateStatus.Downloading(0, 0, manifest.size)
            try {
                val file = withContext(Dispatchers.IO) {
                    downloadAndVerifyApk(manifest)
                }
                _status.value = UpdateStatus.ReadyToInstall(file, manifest)
            } catch (e: CancellationException) {
                _status.value = UpdateStatus.UpdateAvailable(manifest)
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val current = _status.value
        if (current is UpdateStatus.Downloading) {
            _status.value = UpdateStatus.Idle
        }
    }

    fun installUpdate(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            _status.value = UpdateStatus.Error("Downloaded installer file is missing or empty")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Error("Failed to launch package installer: ${e.message}")
        }
    }

    fun resetState() {
        _status.value = UpdateStatus.Idle
    }

    private fun fetchManifest(urlString: String): UpdateManifest {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SYNQ-Android/${currentVersionName}")
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Server returned HTTP $code")
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val androidObj = json.optJSONObject("platforms")?.optJSONObject("android")
                ?: json.optJSONObject("android")
                ?: throw IllegalArgumentException("Manifest is missing 'android' update section")

            return UpdateManifest(
                version = androidObj.optString("version", json.optString("version", "4.5.4")),
                versionCode = androidObj.optInt("versionCode", json.optInt("versionCode", 45)),
                releaseDate = json.optString("releaseDate", ""),
                releaseNotes = json.optString("releaseNotes", "Performance and stability updates"),
                downloadUrl = androidObj.optString("downloadUrl"),
                size = androidObj.optLong("size", 0L),
                sha256 = androidObj.optString("sha256", "").lowercase().trim(),
                certificateFingerprint = androidObj.optString("certificateFingerprint").takeIf { it.isNotBlank() }?.lowercase()?.trim()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndVerifyApk(manifest: UpdateManifest): File {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "SYNQ-v${manifest.version}.apk")
        val partFile = File(updatesDir, "SYNQ-v${manifest.version}.apk.part")

        if (partFile.exists()) partFile.delete()

        val url = URL(manifest.downloadUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "SYNQ-Android/${currentVersionName}")
        }

        val totalBytes = if (conn.contentLengthLong > 0) conn.contentLengthLong else manifest.size
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            var downloadedBytes = 0L
            val buffer = ByteArray(32 * 1024)

            conn.inputStream.use { input: InputStream ->
                FileOutputStream(partFile).use { output ->
                    var read: Int
                    var lastProgressUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes) {
                            lastProgressUpdate = now
                            val pct = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else 0
                            _status.value = UpdateStatus.Downloading(pct, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            // 1. Verify byte count
            if (manifest.size > 0 && downloadedBytes != manifest.size) {
                partFile.delete()
                throw IllegalStateException("Downloaded file size ($downloadedBytes bytes) does not match manifest (${manifest.size} bytes)")
            }

            // 2. Verify SHA-256 integrity
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }.lowercase()
            if (manifest.sha256.isNotBlank() && actualHash != manifest.sha256) {
                partFile.delete()
                throw SecurityException("Cryptographic checksum mismatch! Expected ${manifest.sha256}, got $actualHash")
            }

            // 3. Verify Android package integrity, identity, and certificate
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = context.packageManager.getPackageArchiveInfo(partFile.absolutePath, flags)
                ?: run {
                    partFile.delete()
                    throw SecurityException("Downloaded archive is not a valid Android APK")
                }

            if (pkgInfo.packageName != context.packageName) {
                partFile.delete()
                throw SecurityException("Downloaded package '${pkgInfo.packageName}' does not match '${context.packageName}'")
            }

            // Verify certificate fingerprint against current installed app
            val downloadedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }

            val downloadedCertSha256 = downloadedSignatures?.firstOrNull()?.toByteArray()?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }?.lowercase()

            val currentSignatures = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                }
            } catch (_: Exception) { null }

            val currentCertSha256 = currentSignatures?.firstOrNull()?.toByteArray()?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }?.lowercase()

            if (downloadedCertSha256 != null && currentCertSha256 != null && downloadedCertSha256 != currentCertSha256) {
                partFile.delete()
                throw SecurityException("Signing certificate mismatch! Downloaded cert ($downloadedCertSha256) cannot update installed app ($currentCertSha256)")
            }

            if (manifest.certificateFingerprint != null && downloadedCertSha256 != null &&
                manifest.certificateFingerprint != downloadedCertSha256) {
                partFile.delete()
                throw SecurityException("Manifest certificate mismatch! Expected ${manifest.certificateFingerprint}, got $downloadedCertSha256")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to move verified update into place")
            }

            return targetFile
        } finally {
            conn.disconnect()
        }
    }
}
