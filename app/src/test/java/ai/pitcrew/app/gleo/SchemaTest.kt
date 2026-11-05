package ai.pitcrew.app.gleo

import ai.pitcrew.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gleo 심사 규격(앱 스키마 작성 가이드)과 앱 구현이 어긋나지 않는지 확인한다. */
class SchemaTest {
    private val schema = JSONObject(File("src/main/assets/schema.json").readText())
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val functions = (0 until schema.getJSONArray("actionCalls").length())
        .map { schema.getJSONArray("actionCalls").getJSONObject(it) }

    @Test
    fun `패키지명과 버전이 빌드 설정과 같다`() {
        assertEquals(BuildConfig.APPLICATION_ID, schema.getString("packageName"))
        assertEquals(BuildConfig.VERSION_NAME, schema.getString("version"))
    }

    @Test
    fun `함수는 3개 이하이고 구현된 함수와 일치한다`() {
        assertTrue(functions.size <= 3)
        functions.forEach { assertEquals("function", it.getString("type")) }
        assertEquals(
            GleoFunction.entries.map { it.name }.toSet(),
            functions.map { it.getJSONObject("function").getString("name") }.toSet(),
        )
    }

    @Test
    fun `함수 정의가 규격을 따른다`() {
        functions.map { it.getJSONObject("function") }.forEach { fn ->
            val name = fn.getString("name")
            assertTrue(name, name.matches(Regex("[A-Z]+(_[A-Z]+)*")))
            assertTrue(name, fn.getString("component") in setOf("activity", "service"))
            assertTrue(name, fn.getString("description").isNotBlank())
            val params = fn.getJSONObject("parameters")
            assertEquals("object", params.getString("type"))
            val properties = params.getJSONObject("properties")
            properties.keys().forEach { key ->
                val property = properties.getJSONObject(key)
                assertTrue("$name.$key", property.getString("type") in setOf("string", "number", "boolean", "array"))
                assertTrue("$name.$key", property.getString("description").isNotBlank())
            }
            val required = params.getJSONArray("required")
            (0 until required.length()).forEach { assertTrue("$name required", properties.has(required.getString(it))) }
        }
    }

    @Test
    fun `모든 함수의 action을 매니페스트가 받는다`() {
        GleoFunction.entries.forEach {
            assertTrue(it.name, manifest.contains("\"${it.name}\""))
            assertTrue(it.name, manifest.contains("\"${BuildConfig.APPLICATION_ID}.${it.name}\""))
        }
    }

    @Test
    fun `설명은 영어로 쓴다`() {
        val texts = buildList {
            add(schema.getString("appDescription"))
            functions.map { it.getJSONObject("function") }.forEach { fn ->
                add(fn.getString("description"))
                val properties = fn.getJSONObject("parameters").getJSONObject("properties")
                properties.keys().forEach { add(properties.getJSONObject(it).getString("description")) }
            }
        }
        texts.forEach { assertTrue(it, it.none { c -> c in '가'..'힣' }) }
    }
}
