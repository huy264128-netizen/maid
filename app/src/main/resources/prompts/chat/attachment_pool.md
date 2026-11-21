说明：每个文件和图片均包含 id、文件名（fileName）、描述与 uri，模型内部通信请使用 id；首次访问文件时若 uri 不在内部目录会自动复制到内部目录/<session_id>/<file_id>；如需向用户展示可点击打开的文件卡片，可调用 sendFileCard 工具。
