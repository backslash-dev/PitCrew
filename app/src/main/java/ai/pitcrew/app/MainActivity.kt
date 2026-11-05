package ai.pitcrew.app

import ai.pitcrew.app.ui.PitCrewApp
import ai.pitcrew.app.ui.theme.PitCrewTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val app by lazy { application as PitCrewApplication }
    private val locationGranted = mutableStateOf(false)

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted.value = granted
        if (granted) app.platform.location.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.platform.startAll()
        setContent {
            PitCrewTheme {
                PitCrewApp(
                    app = app,
                    locationGranted = locationGranted.value,
                    onRequestLocation = { locationPermission.launch(ai.pitcrew.app.platform.LocationClient.PERMISSION) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 설정 앱에서 권한을 바꾸고 돌아온 경우를 반영한다
        locationGranted.value = app.platform.location.hasPermission()
        if (locationGranted.value) app.platform.location.start()
    }
}
