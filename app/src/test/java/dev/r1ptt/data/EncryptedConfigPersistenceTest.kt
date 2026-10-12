package dev.r1ptt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EncryptedConfigPersistenceTest {
    private class MemoryStorage(var blob: String? = null) : ConfigBlobStorage {
        var readFailure = false
        var writeFailure = false
        val writes = mutableListOf<String>()
        var beforeCommit: (() -> Unit)? = null

        override fun read(): String? {
            if (readFailure) throw IOException("private storage details")
            return blob
        }

        override fun write(blob: String) {
            writes += blob
            beforeCommit?.invoke()
            if (writeFailure) throw IOException("private storage details")
            this.blob = blob
        }
    }

    private class FakeCipher : ConfigCipher {
        var sealFailure = false
        var openFailure = false
        var seals = 0
        var keyExists = true
        val keyCreationPermissions = mutableListOf<Boolean>()

        override fun seal(plain: String, allowKeyCreation: Boolean): String {
            seals++
            keyCreationPermissions += allowKeyCreation
            if (sealFailure) throw IOException("secret-value")
            check(keyExists || allowKeyCreation) { "Existing key is unavailable" }
            keyExists = true
            return encrypted(plain)
        }

        override fun open(blob: String): String {
            if (openFailure) throw IOException("secret-value")
            require(blob.startsWith("cipher:")) { "Unsupported format" }
            return blob.removePrefix("cipher:").reversed()
        }
    }

    private val codec = object : ConfigCodec<String> {
        override fun encode(value: String) = value
        override fun decode(plain: String): String {
            require(plain != "malformed") { "secret-value" }
            return plain
        }
    }

    private fun persistence(storage: MemoryStorage, cipher: FakeCipher = FakeCipher()) =
        EncryptedConfigPersistence("defaults", storage, cipher, codec)

    @Test
    fun emptyStoreWritesOnlyEncryptedDataAndPublishesAfterCommit() {
        val storage = MemoryStorage()
        val store = persistence(storage)
        storage.beforeCommit = { assertEquals("defaults", store.value) }

        assertNull(store.loadError)
        assertEquals("new-key", store.update { "new-key" })
        assertEquals(listOf(encrypted("new-key")), storage.writes)
        assertEquals("new-key", store.value)
        assertEquals("new-key", persistence(storage).value)
    }

    @Test
    fun encryptionFailureNeverWritesPlaintextAndCanBeRetried() {
        val storage = MemoryStorage(encrypted("old-key"))
        val cipher = FakeCipher().apply { sealFailure = true }
        val store = persistence(storage, cipher)

        val error = expectFailure { store.update { "new-key" } }

        assertEquals(ConfigPersistenceException.Operation.ENCRYPT, error.operation)
        assertFalse(error.message.orEmpty().contains("secret-value"))
        assertEquals(emptyList<String>(), storage.writes)
        assertEquals(encrypted("old-key"), storage.blob)
        assertEquals("old-key", store.value)
        cipher.sealFailure = false
        assertEquals("old-key+retry", store.update { "$it+retry" })
        assertEquals(encrypted("old-key+retry"), storage.blob)
    }

    @Test
    fun encryptionFailureInNewStoreLeavesNothingOnDisk() {
        val storage = MemoryStorage()
        val store = persistence(storage, FakeCipher().apply { sealFailure = true })

        expectFailure { store.update { "new-key" } }

        assertNull(storage.blob)
        assertTrue(storage.writes.isEmpty())
        assertEquals("defaults", store.value)
    }

    @Test
    fun keyCreationIsPermittedOnlyUntilTheFirstSuccessfulCommit() {
        val storage = MemoryStorage()
        val cipher = FakeCipher().apply { keyExists = false }
        val store = persistence(storage, cipher)

        store.update { "first-key" }
        store.update { "second-key" }

        assertEquals(listOf(true, false), cipher.keyCreationPermissions)
    }

    @Test
    fun missingKeyAfterSuccessfulLoadNeverCreatesAReplacementKey() {
        val storage = MemoryStorage(encrypted("old-key"))
        val cipher = FakeCipher()
        val store = persistence(storage, cipher)
        cipher.keyExists = false

        val error = expectFailure { store.update { "new-key" } }

        assertEquals(ConfigPersistenceException.Operation.ENCRYPT, error.operation)
        assertEquals(listOf(false), cipher.keyCreationPermissions)
        assertFalse(cipher.keyExists)
        assertEquals(encrypted("old-key"), storage.blob)
        assertEquals("old-key", store.value)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun storageFailurePreservesThePreviousBlobAndValueForRetry() {
        val storage = MemoryStorage(encrypted("old-key")).apply { writeFailure = true }
        val store = persistence(storage)

        val error = expectFailure { store.update { "new-key" } }

        assertEquals(ConfigPersistenceException.Operation.WRITE, error.operation)
        assertEquals(encrypted("old-key"), storage.blob)
        assertEquals("old-key", store.value)
        assertEquals("old-key", persistence(storage).value)
        storage.writeFailure = false
        assertEquals("old-key+retry", store.update { "$it+retry" })
        assertEquals("old-key+retry", persistence(storage).value)
    }

    @Test
    fun unreadableStoredBlobLocksWritesUntilSuccessfulReload() {
        val storage = MemoryStorage(encrypted("old-key"))
        val cipher = FakeCipher().apply { openFailure = true }
        val store = persistence(storage, cipher)
        var transformed = false

        assertNotNull(store.loadError)
        assertFalse(store.reload())
        val error = expectFailure { store.update { transformed = true; "defaults-with-new-key" } }

        assertEquals(ConfigPersistenceException.Operation.LOAD, error.operation)
        assertFalse(error.message.orEmpty().contains("secret-value"))
        assertFalse(transformed)
        assertEquals(0, cipher.seals)
        assertTrue(storage.writes.isEmpty())
        assertEquals(encrypted("old-key"), storage.blob)
        cipher.openFailure = false
        assertTrue(store.reload())
        assertNull(store.loadError)
        assertEquals("old-key", store.value)
        assertEquals("old-key+retry", store.update { "$it+retry" })
    }

    @Test
    fun failedStorageReadCannotBeMistakenForAnEmptyStore() {
        val storage = MemoryStorage(encrypted("old-key")).apply { readFailure = true }
        val store = persistence(storage)

        expectFailure { store.update { "replacement" } }
        assertTrue(storage.writes.isEmpty())
        assertEquals(encrypted("old-key"), storage.blob)
        storage.readFailure = false
        assertTrue(store.reload())
        assertEquals("old-key", store.value)
    }

    @Test
    fun missingBlobAfterFailedReadCannotUnlockDefaultWrites() {
        val storage = MemoryStorage(encrypted("old-key")).apply { readFailure = true }
        val store = persistence(storage)
        storage.readFailure = false
        storage.blob = null

        assertFalse(store.reload())
        expectFailure { store.update { "replacement" } }
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun malformedDecryptedSettingsArePreservedAndLockWrites() {
        val storage = MemoryStorage(encrypted("malformed"))
        val store = persistence(storage)

        assertNotNull(store.loadError)
        expectFailure { store.update { "replacement" } }
        assertEquals(encrypted("malformed"), storage.blob)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun plaintextStoredBlobIsRejectedWithoutBeingOverwritten() {
        val storage = MemoryStorage("plain:legacy-key")
        val store = persistence(storage)

        assertNotNull(store.loadError)
        expectFailure { store.update { "replacement" } }
        assertEquals("plain:legacy-key", storage.blob)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun failedReloadPreservesLastCommittedValueAndThenRecovers() {
        val storage = MemoryStorage(encrypted("old-key"))
        val store = persistence(storage)
        store.update { "new-key" }
        storage.readFailure = true

        assertFalse(store.reload())
        assertEquals("new-key", store.value)
        expectFailure { store.update { "replacement" } }
        assertEquals(encrypted("new-key"), storage.blob)
        storage.readFailure = false
        assertTrue(store.reload())
        assertEquals("new-key", store.value)
    }

    @Test
    fun invalidEditsLeaveTheCommittedValueAndBlobUnchanged() {
        val storage = MemoryStorage(encrypted("old-key"))
        val cipher = FakeCipher()
        val store = persistence(storage, cipher)

        try {
            store.update { throw IllegalArgumentException("invalid input") }
            throw AssertionError("Expected edit rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals("old-key", store.value)
            assertEquals(encrypted("old-key"), storage.blob)
            assertEquals(0, cipher.seals)
            assertTrue(storage.writes.isEmpty())
        }
    }

    @Test
    fun concurrentUpdatesUseTheLastCommittedValue() {
        val storage = MemoryStorage(encrypted("start"))
        val store = persistence(storage)
        val firstCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failures = mutableListOf<Throwable>()
        storage.beforeCommit = {
            if (firstCommit.count > 0) {
                firstCommit.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
            }
        }
        fun update(suffix: String) {
            try {
                store.update { it + suffix }
            } catch (e: Throwable) {
                synchronized(failures) { failures += e }
            } finally {
                finished.countDown()
            }
        }
        val first = thread { update("+first") }
        val second = thread {
            check(firstCommit.await(5, TimeUnit.SECONDS))
            secondStarted.countDown()
            update("+second")
        }
        try {
            assertTrue(firstCommit.await(5, TimeUnit.SECONDS))
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        } finally {
            releaseCommit.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        first.join()
        second.join()
        assertTrue(failures.isEmpty())
        assertEquals("start+first+second", store.value)
        assertEquals(encrypted("start+first+second"), storage.blob)
    }

    @Test
    fun anUpdateThatChangesNothingSkipsTheKeystoreAndTheDisk() {
        val storage = MemoryStorage(encrypted("start"))
        val cipher = FakeCipher()
        val store = persistence(storage, cipher)

        assertEquals("start", store.update { it })
        assertEquals(0, cipher.seals)
        assertTrue(storage.writes.isEmpty())
        store.update { "next" }
        store.update { "next" }
        assertEquals(listOf(encrypted("next")), storage.writes)
        assertEquals(1, cipher.seals)
    }

    @Test
    fun readersGetTheCommittedValueWithoutWaitingForAWrite() {
        val storage = MemoryStorage(encrypted("start"))
        val store = persistence(storage)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        storage.beforeCommit = { writing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val writer = thread { store.update { "next" } }
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            assertEquals("start", reader.submit<String> { store.value }.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.join(5_000)
            reader.shutdownNow()
        }
        assertEquals("next", store.value)
    }

    private fun expectFailure(action: () -> Unit): ConfigPersistenceException {
        try {
            action()
        } catch (e: ConfigPersistenceException) {
            return e
        }
        throw AssertionError("Expected a persistence failure")
    }

    companion object {
        private fun encrypted(plain: String) = "cipher:" + plain.reversed()
    }
}
