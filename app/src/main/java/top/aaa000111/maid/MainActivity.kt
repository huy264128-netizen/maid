package top.aaa000111.maid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import top.aaa000111.maid.chat.ChatScreen
import top.aaa000111.maid.chat.ChatViewModel
import top.aaa000111.maid.proot.ProotTerminalActivity
import top.aaa000111.maid.ui.theme.MaidTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaidTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenTerminal = {
                        startActivity(Intent(this, ProotTerminalActivity::class.java))
                    },
                )
            }
        }
    }
}
