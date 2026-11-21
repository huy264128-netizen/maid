委托工具先返回任务 ID；请分别使用 waitDeviceTask、waitLinuxTask 或 waitResearchTask 等待对应任务结果。可以并发提交互不依赖的任务，但在使用结果、推进检查点或调用 finish 前必须等待，任务 ID 本身不是完成证明。
