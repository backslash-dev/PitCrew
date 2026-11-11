package ai.pitcrew.app.data

interface WaypointEditor {
    suspend fun add(name: String, latitude: Double, longitude: Double): Boolean
    suspend fun remove(name: String, latitude: Double, longitude: Double): Boolean
}
