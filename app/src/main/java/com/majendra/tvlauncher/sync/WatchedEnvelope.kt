package com.majendra.tvlauncher.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest format of the watched store.
 *
 * Version 1 sealed the whole state with the AndroidKeyStore key, so every byte crossed into the keystore
 * service on each read and write. Version 2 keeps that key as the only way in, but uses it once to wrap a
 * random AES-256 data key; the state itself is sealed in-process with AES-GCM under that data key. The file
 * is exactly as useless without the keystore key as before. Version 1 files remain readable.
 *
 * Layout v2: int 2 · int 12 · wrap IV · int n · wrapped data key · int 12 · IV · ciphertext. Everything
 * before the second IV is authenticated as associated data.
 */
internal class WatchedEnvelope(private val existingKey: () -> SecretKey?, private val createKey: () -> SecretKey) {
    class Opened(val plain: ByteArray, val legacy: Boolean)
    private var dataKey: SecretKey? = null
    private var header: ByteArray? = null // Reused on every write: no keystore call after the first.
    private val random = SecureRandom()

    @Synchronized fun open(bytes: ByteArray): Opened {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return when (input.readInt()) {
            1 -> {
                require(input.readInt() == IV)
                val iv = ByteArray(IV).also(input::readFully)
                val key = existingKey() ?: error("Unavailable encryption key")
                Opened(gcm(Cipher.DECRYPT_MODE, key, iv).doFinal(input.readBytes()), true)
            }
            2 -> {
                require(input.readInt() == IV)
                val wrapIv = ByteArray(IV).also(input::readFully)
                val wrapped = ByteArray(input.readInt().also { require(it in KEY..256) }).also(input::readFully)
                val head = bytes.copyOf(bytes.size - input.available())
                require(input.readInt() == IV)
                val iv = ByteArray(IV).also(input::readFully)
                val master = existingKey() ?: error("Unavailable encryption key")
                val raw = gcm(Cipher.DECRYPT_MODE, master, wrapIv).doFinal(wrapped)
                require(raw.size == KEY)
                val key = SecretKeySpec(raw, "AES")
                val plain = gcm(Cipher.DECRYPT_MODE, key, iv).apply { updateAAD(head) }.doFinal(input.readBytes())
                dataKey = key; header = head
                Opened(plain, false)
            }
            else -> error("Unsupported watched store format")
        }
    }

    @Synchronized fun seal(plain: ByteArray): ByteArray {
        val head = header ?: newHeader()
        val key = dataKey ?: error("Unavailable data key")
        val iv = ByteArray(IV).also(random::nextBytes)
        val body = gcm(Cipher.ENCRYPT_MODE, key, iv).apply { updateAAD(head) }.doFinal(plain)
        // Saved watched choices are about to depend on these bytes: prove they open before they replace the file.
        check(gcm(Cipher.DECRYPT_MODE, key, iv).apply { updateAAD(head) }.doFinal(body).contentEquals(plain))
        return ByteArrayOutputStream(head.size + 4 + IV + body.size).also { bytes ->
            DataOutputStream(bytes).use { out -> out.write(head); out.writeInt(IV); out.write(iv); out.write(body) }
        }.toByteArray()
    }

    private fun newHeader(): ByteArray {
        val master = existingKey() ?: createKey()
        val raw = ByteArray(KEY).also(random::nextBytes)
        // The keystore key requires randomised encryption, so it chooses this IV itself.
        val wrap = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, master) }
        val wrapped = wrap.doFinal(raw)
        val wrapIv = wrap.iv
        check(wrapIv.size == IV)
        // Never rely on a data key the keystore cannot hand back.
        check(gcm(Cipher.DECRYPT_MODE, master, wrapIv).doFinal(wrapped).contentEquals(raw))
        val head = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out -> out.writeInt(2); out.writeInt(IV); out.write(wrapIv); out.writeInt(wrapped.size); out.write(wrapped) }
        }.toByteArray()
        dataKey = SecretKeySpec(raw, "AES"); header = head
        return head
    }

    private fun gcm(mode: Int, key: SecretKey, iv: ByteArray) =
        Cipher.getInstance(TRANSFORMATION).apply { init(mode, key, GCMParameterSpec(128, iv)) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
        const val KEY = 32
    }
}

internal object WatchedPersistPolicy {
    /** Sync stamps move on every successful poll. Alone they wait for the next real change, or this long. */
    const val TIMESTAMP_FLUSH_MILLIS = 60 * 60_000L

    fun timestampsOnly(previous: WatchedState, next: WatchedState): Boolean = previous != next &&
        previous.copy(lastSuccessMillis = next.lastSuccessMillis,
            watching = previous.watching.copy(updatedAt = next.watching.updatedAt)) == next

    fun flushTimestamps(lastWriteAt: Long, now: Long): Boolean = now - lastWriteAt !in 0 until TIMESTAMP_FLUSH_MILLIS
}
