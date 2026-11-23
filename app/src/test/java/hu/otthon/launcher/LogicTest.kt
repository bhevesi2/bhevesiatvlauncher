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
}
