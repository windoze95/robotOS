package dev.r1ptt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The current [Config], persisted encrypted (it holds API keys) and observable. */
class ConfigStore(ctx: Context) {
    // Keep the previous encrypted preference as a read-only migration source. It is never replaced
    // or removed on a failed read/write, and the new file takes precedence once a save succeeds.
    private val persistence = EncryptedConfigPersistence(
        defaults = Config(),
        storage = object : ConfigBlobStorage {
            private val file = AtomicConfigFile(File(ctx.filesDir, "config.enc"))
            override fun read(): String? {
                file.read()?.let { return it }
                // SharedPreferences can turn a failed XML read into an empty map. Existing files
                // without a readable value must therefore lock writes instead of implying defaults.
                val legacy = File(ctx.applicationInfo.dataDir, "shared_prefs/config.xml")
                val existingLegacy = exists(legacy) || exists(File(legacy.path + ".bak"))
                val blob = ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getString(KEY, null)
                check(blob != null || !existingLegacy) { "Existing config preference could not be read" }
                return blob
            }
            override fun write(blob: String) = file.write(blob)

            private fun exists(file: File): Boolean = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                true
            } catch (_: NoSuchFileException) {
                false
            }
        },
        cipher = Sealed,
        codec = object : ConfigCodec<Config> {
            override fun encode(value: Config) = ConfigJson.toJson(value).toString()
            override fun decode(plain: String) = ConfigJson.merge(Config(), JSONObject(plain))
        },
    )
    private val state = MutableStateFlow(persistence.value)

    val flow: StateFlow<Config> = state
    val value: Config get() = state.value

    /** Safe recovery guidance; non-null means saves are locked to preserve inaccessible settings. */
    val loadError: String? get() = persistence.loadError?.message

    @Synchronized
    fun retryReload(): Boolean {
        val loaded = persistence.reload()
        if (loaded) state.value = persistence.value
        return loaded
    }

    @Synchronized
    fun update(transform: (Config) -> Config): Config {
        val next = persistence.update(transform)
        state.value = next
        return next
    }

    /** Validation runs under the write lock; a rejected or malformed import changes nothing. */
    fun import(json: String, validate: (JSONObject) -> Unit = {}): Config = update {
        val patch = JSONObject(json)
        validate(patch)
        ConfigJson.merge(it, patch)
    }

    private companion object {
        const val KEY = "config"
    }
}

/** AES-GCM with a non-exportable Android Keystore key, so the keys aren't sitting in plain XML. */
internal object Sealed : ConfigCipher {
    private const val ALIAS = "r1ptt-config"

    override fun seal(plain: String, allowKeyCreation: Boolean): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(create = allowKeyCreation))
        }
        return "v1:" + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun open(blob: String): String {
        require(blob.startsWith("v1:")) { "Unsupported secure config format" }
        val pieces = blob.split(":", limit = 3)
        require(pieces.size == 3) { "Incomplete secure config" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // A missing key is a recovery error. Never replace it with a new key during a read.
        c.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, unb64(pieces[1])))
        return String(c.doFinal(unb64(pieces[2])), Charsets.UTF_8)
    }

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Stored configuration key is unavailable" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
