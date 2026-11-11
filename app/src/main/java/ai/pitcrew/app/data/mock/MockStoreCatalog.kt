package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.Store

class MockStoreCatalog(private val stores: List<Store> = DEFAULT_STORES) : StoreCatalog {
    override suspend fun findOnRoute(storeName: String?): Store? {
        val query = storeName?.replace(" ", "")?.takeIf { it.isNotBlank() }
        val candidates = if (query == null) stores else stores.filter { it.displayName.replace(" ", "").contains(query) }
        return candidates.minByOrNull { it.detourMinutes }
    }

    override suspend fun candidatesOnRoute(): List<Store> = stores.sortedBy { it.detourMinutes }

    companion object {
        val DEFAULT_STORES = listOf(
            Store("s-semo-yeoksam", "세모 커피", "역삼점", 37.5006, 127.0364, detourMinutes = 2, prepMinutes = 6),
            Store("s-noeul-seocho", "노을 베이커리", "서초점", 37.4925, 127.0156, detourMinutes = 4, prepMinutes = 10),
            Store("s-hansot-gangnam", "한솥밥상", "강남점", 37.4979, 127.0276, detourMinutes = 3, prepMinutes = 12),
        )
    }
}
