package hu.otthon.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts that read Home Assistant's answers and the settings file. */
class JsonTest {

    @Test
    fun secretsAreFoundAndStripped() {
        val j = JSONObject("""{"ha_url":"http://x","ha_token":"abc","nas":{"host":"h","password":"p"}}""")
        assertTrue(Secrets.hasAny(j))
        Secrets.strip(j)
        assertFalse(Secrets.hasAny(j))
        assertFalse(j.has("ha_token"))
        assertFalse(j.getJSONObject("nas").has("password"))
        assertEquals("http://x", j.getString("ha_url"))
    }

    @Test
    fun noSecretsInPlainSettings() {
        assertFalse(Secrets.hasAny(JSONObject("""{"ha_url":"http://x","nas":{"host":"h"}}""")))
    }

    @Test
    fun liveUpdatesAreRead() {
        val seen = ArrayList<String>()
        val w = HaWatch("http://x", "t", listOf("binary_sensor.a", "input_text.n")) { e, s, at, first -> seen.add("$e=$s@$at/$first") }
        // the states on subscribing
        w.handle(JSONObject("""{"a":{"binary_sensor.a":{"s":"off","a":{},"c":"x","lc":1700000000.5}}}"""))
        // a change of state, and one of the attributes only (not a new state)
        w.handle(JSONObject("""{"c":{"binary_sensor.a":{"+":{"s":"on","c":"y","lc":1700000010.25}}}}"""))
        w.handle(JSONObject("""{"c":{"binary_sensor.a":{"+":{"a":{"x":1},"lu":1700000011.0}}}}"""))
        assertEquals(listOf("binary_sensor.a=off@1700000000500/true", "binary_sensor.a=on@1700000010250/false"), seen)
    }
}

/** Reading the settings, GitHub's releases and the carried settings. */
class SettingsAndUpdatesTest {

    @Test
    fun settingsAreRead() {
        val c = Config.parse(JSONObject("""{"ha_url":" http://192.168.2.2:8123/ ","ha_token":"t","state_entity":"sensor.x_launcher","report_state":false}"""))
        assertEquals("http://192.168.2.2:8123", c.haUrl)
        assertTrue(c.haEnabled)
        assertFalse(c.reportState)
        assertEquals("sensor.x_launcher", c.stateEntity)
        assertEquals(Config.DEFAULT_NOTIFY_ENTITY, c.notifyEntity)
        assertTrue(c.pixelShift)
    }

    @Test
    fun noHomeAssistantWithoutToken() {
        val c = Config.parse(JSONObject("""{"ha_url":"http://x"}"""))
        assertFalse(c.haEnabled)
        assertEquals(null, c.client())
        // a state sensor that is not a sensor is not taken
        assertEquals(null, Config.parse(JSONObject("""{"state_entity":"light.x"}""")).stateEntity)
        assertTrue(Config.parse(JSONObject("{}")).reportState)
    }

    @Test
    fun releaseFromGitHub() {
        val r = Updates.Release.from(JSONObject("""{"name":"build-120","tag_name":"build-120","published_at":"2026-11-23T10:00:00Z",
            "assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"launcher.apk","browser_download_url":"https://e/launcher.apk"}]}"""))!!
        assertEquals("build-120", r.name)
        assertEquals("https://e/launcher.apk", r.apk)
        assertEquals(java.time.Instant.parse("2026-11-23T10:00:00Z").toEpochMilli() / 60_000L, r.minute)
        assertEquals(null, Updates.Release.from(JSONObject("""{"name":"x","published_at":"2026-11-23T10:00:00Z","assets":[]}""")))
    }

    @Test
    fun newerOnlyWellAfterTheInstalledBuild() {
        val r = Updates.Release("b", 1000L, "u")
        assertTrue(Updates.isNewer(900L, r))
        // a release published with the installed build (within 15 minutes) is the same build
        assertFalse(Updates.isNewer(990L, r))
        assertFalse(Updates.isNewer(1000L, r))
    }

    @Test
    fun settingsAreCarried() {
        val config = JSONObject("""{"ha_url":"http://x","ha_token":"secret"}""")
        val prefs = mapOf<String, Any?>(
            "kids_ha_pin" to "1234", "kids_ask" to true, "ma_present" to 3, "update_checked" to 5L,
            "local_adb" to "ok", "front" to true, "volume" to 0.5f, "set" to setOf("a", "b")
        )
        val packed = JSONObject(Carry.pack(config, prefs).toString())
        assertEquals("secret", packed.getJSONObject("config").getString("ha_token"))
        val back = Carry.values(packed).associate { it.key to it.value }
        assertEquals("1234", back["kids_ha_pin"])
        assertEquals(true, back["kids_ask"])
        assertEquals(3, back["ma_present"])
        assertEquals(0.5f, back["volume"])
        assertEquals(setOf("a", "b"), back["set"])
        // what belongs to the old install stays behind (its ADB key is not the new one's)
        assertFalse(back.containsKey("local_adb"))
        assertFalse(back.containsKey("front"))
        assertFalse(back.containsKey("update_checked"))
    }
}
