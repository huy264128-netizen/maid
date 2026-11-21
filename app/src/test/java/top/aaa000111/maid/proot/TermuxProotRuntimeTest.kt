package top.aaa000111.maid.proot

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import com.github.luben.zstd.ZstdOutputStream

class TermuxProotRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun prootRuntime_usesGlobalSingleRootfsUnderFilesDirAndSupportsCustomArchiveImportAndReset() {
        val nativeLibDir = tempFolder.newFolder("native_libs")
        val cacheDir = tempFolder.newFolder("cache")
        val filesDir = tempFolder.newFolder("files")

        val fakeProot = File(nativeLibDir, TermuxProotRuntime.PROOT_BINARY_NAME).apply {
            writeText("elf")
        }
        val fakeLoader = File(nativeLibDir, TermuxProotRuntime.PROOT_LOADER_BINARY_NAME).apply {
            writeText("elf")
        }

        val context: Context = object : Application() {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    nativeLibraryDir = nativeLibDir.absolutePath
                }
            }

            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
        }

        // 1. 验证二进制路径与全局单一 rootfs 路径约定 (files/rootfs)
        assertEquals(fakeProot.absolutePath, TermuxProotRuntime.getProotExecutable(context).absolutePath)
        assertEquals(fakeLoader.absolutePath, TermuxProotRuntime.getLoaderExecutable(context).absolutePath)
        assertTrue(TermuxProotRuntime.isAvailable(context))

        val expectedRootfsDir = File(filesDir, "rootfs")
        assertEquals(expectedRootfsDir.absolutePath, TermuxProotRuntime.getRootfsDir(context).absolutePath)
        assertFalse(TermuxProotRuntime.isRootfsPopulated(context))
        assertFalse(TermuxProotRuntime.isRootfsInitialized(context))

        // 2. 启动调用 init(context) 时，若没有 rootfs，自动初始化 rootfs 标准结构与基础配置
        val ensuredRootfs = TermuxProotRuntime.init(context)
        assertTrue(ensuredRootfs.isDirectory)
        assertEquals(expectedRootfsDir.absolutePath, ensuredRootfs.absolutePath)
        assertEquals(expectedRootfsDir.absolutePath, TermuxProotRuntime.globalRootfsDir?.absolutePath)
        assertTrue(TermuxProotRuntime.isRootfsPopulated(context))
        assertTrue(TermuxProotRuntime.isRootfsInitialized(context))
        assertTrue(File(ensuredRootfs, TermuxProotRuntime.ROOTFS_INIT_MARKER_FILE_NAME).isFile)
        assertTrue(File(ensuredRootfs, "etc/passwd").isFile)
        assertTrue(File(ensuredRootfs, "etc/resolv.conf").isFile)
        assertTrue(File(ensuredRootfs, "root").isDirectory)
        assertTrue(File(ensuredRootfs, "usr/local/bin").isDirectory)

        // 3. 导入自定义 rootfs.tar.gz 归档文件并立即应用到 files/rootfs
        val customTarGzBytes = buildTestTarGz(
            entries = listOf(
                TarTestEntry.Directory("alpine-minirootfs/"),
                TarTestEntry.Directory("alpine-minirootfs/bin/"),
                TarTestEntry.Directory("alpine-minirootfs/etc/"),
                TarTestEntry.RegularFile(
                    name = "alpine-minirootfs/etc/os-release",
                    content = "NAME=\"Alpine Linux\"\nVERSION_ID=3.20.0\n",
                    mode = 0b110_100_100,
                ),
                TarTestEntry.RegularFile(
                    name = "alpine-minirootfs/bin/hello",
                    content = "#!/bin/sh\necho hello_from_archive\n",
                    mode = 0b111_101_101,
                ),
            ),
        )
        val importedStatus = TermuxProotRuntime.importCustomRootfsArchive(
            context = context,
            inputStream = ByteArrayInputStream(customTarGzBytes),
            displayName = "alpine-minirootfs-3.20.0.tar.gz",
            applyImmediately = true,
        )
        assertTrue(importedStatus.hasCustomArchive)
        assertEquals("alpine-minirootfs-3.20.0.tar.gz", importedStatus.customArchiveName)
        assertTrue(importedStatus.activeSourceLabel.contains("alpine-minirootfs-3.20.0.tar.gz"))

        // 验证单层顶层目录 alpine-minirootfs/ 已自动展平至 files/rootfs 根目录，且文件与可执行权限就绪
        val osReleaseFile = File(expectedRootfsDir, "etc/os-release")
        val helloScriptFile = File(expectedRootfsDir, "bin/hello")
        assertTrue(osReleaseFile.isFile)
        assertTrue(osReleaseFile.readText().contains("Alpine Linux"))
        assertTrue(helloScriptFile.isFile)
        assertTrue(helloScriptFile.canExecute())

        // 4. 在 rootfs 内写入临时运行污染文件后调用 resetRootfs，验证污染文件被清除且从自定义归档重新解压还原
        val dirtyFile = File(expectedRootfsDir, "root/dirty_runtime_state.tmp").apply {
            writeText("dirty")
        }
        osReleaseFile.writeText("MODIFIED_DURING_RUNTIME")
        assertTrue(dirtyFile.exists())

        val resetDir = TermuxProotRuntime.resetRootfs(context)
        assertEquals(expectedRootfsDir.absolutePath, resetDir.absolutePath)
        assertFalse(dirtyFile.exists())
        assertTrue(File(resetDir, "etc/os-release").readText().contains("Alpine Linux"))
        assertTrue(File(resetDir, "bin/hello").isFile)

        // 5. 移除自定义归档后恢复默认初始化源
        val clearedStatus = TermuxProotRuntime.clearCustomRootfsArchive(
            context = context,
            resetImmediately = true,
        )
        assertFalse(clearedStatus.hasCustomArchive)
        assertFalse(File(resetDir, "bin/hello").exists())
        assertTrue(File(resetDir, "etc/passwd").isFile)

        // 6. 验证默认环境变量与命令行参数固定挂载全局单一 rootfs (-r <filesDir>/rootfs)
        val env = TermuxProotRuntime.buildDefaultEnvironment(
            context = context,
            disableSeccomp = true,
        )
        assertEquals(fakeLoader.absolutePath, env["PROOT_LOADER"])
        assertEquals(expectedRootfsDir.absolutePath, env["PROOT_ROOTFS_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertTrue(env["PROOT_TMP_DIR"]?.startsWith(cacheDir.absolutePath) == true)

        val args = TermuxProotRuntime.buildProotCommandArgs(
            context = context,
            command = "uname -a",
            bindMounts = listOf("/sdcard"),
        )
        val rIndex = args.indexOf("-r")
        assertTrue(rIndex >= 0)
        assertEquals(expectedRootfsDir.absolutePath, args[rIndex + 1])
        assertTrue(args.contains("-0"))
        assertTrue(args.contains("--link2symlink"))
        assertTrue(args.contains("--sysvipc"))
        assertTrue(args.contains("--ashmem-memfd"))
    }

    @Test
    fun prootConnection_persistsUnderlyingShellProcessAndPreservesCwdEnvAndDisconnectsOnReset() {
        val nativeLibDir = tempFolder.newFolder("native_libs_conn")
        val cacheDir = tempFolder.newFolder("cache_conn")
        val filesDir = tempFolder.newFolder("files_conn")
        val workA = tempFolder.newFolder("work_a")

        File(nativeLibDir, TermuxProotRuntime.PROOT_BINARY_NAME).writeText("elf")
        File(nativeLibDir, TermuxProotRuntime.PROOT_LOADER_BINARY_NAME).writeText("elf")

        val context: Context = object : Application() {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    nativeLibraryDir = nativeLibDir.absolutePath
                }
            }

            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
        }

        // 在主机 JVM 单元测试中，用真实的 /bin/sh 作为底层常驻进程验证 ProotConnection 持久连接与状态保持
        ProotConnectionManager.customProcessStarterForTest = { _, env ->
            val pb = ProcessBuilder("/bin/sh")
            pb.environment().putAll(env)
            pb.start()
        }

        try {
            TermuxProotRuntime.init(context)
            assertFalse(ProotConnectionManager.isConnected)

            // 1. 第 1 条命令：在持久连接中切换目录 (cd) 并导出环境变量 (export)
            val r1 = TermuxProotRuntime.executeInProot(
                context = context,
                command = "cd '${workA.absolutePath}' && export MAID_SESSION_VAR='persisted_42' && pwd",
            )
            assertTrue(r1.isSuccess)
            assertEquals(workA.canonicalPath, File(r1.stdout.trim()).canonicalPath)
            assertTrue(ProotConnectionManager.isConnected)

            val connectionBefore = ProotConnectionManager.currentConnection!!
            assertTrue(connectionBefore.isAlive)
            assertEquals(1L, connectionBefore.snapshotStatus().executedCommandCount)

            // 2. 第 2 条命令：复用同一个 ProotConnection 进程，验证 pwd 与 export 变量均跨调用保留，且 stderr 与非零退出码正常捕获
            val r2 = TermuxProotRuntime.executeInProot(
                context = context,
                command = "printf '%s|%s' \"\$(pwd)\" \"\$MAID_SESSION_VAR\"; echo 'warn_msg' >&2; (exit 7)",
            )
            assertEquals(7, r2.exitCode)
            assertEquals("${workA.canonicalPath}|persisted_42", r2.stdout.trim())
            assertEquals("warn_msg", r2.stderr.trim())
            assertEquals(connectionBefore.connectionId, ProotConnectionManager.currentConnection?.connectionId)
            assertEquals(2L, connectionBefore.snapshotStatus().executedCommandCount)

            // 3. 调用 resetRootfs 时：自动断开并销毁旧的持久连接
            TermuxProotRuntime.resetRootfs(context)
            assertFalse(connectionBefore.isAlive)
            assertFalse(ProotConnectionManager.isConnected)

            // 4. 重置后再次执行命令时自动建立新一代 ProotConnection，旧环境变量已被清空
            val r3 = TermuxProotRuntime.executeInProot(
                context = context,
                command = "printf 'var=%s' \"\${MAID_SESSION_VAR:-empty}\"",
            )
            assertTrue(r3.isSuccess)
            assertEquals("var=empty", r3.stdout.trim())
            val connectionAfter = ProotConnectionManager.currentConnection!!
            assertTrue(connectionAfter.generation > connectionBefore.generation)
        } finally {
            ProotConnectionManager.disconnect()
            ProotConnectionManager.customProcessStarterForTest = null
        }
    }

    @Test
    fun `ProotVtfxTerminalController bridges vtfx TerminalSession with persistent ProotConnection`() {
        val nativeLibDir = tempFolder.newFolder("native_libs_vtfx")
        val cacheDir = tempFolder.newFolder("cache_vtfx")
        val filesDir = tempFolder.newFolder("files_vtfx")

        File(nativeLibDir, TermuxProotRuntime.PROOT_BINARY_NAME).writeText("elf")
        File(nativeLibDir, TermuxProotRuntime.PROOT_LOADER_BINARY_NAME).writeText("elf")

        val context: Context = object : Application() {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    nativeLibraryDir = nativeLibDir.absolutePath
                }
            }

            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
        }

        ProotConnectionManager.customProcessStarterForTest = { _, env ->
            val pb = ProcessBuilder("/bin/bash", "--rcfile", File(filesDir, "rootfs/root/.bashrc").absolutePath, "-i")
            pb.environment().putAll(env)
            pb.start()
        }

        val controller = ProotVtfxTerminalController(
            context = context,
            clipboardOverride = null,
            urlOpenerOverride = null,
        )

        try {
            controller.ensureConnected()
            // 发送终端命令和回车给 vtfx TerminalSession -> 异步写入 ProotConnection -> stdout 回流到 TerminalSession.feed 且命令结束后自动打印 root@maid-proot 提示符
            controller.session.sendText("echo VTFX_PROOT_OK_99\r")

            var screenText = ""
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                val snap = controller.session.snapshot()
                screenText = (0 until snap.rows).joinToString("\n") { row ->
                    val t = snap.line(row).text().trimEnd()
                    "[$row]: $t"
                }
                if (screenText.contains("VTFX_PROOT_OK_99") && screenText.contains("root@maid-proot")) {
                    break
                }
                Thread.sleep(30L)
            }

            assertTrue(
                "Expected vtfx terminal screen to contain VTFX_PROOT_OK_99 and root@maid-proot prompt, actual:\n$screenText",
                screenText.contains("VTFX_PROOT_OK_99") && screenText.contains("root@maid-proot:"),
            )
            assertTrue(ProotConnectionManager.isConnected)
        } finally {
            controller.close()
            ProotConnectionManager.disconnect()
            ProotConnectionManager.customProcessStarterForTest = null
        }
    }

    @Test
    fun tarArchiveExtractor_extractsTarZstArchiveAndReplacesFallbackSkeletonWhenRealArchiveAvailable() {
        val nativeLibDir = tempFolder.newFolder("native_libs_zst")
        val cacheDir = tempFolder.newFolder("cache_zst")
        val filesDir = tempFolder.newFolder("files_zst")

        File(nativeLibDir, TermuxProotRuntime.PROOT_BINARY_NAME).writeText("elf")
        File(nativeLibDir, TermuxProotRuntime.PROOT_LOADER_BINARY_NAME).writeText("elf")

        val context: Context = object : Application() {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    nativeLibraryDir = nativeLibDir.absolutePath
                }
            }

            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
        }

        // 1. 先在无归档情况下 init -> 生成最小 FHS 骨架 (extractedEntries=0)
        val rootfsDir = TermuxProotRuntime.init(context)
        val markerBefore = File(rootfsDir, TermuxProotRuntime.ROOTFS_INIT_MARKER_FILE_NAME).readText()
        assertTrue(markerBefore.contains("extractedEntries=0"))

        // 2. 构造一个 .tar.zst (Zstandard) 格式的 Debian rootfs 归档并导入（包含桌面宿主机遗留的 127.0.0.53 DNS、旧主机名与 apt 目录）
        val tarZstBytes = buildTestTarZst(
            entries = listOf(
                TarTestEntry.Directory("debian-rootfs/"),
                TarTestEntry.Directory("debian-rootfs/bin/"),
                TarTestEntry.Directory("debian-rootfs/etc/"),
                TarTestEntry.Directory("debian-rootfs/etc/apt/"),
                TarTestEntry.RegularFile(
                    name = "debian-rootfs/etc/debian_version",
                    content = "12.8\n",
                ),
                TarTestEntry.RegularFile(
                    name = "debian-rootfs/etc/hostname",
                    content = "cachyos-ZephyrusG16\n",
                ),
                TarTestEntry.RegularFile(
                    name = "debian-rootfs/etc/resolv.conf",
                    content = "# Generated by NetworkManager\nnameserver 127.0.0.53\noptions edns0 trust-ad\n",
                ),
                TarTestEntry.RegularFile(
                    name = "debian-rootfs/bin/debian-hello",
                    content = "#!/bin/sh\necho hello_debian_zst\n",
                    mode = 0b111_101_101,
                ),
            ),
        )
        // 验证 Zstandard 魔数 0x28 0xB5 0x2F 0xFD
        assertEquals(0x28, tarZstBytes[0].toInt() and 0xFF)
        assertEquals(0xB5, tarZstBytes[1].toInt() and 0xFF)
        assertEquals(0x2F, tarZstBytes[2].toInt() and 0xFF)
        assertEquals(0xFD, tarZstBytes[3].toInt() and 0xFF)

        // 导入自定义 .tar.zst（不立即 reset，验证后续 ensureRootfsInitialized 也能自动替换旧骨架并执行系统配置）
        val status = TermuxProotRuntime.importCustomRootfsArchive(
            context = context,
            inputStream = ByteArrayInputStream(tarZstBytes),
            displayName = "debian-rootfs.tar.zst",
            applyImmediately = false,
        )
        assertTrue(status.hasCustomArchive)
        assertEquals("debian-rootfs.tar.zst", status.customArchiveName)

        // 3. 调用 ensureRootfsInitialized -> 发现原先只是最小骨架且现在已有真实 .tar.zst 归档，自动解压并配置系统
        TermuxProotRuntime.ensureRootfsInitialized(context)
        val debianVersionFile = File(rootfsDir, "etc/debian_version")
        val debianHelloFile = File(rootfsDir, "bin/debian-hello")
        assertTrue(debianVersionFile.isFile)
        assertEquals("12.8", debianVersionFile.readText().trim())
        assertTrue(debianHelloFile.isFile)
        assertTrue(debianHelloFile.canExecute())

        // 4. 验证自动系统配置已生效（而非仅单纯解压原归档内容）
        val resolvConfText = File(rootfsDir, "etc/resolv.conf").readText()
        assertFalse(resolvConfText.contains("127.0.0.53"))
        assertTrue(resolvConfText.contains("nameserver 8.8.8.8"))
        assertTrue(resolvConfText.contains("nameserver 1.1.1.1"))

        assertEquals("maid-proot", File(rootfsDir, "etc/hostname").readText().trim())
        val hostsText = File(rootfsDir, "etc/hosts").readText()
        assertTrue(hostsText.contains("127.0.0.1\tlocalhost maid-proot"))

        val groupText = File(rootfsDir, "etc/group").readText()
        assertTrue(groupText.contains("aid_inet:x:3003:root,_apt"))
        assertTrue(groupText.contains("aid_net_raw:x:3004:root,_apt"))

        val aptProotConf = File(rootfsDir, "etc/apt/apt.conf.d/99maid-proot")
        assertTrue(aptProotConf.isFile)
        assertTrue(aptProotConf.readText().contains("APT::Sandbox::User \"root\";"))
        assertTrue(aptProotConf.readText().contains("APT::Sandbox::Seccomp \"false\";"))

        val policyRcFile = File(rootfsDir, "usr/sbin/policy-rc.d")
        assertTrue(policyRcFile.isFile)
        assertTrue(policyRcFile.canExecute())
        assertTrue(policyRcFile.readText().contains("exit 101"))

        val maidProfileScript = File(rootfsDir, "etc/profile.d/maid-proot.sh")
        assertTrue(maidProfileScript.isFile)
        assertTrue(maidProfileScript.readText().contains("DEBIAN_FRONTEND=\"noninteractive\""))

        val rootBashrc = File(rootfsDir, "root/.bashrc")
        assertTrue(rootBashrc.isFile)
        assertTrue(rootBashrc.readText().contains("/etc/profile.d/maid-proot.sh"))

        val markerAfter = File(rootfsDir, TermuxProotRuntime.ROOTFS_INIT_MARKER_FILE_NAME).readText()
        assertTrue(markerAfter.contains("systemConfigured=true"))
    }

    private sealed interface TarTestEntry {
        val name: String

        data class Directory(override val name: String) : TarTestEntry
        data class RegularFile(
            override val name: String,
            val content: String,
            val mode: Int = 0b110_100_100,
        ) : TarTestEntry
    }

    private fun buildTestTarBytes(entries: List<TarTestEntry>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for (entry in entries) {
            when (entry) {
                is TarTestEntry.Directory -> {
                    val dirName = if (entry.name.endsWith("/")) entry.name else "${entry.name}/"
                    tarOut.write(
                        buildTarHeaderBlock(
                            name = dirName,
                            mode = 0b111_101_101,
                            size = 0L,
                            typeFlag = '5',
                        ),
                    )
                }

                is TarTestEntry.RegularFile -> {
                    val payload = entry.content.toByteArray(StandardCharsets.UTF_8)
                    tarOut.write(
                        buildTarHeaderBlock(
                            name = entry.name,
                            mode = entry.mode,
                            size = payload.size.toLong(),
                            typeFlag = '0',
                        ),
                    )
                    tarOut.write(payload)
                    val rem = payload.size % 512
                    if (rem != 0) {
                        tarOut.write(ByteArray(512 - rem))
                    }
                }
            }
        }
        // Two 512-byte zero blocks at EOF
        tarOut.write(ByteArray(1024))
        return tarOut.toByteArray()
    }

    private fun buildTestTarGz(entries: List<TarTestEntry>): ByteArray {
        val tarBytes = buildTestTarBytes(entries)
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { gzip ->
            gzip.write(tarBytes)
        }
        return gzOut.toByteArray()
    }

    private fun buildTestTarZst(entries: List<TarTestEntry>): ByteArray {
        val tarBytes = buildTestTarBytes(entries)
        val zstOut = ByteArrayOutputStream()
        ZstdOutputStream(zstOut).use { zstd ->
            zstd.write(tarBytes)
        }
        return zstOut.toByteArray()
    }

    private fun buildTarHeaderBlock(
        name: String,
        mode: Int,
        size: Long,
        typeFlag: Char,
    ): ByteArray {
        val block = ByteArray(512)
        writeAscii(block, 0, 100, name)
        writeOctal(block, 100, 8, mode.toLong())
        writeOctal(block, 108, 8, 0L)
        writeOctal(block, 116, 8, 0L)
        writeOctal(block, 124, 12, size)
        writeOctal(block, 136, 12, 1_700_000_000L)
        // Checksum field filled with spaces before calculation
        for (i in 148 until 156) {
            block[i] = ' '.code.toByte()
        }
        block[156] = typeFlag.code.toByte()
        writeAscii(block, 257, 6, "ustar")
        writeAscii(block, 263, 2, "00")

        var checksum = 0L
        for (b in block) {
            checksum += (b.toInt() and 0xFF)
        }
        writeOctal(block, 148, 7, checksum)
        block[155] = ' '.code.toByte()
        return block
    }

    private fun writeAscii(block: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, block, offset, count)
    }

    private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val octal = value.toString(8).padStart(length - 1, '0')
        writeAscii(block, offset, length - 1, octal)
        block[offset + length - 1] = 0
    }
}
