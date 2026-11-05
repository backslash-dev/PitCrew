package ai.pitcrew.app

import ai.pitcrew.app.platform.LocationClient
import ai.pitcrew.app.ui.diagnostics.DiagnosticsScreen
import ai.pitcrew.app.ui.theme.PitCrewTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val platform by lazy { (application as PitCrewApplication).platform }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) platform.location.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!platform.location.hasPermission()) {
            locationPermission.launch(LocationClient.PERMISSION)
        }
        setContent {
            PitCrewTheme {
                DiagnosticsScreen(platform)
            }
        }
    }
}
