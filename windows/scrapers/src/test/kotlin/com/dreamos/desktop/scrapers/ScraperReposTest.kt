package com.dreamos.desktop.scrapers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScraperReposTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }

    private val addonXml = """<addon id="script.module.x" name="X Module" version="1.2.3">
        <requires><import addon="xbmc.python" version="3.0.0"/><import addon="script.module.dep" optional="true"/></requires>
        <extension point="xbmc.python.module" library="lib"/></addon>"""

    @Test fun parsesAddonsAndRepositoryDirs() {
        val xml = """<addons>
            <addon id="repository.x" name="X Repo" version="1.0"><extension point="xbmc.addon.repository">
              <dir><info compressed="false">https://example.org/zips/addons.xml</info><checksum>https://example.org/zips/addons.xml.md5</checksum>
              <datadir zip="true">https://example.org/zips/</datadir></dir></extension></addon>
            $addonXml</addons>"""
        val list = ScraperRepos.parseAddons(xml)
        assertEquals(listOf("repository.x", "script.module.x"), list.map { it.id })
        assertEquals("https://example.org/zips/", list[0].specs.single().datadir)
        assertTrue(list[1].module)
        assertEquals(listOf("xbmc.python" to false, "script.module.dep" to true), list[1].requires)
    }

    @Test fun externalEntitiesAreNotRead() {
        val secret = Files.createTempFile("secret", ".txt").toFile().apply { writeText("TOPSECRET") }
        val xml = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "${secret.toURI()}">]><addons><addon id="a" name="&x;" version="1"/></addons>"""
        val names = ScraperRepos.parseAddons(xml).map { it.name }
        assertTrue(names.none { "TOPSECRET" in it })
    }

    @Test fun unpacksOneAddon() {
        val dir = Files.createTempDirectory("addons").toFile()
        val out = ScraperRepos.unpack(zip("script.module.x/addon.xml" to addonXml, "script.module.x/lib/a.py" to "x=1"), "script.module.x", File(dir, "addons"))
        assertTrue(File(out, "lib/a.py").isFile)
    }

    @Test fun refusesUnsafeZips() {
        val dir = File(Files.createTempDirectory("addons").toFile(), "addons")
        for (bad in listOf("script.module.x/../../evil.py", "script.module.x/C:/evil.py", "script.module.x/lib/CON.py", "script.module.x/nul")) {
            assertFailsWith<ScraperRepos.UserError>(bad) { ScraperRepos.unpack(zip("script.module.x/addon.xml" to addonXml, bad to "x"), null, dir) }
        }
        assertFailsWith<ScraperRepos.UserError> { ScraperRepos.unpack(zip("script.module.x/addon.xml" to addonXml, "other/a.py" to "x"), null, dir) }
        assertFailsWith<ScraperRepos.UserError> { ScraperRepos.unpack(zip("script.module.x/addon.xml" to addonXml), "script.module.y", dir) }
    }

    @Test fun downloadsStopAtTheirCap() {
        assertEquals(10, ScraperRepos.readCapped(ByteArrayInputStream(ByteArray(10)), 10).size)
        var read = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int { read++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertFailsWith<ScraperRepos.UserError> { ScraperRepos.readCapped(endless, 1000) }
        assertTrue(read <= 1001, "read $read bytes past a 1000-byte cap")
    }

    @Test fun normalizesAddresses() {
        assertEquals("https://github.com/a/b", ScraperRepos.normalize("http://github.com/a/b"))
        assertEquals("https://example.org/x", ScraperRepos.normalize("example.org/x"))
        assertFailsWith<ScraperRepos.UserError> { ScraperRepos.normalize("ftp://example.org") }
        assertFailsWith<ScraperRepos.UserError> { ScraperRepos.normalize("not an address") }
    }

    @Test fun comparesVersionsLikeKodi() {
        assertTrue(ScraperRepos.compareVersions("1.0.45", "1.0.9") > 0)
        assertEquals(0, ScraperRepos.compareVersions("1.0", "1.0.0"))
    }

    @Test fun mapsQuality() {
        fun q(label: String, name: String) = ScraperSources.quality(ScraperEngine.Item("a".repeat(40), name, label, 0, 0, "p", false))
        assertEquals("4K", q("2160p", "x"))
        assertEquals("1080p", q("", "Movie.1999.1080p.BluRay"))
        assertEquals("SD", q("CAM", "Movie.2026.1080p.CAM"))
        assertEquals("SD", q("", "Movie.10800p"))
    }
}
