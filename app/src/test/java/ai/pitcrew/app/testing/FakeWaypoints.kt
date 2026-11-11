package ai.pitcrew.app.testing

import ai.pitcrew.app.data.WaypointEditor

class FakeWaypoints(private val ok: () -> Boolean = { true }) : WaypointEditor {
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()

    override suspend fun add(name: String, latitude: Double, longitude: Double): Boolean = ok().also { if (it) added += name }

    override suspend fun remove(name: String, latitude: Double, longitude: Double): Boolean = ok().also { if (it) removed += name }
}
