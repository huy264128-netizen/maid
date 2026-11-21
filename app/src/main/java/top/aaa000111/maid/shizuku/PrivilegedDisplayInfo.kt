package top.aaa000111.maid.shizuku

import android.os.Parcel
import android.os.Parcelable

data class PrivilegedDisplayInfo(
    val displayId: Int,
    val baseWidth: Int,
    val baseHeight: Int,
    val initialWidth: Int,
    val initialHeight: Int,
    val baseDensityDpi: Int,
    val initialDensityDpi: Int,
    val rotation: Int,
    val hasNavigationBar: Boolean,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        displayId = parcel.readInt(),
        baseWidth = parcel.readInt(),
        baseHeight = parcel.readInt(),
        initialWidth = parcel.readInt(),
        initialHeight = parcel.readInt(),
        baseDensityDpi = parcel.readInt(),
        initialDensityDpi = parcel.readInt(),
        rotation = parcel.readInt(),
        hasNavigationBar = parcel.readByte() != 0.toByte(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(displayId)
        parcel.writeInt(baseWidth)
        parcel.writeInt(baseHeight)
        parcel.writeInt(initialWidth)
        parcel.writeInt(initialHeight)
        parcel.writeInt(baseDensityDpi)
        parcel.writeInt(initialDensityDpi)
        parcel.writeInt(rotation)
        parcel.writeByte(if (hasNavigationBar) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PrivilegedDisplayInfo> {
        override fun createFromParcel(parcel: Parcel): PrivilegedDisplayInfo {
            return PrivilegedDisplayInfo(parcel)
        }

        override fun newArray(size: Int): Array<PrivilegedDisplayInfo?> {
            return arrayOfNulls(size)
        }
    }
}
