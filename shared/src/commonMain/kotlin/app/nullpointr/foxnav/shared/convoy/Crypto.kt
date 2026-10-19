package app.nullpointr.foxnav.shared.convoy

/** AES-256-GCM encryption with a 16-byte tag; [aad] is authenticated but not encrypted. */
internal expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

/** The plaintext, or null when the ciphertext, nonce, key or [aad] don't match. */
internal expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

/** Cryptographically secure random bytes. */
expect fun secureRandomBytes(size: Int): ByteArray

internal expect fun sha256(data: ByteArray): ByteArray

/** HKDF with SHA-256 (RFC 5869): [length] bytes from [ikm]. */
internal expect fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

/** The Ed25519 public key for a 32-byte private key (any 32 random bytes). */
internal expect fun ed25519PublicKey(privateKey: ByteArray): ByteArray

/** A 64-byte Ed25519 signature: deterministic, so the same key and message give the same one. */
internal expect fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray

/** Whether [signature] is [publicKey]'s over [message]; false for malformed keys or signatures too. */
expect fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

/** The X25519 public key for a 32-byte private key (any 32 random bytes). */
internal expect fun x25519PublicKey(privateKey: ByteArray): ByteArray

/** The 32-byte X25519 shared secret, or null when [publicKey] gives none (all zeros). */
internal expect fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray?

/** AES key wrap (RFC 3394) of [key] under [kek]: 8 bytes longer than [key]. */
internal expect fun aesKeyWrap(kek: ByteArray, key: ByteArray): ByteArray

/** The key [wrapped] holds, or null when it wasn't wrapped under [kek]. */
internal expect fun aesKeyUnwrap(kek: ByteArray, wrapped: ByteArray): ByteArray?
