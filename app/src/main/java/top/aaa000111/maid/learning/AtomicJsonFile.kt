package top.aaa000111.maid.learning

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 同目录临时文件 + fsync + 原子替换；失败时保留旧文件。调用方在 IO 调度器执行。 */
internal fun atomicWrite(path: Path, content: String) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".maid-", ".tmp")
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
