package ro.davidmontei.loom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import ro.davidmontei.loom.ui.theme.LoomTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        /** Opens straight to the download queue (notification taps, the "Downloads" shortcut). */
        const val ACTION_OPEN_QUEUE = "ro.davidmontei.loom.action.OPEN_QUEUE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoomTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loom", style = MaterialTheme.typography.displaySmall)
                }
            }
        }
    }
}
