package hu.otthon.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts that decide things without a screen: run by the build before every release. */
class LogicTest {

    @Test
    fun noteFromTheHelper() {
        val n = Inbox.note("1794651277|garage|warn|Garage gate open|Open for 15 minutes")
        assertNotNull(n)
        assertEquals("note:1794651277", n!!.id)
        assertEquals(1794651277000L, n.at)
        assertEquals("garage", n.icon)
        assertEquals("warn", n.level)
        assertEquals("Garage gate open", n.title)
        assertEquals("Open for 15 minutes", n.text)
    }

    @Test
    fun noteKeepsBarsInItsText() {
        assertEquals("a | b", Inbox.note("5|bell|info|T|a | b")!!.text)
    }

    @Test
    fun notANote() {
        assertNull(Inbox.note(""))
        assertNull(Inbox.note("unknown"))
        assertNull(Inbox.note("x|bell|info|T|text"))
        assertNull(Inbox.note("5|bell|info| | "))
    }

    @Test
    fun alertIdIsTheSameForTheSameChange() {
        val t = PopupTrigger("binary_sensor.entrance_camera_person_occupancy", "camera.entrance_camera")
        assertEquals(Inbox.alert(t, 1_000_500L).id, Inbox.alert(t, 1_000_900L).id)
        assertEquals("camera.entrance_camera", Inbox.alert(t, 1L).camera)
    }

    @Test
    fun triggerTitleAndCamera() {
        val t = PopupTrigger("binary_sensor.entrance_camera_person_occupancy", "camera.entrance_camera")
        assertEquals("Entrance · person", t.title)
        assertEquals("camera.driveway_camera", PopupTrigger.cameraFor("binary_sensor.driveway_camera_person_occupancy"))
    }

    @Test
    fun hungarianTexts() {
        Lang.set("hu")
        try {
            assertEquals("Értesítések", Lang.tr("Notifications"))
            assertEquals("Értesítések · 3", Lang.tr("Notifications · 3"))
            assertEquals("Tegnap 21:10", Lang.tr("Yesterday 21:10"))
            assertEquals("Bejárat · Személy".lowercase(), Lang.tr("Bejárat · person").lowercase())
        } finally {
            Lang.set("en")
        }
        assertEquals("Notifications", Lang.tr("Notifications"))
    }

    @Test
    fun memoryTiers() {
        // the TCL reports 2.4 GB
        assertEquals(LowRam.HIGH, LowRam.auto(2.34, false, 192))
        // near a boundary the maker's per-app limit decides
        assertEquals(LowRam.HIGH, LowRam.auto(2.15, false, 192))
        assertEquals(LowRam.MEDIUM, LowRam.auto(2.15, false, 128))
        // a 2 GB TV like the Sony
        assertEquals(LowRam.MEDIUM, LowRam.auto(1.8, false, 192))
        assertEquals(LowRam.MEDIUM, LowRam.auto(1.45, false, 256))
        assertEquals(LowRam.LOW, LowRam.auto(1.45, false, 128))
        assertEquals(LowRam.LOW, LowRam.auto(1.2, false, 256))
        // Android's own low-RAM mark wins
        assertEquals(LowRam.LOW, LowRam.auto(3.0, true, 512))
    }

    @Test
    fun recoPictureSizes() {
        // kept in steps of 128 pixels, never smaller than shown
        assertEquals(128, RecoDisk.bucket(0))
        assertEquals(128, RecoDisk.bucket(100))
        assertEquals(128, RecoDisk.bucket(128))
        assertEquals(256, RecoDisk.bucket(129))
        assertEquals(512, RecoDisk.bucket(493))
        // one file per address, the same each time
        assertEquals(RecoDisk.hash("https://x/a.jpg"), RecoDisk.hash("https://x/a.jpg"))
        assertEquals(40, RecoDisk.hash("https://x/a.jpg").length)
    }

    @Test
    fun autoOffCommands() {
        val c = AutoOff.parse("1795725209|sensor.living_room_tv_launcher|auto_off|60")!!
        assertEquals(1795725209L, c.id)
        assertEquals("sensor.living_room_tv_launcher", c.target)
        assertEquals("auto_off", c.command)
        assertEquals(60, c.seconds)
        assertEquals("cancel", AutoOff.parse("1795725300|sensor.x_launcher|cancel")!!.command)
        assertEquals(60, AutoOff.parse("1|sensor.x|auto_off")!!.seconds)
        assertEquals(10, AutoOff.parse("1|sensor.x|auto_off|2")!!.seconds)
        assertNull(AutoOff.parse(""))
        assertNull(AutoOff.parse("unknown"))
        assertNull(AutoOff.parse("x|sensor.x|auto_off"))
    }

    @Test
    fun folders() {
        var list = listOf(Folders.Folder("a", "Sport", listOf("p1")), Folders.Folder("b", "Kids", emptyList()))
        // an app is in one folder at most
        list = Folders.withApp(list, "b", "p1")
        assertEquals(emptyList<String>(), list[0].apps)
        assertEquals(listOf("p1"), list[1].apps)
        assertEquals("b", Folders.folderOf(list, "p1")!!.id)
        list = Folders.without(list, "p1")
        assertNull(Folders.folderOf(list, "p1"))
        // stored and read back
        val back = Folders.parse(Folders.toJson(Folders.withApp(list, "a", "p2")))
        assertEquals(listOf("p2"), back[0].apps)
        assertEquals("Kids", back[1].name)
        assertEquals(emptyList<Folders.Folder>(), Folders.parse("not json"))
        // names and ids that are not taken
        assertEquals("Folder 1", Folders.nextName(emptyList(), "Folder"))
        assertEquals("Folder 2", Folders.nextName(listOf(Folders.Folder("x", "Folder 1", emptyList())), "Folder"))
        assertEquals("folder:a", Folders.token("a"))
        assertEquals("a", Folders.idOf("folder:a"))
    }

    @Test
    fun mediaNames() {
        val f = MediaLib.guess("Filmek\\Dune.Part.Two.2024.2160p.UHD.BluRay.DV.HDR.x265-GRP.mkv")
        assertEquals(false, f.series)
        assertEquals("Dune Part Two", f.title)
        assertEquals(2024, f.year)
        assertEquals(true, f.uhd)
        assertEquals(true, f.dv)
        val e = MediaLib.guess("Sorozatok\\The Bear\\Season 2\\The.Bear.S02E05.1080p.WEB-DL.mkv")
        assertEquals(true, e.series)
        assertEquals("The Bear", e.title)
        assertEquals(2, e.season)
        assertEquals(5, e.episode)
        // only the episode in the name: the series' folder gives the title
        val f2 = MediaLib.guess("Sorozatok\\Shogun\\S01E03.mkv")
        assertEquals("Shogun", f2.title)
        assertEquals(3, f2.episode)
        val m = MediaLib.guess("Filmek\\A Tanú (1969).avi")
        assertEquals("A Tanú", m.title)
        assertEquals(1969, m.year)
        // a year in the title is not the release year
        val y = MediaLib.guess("2001 A Space Odyssey 1968 1080p.mkv")
        assertEquals("2001 A Space Odyssey", y.title)
        assertEquals(1968, y.year)
    }

    @Test
    fun networkPaths() {
        assertEquals(Triple("192.168.2.2", "Filmek", ""), MediaLib.parsePath("\\\\192.168.2.2\\Filmek"))
        assertEquals(Triple("NAS", "Media", "Sorozatok/4K"), MediaLib.parsePath("\\\\NAS\\Media\\Sorozatok\\4K\\"))
        assertEquals(Triple("NAS", "Media", "Filmek"), MediaLib.parsePath("smb://NAS/Media/Filmek"))
        assertEquals(Triple("NAS", "Media", ""), MediaLib.parsePath("NAS/Media"))
        assertNull(MediaLib.parsePath("\\\\NAS"))
    }

    @Test
    fun updateNumbers() {
        val r = Updates.Release("Build 136", 1000L, "x.apk")
        // published only 11 minutes after the installed build: still newer by its number
        assertEquals(true, Updates.isNewer("0.1.135", 989L, r))
        assertEquals(false, Updates.isNewer("0.1.136", 989L, r))
        assertEquals(false, Updates.isNewer("0.1.137", 989L, r))
        // no number to read: by the time
        assertEquals(true, Updates.isNewer(null, 900L, r))
        assertEquals(136, Updates.buildNumber("Build 136"))
    }

    @Test
    fun frameRates() {
        val tcl = listOf(60f, 59.94f, 50f, 30f, 24f, 23.976f, 120f)
        assertEquals(23.976f, FrameRate.pick(23.976f, tcl))
        assertEquals(50f, FrameRate.pick(25f, tcl))
        assertEquals(59.94f, FrameRate.pick(29.97f, tcl))
        // only 24 Hz for a 23.976 film: close enough (one frame in 42 s)
        assertEquals(24f, FrameRate.pick(23.976f, listOf(60f, 50f, 24f)))
        // a 60 Hz-only screen keeps what it has
        assertEquals(null, FrameRate.pick(23.976f, listOf(60f)))
        assertEquals(60f, FrameRate.pick(60f, listOf(60f, 50f)))
    }

    @Test
    fun musicNames() {
        assertEquals("Abbey Road", MusicInfo.bare("Abbey Road (Remastered 2009)"))
        assertEquals("Help!", MusicInfo.bare("Help! - 2009 Remaster"))
        assertEquals("Parachutes", MusicInfo.bare("Parachutes [Deluxe Edition]"))
        assertEquals("beyonce and jay z", MusicInfo.norm("Beyoncé & Jay-Z"))
        assertEquals("ossian", MusicInfo.norm("Ossián"))
    }
}
