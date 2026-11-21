当 Linux 脚本或 CLI 工具需要在容器内直接调用本机已配置的云端大模型 API 时，可调用 executeProotLinuxTaskWithLlmApi 工具（当前已在「设置 -> 模型安全性」中开启「允许使用本机 LLM API Key」，可按需通过 providers 参数限定服务商或模型角色，任务结束后自动清理注入的环境变量）。
