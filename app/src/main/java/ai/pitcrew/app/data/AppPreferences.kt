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

    private val _proactive = MutableStateFlow(prefs.getBoolean(KEY_PROACTIVE, true))
    /** 늦음·배터리 부족·단골 메뉴를 먼저 묻는지. */
    val proactive: StateFlow<Boolean> = _proactive.asStateFlow()

    fun setProactive(value: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE, value).apply()
        _proactive.value = value
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PROACTIVE = "proactive"
    }
}
