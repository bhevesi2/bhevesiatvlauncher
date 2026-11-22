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
