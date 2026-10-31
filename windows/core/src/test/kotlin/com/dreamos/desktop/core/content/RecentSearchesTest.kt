package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.store.DreamStore
import java.nio.file.Files
import kotlin.test.*

/** Recent searches and My List's view: per profile, the Fire Stick's rules. */
class RecentSearchesTest {
    @BeforeTest
    fun setUp() {
        DreamStore.use(DreamStore(Files.createTempDirectory("dream-test")))
        DreamStore.currentProfile = DreamStore.FIRST_PROFILE
    }

    @AfterTest
    fun tearDown() { DreamStore.currentProfile = DreamStore.FIRST_PROFILE }

    @Test
    fun recentSearchesAreNewestFirstWithoutRepeatsAndEightAtMost() {
        RecentSearches.add("a")
        assertEquals(emptyList(), RecentSearches.list(), "one letter isn't kept")
        RecentSearches.add("horror")
        RecentSearches.add("Brad Pitt")
        RecentSearches.add("HORROR ")
        assertEquals(listOf("HORROR", "Brad Pitt"), RecentSearches.list())
        for (i in 1..10) RecentSearches.add("query $i")
        assertEquals(RecentSearches.MAX, RecentSearches.list().size)
        assertEquals("query 10", RecentSearches.list().first())
        RecentSearches.clear()
        assertEquals(emptyList(), RecentSearches.list())
    }

    @Test
    fun recentSearchesAndMyListViewArePerProfile() {
        RecentSearches.add("comedy")
        MyListView.save("tv", "az")
        DreamStore.currentProfile = "kid"
        assertEquals(emptyList(), RecentSearches.list())
        assertEquals("all" to "recent", MyListView.load())
        DreamStore.currentProfile = DreamStore.FIRST_PROFILE
        assertEquals(listOf("comedy"), RecentSearches.list())
        assertEquals("tv" to "az", MyListView.load())
    }

    @Test
    fun aToZFilesTitlesWithoutTheirArticle() {
        val names = listOf("The Office", "A Quiet Place", "Barry", "An Officer and a Gentleman")
        assertEquals(listOf("Barry", "The Office", "An Officer and a Gentleman", "A Quiet Place"),
            names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { MyListView.sortName(it) }))
    }
}
