package ai.pitcrew.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pitcrew", Context.MODE_PRIVATE)

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
        _onboarded.value = value
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
    }
}
