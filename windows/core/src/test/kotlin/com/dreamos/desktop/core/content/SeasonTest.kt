package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

/** The seasonal hubs without the network: the calendar, which hub is on, countdowns, tiles and page rows. */
class SeasonTest {
    private val ny = ZoneId.of("America/New_York")

    @BeforeTest
    fun setUp() {
        DreamStore.use(DreamStore(Files.createTempDirectory("dream-test")))
        DreamTime.setZone(ny.id)
        DreamTime.fixedNow = ZonedDateTime.of(2026, 10, 30, 14, 0, 0, 0, ny).toInstant().toEpochMilli()
    }

    @AfterTest
    fun tearDown() {
        DreamTime.fixedNow = null
    }

    private fun day(s: String) = SDay.parse(s)!!
    private fun top(s: String, kids: Boolean = false) = SeasonCalendar.top(day(s), kids)

    @Test
    fun calendarSelfCheck() {
        val (bad, summary) = SeasonCalendar.selfCheck(2026..2027)
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        assertTrue(summary.startsWith("20 hubs"), summary)
        assertEquals(20, SeasonCatalog.ALL.size)
        assertEquals(day("2026-04-05"), SDay.easter(2026))
        assertEquals(day("2026-11-26"), SDay.thanksgiving(2026))
        assertEquals(1, day("2026-10-26").dow)
        assertNull(SDay.parse("2026-02-30"))
    }

    @Test
    fun whichHubIsOn() {
        assertEquals("halloween", top("2026-10-26").id)
        assertEquals("halloween", top("2026-10-31").id)
        assertEquals("fall", top("2026-11-02").id)
        assertEquals("thanksgiving", top("2026-11-26").id)
        assertEquals("christmas", top("2026-12-24").id)
        assertEquals("newyear", top("2026-12-31").id)
        assertEquals("winter", top("2027-01-05").id)
        // Black History Month leads February until Valentine's week takes over.
        assertEquals("blackhistory", top("2026-02-03").id)
        assertEquals("valentines", top("2026-02-12").id)
        // Awards Season's last days beat St. Patrick's.
        assertEquals("awards", top("2026-03-13").id)
        assertEquals("stpatricks", top("2026-03-17").id)
        assertEquals("easter", top("2026-04-05").id)
        assertEquals("july4", top("2026-07-04").id)
        assertEquals("pride", top("2026-06-10").id)
        // A kids profile sees only hubs with rows for kids.
        for (d in listOf("2026-02-03", "2026-06-10", "2026-10-26", "2026-12-24")) {
            assertTrue(SeasonCalendar.active(day(d), kids = true).all { it.forKids() })
        }
    }

    @Test
    fun countdownWording() {
        val halloween = SeasonCalendar.find("halloween")!!
        assertEquals("5 Nights Until Halloween", halloween.status(day("2026-10-26")))
        assertEquals("Halloween Is Tomorrow Night", halloween.status(day("2026-10-30")))
        assertEquals("Happy Halloween", halloween.status(day("2026-10-31")))
        assertEquals("Night 26 of 31", halloween.night(day("2026-10-26")))
        assertNull(halloween.night(day("2026-11-01")))
        val christmas = SeasonCalendar.find("christmas")!!
        assertEquals("Tonight Is Christmas Eve", christmas.status(day("2026-12-24")))
        assertEquals("Merry Christmas", christmas.status(day("2026-12-25")))
        assertEquals("Happy Holidays", christmas.status(day("2026-12-26")))
        assertEquals("Tonight Is New Year's Eve", SeasonCalendar.find("newyear")!!.status(day("2026-12-31")))
        assertEquals("2 Days Until Awards Night", SeasonCalendar.find("awards")!!.status(day("2026-03-13")))
        assertEquals("Happy Fourth of July", SeasonCalendar.find("july4")!!.status(day("2026-07-04")))
        // No countdown: the tagline.
        val fall = SeasonCalendar.find("fall")!!
        assertEquals(fall.tagline, fall.status(day("2026-11-02")))
        val all = SeasonCatalog.ALL.flatMap { h -> (0..400).map { h.status(day("2026-01-01") + it) } }
        assertTrue(all.none { '—' in it })
    }

    @Test
    fun placeholdersFill() {
        val newYear = SeasonCalendar.find("newyear")!!
        assertEquals("Best of 2026", SeasonCalendar.fill("Best of {LASTYEAR}", newYear, day("2026-12-31")))
        assertEquals("Best of 2026", SeasonCalendar.fill("Best of {LASTYEAR}", newYear, day("2027-01-02")))
        val spring = SeasonCalendar.find("spring")!!
        assertEquals("&primary_release_date.gte=2024-03-31", SeasonCalendar.fill("&primary_release_date.gte={YEARSAGO:2}", spring, day("2026-03-31")))
    }

    @Test
    fun todayFollowsTheViewersZone() {
        // 2026-10-31 02:00 UTC is still the 30th in New York, already the 31st in London.
        DreamTime.fixedNow = Instant.parse("2026-10-31T02:00:00Z").toEpochMilli()
        assertEquals(day("2026-10-30"), Seasons.today())
        DreamTime.setZone("Europe/London")
        assertEquals(day("2026-10-31"), Seasons.today())
        assertEquals("Happy Halloween", Seasons.tile(Seasons.active()).status)
    }

    @Test
    fun tilesAndOverride() {
        val tile = Seasons.tile(SeasonCalendar.find("halloween")!!, day("2026-10-26"))
        assertEquals("31 Nights of Horror", tile.title)
        assertEquals("Halloween", tile.kicker)
        assertEquals("5 Nights Until Halloween", tile.status)
        assertEquals("Night 26 of 31", tile.night)
        assertEquals("Night 26 of 31", tile.sub)
        assertTrue(tile.hasCountdown)
        assertEquals("Seasonal Collection", Seasons.tile(SeasonCalendar.find("fall")!!, day("2026-11-02")).sub)
        // Today (Oct 30): Halloween leads, Fall is also on.
        assertEquals(listOf("halloween", "fall"), Seasons.tiles().map { it.id })
        assertEquals("halloween", Seasons.forGenre("Horror")?.id)
        assertNull(Seasons.forGenre("Drama"))
        // The developer override: a pretend day, then a forced hub, then back.
        assertTrue(Seasons.override(date = "2026-12-24"))
        assertEquals("christmas", Seasons.active().id)
        assertTrue(Seasons.override(force = "pride"))
        assertEquals("pride", Seasons.active().id)
        assertFalse(Seasons.override(force = "nope"))
        assertTrue(Seasons.override(reset = true))
        assertEquals("halloween", Seasons.active().id)
        assertEquals("halloween", Seasons.hub("HALLOWEEN")?.id)
    }

    @Test
    fun hubRows() {
        val claims = RowClaims()
        val home = Seasons.homeSeasonRow(claims)
        assertEquals("31 Nights of Horror", home.name)
        assertEquals("halloween", home.hubs.single().id)
        val page = Seasons.seasonPage("halloween", RowClaims())
        assertTrue(page.first().spotlight)
        assertEquals("Tonight's Pick  ·  Night 30 of 31", page.first().kicker?.invoke(com.dreamos.desktop.core.model.Title(1, com.dreamos.desktop.core.model.MediaKind.MOVIE, "x")))
        assertEquals("More to Celebrate", page.last().name)
        assertEquals(listOf("fall"), page.last().hubs.map { it.id })
        val rows = SeasonCalendar.find("halloween")!!.rows
        assertEquals(rows.size + 2, page.size)
        assertEquals(page.map { it.id }.size, page.map { it.id }.toSet().size)
        assertTrue(Seasons.seasonPage("nope", RowClaims()).isEmpty())
        // A genre page links the matching hub after its first row.
        val horror = BrowsePages.genrePage("Horror", RowClaims())
        assertEquals("31 Nights of Horror", horror[1].name)
        // Tonight's Pick is the same all day and moves on.
        val i = Seasons.pickIndex(day("2026-10-30"), "halloween", 37)
        assertEquals(i, Seasons.pickIndex(day("2026-10-30"), "halloween", 37))
        assertTrue(i in 0 until 37)
    }

    @Test
    fun kidsProfileSeesKidsRowsOnly() {
        DreamStore.shared.file("profiles").putBoolean("kids_${DreamStore.currentProfile}", true)
        assertTrue(Seasons.kids())
        val hub = Seasons.active()
        assertTrue(hub.forKids())
        val page = Seasons.seasonPage(hub.id, RowClaims())
        assertEquals(hub.rowsFor(kids = true).size + 1 + (if (page.last().name == "More to Celebrate") 1 else 0), page.size)
    }
}
