package top.aaa000111.maid.shizuku

enum class ShizukuAvailability {
    NOT_INSTALLED,
    STOPPED_OR_DISCONNECTED,
    PERMISSION_REQUIRED,
    READY,
}

data class ShizukuServerInfo(
    val uid: Int,
    val apiVersion: Int,
    val latestSupportedVersion: Int,
    val seLinuxContext: String?,
    val isPreV11: Boolean,
) {
    val isRoot: Boolean
        get() = uid == 0

    val isAdbShell: Boolean
        get() = uid == 2000
}

data class ShizukuStatus(
    val isManagerInstalled: Boolean = false,
    val isBinderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val shouldShowRationale: Boolean = false,
    val serverInfo: ShizukuServerInfo? = null,
    val isUserServiceBound: Boolean = false,
    val userServiceUid: Int? = null,
    val userServicePid: Int? = null,
    val userServiceSeLinuxContext: String? = null,
) {
    val availability: ShizukuAvailability
        get() = when {
            !isBinderAlive && !isManagerInstalled -> ShizukuAvailability.NOT_INSTALLED
            !isBinderAlive -> ShizukuAvailability.STOPPED_OR_DISCONNECTED
            !permissionGranted -> ShizukuAvailability.PERMISSION_REQUIRED
            else -> ShizukuAvailability.READY
        }

    val isReady: Boolean
        get() = availability == ShizukuAvailability.READY
}
