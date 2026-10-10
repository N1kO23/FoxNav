package app.nullpointr.foxnav.shared.convoy

/** AES-256-GCM encryption with a 16-byte tag; [aad] is authenticated but not encrypted. */
internal expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

/** The plaintext, or null when the ciphertext, nonce, key or [aad] don't match. */
internal expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

/** Cryptographically secure random bytes. */
expect fun secureRandomBytes(size: Int): ByteArray
