package top.aaa000111.maid.display

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** 主线程持有最后一帧，持续回收旧帧，避免阻塞虚拟显示器输出。 */
internal class VirtualDisplayCapture(width: Int, height: Int, handler: Handler) : AutoCloseable {
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val firstFrame = CompletableDeferred<Unit>()
    private var latest: Image? = null
    private var closed = false
    val surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (!closed) {
                source.acquireLatestImage()?.let { frame ->
                    latest?.close()
                    latest = frame
                    firstFrame.complete(Unit)
                }
            }
        }, handler)
    }

    suspend fun snapshot(): Bitmap {
        withTimeout(5.seconds) { firstFrame.await() }
        val image = checkNotNull(latest) { "The virtual display stopped" }
        val plane = image.planes.single()
        check(plane.pixelStride == 4) { "Expected an RGBA frame" }
        val row = ByteArray(image.width * plane.pixelStride)
        val pixels = ByteBuffer.allocate(row.size * image.height)
        // Captures can reuse this Image until the next frame. Keep its buffer position intact.
        val source = plane.buffer.duplicate()
        val origin = source.position()
        repeat(image.height) { y ->
            source.position(origin + y * plane.rowStride)
            source.get(row)
            pixels.put(row)
        }
        pixels.rewind()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.apply { copyPixelsFromBuffer(pixels) }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    override fun close() {
        closed = true
        reader.setOnImageAvailableListener(null, null)
        latest?.close()
        latest = null
        reader.close()
        firstFrame.completeExceptionally(IllegalStateException("The virtual display stopped"))
    }
}
