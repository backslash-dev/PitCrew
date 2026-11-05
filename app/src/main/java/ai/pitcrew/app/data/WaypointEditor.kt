package ai.pitcrew.app.data

/** 내비게이션 경유지 편집. 작업 실행부가 플랫폼 SDK를 직접 알지 않도록 둔다. */
interface WaypointEditor {
    suspend fun add(name: String, latitude: Double, longitude: Double): Boolean
    suspend fun remove(name: String, latitude: Double, longitude: Double): Boolean
}
