package top.aaa000111.maid.shizuku

import android.os.Parcel
import android.os.Parcelable

data class PrivilegedProcessInfo(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val importance: Int,
    val packages: List<String>,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        pid = parcel.readInt(),
        uid = parcel.readInt(),
        processName = parcel.readString().orEmpty(),
        importance = parcel.readInt(),
        packages = parcel.createStringArrayList().orEmpty(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(pid)
        parcel.writeInt(uid)
        parcel.writeString(processName)
        parcel.writeInt(importance)
        parcel.writeStringList(packages)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PrivilegedProcessInfo> {
        override fun createFromParcel(parcel: Parcel): PrivilegedProcessInfo {
            return PrivilegedProcessInfo(parcel)
        }

        override fun newArray(size: Int): Array<PrivilegedProcessInfo?> {
            return arrayOfNulls(size)
        }
    }
}
