package dev.imirror.receiver.airplay

import dev.imirror.receiver.airplay.handshake.RaopRsa
import dev.imirror.receiver.util.NetworkUtils
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import javax.crypto.Cipher

class RaopChallengeTest {
    private val challenge = ByteArray(16) { it.toByte() }
    private val mac = byteArrayOf(2, 17, 34, 51, 68, 85)

    @Before fun setup() {
        mockkObject(NetworkUtils)
        every { NetworkUtils.getMacAddress(any()) } returns "02:11:22:33:44:55"
    }
    @After fun cleanup() = unmockkObject(NetworkUtils)

    @Test fun `IPv4 OPTIONS signs challenge plus local endpoint and advertised identity padded to 32 bytes`() {
        checkResponse("192.0.2.42", 32, padded = false)
    }

    @Test fun `IPv6 OPTIONS retains all 16 address bytes in the 38 byte response message`() {
        checkResponse("2001:db8::42", 38, padded = true)
    }

    @Test fun `malformed and oversized challenges fail without signing arbitrary data`() {
        for (value in listOf("", "AA==", "!".repeat(22), "A".repeat(100))) {
            val response = handler("192.0.2.42").routeRequest(RtspRequest("OPTIONS", "*",
                mapOf("Apple-Challenge" to value), ""))
            assertEquals(400, response.statusCode)
            assertNull(response.headers["Apple-Response"])
        }
    }

    @Test fun `ordinary OPTIONS remains available without an authentication challenge`() {
        val response = handler("192.0.2.42").routeRequest(RtspRequest("OPTIONS", "*", emptyMap(), ""))
        assertEquals(200, response.statusCode)
        assertTrue(response.headers["Public"]!!.contains("RECORD"))
        assertNull(response.headers["Apple-Response"])
    }

    private fun checkResponse(localIp: String, expectedSize: Int, padded: Boolean) {
        val encoder = if (padded) Base64.getEncoder() else Base64.getEncoder().withoutPadding()
        val response = handler(localIp).routeRequest(RtspRequest("OPTIONS", "*",
            mapOf("apple-challenge" to encoder.encodeToString(challenge)), ""))
        assertEquals(200, response.statusCode)
        val signed = response.headers["Apple-Response"]
        assertNotNull("Legacy senders require Apple-Response", signed)
        assertFalse(signed!!.contains('='))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, RaopRsa.publicKeyForTest())
        val message = cipher.doFinal(Base64.getDecoder().decode(signed))
        val expected = (challenge + InetAddress.getByName(localIp).address + mac).copyOf(expectedSize)
        assertArrayEquals(expected, message)
    }

    private fun handler(localIp: String): RtspHandler {
        val handler = RtspHandler(mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {})
        val socket = mockk<Socket>()
        every { socket.localAddress } returns InetAddress.getByName(localIp)
        RtspHandler::class.java.getDeclaredField("activeClient").apply { isAccessible = true }
            .set(handler, socket)
        return handler
    }
}
