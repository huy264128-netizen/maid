package top.aaa000111.maid.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Maid 核心无障碍服务，负责在用户授权开启后接收无障碍连接事件，
 * 并提供服务实例供自动化 ToolSet 执行节点读取与手势操作。
 */
class MaidAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可供后续交互及事件监听 ToolSet 接入
    }

    override fun onInterrupt() {
        // 服务中断或系统暂停回调
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _isConnected.value = false
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: MaidAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
