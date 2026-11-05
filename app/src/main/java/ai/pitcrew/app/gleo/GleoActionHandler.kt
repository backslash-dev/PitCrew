package ai.pitcrew.app.gleo

fun interface GleoActionHandler {
    suspend fun handle(request: GleoRequest): GleoResponse
}
