package app.nullpointr.foxnav.shared.convoy

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES-GCM and SHA-256 come from the platform. Ed25519, X25519, HKDF and AES-KW come from
// BouncyCastle's own classes rather than through JCA, because Android only has some of them,
// and only on newer versions.

private const val TAG_BITS = 128
private const val CURVE_KEY_SIZE = 32
private val random = SecureRandom()

internal actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
    cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

internal actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
    try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
    ByteArray(length).also { out ->
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }.generateBytes(out, 0, length)
    }

internal actual fun ed25519PublicKey(privateKey: ByteArray): ByteArray =
    Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

internal actual fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray =
    Ed25519Signer().apply {
        init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        update(message, 0, message.size)
    }.generateSignature()

actual fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != CURVE_KEY_SIZE || signature.size != Ed25519PrivateKeyParameters.SIGNATURE_SIZE) return false
    return try {
        Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
        }.verifySignature(signature)
    } catch (_: IllegalArgumentException) {
        false
    }
}

internal actual fun x25519PublicKey(privateKey: ByteArray): ByteArray =
    X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

internal actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
    if (publicKey.size != CURVE_KEY_SIZE) return null
    val secret = ByteArray(CURVE_KEY_SIZE)
    return try {
        X25519Agreement().apply { init(X25519PrivateKeyParameters(privateKey, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(publicKey, 0), secret, 0)
        secret
    } catch (_: IllegalStateException) {
        null // an all-zero secret: a public key of low order
    }
}

internal actual fun aesKeyWrap(kek: ByteArray, key: ByteArray): ByteArray =
    AESWrapEngine().apply { init(true, KeyParameter(kek)) }.wrap(key, 0, key.size)

internal actual fun aesKeyUnwrap(kek: ByteArray, wrapped: ByteArray): ByteArray? =
    try {
        AESWrapEngine().apply { init(false, KeyParameter(kek)) }.unwrap(wrapped, 0, wrapped.size)
    } catch (_: InvalidCipherTextException) {
        null
    }

private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad)
    }
