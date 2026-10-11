package dev.r1ptt.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdateActivity : DeviceActivity() {
    private val updates get() = (application as App).updates
    private var uiScope: kotlinx.coroutines.CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private lateinit var confirm: Button
    private lateinit var cancel: Button
    private lateinit var release: TextView
    private lateinit var notes: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ui.page("App updates") { updates.cancel(); finish() }
        setContentView(page.root)
        wheelScrollTarget = page.scroll
        val root = page.body
        fun button(text: String, primary: Boolean = false, action: () -> Unit) = ui.button(text, primary, action = action).also(root::addView)
        root.addView(ui.card().apply {
            addView(ui.mono("Installed", R.color.accent))
            addView(ui.title("robotOS ${updates.installedVersion()}", 26f).apply { setPadding(0, ui.dp(4), 0, 0) })
        })
        status = ui.text("", 18f).apply { setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(12)); root.addView(this) }
        release = ui.mono("", R.color.dim, 13f, 500).apply { setPadding(ui.dp(4), 0, 0, ui.dp(16)); root.addView(this) }
        check = button("Check for updates", primary = true) { updates.check() }
        download = button("Download update", primary = true) { updates.download() }
        install = button("Install update", primary = true) {
            if (!packageManager.canRequestPackageInstalls()) {
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Allow robotOS update requests?")
                    .setMessage("Android requires you to allow this source before robotOS can request an update. You will still confirm each installation. You can turn this off again in Android settings.")
                    .setPositiveButton("Open Android settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { status.setText(R.string.update_permission_hint) }
                    }.setNegativeButton("Cancel", null))
            } else {
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Install the verified update?")
                    .setMessage("Finish your conversation first. Voice will pause while Android confirms and installs the update. Your conversations and settings stay in place.")
                    .setPositiveButton("Continue") { _, _ -> updates.install() }
                    .setNegativeButton("Cancel", null))
            }
        }
        confirm = button("Continue to Android confirmation", primary = true) {
            updates.confirmation()?.let {
                try { @Suppress("DEPRECATION") startActivityForResult(it, CONFIRM_INSTALL) }
                catch (_: Exception) { updates.confirmationReturned(cancelled = true) }
            }
        }
        cancel = button("Cancel update") { updates.cancel() }
        notes = button("Release notes") {
            updates.state.value.release?.let { version ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${ReleaseManifest.REPOSITORY}/releases/tag/${version.tag}"))) }
                    .onFailure { status.text = "No browser is available to open release notes." }
            }
        }
        root.addView(ui.note("Updates are checked only when you ask. Downloads stop when you leave this screen."))
        render(updates.state.value)
    }

    override fun onResume() {
        super.onResume()
        updates.foreground(true)
        uiScope = MainScope().also { scope -> scope.launch { updates.state.collect(::render) } }
    }

    private fun render(s: UpdateState) {
        status.text = s.message
        val working = s.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING) || updates.installing
        check.visibility = if (s.phase == UpdatePhase.IDLE && !working) View.VISIBLE else View.GONE
        download.visibility = if (s.phase == UpdatePhase.AVAILABLE && !working) View.VISIBLE else View.GONE
        install.visibility = if (s.phase == UpdatePhase.READY && !working) View.VISIBLE else View.GONE
        confirm.visibility = if (s.phase == UpdatePhase.CONFIRM && updates.installing) View.VISIBLE else View.GONE
        cancel.visibility = if (working) View.VISIBLE else View.GONE
        notes.visibility = if (s.release != null && !working) View.VISIBLE else View.GONE
        release.visibility = if (s.release != null) View.VISIBLE else View.GONE
        release.text = s.release?.let { "Version ${it.versionName}\nDownload ${android.text.format.Formatter.formatShortFileSize(this, it.apkSize)}" }.orEmpty()
    }

    override fun onPause() {
        uiScope?.cancel(); uiScope = null
        updates.foreground(false)
        super.onPause()
    }

    @Deprecated("Uses platform activity result for PackageInstaller's confirmation UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIRM_INSTALL) updates.confirmationReturned(resultCode != RESULT_OK)
    }

    companion object { private const val CONFIRM_INSTALL = 1 }
}
