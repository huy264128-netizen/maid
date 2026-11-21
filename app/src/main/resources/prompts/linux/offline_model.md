[内置离线端侧小模型（无需条件授权，开箱即用）]
当前 PRoot 终端已默认注入宿主 App 端侧轻量模型（TinyModel）的本地 HTTP 桥接与快捷命令，调用离线模型时，本机 HTTP 请求无需鉴权：
- 快捷命令行：`maid-tiny "你的问题"` 或管道输入 `cat file.txt | maid-tiny -s "提取要点"`（支持 `-s/--system`、`-m/--max-tokens`、`-t/--temperature`、`--raw`、`--health`。需要同时读取参数提示和管道内容时，添加 `--stdin`）；
- OpenAI 兼容环境变量：`MAID_TINY_MODEL_BASE_URL`（`http://127.0.0.1:<port>/v1`，支持 `/v1/models` 与 `/v1/chat/completions` 含流式/非流式）、`MAID_TINY_MODEL_NAME`；
- 精简生成端点：`MAID_TINY_MODEL_GENERATE_URL`（`POST /generate`，JSON：`{"prompt":"...","system":"...","max_tokens":512}`）。
适合在脚本中批量执行短文本分类、抽取、清洗、摘要或翻译等轻量推理任务；复杂深度推理仍由上层主模型完成。
