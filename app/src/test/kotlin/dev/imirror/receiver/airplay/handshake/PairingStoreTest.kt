package dev.imirror.receiver.airplay.handshake

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStoreTest {
    @Test
    fun `only enrolled keys remain trusted across store recreation and removal revokes trust`() {
        val context = memoryContext()
        val controllerA = ByteArray(32) { it.toByte() }
        val controllerB = ByteArray(32) { (it + 1).toByte() }
        PairingStore(context).add("Mac", controllerA)

        val restored = PairingStore(context)
        assertTrue(restored.containsPublicKey(controllerA))
        assertFalse("one enrollment must not trust another sender", restored.containsPublicKey(controllerB))
        restored.remove("Mac")
        assertFalse(restored.containsPublicKey(controllerA))
    }

    @Test
    fun `malformed saved entries do not authorize or crash verification`() {
        val context = memoryContext(mutableMapOf("ltpk_broken" to "zz", "ltpk_odd" to "f"))
        val store = PairingStore(context)
        assertFalse(store.containsPublicKey(ByteArray(32)))
        assertFalse(store.containsPublicKey(ByteArray(0)))
        assertThrows(IllegalArgumentException::class.java) { store.add("Mac", ByteArray(31)) }
    }

    private fun memoryContext(values: MutableMap<String, Any> = mutableMapOf()): Context {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.all } answers { values.toMap() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.apply() } just Runs
        return context
    }
}
