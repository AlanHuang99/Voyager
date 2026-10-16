package com.voyagerfiles.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackIdentityStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun resourceIdentitySurvivesAStoreRestartWithoutExposingRemoteDetails() {
        val directory = temp.newFolder("no-backup")
        val identity = PlaybackResourceIdentity(12, "https://private.example/media/movie.mp4", "alice")
        val token = PlaybackIdentityStore(directory).tokenFor(identity)

        assertEquals(token, PlaybackIdentityStore(directory).tokenFor(identity))
        assertEquals(43, token.length)
        assertFalse(token.contains("private"))
        assertFalse(token.contains("alice"))
        assertFalse(token.contains("movie"))
    }

    @Test
    fun differentFilesAccountsConnectionsAndInstallationsGetDifferentIdentities() {
        val directory = temp.newFolder("first-install")
        val store = PlaybackIdentityStore(directory)
        val identity = PlaybackResourceIdentity(12, "https://example.test/movie.mp4", "alice")
        val token = store.tokenFor(identity)

        listOf(
            identity.copy(connectionId = 13),
            identity.copy(url = "https://example.test/other.mp4"),
            identity.copy(url = "http://example.test/movie.mp4"),
            identity.copy(url = "https://example.test:8443/movie.mp4"),
            identity.copy(username = "bob"),
        ).forEach { other -> assertNotEquals(token, store.tokenFor(other)) }
        assertNotEquals(token, PlaybackIdentityStore(temp.newFolder("second-install")).tokenFor(identity))
    }

    @Test
    fun identityFieldsCannotAliasWhenTheyContainSeparators() {
        val store = PlaybackIdentityStore(temp.newFolder("no-backup"))

        assertNotEquals(
            store.tokenFor(PlaybackResourceIdentity(1, "https://example.test/a\nb", "c")),
            store.tokenFor(PlaybackResourceIdentity(1, "https://example.test/a", "b\nc")),
        )
    }
}
