package top.aaa000111.maid.shizuku;

import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import top.aaa000111.maid.shizuku.PrivilegedDisplayInfo;
import top.aaa000111.maid.shizuku.PrivilegedProcessInfo;
import top.aaa000111.maid.shizuku.ShellCommandResult;

interface IShizukuUserService {
    // Reserved by Shizuku server (transaction code 16777115)
    void destroy() = 16777114;

    // Service identity & lifecycle
    void exit() = 1;
    int getProcessUid() = 2;
    int getProcessPid() = 3;
    String getSELinuxContext() = 4;

    // Reliable single-command execution
    ShellCommandResult executeCommand(
        in String[] command,
        String stdinData,
        String workingDir,
        in String[] env,
        long timeoutMillis
    ) = 5;

    ShellCommandResult executeShell(
        String shellCommand,
        String stdinData,
        String workingDir,
        in String[] env,
        long timeoutMillis
    ) = 6;

    // SystemProperties
    String getSystemProperty(String key, String defaultValue) = 7;
    boolean setSystemProperty(String key, String value) = 8;

    // Reflection availability status for ActivityManager / InputManager / WindowManager
    Bundle getSystemManagersStatus() = 9;

    // ActivityManager (IActivityManager / IActivityTaskManager via reflection)
    boolean forceStopPackage(String packageName, int userId) = 10;
    boolean killBackgroundProcesses(String packageName, int userId) = 11;
    List<PrivilegedProcessInfo> getRunningAppProcesses() = 12;
    String getTopActivity() = 13;
    int startActivityAsUser(in Intent intent, int userId) = 14;

    // InputManager (IInputManager via reflection)
    boolean injectInputEvent(in Bundle eventBundle, int mode) = 15;
    boolean injectTap(float x, float y, int displayId) = 16;
    boolean injectSwipe(float startX, float startY, float endX, float endY, long durationMs, int displayId) = 17;
    boolean injectKeyEvent(int keyCode, boolean longPress, int metaState, int displayId) = 18;
    boolean injectText(String text, int displayId) = 19;

    // WindowManager (IWindowManager via reflection)
    PrivilegedDisplayInfo getDisplayInfo(int displayId) = 20;
    boolean setForcedDisplaySize(int displayId, int width, int height) = 21;
    boolean clearForcedDisplaySize(int displayId) = 22;
    boolean setForcedDisplayDensity(int displayId, int densityDpi, int userId) = 23;
    boolean clearForcedDisplayDensity(int displayId, int userId) = 24;
    boolean freezeRotation(int displayId, int rotation) = 25;
    boolean thawRotation(int displayId) = 26;
    boolean isKeyguardLocked() = 27;

    // Screen capture (streams PNG via ParcelFileDescriptor to avoid Binder size limit)
    boolean captureScreenshot(int displayId, in ParcelFileDescriptor outputFd) = 28;
}
