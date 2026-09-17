package dev.imirror.receiver.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AlwaysReadyBootMigrationTest {
    private val boot = booleanPreferencesKey("start_on_boot")
    private val name = stringPreferencesKey("display_name")
    private val pin = booleanPreferencesKey("airplay_pin_auth")

    @Test
    fun `existing receiver is enabled once without losing room or PIN settings`() = runTest {
        val old = mutablePreferencesOf(boot to false, name to "Bedroom", pin to true)
        assertTrue(AlwaysReadyBootMigration.shouldMigrate(old))
        val migrated = AlwaysReadyBootMigration.migrate(old)
        assertEquals(true, migrated[boot])
        assertEquals("Bedroom", migrated[name])
        assertEquals(true, migrated[pin])
        assertEquals(false, old[boot])
        assertFalse(AlwaysReadyBootMigration.shouldMigrate(migrated))
    }

    @Test
    fun `user can turn off boot restoration permanently after the one-time upgrade`() = runTest {
        val migrated = AlwaysReadyBootMigration.migrate(emptyPreferences()).toMutablePreferences()
        migrated[boot] = false
        assertFalse(AlwaysReadyBootMigration.shouldMigrate(migrated))
        assertEquals(false, AlwaysReadyBootMigration.migrate(migrated)[boot])
    }

    @Test
    fun `reset followed by explicit opt-out cannot accidentally reapply upgrade next launch`() = runTest {
        val preferences = mutablePreferencesOf(boot to true)
        preferences.clear()
        AlwaysReadyBootMigration.markApplied(preferences)
        preferences[boot] = false
        assertFalse(AlwaysReadyBootMigration.shouldMigrate(preferences))
        assertEquals(false, AlwaysReadyBootMigration.migrate(preferences)[boot])
    }

    @Test
    fun `fresh install starts enabled and migration is idempotent`() = runTest {
        val first = AlwaysReadyBootMigration.migrate(emptyPreferences())
        assertEquals(true, first[boot])
        assertEquals(first, AlwaysReadyBootMigration.migrate(first))
    }
}
