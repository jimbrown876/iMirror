package dev.imirror.receiver.airplay.handshake

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exercises the full pair-setup → pair-verify (M1, M2) exchange by simulating a macOS
 * client in-process, validating signature order, the SHA-512 key/IV derivation, and the
 * continuous AES-CTR keystream across M1/M2 — without needing a real device.
 */
class PairingSessionTest {

    @Test
    fun pairSetupReturnsServerEd25519PublicKey() {
        val server = PairingSession(PairingKeys.create(seed(1)))
        val pub = server.pairSetup(ByteArray(32))
        assertEquals(32, pub.size)
    }

    @Test
    fun pairVerifyRoundTripSucceeds() {
        val serverEdSeed = seed(7)
        val server = PairingSession(PairingKeys.create(serverEdSeed))
        val serverEdPublic = Ed25519PrivateKeyParameters(serverEdSeed, 0).generatePublicKey().encoded

        // --- client identity + ephemeral ECDH key ---
        val clientEd = Ed25519PrivateKeyParameters(seed(9), 0)
        val clientEdPublic = clientEd.generatePublicKey().encoded
        val clientEcdh = X25519PrivateKeyParameters(SecureRandom())
        val clientEcdhPublic = clientEcdh.generatePublicKey().encoded

        // --- M1: client → server ---
        val m1 = byteArrayOf(1, 0, 0, 0) + clientEcdhPublic + clientEdPublic
        val m1Resp = server.pairVerify(m1)
        assertEquals(96, m1Resp.size)
        assertNull("M1 must not release a usable stream secret before signature verification", server.sharedSecret)
        assertFalse(server.isVerified)
        val serverEcdhPublic = m1Resp.copyOfRange(0, 32)
        val encServerSig = m1Resp.copyOfRange(32, 96)

        // client derives the same shared secret + AES-CTR cipher
        val secret = ByteArray(32)
        X25519Agreement().apply { init(clientEcdh) }
            .calculateAgreement(X25519PublicKeyParameters(serverEcdhPublic, 0), secret, 0)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"),
                IvParameterSpec(derive("Pair-Verify-AES-IV", secret))
            )
        }

        // decrypt + verify the server's signature over (serverPub ‖ clientPub)
        val serverSig = cipher.update(encServerSig)
        assertTrue(
            "server signature must verify against its advertised Ed25519 key",
            ed25519Verify(serverEdPublic, serverEcdhPublic + clientEcdhPublic, serverSig)
        )

        // --- M2: client signs (clientPub ‖ serverPub), encrypts at keystream offset 64 ---
        val clientSig = ed25519Sign(clientEd, clientEcdhPublic + serverEcdhPublic)
        val encClientSig = cipher.update(clientSig)
        val m2 = byteArrayOf(0, 0, 0, 0) + encClientSig
        val m2Resp = server.pairVerify(m2)
        assertEquals(0, m2Resp.size)   // empty body = pairing verified
        assertTrue(server.isVerified)
        org.junit.Assert.assertArrayEquals(secret, server.sharedSecret)
    }

    @Test
    fun `a PIN-paired controller can reconnect but a different controller cannot`() {
        val paired = Ed25519PrivateKeyParameters(seed(9), 0)
        val trustedKey = paired.generatePublicKey().encoded
        val trust: (ByteArray) -> Boolean = { MessageDigest.isEqual(it, trustedKey) }

        repeat(2) { // A new socket/session must prove the same long-term identity each time.
            val server = PairingSession(PairingKeys.create(seed(7)), trust)
            val m2 = prepareClientM2(server, paired)
            assertFalse(server.isVerified)
            server.pairVerify(m2)
            assertTrue(server.isVerified)
        }

        val stranger = Ed25519PrivateKeyParameters(seed(20), 0)
        val otherSession = PairingSession(PairingKeys.create(seed(7)), trust)
        assertThrows(SecurityException::class.java) { prepareClientM2(otherSession, stranger) }
        assertNull(otherSession.sharedSecret)
        assertFalse(otherSession.isVerified)
    }

    @Test
    fun `copying a paired public key does not authorize an attacker`() {
        val pairedKey = Ed25519PrivateKeyParameters(seed(9), 0).generatePublicKey().encoded
        val attacker = Ed25519PrivateKeyParameters(seed(20), 0)
        val server = PairingSession(PairingKeys.create(seed(7))) { MessageDigest.isEqual(it, pairedKey) }
        val forgedM2 = prepareClientM2(server, attacker, pairedKey)

        assertNull(server.sharedSecret)
        assertThrows(SecurityException::class.java) { server.pairVerify(forgedM2) }
        assertNull(server.sharedSecret)
        assertFalse(server.isVerified)
    }

    @Test
    fun `new verification and invalid messages revoke previous connection verification`() {
        val client = Ed25519PrivateKeyParameters(seed(9), 0)
        val server = PairingSession(PairingKeys.create(seed(7)))
        server.pairVerify(prepareClientM2(server, client))
        assertTrue(server.isVerified)

        prepareClientM2(server, client)
        assertNull(server.sharedSecret)
        assertFalse(server.isVerified)
        assertThrows(IllegalArgumentException::class.java) { server.pairVerify(byteArrayOf(0)) }
        assertNull(server.sharedSecret)
    }

    @Test
    fun `completed M2 cannot be replayed`() {
        val server = PairingSession(PairingKeys.create(seed(7)))
        val m2 = prepareClientM2(server, Ed25519PrivateKeyParameters(seed(9), 0))
        server.pairVerify(m2)
        assertTrue(server.isVerified)
        assertThrows(IllegalStateException::class.java) { server.pairVerify(m2) }
        assertFalse(server.isVerified)
    }

    @Test
    fun `companion identity requires a verified incumbent and fresh signature`() {
        val owner = Ed25519PrivateKeyParameters(seed(9), 0)
        val ownerKey = owner.generatePublicKey().encoded
        val stranger = Ed25519PrivateKeyParameters(seed(20), 0)
        val incumbent = PairingSession(PairingKeys.create(seed(7)))
        val m2 = prepareClientM2(incumbent, owner)
        assertFalse(incumbent.isVerifiedController(ownerKey))
        incumbent.pairVerify(m2)
        assertTrue(incumbent.isVerifiedController(ownerKey))
        assertFalse(incumbent.isVerifiedController(stranger.generatePublicKey().encoded))

        val companion = PairingSession(PairingKeys.create(seed(7)), incumbent::isVerifiedController)
        val forged = prepareClientM2(companion, stranger, ownerKey)
        assertThrows(SecurityException::class.java) { companion.pairVerify(forged) }
        assertFalse(companion.isVerified)
        companion.pairVerify(prepareClientM2(companion, owner))
        assertTrue(companion.isVerified)
        assertTrue(incumbent.isVerified)

        prepareClientM2(incumbent, owner)
        assertFalse(incumbent.isVerifiedController(ownerKey))
    }

    /** Simulates the sender; advertised key may intentionally differ to test key impersonation. */
    private fun prepareClientM2(
        server: PairingSession,
        signingKey: Ed25519PrivateKeyParameters,
        advertisedKey: ByteArray = signingKey.generatePublicKey().encoded
    ): ByteArray {
        val ecdh = X25519PrivateKeyParameters(SecureRandom())
        val public = ecdh.generatePublicKey().encoded
        val response = server.pairVerify(byteArrayOf(1, 0, 0, 0) + public + advertisedKey)
        val serverPublic = response.copyOfRange(0, 32)
        val secret = ByteArray(32)
        X25519Agreement().apply { init(ecdh) }
            .calculateAgreement(X25519PublicKeyParameters(serverPublic, 0), secret, 0)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"),
                IvParameterSpec(derive("Pair-Verify-AES-IV", secret)))
        }
        cipher.update(response.copyOfRange(32, 96))
        return byteArrayOf(0, 0, 0, 0) + cipher.update(ed25519Sign(signingKey, public + serverPublic))
    }

    private fun seed(b: Int) = ByteArray(32) { b.toByte() }

    private fun derive(salt: String, secret: ByteArray) =
        MessageDigest.getInstance("SHA-512")
            .digest(salt.toByteArray(Charsets.US_ASCII) + secret).copyOf(16)

    private fun ed25519Sign(key: Ed25519PrivateKeyParameters, msg: ByteArray) =
        Ed25519Signer().run { init(true, key); update(msg, 0, msg.size); generateSignature() }

    private fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray) =
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(pub, 0)); update(msg, 0, msg.size); verifySignature(sig)
        }
}
