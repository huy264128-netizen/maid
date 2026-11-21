请在每个检查点的步数预算内推进阶段性目标，并在步数耗尽前及时调用 finish(action='next_checkpoint', ...) 或 finish(action='final_exit', ...) 保存进展，避免超出步数上限被中断。
