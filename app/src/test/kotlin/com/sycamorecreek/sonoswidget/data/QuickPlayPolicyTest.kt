package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.widget.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPlayPolicyTest {

    private val morning = Favorite("FV:2/1", "Morning Mix")
    private val likes = Favorite("FV:2/2", "Your Likes")
    private val newReleases = Favorite("FV:2/3", "New Releases")
    private val favorites = listOf(morning, likes, newReleases)

    @Test fun `no picks offers the first favorites in Sonos order`() {
        assertEquals(listOf(morning, likes), QuickPlayPolicy.resolve(favorites, listOf(null, null)))
    }

    @Test fun `chosen favorites keep their slots`() {
        val picks = listOf(QuickPlayPolicy.Pick(likes.id, likes.title), QuickPlayPolicy.Pick(newReleases.id, newReleases.title))

        assertEquals(listOf(likes, newReleases), QuickPlayPolicy.resolve(favorites, picks))
    }

    @Test fun `an unchosen slot skips a favorite another slot already shows`() {
        val picks = listOf(null, QuickPlayPolicy.Pick(morning.id, morning.title))

        assertEquals(listOf(likes, morning), QuickPlayPolicy.resolve(favorites, picks))
    }

    @Test fun `a renumbered favorite is still found by its title`() {
        val renumbered = listOf(newReleases.copy(id = "FV:2/9"), morning)
        val picks = listOf(QuickPlayPolicy.Pick(newReleases.id, newReleases.title), null)

        assertEquals(listOf(renumbered[0], morning), QuickPlayPolicy.resolve(renumbered, picks))
    }

    @Test fun `a stale id never plays whatever now sits at that position`() {
        // "New Releases" was removed; its old id now belongs to something else.
        val reshuffled = listOf(morning, Favorite(newReleases.id, "Jazz Radio"))
        val pick = QuickPlayPolicy.Pick(newReleases.id, newReleases.title)

        assertNull(QuickPlayPolicy.match(reshuffled, pick))
        assertEquals(listOf(morning), QuickPlayPolicy.resolve(reshuffled, listOf(pick, null)))
    }

    @Test fun `a removed pick hides its button rather than substituting one`() {
        val picks = listOf(QuickPlayPolicy.Pick("FV:2/99", "Gone"), null)

        assertEquals(listOf(morning), QuickPlayPolicy.resolve(favorites, picks))
    }

    @Test fun `no favorites means no buttons`() {
        assertTrue(QuickPlayPolicy.resolve(emptyList(), listOf(QuickPlayPolicy.Pick("a", "b"), null)).isEmpty())
    }

    @Test fun `both slots choosing the same favorite show it once`() {
        val pick = QuickPlayPolicy.Pick(likes.id, likes.title)

        assertEquals(listOf(likes), QuickPlayPolicy.resolve(favorites, listOf(pick, pick)))
    }
}
