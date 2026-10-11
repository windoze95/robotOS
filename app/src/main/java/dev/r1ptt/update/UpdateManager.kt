package dev.r1ptt.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.r1ptt.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, STAGING, CONFIRM, INSTALLING }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE,
    val message: String = "Check when you want to update. No background checks or downloads.",
    val release: ReleaseManifest? = null)

/** User-driven work only. All admission/session state is owned by the main thread. */
class UpdateManager(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("app-updater", 0)
    private val installer = app.packageManager.packageInstaller
    private val gate = UpdateGate()
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    val installing get() = gate.installing
    private var foreground = false
    private var externalInstaller = false
    private var job: Job? = null
    private var http: UpdateHttp? = null
    private var generation = 0L
    private var signedMetadata: ByteArray? = null
    private var ready: File? = null
    private var approval: Intent? = null
    private var sessionId = -1
    private val timeout = Runnable { cancelInstall("Update confirmation timed out. You can try again.") }

    @Suppress("DEPRECATION")
    private fun installed() = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun installedVersion(): String = installed().versionName ?: "Unknown"

    private fun certificate(): X509Certificate {
        val signatures = installed().signingInfo?.apkContentsSigners
        if (signatures == null || signatures.size != 1)
            throw UpdateException("This app's signing identity is not supported for updates.")
        return CertificateFactory.getInstance("X.509").generateCertificate(signatures.single().toByteArray().inputStream()) as X509Certificate
    }

    private fun authenticate(bytes: ByteArray): ReleaseManifest {
        val cert = certificate()
        return ReleaseManifest.verify(bytes, cert.publicKey, ReleaseManifest.sha256(cert.encoded))
    }

    /** Startup recovery never retries an install and only abandons this app's saved session. */
    fun recover() {
        sessionId = prefs.getInt("session", -1)
        val expected = prefs.getLong("installVersion", 0)
        if (sessionId >= 0) {
            gate.begin(foreground = true, voiceBusy = false)
            val message = if (expected > 0 && installed().longVersionCode >= expected) "robotOS is updated."
                else "The previous update was interrupted. Check again when ready."
            cancelInstall(message)
        }
        File(app.cacheDir, "updates").deleteRecursively()
    }

    fun foreground(value: Boolean) {
        foreground = value
        if (!value && !externalInstaller) {
            if (installing) cancelInstall("Update paused because you left the update screen.")
            else cancelWork("Update paused. Check or download again when ready.")
        }
    }

    fun allowVoice(): Boolean {
        if (installing) {
            Toast.makeText(app, "Finish or cancel the update in App updates first", Toast.LENGTH_SHORT).show()
            return false
        }
        cancelWork("Update paused for your conversation. You can retry afterwards.")
        return true
    }

    fun check() {
        if (!app.workspace.networkAllowed) return
        if (!foreground || installing || app.turns.busy) return message("Finish your conversation before checking for updates.")
        cancelWork()
        discardDownload()
        signedMetadata = null
        val id = generation
        val transport = UpdateHttp().also { http = it }
        _state.value = UpdateState(UpdatePhase.CHECKING, "Checking for a signed release…")
        job = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { transport.metadata() }
                if (id != generation) return@launch
                if (bytes == null) { _state.value = UpdateState(message = "No published app updates yet."); return@launch }
                val release = authenticate(bytes)
                if (!UpdatePolicy.newer(release, installed().longVersionCode, prefs.getLong("highestSeen", 0), Build.VERSION.SDK_INT)) {
                    _state.value = UpdateState(message = "You have this release of robotOS.")
                    return@launch
                }
                // Only an authenticated release can advance the local replay fence.
                if (!prefs.edit().putLong("highestSeen", release.versionCode).commit())
                    throw UpdateException("Could not save update verification state.")
                signedMetadata = bytes
                _state.value = UpdateState(UpdatePhase.AVAILABLE, "robotOS ${release.versionName} is available.", release)
            } catch (_: CancellationException) {
                // The cancelling action already published the next state.
            } catch (e: Exception) {
                if (id == generation) _state.value = UpdateState(message = safeError(e))
            } finally { if (id == generation) { job = null; http = null } }
        }
    }

    fun download() {
        if (!app.workspace.networkAllowed) return
        val release = _state.value.release ?: return
        if (!foreground || installing || app.turns.busy) return message("Finish your conversation before downloading.")
        cancelWork()
        discardDownload()
        val id = generation
        val transport = UpdateHttp().also { http = it }
        val directory = File(app.cacheDir, "updates/${UUID.randomUUID()}")
        _state.value = UpdateState(UpdatePhase.DOWNLOADING, "Downloading and verifying robotOS ${release.versionName}…", release)
        job = scope.launch {
            var retained = false
            try {
                val file = withContext(Dispatchers.IO) {
                    transport.download(release, directory).also { verifyApk(it, release) }
                }
                if (id != generation) return@launch
                ready = file
                retained = true
                _state.value = UpdateState(UpdatePhase.READY, "Verified update ready. Install when your conversation is finished.", release)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (id == generation) _state.value = UpdateState(UpdatePhase.AVAILABLE, safeError(e), release)
            } finally {
                if (!retained) directory.deleteRecursively()
                if (id == generation) { job = null; http = null }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: ReleaseManifest) {
        UpdateFiles.verify(file, release)
        val apk = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("Downloaded file is not a valid APK.")
        val certs = apk.signingInfo?.apkContentsSigners?.map { ReleaseManifest.sha256(it.toByteArray()) }.orEmpty()
        UpdatePolicy.apkMatches(release, apk.packageName, apk.longVersionCode, apk.versionName, certs,
            apk.applicationInfo?.minSdkVersion ?: -1)
    }

    fun install() {
        if (!app.workspace.networkAllowed) return
        val bytes = signedMetadata ?: return
        val file = ready ?: return
        if (!app.packageManager.canRequestPackageInstalls()) return message("Allow update requests in Android settings first.")
        if (!gate.begin(foreground, app.turns.busy || app.smsBusy)) return message("Install after voice and SMS sending have finished.")
        if (!app.turns.prepareForUpdate()) { gate.finish(); return message("Your conversation is still finishing. Try again shortly.") }
        val release = try {
            authenticate(bytes).also {
                if (!UpdatePolicy.newer(it, installed().longVersionCode, prefs.getLong("highestSeen", 0), Build.VERSION.SDK_INT))
                    throw UpdateException("This release is already installed.")
            }
        } catch (e: Exception) { gate.finish(); return message(safeError(e)) }
        cancelWork()
        val id = generation
        _state.value = UpdateState(UpdatePhase.STAGING, "Preparing Android's installer. Voice is paused until this finishes or is cancelled.", release)
        job = scope.launch {
            try {
                // No new voice turn can start once the main-thread gate above has been acquired.
                withContext(Dispatchers.IO) { verifyApk(file, release) }
                if (!app.workspace.networkAllowed || id != generation || !foreground || !installing) throw UpdateException("Update cancelled before installation.")
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(ReleaseManifest.PACKAGE)
                    setSize(release.apkSize)
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(params)
                if (!prefs.edit().putInt("session", sessionId).putLong("installVersion", release.versionCode).commit())
                    throw UpdateException("Could not save the install recovery state.")
                val stagedId = sessionId
                withContext(Dispatchers.IO) {
                    installer.openSession(stagedId).use { session ->
                        session.openWrite("base.apk", 0, release.apkSize).use { output ->
                            file.inputStream().use { it.copyTo(output) }
                            session.fsync(output)
                        }
                    }
                }
                if (!app.workspace.networkAllowed || id != generation || !foreground || !installing || app.turns.busy || app.smsBusy)
                    throw UpdateException("Update cancelled before installation.")
                val callback = PendingIntent.getBroadcast(app, stagedId,
                    Intent(app, UpdateInstallReceiver::class.java).setAction(RESULT_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                _state.value = UpdateState(UpdatePhase.INSTALLING, "Waiting for Android's confirmation…", release)
                installer.openSession(stagedId).use { it.commit(callback.intentSender) }
                main.postDelayed(timeout, 5 * 60_000L) // bounded active handoff only; no idle polling
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (id == generation) cancelInstall(safeError(e))
            } finally { if (id == generation) job = null }
        }
    }

    fun installResult(intent: Intent) {
        val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        when (InstallResultPolicy.classify(sessionId, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2),
            intent.action == RESULT_ACTION, intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            confirmation != null)) {
            InstallResult.IGNORE -> return
            InstallResult.CONFIRM -> {
                approval = confirmation
                _state.value = _state.value.copy(phase = UpdatePhase.CONFIRM,
                    message = "Continue to Android to confirm this update. Voice remains paused.")
            }
            InstallResult.SUCCESS -> {
                clearSession()
                discardDownload()
                _state.value = UpdateState(message = "Update installed. Open robotOS to continue.")
            }
            InstallResult.FAILURE -> cancelInstall("Android did not install the update. Your current app and data remain in place.")
        }
    }

    fun confirmation(): Intent? {
        if (!app.workspace.networkAllowed || !foreground || !installing) return null
        return approval?.also { externalInstaller = true }
    }

    fun confirmationReturned(cancelled: Boolean) {
        externalInstaller = false
        if (cancelled && installing) cancelInstall("Update request cancelled. Check the installed version before retrying.")
    }

    fun restrict() {
        if (installing) cancelInstall("Device access restricted; update cancelled.")
        else cancelWork("Device access restricted; update paused.")
        approval = null
    }

    fun cancel() {
        if (installing) cancelInstall("Update request cancelled. Check the installed version before retrying.")
        else cancelWork("Update cancelled. You can retry when ready.")
    }

    private fun cancelInstall(text: String) {
        cancelWork()
        if (sessionId >= 0) {
            try {
                val info = installer.getSessionInfo(sessionId)
                if (info != null) {
                    if (info.installerPackageName != app.packageName) throw UpdateException("Unexpected installer owner")
                    installer.abandonSession(sessionId)
                }
            } catch (_: Exception) {
                message("Could not cancel the pending Android installer. Return to App updates and retry cancellation.")
                return // preserve the fence and session ID until cancellation is confirmed
            }
        }
        clearSession()
        _state.value = UpdateState(if (ready != null) UpdatePhase.READY else UpdatePhase.IDLE, text,
            if (ready != null) _state.value.release else null)
    }

    @SuppressLint("ApplySharedPref") // Synchronous best-effort cleanup; stale records are rechecked at startup.
    private fun clearSession() {
        main.removeCallbacks(timeout)
        prefs.edit().remove("session").remove("installVersion").commit()
        sessionId = -1
        approval = null
        externalInstaller = false
        gate.finish()
    }

    private fun cancelWork(text: String? = null) {
        generation++
        val active = job?.isActive == true
        job?.cancel(); job = null
        http?.cancel(); http = null
        if (active && text != null && !installing) _state.value = UpdateState(
            if (_state.value.release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE, text, _state.value.release)
    }

    private fun discardDownload() { ready?.parentFile?.deleteRecursively(); ready = null }
    private fun message(text: String) { _state.value = _state.value.copy(message = text) }
    private fun safeError(e: Exception) = if (e is UpdateException) e.message!!
        else "Update unavailable. Check your connection and available storage, then try again."

    companion object { const val RESULT_ACTION = "dev.r1ptt.UPDATE_RESULT" }
}
