package top.aaa000111.maid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import top.aaa000111.maid.chat.ChatBackgroundRuntime
import top.aaa000111.maid.service.StatusTextForegroundService
import top.aaa000111.maid.chat.ChatScreen
import top.aaa000111.maid.chat.ChatViewModel
import top.aaa000111.maid.proot.ProotTerminalActivity
import top.aaa000111.maid.ui.theme.MaidTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatBackgroundRuntime.current.runningSessionIds.collect { ids ->
                    if (ids.isNotEmpty()) {
                        StatusTextForegroundService.start(applicationContext)
                        requestTaskNotificationsOnce()
                    }
                }
            }
        }
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

    private fun requestTaskNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getPreferences(MODE_PRIVATE)
        val key = "task_notification_permission_requested"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
