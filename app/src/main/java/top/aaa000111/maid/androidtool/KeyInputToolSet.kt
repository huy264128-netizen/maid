package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

class KeyInputToolSet : AndroidToolSet() {
    override val name = "key_input"

    override fun requiredPrivileges(): Set<AndroidPrivilege> =
        setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT)

    @Tool
    @LLMDescription("Send an Android KeyEvent keyCode (e.g. 4=BACK, 3=HOME, 187=APP_SWITCH, 66=ENTER, 26=POWER).")
    suspend fun pressKey(
        @LLMDescription("Android KeyEvent key code.") keyCode: Int,
        @LLMDescription("Whether to perform a long press.") longPress: Boolean = false,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.injectKeyEvent(keyCode, longPress = longPress, displayId = displayId)
                .getOrDefault(false)
            return if (ok) {
                "Pressed keyCode=$keyCode (longPress=$longPress) via Shizuku"
            } else {
                "Failed to press keyCode=$keyCode via Shizuku"
            }
        }

        if (PrivilegeBackend.isRootAvailable()) {
            val flag = if (longPress) "--longpress " else ""
            val result = PrivilegeBackend.execRootShell("input keyevent $flag$keyCode")
            return if (result.isSuccess) {
                "Pressed keyCode=$keyCode via Root"
            } else {
                "Failed to press keyCode=$keyCode via Root: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
            }
        }

        return "No available privilege (Shizuku / Root) to press key"
    }
}
