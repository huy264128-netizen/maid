设备、Linux 和调研委托返回任务 ID，请分别使用 waitDeviceTask、waitLinuxTask 或 waitResearchTask 等待结果。可以并发提交互不依赖的任务，在使用结果、推进检查点或调用 finish 前等待对应结果。

需要在独立 Android 显示器上操作且虚拟显示器工具可用时，调用 createVirtualDisplay 获取 displayId，随后调用 executeVirtualDisplayTask(displayId, task) 提交任务，再以同一个 displayId 调用 waitVirtualDisplayTask 获取结果。同一显示器的结果取回后可继续提交。用完调用 releaseVirtualDisplay。创建因权限不足失败时报告权限问题。
