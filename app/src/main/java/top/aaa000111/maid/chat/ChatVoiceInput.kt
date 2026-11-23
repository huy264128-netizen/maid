package top.aaa000111.maid.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.aaa000111.maid.R
import top.aaa000111.maid.asr.Asr
import top.aaa000111.maid.asr.BundledVoiceModel
import top.aaa000111.maid.asr.VoiceRecording

data class ChatVoiceInput(
    val recording: Boolean,
    val processing: Boolean,
    val onClick: () -> Unit,
)

private enum class VoiceDialog { PermissionRationale, PermissionSettings }

@Composable
internal fun rememberChatVoiceInput(
    enabled: Boolean,
    onText: (String) -> Unit,
): ChatVoiceInput {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<VoiceDialog?>(null) }
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var stopSignal by remember { mutableStateOf<AtomicBoolean?>(null) }
    var processing by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }

    fun showMessage(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun startRecording() {
        if (!enabled || voiceJob != null || processing ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val stop = AtomicBoolean(false)
        stopSignal = stop
        voiceJob = scope.launch {
            try {
                val samples = VoiceRecording.capture(stop)
                stopSignal = null
                processing = true
                val text = if (samples.isEmpty()) {
                    ""
                } else {
                    val modelDirectory = BundledVoiceModel.prepare(context)
                    Asr.transcribe(samples, VoiceRecording.SAMPLE_RATE, modelDirectory)
                }
                if (text.isBlank()) {
                    showMessage(R.string.chat_voice_empty)
                } else {
                    onText(cleanVoiceTranscriptIfEnabled(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                showMessage(R.string.chat_voice_permission_denied)
            } catch (e: Exception) {
                Log.w("ChatVoiceInput", "Voice input failed", e)
                showMessage(R.string.chat_voice_error)
            } finally {
                stopSignal = null
                processing = false
                voiceJob = null
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        voiceJob?.cancel()
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requestedHere = permissionPending
        permissionPending = false
        if (requestedHere) {
            when {
                granted -> startRecording()
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true ->
                    showMessage(R.string.chat_voice_permission_denied)
                else -> dialog = VoiceDialog.PermissionSettings
            }
        }
    }

    fun launchPermissionRequest() {
        permissionPending = true
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    dialog?.let { current ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = {
                Text(stringResource(R.string.chat_voice_permission_title))
            },
            text = {
                Text(stringResource(R.string.chat_voice_permission_hint))
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    when (current) {

                        VoiceDialog.PermissionRationale -> launchPermissionRequest()
                        VoiceDialog.PermissionSettings -> context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    }
                }) {
                    Text(stringResource(when (current) {

                        VoiceDialog.PermissionRationale -> R.string.chat_voice_permission_continue
                        VoiceDialog.PermissionSettings -> R.string.chat_voice_permission_settings
                    }))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    return ChatVoiceInput(
        recording = stopSignal?.get() == false,
        processing = processing || permissionPending || (voiceJob != null && stopSignal?.get() == true),
        onClick = {
            val recording = stopSignal
            if (recording != null) {
                recording.set(true)
                processing = true
            } else if (enabled && !processing && !permissionPending && voiceJob == null) {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> startRecording()
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true ->
                        dialog = VoiceDialog.PermissionRationale
                    else -> launchPermissionRequest()
                }
            }
        },
    )
}
