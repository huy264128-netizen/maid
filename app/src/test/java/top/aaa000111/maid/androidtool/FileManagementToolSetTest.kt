package top.aaa000111.maid.androidtool

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import java.io.File

class FileManagementToolSetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var stubContext: Context

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("mock_sdcard")
        stubContext = allocateStubInstance<Context>(Application::class.java)
    }

    @Test
    fun writeReadListCopyMoveSearchAndDeleteFiles_workWithDirectFileAccess() = runBlocking {
        val toolSet = FileManagementToolSet(
            context = stubContext,
            defaultRootDirProvider = { rootDir },
            directFileAccessChecker = { true },
        )

        // 1. 创建目录与写入文本文件
        val mkdirResult = toolSet.createDirectory("notes/daily")
        assertTrue(mkdirResult.contains("Created directory"))
        assertTrue(File(rootDir, "notes/daily").isDirectory)

        val writeResult = toolSet.writeTextFile(
            path = "notes/daily/todo.txt",
            content = "第一行：整理文件\n第二行：测试权限",
            append = false,
        )
        assertTrue(writeResult.contains("Wrote"))

        // 追加内容
        val appendResult = toolSet.writeTextFile(
            path = "notes/daily/todo.txt",
            content = "\n第三行：完成验证",
            append = true,
        )
        assertTrue(appendResult.contains("append=true"))

        // 2. 读取文本文件
        val readResult = toolSet.readTextFile("notes/daily/todo.txt")
        assertTrue(readResult.contains("第一行：整理文件"))
        assertTrue(readResult.contains("第二行：测试权限"))
        assertTrue(readResult.contains("第三行：完成验证"))

        // 3. 列出目录
        val listResult = toolSet.listDirectory("notes/daily")
        assertTrue(listResult.contains("todo.txt"))

        // 4. 复制与移动文件
        val copyResult = toolSet.copyOrMoveFile(
            sourcePath = "notes/daily/todo.txt",
            targetPath = "notes/backup/todo_copy.txt",
            move = false,
            overwrite = false,
        )
        assertTrue(copyResult.contains("Copied"))
        assertTrue(File(rootDir, "notes/daily/todo.txt").isFile)
        assertTrue(File(rootDir, "notes/backup/todo_copy.txt").isFile)

        val moveResult = toolSet.copyOrMoveFile(
            sourcePath = "notes/backup/todo_copy.txt",
            targetPath = "notes/backup/todo_moved.txt",
            move = true,
            overwrite = true,
        )
        assertTrue(moveResult.contains("Moved"))
        assertFalse(File(rootDir, "notes/backup/todo_copy.txt").exists())
        assertTrue(File(rootDir, "notes/backup/todo_moved.txt").isFile)

        // 5. 搜索文件与查看详情
        val searchResult = toolSet.searchFiles(
            nameQuery = "todo",
            directoryPath = "notes",
        )
        assertTrue(searchResult.contains("todo.txt"))
        assertTrue(searchResult.contains("todo_moved.txt"))

        val detailResult = toolSet.getFileDetails("notes/daily/todo.txt")
        assertTrue(detailResult.contains("type=file"))
        assertTrue(detailResult.contains("todo.txt"))

        // 6. 删除文件与目录，且保护根目录不可直接删除
        val rootProtectResult = toolSet.deleteFileOrDirectory(
            path = rootDir.absolutePath,
            recursive = true,
        )
        assertTrue(rootProtectResult.contains("Refusing to delete protected root storage directory"))
        assertTrue(rootDir.exists())

        val nonRecursiveDirDelete = toolSet.deleteFileOrDirectory(
            path = "notes",
            recursive = false,
        )
        assertTrue(nonRecursiveDirDelete.contains("Directory is not empty"))

        val recursiveDirDelete = toolSet.deleteFileOrDirectory(
            path = "notes",
            recursive = true,
        )
        assertTrue(recursiveDirDelete.contains("Deleted:"))
        assertFalse(File(rootDir, "notes").exists())
    }

    private fun runLocalHostShell(cmd: String) =
        ShellCommandExecutor.executeCommand(arrayOf("sh", "-c", cmd))

    @Test
    fun fileManagementAndSettingsAndUsageStats_fallbackToShizukuOrRootChannelWhenNormalPermissionNotGranted() =
        runBlocking {
            val executedCommands = mutableListOf<String>()
            val shizukuFileToolSet = FileManagementToolSet(
                context = stubContext,
                defaultRootDirProvider = { rootDir },
                directFileAccessChecker = { false },
                privilegedShellRunner = { cmd ->
                    executedCommands += cmd
                    runLocalHostShell(cmd)
                },
            )

            val mkdirRes = shizukuFileToolSet.createDirectory("shizuku_dir")
            assertTrue(mkdirRes.contains("Created directory"))
            assertTrue(File(rootDir, "shizuku_dir").isDirectory)

            val writeRes = shizukuFileToolSet.writeTextFile(
                path = "shizuku_dir/msg.txt",
                content = "通过 Shizuku 通道写入的文本",
                append = false,
            )
            assertTrue(writeRes.contains("Wrote"))

            val readRes = shizukuFileToolSet.readTextFile("shizuku_dir/msg.txt")
            assertTrue(readRes.contains("通过 Shizuku 通道写入的文本"))

            val listRes = shizukuFileToolSet.listDirectory("shizuku_dir")
            assertTrue(listRes.contains("msg.txt"))

            val copyRes = shizukuFileToolSet.copyOrMoveFile(
                sourcePath = "shizuku_dir/msg.txt",
                targetPath = "shizuku_dir/msg2.txt",
                move = false,
            )
            assertTrue(copyRes.contains("Copied"))
            assertTrue(File(rootDir, "shizuku_dir/msg2.txt").isFile)

            val deleteRes = shizukuFileToolSet.deleteFileOrDirectory(
                path = "shizuku_dir",
                recursive = true,
            )
            assertTrue(deleteRes.contains("Deleted:"))
            assertFalse(File(rootDir, "shizuku_dir").exists())
            assertTrue(executedCommands.isNotEmpty())
            assertFalse(executedCommands.any { it.contains("appops") || it.contains("pm grant") })

            // SystemSettingsToolSet 在未授予 WRITE_SETTINGS 时直接走 Shizuku/Root 执行 settings put system
            val settingsCommands = mutableListOf<String>()
            val settingsToolSet = SystemSettingsToolSet(
                context = stubContext,
                writeSettingsChecker = { false },
                privilegedShellRunner = { cmd ->
                    settingsCommands += cmd
                    runLocalHostShell("true")
                },
            )
            val brightnessRes = settingsToolSet.setScreenBrightness(180, autoBrightness = false)
            assertTrue(brightnessRes.contains("via Shizuku/Root"))
            assertTrue(settingsCommands.any { it.contains("settings put system screen_brightness 180") })
            assertFalse(settingsCommands.any { it.contains("appops") })

            // UsageStatsToolSet 在未授予 PACKAGE_USAGE_STATS 时直接走 Shizuku/Root 执行 dumpsys
            val usageCommands = mutableListOf<String>()
            val usageToolSet = UsageStatsToolSet(
                context = stubContext,
                usageStatsPermissionChecker = { false },
                privilegedShellRunner = { cmd ->
                    usageCommands += cmd
                    runLocalHostShell("echo 'package=top.aaa000111.maid totalTime=120s'")
                },
            )
            val usageRes = usageToolSet.getAppUsageSummary(packageName = "top.aaa000111.maid")
            assertTrue(usageRes.contains("top.aaa000111.maid"))
            assertTrue(usageCommands.any { it.contains("dumpsys usagestats") })
            assertFalse(usageCommands.any { it.contains("appops") })
        }

    @Test
    fun importFileToSessionAndExportFileFromSession_workInDirectAndPrivilegedModes() = runBlocking {
        val internalRoot = tempFolder.newFolder("session_internal_root")
        top.aaa000111.maid.chat.SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)
        try {
            val session = top.aaa000111.maid.chat.ChatSession(id = "toolset_io_session")

            // 1. 直接文件访问模式：移入 -> 查看详情 -> 导出到目录
            val directToolSet = FileManagementToolSet(
                context = stubContext,
                defaultRootDirProvider = { rootDir },
                directFileAccessChecker = { true },
            )
            val directSource = File(rootDir, "inbox/spec.md").apply {
                parentFile?.mkdirs()
                writeText("# 需求文档\n内容A", Charsets.UTF_8)
            }
            kotlinx.coroutines.withContext(top.aaa000111.maid.chat.ChatSessionContext(session)) {
                val importOut = directToolSet.importFileToSession(
                    uri = "inbox/spec.md",
                    description = "产品需求文档",
                )
                assertTrue(importOut.contains("id=file_1"))
                assertTrue(importOut.contains("fileName=spec.md"))
                assertTrue(importOut.contains("sourceDeleted=true"))
                assertFalse(directSource.exists())

                val details = directToolSet.getFileDetails("file_1")
                assertTrue(details.contains("attachmentId=file_1"))
                assertTrue(details.contains("fileName=spec.md"))
                assertTrue(details.contains("description=产品需求文档"))

                val exportOut = directToolSet.exportFileFromSession(
                    attachmentId = "file_1",
                    targetUri = "outbox/",
                )
                assertTrue(exportOut.contains("Exported"))
                val exportedDirect = File(rootDir, "outbox/spec.md")
                assertTrue(exportedDirect.exists())
                assertEquals("# 需求文档\n内容A", exportedDirect.readText(Charsets.UTF_8))
            }

            // 2. Shizuku / Root 特权通道模式：移入 -> 导出
            val privilegedToolSet = FileManagementToolSet(
                context = stubContext,
                defaultRootDirProvider = { rootDir },
                directFileAccessChecker = { false },
                privilegedShellRunner = { cmd -> runLocalHostShell(cmd) },
            )
            val privSource = File(rootDir, "priv_in/log_data.txt").apply {
                parentFile?.mkdirs()
                writeText("privileged payload 123", Charsets.UTF_8)
            }
            kotlinx.coroutines.withContext(top.aaa000111.maid.chat.ChatSessionContext(session)) {
                val importOut = privilegedToolSet.importFileToSession(
                    uri = "priv_in/log_data.txt",
                    description = "系统日志",
                )
                assertTrue(importOut.contains("id=file_2"))
                assertTrue(importOut.contains("fileName=log_data.txt"))
                assertTrue(importOut.contains("sourceDeleted=true"))
                assertFalse(privSource.exists())

                val exportOut = privilegedToolSet.exportFileFromSession(
                    attachmentId = "file_2",
                    targetUri = "priv_out/",
                )
                assertTrue(exportOut.contains("Exported"))
                val exportedPriv = File(rootDir, "priv_out/log_data.txt")
                assertTrue(exportedPriv.exists())
                assertEquals("privileged payload 123", exportedPriv.readText(Charsets.UTF_8))
            }
        } finally {
            top.aaa000111.maid.chat.SessionAttachmentStorage.setCustomInternalRootDirForTest(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateStubInstance(clazz: Class<out T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }
}
