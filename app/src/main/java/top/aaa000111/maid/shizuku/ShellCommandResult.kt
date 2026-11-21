package top.aaa000111.maid.shizuku

import android.os.Parcel
import android.os.Parcelable

/**
 * 单命令执行的标准输出、标准错误与退出状态结果。
 */
data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
) : Parcelable {

    val isSuccess: Boolean
        get() = !timedOut && errorMessage == null && exitCode == 0

    constructor(parcel: Parcel) : this(
        exitCode = parcel.readInt(),
        stdout = parcel.readString().orEmpty(),
        stderr = parcel.readString().orEmpty(),
        timedOut = parcel.readByte() != 0.toByte(),
        stdoutTruncated = parcel.readByte() != 0.toByte(),
        stderrTruncated = parcel.readByte() != 0.toByte(),
        durationMs = parcel.readLong(),
        errorMessage = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
        parcel.writeByte(if (timedOut) 1 else 0)
        parcel.writeByte(if (stdoutTruncated) 1 else 0)
        parcel.writeByte(if (stderrTruncated) 1 else 0)
        parcel.writeLong(durationMs)
        parcel.writeString(errorMessage)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ShellCommandResult> {
        override fun createFromParcel(parcel: Parcel): ShellCommandResult {
            return ShellCommandResult(parcel)
        }

        override fun newArray(size: Int): Array<ShellCommandResult?> {
            return arrayOfNulls(size)
        }

        fun failure(message: String, durationMs: Long = 0L): ShellCommandResult {
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                durationMs = durationMs,
                errorMessage = message,
            )
        }
    }
}
