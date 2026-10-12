package dev.r1ptt.data

/** A write atomically replaces the entire synced blob, or throws and leaves the previous blob intact. */
internal interface ConfigBlobStorage {
    fun read(): String?
    fun write(blob: String)
}

internal interface ConfigCipher {
    /** Creating a new key is safe only while there is no committed blob that needs the old key. */
    fun seal(plain: String, allowKeyCreation: Boolean): String
    fun open(blob: String): String
}

internal interface ConfigCodec<T> {
    fun encode(value: T): String
    fun decode(plain: String): T
}

/** Safe to show on-screen or return from provisioning; underlying exception text may contain keys. */
class ConfigPersistenceException internal constructor(
    val operation: Operation,
    cause: Exception? = null,
) : IllegalStateException(
    when (operation) {
        Operation.LOAD -> "Stored settings could not be read securely. Existing settings have been kept. " +
            "Retry loading after restoring Android Keystore or storage access. If recovery is impossible, " +
            "clear app data and re-import your settings."
        Operation.ENCRYPT -> "Secure storage is unavailable. Settings were not saved. " +
            "Restore Android Keystore access and retry."
        Operation.WRITE -> "Settings could not be saved to storage. Previous settings have been kept. Retry saving."
    },
    cause,
) {
    enum class Operation { LOAD, ENCRYPT, WRITE }
}

/**
 * Coordinates encryption, persistence and publication without depending on Android. Defaults allow
 * the app to start, but a failed load locks writes so they cannot replace inaccessible credentials.
 */
internal class EncryptedConfigPersistence<T>(
    defaults: T,
    private val storage: ConfigBlobStorage,
    private val cipher: ConfigCipher,
    private val codec: ConfigCodec<T>,
) {
    @Volatile private var current = defaults
    @Volatile private var failure: ConfigPersistenceException? = null
    private var hasStoredBlob = false
    /** Plain text of the committed blob, so an update that changes nothing skips Keystore and disk. */
    private var storedPlain: String? = null

    init {
        reload()
    }

    /** The last committed value. Readers never wait for an update's encryption or fsync. */
    val value: T
        get() = current

    val loadError: ConfigPersistenceException?
        get() = failure

    /** A failed retry preserves both the file and the last successfully loaded value. */
    @Synchronized
    fun reload(): Boolean = try {
        val blob = storage.read()
        // A missing blob cannot unlock a failed load. Recovery must read the existing settings,
        // or the user must explicitly clear app data and start a fresh store.
        check(blob != null || failure == null) { "Stored settings disappeared during recovery" }
        val plain = blob?.let { cipher.open(it) }
        val loaded = plain?.let { codec.decode(it) } ?: current
        current = loaded
        if (blob != null) {
            hasStoredBlob = true
            storedPlain = plain
        }
        failure = null
        true
    } catch (e: Exception) {
        failure = ConfigPersistenceException(ConfigPersistenceException.Operation.LOAD, e)
        false
    }

    @Synchronized
    fun update(transform: (T) -> T): T {
        failure?.let { throw it }
        val next = transform(current)
        val plain = codec.encode(next)
        if (hasStoredBlob && plain == storedPlain) {
            current = next
            return next
        }
        val blob = try {
            cipher.seal(plain, allowKeyCreation = !hasStoredBlob)
        } catch (e: Exception) {
            throw ConfigPersistenceException(ConfigPersistenceException.Operation.ENCRYPT, e)
        }
        try {
            storage.write(blob)
        } catch (e: Exception) {
            throw ConfigPersistenceException(ConfigPersistenceException.Operation.WRITE, e)
        }
        // Publish only after encrypted bytes have been committed successfully.
        current = next
        hasStoredBlob = true
        storedPlain = plain
        return next
    }
}
