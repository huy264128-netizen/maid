package top.aaa000111.maid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import top.aaa000111.maid.settings.SettingsScreen
import top.aaa000111.maid.settings.SettingsViewModel
import top.aaa000111.maid.ui.theme.MaidTheme

class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaidTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                )
            }
        }
    }
}
