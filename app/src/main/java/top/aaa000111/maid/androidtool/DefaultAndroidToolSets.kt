package top.aaa000111.maid.androidtool

import android.content.Context

/**
 * 内置 Android [AndroidToolSet] 集合构造器，用于一次性挂载到 [AndroidToolsetDispatcher] 并按权限动态加载。
 */
object DefaultAndroidToolSets {

    fun createAll(context: Context): List<AndroidToolSet> {
        return listOf(
            InputToolSet(),
            ScreenToolSet(context),
            ShellToolSet(),
            AppControlToolSet(),
            WindowToolSet(),
            RootSystemToolSet(),
            DevicePolicyToolSet(context),
            FileManagementToolSet(context),
            UsageStatsToolSet(context),
            SystemSettingsToolSet(context),
        )
    }

    fun createDispatcher(
        context: Context,
        checkRoot: Boolean = true,
    ): AndroidToolsetDispatcher {
        return AndroidToolsetDispatcher(
            context = context,
            toolSets = createAll(context),
            checkRoot = checkRoot,
        )
    }
}
