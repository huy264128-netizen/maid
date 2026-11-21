package top.aaa000111.maid.proot

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局单一 rootfs (`<filesDir>/rootfs`) 的初始化来源与状态概览。
 */
data class RootfsArchiveStatus(
    val rootfsDirPath: String = "",
    val isInitialized: Boolean = false,
    val hasCustomArchive: Boolean = false,
    val customArchiveName: String? = null,
    val customArchiveByteSize: Long = 0L,
    val hasBundledAssetArchive: Boolean = false,
    val bundledAssetPath: String? = null,
    val activeSourceLabel: String = "",
    val lastInitializedAtMillis: Long? = null,
)

/**
 * 随项目一起编译并打包进 APK `nativeLibraryDir` 的 Termux PRoot 运行时封装。
 *
 * 编译产物（见 `app/src/main/cpp/CMakeLists.txt`）：
 * - `libproot.so`：Termux PRoot 主可执行程序（PIE executable，内置静态 `libtalloc`）
 * - `libproot_loader.so`：PRoot freestanding loader 可执行程序
 *
 * 全局单一 rootfs 约定与归档初始化源：
 * - 程序固定使用单一 rootfs 目录：`<context.filesDir>/rootfs`（见 [DEFAULT_ROOTFS_DIR_NAME] / [getRootfsDir] / [ensureRootfsDir]）。
 * - 启动自动初始化（[init] -> [ensureRootfsInitialized]）与重置（[resetRootfs]）时的初始文件来源优先级：
 *   1. 用户在设置中导入/设置的自定义 rootfs 归档文件（持久化保存在 `<filesDir>/rootfs_init/custom_rootfs.tar.gz`，见 [importCustomRootfsArchive]）；
 *   2. APK `assets/` 中打包的默认 rootfs 归档文件（按 [BUNDLED_ROOTFS_ASSET_CANDIDATES] 顺序查找，默认 `assets/rootfs.tar.gz`）；
 *   3. 若归档内缺失基础 `/etc` 配置或未提供归档，自动补齐最小 FHS 目录骨架与 `/etc` 基础配置。
 */
object TermuxProotRuntime {

    const val PROOT_BINARY_NAME = "libproot.so"
    const val PROOT_LOADER_BINARY_NAME = "libproot_loader.so"

    /**
     * 全局约定的单一 rootfs 目录名（位于 `context.filesDir/rootfs`）。
     */
    const val DEFAULT_ROOTFS_DIR_NAME = "rootfs"

    /**
     * 存放用户自定义导入的 rootfs 初始化归档文件的内部目录（位于 `context.filesDir/rootfs_init`）。
     */
    const val ROOTFS_INIT_STORAGE_DIR_NAME = "rootfs_init"
    const val CUSTOM_ROOTFS_ARCHIVE_FILE_NAME = "custom_rootfs.tar.gz"
    const val CUSTOM_ROOTFS_META_FILE_NAME = "custom_rootfs.meta"

    /**
     * APK `assets/` 中默认支持的 rootfs 归档文件路径候选列表
     *（优先匹配 `assets/debian-rootfs.tar.zst`、`assets/rootfs.tar.zst`、`assets/rootfs.tar.gz`，同时自动扫描 `assets/` 下任意 `.tar.zst` / `.tzst` / `.tar.gz` / `.tgz` / `.tar` 文件）。
     */
    val BUNDLED_ROOTFS_ASSET_CANDIDATES: List<String> = listOf(
        "debian-rootfs.tar.zst",
        "rootfs.tar.zst",
        "rootfs.tzst",
        "rootfs.tar.gz",
        "rootfs.tgz",
        "rootfs.tar",
        "rootfs/debian-rootfs.tar.zst",
        "rootfs/rootfs.tar.zst",
        "rootfs/rootfs.tzst",
        "rootfs/rootfs.tar.gz",
        "rootfs/rootfs.tgz",
        "rootfs/rootfs.tar",
    )

    private const val PROOT_TMP_DIR_NAME = "proot_tmp"
    const val ROOTFS_INIT_MARKER_FILE_NAME = ".maid_rootfs_initialized"
    internal const val MAID_TINY_CLI_VERSION_MARKER = "# Maid TinyModel CLI v3"

    internal val MAID_TINY_CLI_SCRIPT: String = """
        #!/bin/sh
        $MAID_TINY_CLI_VERSION_MARKER
        set -eu

        if [ -z "${'$'}{MAID_TINY_MODEL_GENERATE_URL:-}" ]; then
          echo "maid-tiny: MAID_TINY_MODEL_GENERATE_URL is not set." >&2
          exit 1
        fi

        SYSTEM_PROMPT=""
        MAX_TOKENS="512"
        TEMPERATURE=""
        RAW_OUTPUT="0"
        HEALTH_ONLY="0"
        READ_STDIN="0"

        while [ ${'$'}# -gt 0 ]; do
          case "${'$'}1" in
            -s|--system)
              shift
              SYSTEM_PROMPT="${'$'}{1:-}"
              ;;
            -m|--max-tokens)
              shift
              MAX_TOKENS="${'$'}{1:-512}"
              ;;
            -t|--temperature)
              shift
              TEMPERATURE="${'$'}{1:-}"
              ;;
            --raw)
              RAW_OUTPUT="1"
              ;;
            --health)
              HEALTH_ONLY="1"
              ;;
            --stdin)
              READ_STDIN="1"
              ;;
            -h|--help)
              echo "Usage: maid-tiny [-s SYSTEM] [-m MAX_TOKENS] [-t TEMP] [--raw] [--health] [--stdin] [PROMPT...]"
              echo "Reads piped stdin when no prompt is given. Use --stdin to combine stdin with prompt arguments."
              exit 0
              ;;
            --)
              shift
              break
              ;;
            -*)
              echo "maid-tiny: unknown option: ${'$'}1" >&2
              exit 2
              ;;
            *)
              break
              ;;
          esac
          shift || true
        done

        if [ "${'$'}HEALTH_ONLY" = "1" ]; then
          HEALTH_URL="${'$'}{MAID_TINY_MODEL_HEALTH_URL:-${'$'}{MAID_TINY_MODEL_BASE_URL%/v1}/health}"
          if command -v python3 >/dev/null 2>&1; then
            exec python3 -c '
        import sys, urllib.request, urllib.error
        req = urllib.request.Request(sys.argv[1])
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                sys.stdout.write(resp.read().decode("utf-8", "replace") + "\n")
        except urllib.error.HTTPError as e:
            sys.stderr.write(e.read().decode("utf-8", "replace") + "\n")
            sys.exit(1)
        ' "${'$'}HEALTH_URL"
          elif command -v curl >/dev/null 2>&1; then
            exec curl -fsS "${'$'}HEALTH_URL"
          else
            echo "maid-tiny: requires python3 or curl." >&2
            exit 1
          fi
        fi

        ARG_PROMPT="${'$'}*"
        STDIN_DATA=""
        # The persistent Agent shell has non-TTY stdin even without a pipeline.
        if [ "${'$'}READ_STDIN" = "1" ] || { [ -z "${'$'}ARG_PROMPT" ] && [ ! -t 0 ]; }; then
          STDIN_DATA="${'$'}(cat)"
        fi

        if [ -n "${'$'}ARG_PROMPT" ] && [ -n "${'$'}STDIN_DATA" ]; then
          FULL_PROMPT="${'$'}{ARG_PROMPT}

        ${'$'}{STDIN_DATA}"
        elif [ -n "${'$'}ARG_PROMPT" ]; then
          FULL_PROMPT="${'$'}ARG_PROMPT"
        else
          FULL_PROMPT="${'$'}STDIN_DATA"
        fi

        if [ -z "${'$'}(printf '%s' "${'$'}FULL_PROMPT" | tr -d '[:space:]')" ]; then
          echo "maid-tiny: prompt is empty. Pass text as arguments or via stdin." >&2
          exit 2
        fi

        if command -v python3 >/dev/null 2>&1; then
          export MAID_TINY_CLI_PROMPT="${'$'}FULL_PROMPT"
          export MAID_TINY_CLI_SYSTEM="${'$'}SYSTEM_PROMPT"
          export MAID_TINY_CLI_MAX_TOKENS="${'$'}MAX_TOKENS"
          export MAID_TINY_CLI_TEMP="${'$'}TEMPERATURE"
          export MAID_TINY_CLI_RAW="${'$'}RAW_OUTPUT"
          exec python3 -c '
        import json, os, sys, urllib.request, urllib.error

        payload = {
            "prompt": os.environ["MAID_TINY_CLI_PROMPT"],
            "max_tokens": int(os.environ.get("MAID_TINY_CLI_MAX_TOKENS") or "512"),
        }
        sys_prompt = os.environ.get("MAID_TINY_CLI_SYSTEM", "")
        if sys_prompt:
            payload["system"] = sys_prompt
        temp = os.environ.get("MAID_TINY_CLI_TEMP", "")
        if temp:
            payload["temperature"] = float(temp)

        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            os.environ["MAID_TINY_MODEL_GENERATE_URL"],
            data=data,
            headers={
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            sys.stderr.write(e.read().decode("utf-8", "replace") + "\n")
            sys.exit(1)
        except Exception as e:
            sys.stderr.write(f"maid-tiny request failed: {e}\n")
            sys.exit(1)

        if os.environ.get("MAID_TINY_CLI_RAW") == "1":
            sys.stdout.write(raw + "\n")
        else:
            parsed = json.loads(raw)
            text = parsed.get("text", "")
            sys.stdout.write(text)
            if text and not text.endswith("\n"):
                sys.stdout.write("\n")
        '
        elif command -v curl >/dev/null 2>&1; then
          json_escape() {
            printf '%s' "${'$'}1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e ':a;N;${'$'}!ba;s/\n/\\n/g' -e 's/\r/\\r/g' -e 's/\t/\\t/g'
          }
          ESC_PROMPT="${'$'}(json_escape "${'$'}FULL_PROMPT")"
          ESC_SYSTEM="${'$'}(json_escape "${'$'}SYSTEM_PROMPT")"
          BODY="{\"prompt\":\"${'$'}ESC_PROMPT\",\"max_tokens\":${'$'}MAX_TOKENS"
          if [ -n "${'$'}ESC_SYSTEM" ]; then
            BODY="${'$'}BODY,\"system\":\"${'$'}ESC_SYSTEM\""
          fi
          if [ -n "${'$'}TEMPERATURE" ]; then
            BODY="${'$'}BODY,\"temperature\":${'$'}TEMPERATURE"
          fi
          BODY="${'$'}BODY}"
          RESP="${'$'}(curl -fsS -X POST \
            -H "Content-Type: application/json" \
            -d "${'$'}BODY" \
            "${'$'}MAID_TINY_MODEL_GENERATE_URL")"
          if [ "${'$'}RAW_OUTPUT" = "1" ]; then
            printf '%s\n' "${'$'}RESP"
          elif command -v jq >/dev/null 2>&1; then
            printf '%s' "${'$'}RESP" | jq -r '.text // empty'
          else
            printf '%s\n' "${'$'}RESP"
          fi
        else
          echo "maid-tiny: requires python3 or curl. You can also call ${'$'}MAID_TINY_MODEL_BASE_URL directly." >&2
          exit 1
        fi
    """.trimIndent() + "\n"

    /**
     * 标准最小 Linux rootfs 目录骨架（FHS）。
     */
    val DEFAULT_ROOTFS_DIRECTORIES: List<String> = listOf(
        "bin",
        "dev",
        "etc",
        "home",
        "lib",
        "lib64",
        "mnt",
        "opt",
        "proc",
        "root",
        "run",
        "sbin",
        "srv",
        "sys",
        "tmp",
        "usr/bin",
        "usr/include",
        "usr/lib",
        "usr/local/bin",
        "usr/local/lib",
        "usr/local/sbin",
        "usr/sbin",
        "usr/share",
        "var/cache",
        "var/lib",
        "var/log",
        "var/tmp",
    )

    @Volatile
    private var initializedRootfsDir: File? = null

    private val rootfsLock = Any()

    private val beforeRootfsResetListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 注册在 [resetRootfs] 清空 rootfs 前触发的回调（例如断开 Agent 专用的独立 PRoot 终端连接）。
     */
    fun registerBeforeRootfsResetListener(listener: () -> Unit): Closeable {
        beforeRootfsResetListeners.add(listener)
        return Closeable {
            beforeRootfsResetListeners.remove(listener)
        }
    }

    /**
     * 全局统一的单一 rootfs 目录 (`<filesDir>/rootfs`)。
     * 在 [init] 后可直接读取；若尚未调用 [init]，请使用 [getRootfsDir] 或 [ensureRootfsDir]。
     */
    val globalRootfsDir: File?
        get() = initializedRootfsDir

    /**
     * 初始化 Termux PRoot 运行时：
     * 确保 `PROOT_TMP_DIR` 已就绪，并在启动时如果尚未存在或未初始化全局单一 rootfs (`<filesDir>/rootfs`)，
     * 自动从已配置的初始化归档文件（自定义归档或 `assets/rootfs.tar.gz`）解压并初始化 rootfs。
     */
    fun init(context: Context): File {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        ensureProotTmpDir(appContext)
        return ensureRootfsInitialized(appContext)
    }

    /**
     * 获取全局约定的单一 rootfs 目录 (`<filesDir>/rootfs`)。
     */
    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, DEFAULT_ROOTFS_DIR_NAME)
    }

    /**
     * 获取并确保全局单一 rootfs 目录 (`<filesDir>/rootfs`) 已创建。
     */
    fun ensureRootfsDir(context: Context): File {
        val rootfsDir = getRootfsDir(context)
        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs()
        }
        initializedRootfsDir = rootfsDir
        return rootfsDir
    }

    /**
     * 获取存放自定义 rootfs 归档文件的内部目录 (`<filesDir>/rootfs_init`)。
     */
    fun getRootfsInitStorageDir(context: Context): File {
        return File(context.filesDir, ROOTFS_INIT_STORAGE_DIR_NAME)
    }

    /**
     * 获取用户在设置中导入的自定义 rootfs 归档文件 (`<filesDir>/rootfs_init/custom_rootfs.tar.gz`)。
     */
    fun getCustomRootfsArchiveFile(context: Context): File {
        return File(getRootfsInitStorageDir(context), CUSTOM_ROOTFS_ARCHIVE_FILE_NAME)
    }

    private fun getCustomRootfsMetaFile(context: Context): File {
        return File(getRootfsInitStorageDir(context), CUSTOM_ROOTFS_META_FILE_NAME)
    }

    /**
     * 检查用户是否已导入自定义 rootfs 初始化归档文件。
     */
    fun hasCustomRootfsArchive(context: Context): Boolean {
        val file = getCustomRootfsArchiveFile(context)
        return file.isFile && file.length() > 0L
    }

    /**
     * 获取用户导入的自定义 rootfs 归档原始文件名（如 `alpine-minirootfs-aarch64.tar.gz`）。
     */
    fun getCustomRootfsArchiveDisplayName(context: Context): String? {
        if (!hasCustomRootfsArchive(context)) return null
        val metaFile = getCustomRootfsMetaFile(context)
        val savedName = if (metaFile.isFile) {
            runCatching { metaFile.readText(Charsets.UTF_8).lineSequence().firstOrNull()?.trim() }.getOrNull()
        } else {
            null
        }
        return savedName?.takeIf { it.isNotEmpty() } ?: CUSTOM_ROOTFS_ARCHIVE_FILE_NAME
    }

    /**
     * 查找 APK `assets/` 中打包的默认 rootfs 归档路径（例如 `debian-rootfs.tar.zst` 或 `rootfs.tar.gz`）。
     * 优先按 [BUNDLED_ROOTFS_ASSET_CANDIDATES] 匹配，其次动态扫描 `assets/` 根目录与 `assets/rootfs/` 下的 `.tar.zst` / `.tzst` / `.tar.gz` / `.tgz` / `.tar` 归档文件。
     */
    fun findBundledRootfsAssetPath(context: Context): String? {
        val assetManager = runCatching { context.assets }.getOrNull() ?: return null
        for (candidate in BUNDLED_ROOTFS_ASSET_CANDIDATES) {
            val exists = runCatching {
                assetManager.open(candidate).use { stream ->
                    stream.read() >= 0
                }
            }.getOrDefault(false)
            if (exists) {
                return candidate
            }
        }
        val supportedSuffixes = listOf(".tar.zst", ".tzst", ".tar.gz", ".tgz", ".tar")
        for (dir in listOf("", "rootfs")) {
            val entries = runCatching { assetManager.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
            val matched = entries.firstOrNull { name ->
                val lower = name.lowercase()
                supportedSuffixes.any { lower.endsWith(it) }
            }
            if (matched != null) {
                return if (dir.isEmpty()) matched else "$dir/$matched"
            }
        }
        return null
    }

    /**
     * 查询当前 rootfs 目录与初始化归档源的状态信息，供设置界面展示。
     */
    fun getRootfsArchiveStatus(context: Context): RootfsArchiveStatus {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val rootfsDir = getRootfsDir(appContext)
        val isInit = isRootfsInitialized(appContext)
        val customFile = getCustomRootfsArchiveFile(appContext)
        val hasCustom = customFile.isFile && customFile.length() > 0L
        val customName = getCustomRootfsArchiveDisplayName(appContext)
        val bundledPath = findBundledRootfsAssetPath(appContext)
        val hasBundled = bundledPath != null

        val markerFile = File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME)
        val markerProps = if (markerFile.isFile) {
            runCatching {
                markerFile.readLines(Charsets.UTF_8)
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val activeSource = markerProps["source"] ?: when {
            hasCustom -> "自定义归档 (${customName ?: CUSTOM_ROOTFS_ARCHIVE_FILE_NAME})"
            hasBundled -> "内置归档 (assets/$bundledPath)"
            else -> "内置最小 FHS 骨架"
        }
        val lastInitMillis = markerProps["initializedAtMillis"]?.toLongOrNull()

        return RootfsArchiveStatus(
            rootfsDirPath = rootfsDir.absolutePath,
            isInitialized = isInit,
            hasCustomArchive = hasCustom,
            customArchiveName = customName,
            customArchiveByteSize = if (hasCustom) customFile.length() else 0L,
            hasBundledAssetArchive = hasBundled,
            bundledAssetPath = bundledPath,
            activeSourceLabel = activeSource,
            lastInitializedAtMillis = lastInitMillis,
        )
    }

    /**
     * 检查全局单一 rootfs (`<filesDir>/rootfs`) 是否已部署或完成初始化（目录存在且非空）。
     */
    fun isRootfsPopulated(context: Context): Boolean {
        val rootfsDir = getRootfsDir(context)
        return rootfsDir.isDirectory && !rootfsDir.list().isNullOrEmpty()
    }

    /**
     * 检查全局单一 rootfs (`<filesDir>/rootfs`) 是否已完成基础结构初始化。
     */
    fun isRootfsInitialized(context: Context): Boolean {
        val rootfsDir = getRootfsDir(context)
        if (!rootfsDir.isDirectory) return false
        val marker = File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME)
        return marker.isFile || (
            File(rootfsDir, "etc").isDirectory &&
                File(rootfsDir, "root").isDirectory &&
                File(rootfsDir, "bin").isDirectory
            )
    }

    /**
     * 若全局单一 rootfs (`<filesDir>/rootfs`) 不存在、为空、尚未初始化，
     * 或此前仅回退生成了内置最小 FHS 骨架而当前已存在有效归档源（自定义归档或 `assets/debian-rootfs.tar.zst` 等），
     * 自动从已配置的 rootfs 归档解压并初始化。
     */
    fun ensureRootfsInitialized(context: Context): File = synchronized(rootfsLock) {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val rootfsDir = ensureRootfsDir(appContext)
        val shouldExtract = !isRootfsPopulated(appContext) ||
            !isRootfsInitialized(appContext) ||
            isFallbackSkeletonPendingRealArchive(appContext, rootfsDir)
        if (shouldExtract) {
            if (isFallbackSkeletonPendingRealArchive(appContext, rootfsDir)) {
                clearDirectoryContents(rootfsDir)
            }
            extractAndInitializeRootfsLocked(appContext, rootfsDir)
        } else if (!isRootfsSystemConfigured(rootfsDir)) {
            configureExistingRootfsSystemLocked(rootfsDir)
        }
        ensureMaidTinyCliInstalledLocked(rootfsDir)
        initializedRootfsDir = rootfsDir
        return rootfsDir
    }

    private fun isRootfsSystemConfigured(rootfsDir: File): Boolean {
        val markerFile = File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME)
        if (!markerFile.isFile) return false
        val markerText = runCatching { markerFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (!markerText.contains("systemConfigured=true")) return false
        val rootBashrc = File(rootfsDir, "root/.bashrc")
        val bashrcText = readRegularFileOrNull(rootBashrc).orEmpty()
        return bashrcText.contains("# Maid PRoot auto-configuration v3")
    }

    private fun configureExistingRootfsSystemLocked(rootfsDir: File) {
        val markerFile = File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME)
        val markerLines = if (markerFile.isFile) {
            runCatching { markerFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val existingSource = markerLines
            .firstOrNull { it.startsWith("source=") }
            ?.removePrefix("source=")
            ?.takeIf { it.isNotBlank() }
            ?: "既有已解压 rootfs"
        val existingEntries = markerLines
            .firstOrNull { it.startsWith("extractedEntries=") }
            ?.removePrefix("extractedEntries=")
            ?.toIntOrNull()
            ?: -1
        ensureBaseFhsAndEtcConfigLocked(
            rootfsDir = rootfsDir,
            sourceDescription = existingSource,
            extractedEntries = existingEntries,
        )
    }

    private fun isFallbackSkeletonPendingRealArchive(context: Context, rootfsDir: File): Boolean {
        val markerFile = File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME)
        if (!markerFile.isFile) return false
        val markerText = runCatching { markerFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val wasSkeletonOnly = markerText.contains("extractedEntries=0") ||
            markerText.contains("source=内置最小 FHS 骨架")
        if (!wasSkeletonOnly) return false
        return hasCustomRootfsArchive(context) || findBundledRootfsAssetPath(context) != null
    }

    /**
     * 重置全局单一 rootfs (`<filesDir>/rootfs`)：
     * 先自动断开当前持有的持久 [ProotConnection] 进程连接，再清空 `<filesDir>/rootfs` 下的所有文件与子目录
     *（同时清理 `PROOT_TMP_DIR` 临时目录），并重新从当前生效的初始化归档文件（自定义归档或 `assets/rootfs.tar.gz`）解压初始化一个干净的 rootfs。
     */
    fun resetRootfs(context: Context): File = synchronized(rootfsLock) {
        ProotConnectionManager.disconnect()
        for (listener in beforeRootfsResetListeners) {
            runCatching { listener() }
        }
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val rootfsDir = getRootfsDir(appContext)
        clearDirectoryContents(rootfsDir)
        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs()
        }

        val tmpDir = File(appContext.cacheDir, PROOT_TMP_DIR_NAME)
        clearDirectoryContents(tmpDir)
        ensureProotTmpDir(appContext)

        extractAndInitializeRootfsLocked(appContext, rootfsDir)
        initializedRootfsDir = rootfsDir
        return rootfsDir
    }

    /**
     * 从用户在设置中选择的本地归档文件 URI（`content://` 或 `file://` 或本地路径，支持 `.tar.gz` / `.tgz` / `.tar`）
     * 导入为全局自定义 rootfs 初始化归档（保存到 `<filesDir>/rootfs_init/custom_rootfs.tar.gz`），
     * 并在 [applyImmediately] 为 `true` 时立即重置并解压到 `<filesDir>/rootfs`。
     */
    fun importCustomRootfsArchive(
        context: Context,
        archiveUri: String,
        displayName: String? = null,
        applyImmediately: Boolean = true,
    ): RootfsArchiveStatus = synchronized(rootfsLock) {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val resolvedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveArchiveDisplayName(appContext, archiveUri)

        openArchiveInputStream(appContext, archiveUri).use { input ->
            return importCustomRootfsArchiveFromStreamLocked(
                context = appContext,
                inputStream = input,
                displayName = resolvedName,
                applyImmediately = applyImmediately,
            )
        }
    }

    /**
     * 从输入流导入自定义 rootfs 初始化归档（支持 `.tar.gz` / `.tgz` / `.tar`），
     * 先在临时目录校验归档可正常解压，校验通过后保存至 `<filesDir>/rootfs_init/custom_rootfs.tar.gz`，
     * 并在 [applyImmediately] 为 `true` 时立即重置并应用到 `<filesDir>/rootfs`。
     */
    fun importCustomRootfsArchive(
        context: Context,
        inputStream: InputStream,
        displayName: String = CUSTOM_ROOTFS_ARCHIVE_FILE_NAME,
        applyImmediately: Boolean = true,
    ): RootfsArchiveStatus = synchronized(rootfsLock) {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        return importCustomRootfsArchiveFromStreamLocked(
            context = appContext,
            inputStream = inputStream,
            displayName = displayName,
            applyImmediately = applyImmediately,
        )
    }

    /**
     * 清除用户导入的自定义 rootfs 初始化归档，恢复使用内置 `assets/rootfs.tar.gz` 作为初始化源；
     * 若 [resetImmediately] 为 `true`，同时立即重置 `<filesDir>/rootfs`。
     */
    fun clearCustomRootfsArchive(
        context: Context,
        resetImmediately: Boolean = false,
    ): RootfsArchiveStatus = synchronized(rootfsLock) {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching { getCustomRootfsArchiveFile(appContext).delete() }
        runCatching { getCustomRootfsMetaFile(appContext).delete() }
        if (resetImmediately) {
            resetRootfs(appContext)
        }
        return getRootfsArchiveStatus(appContext)
    }

    private fun importCustomRootfsArchiveFromStreamLocked(
        context: Context,
        inputStream: InputStream,
        displayName: String,
        applyImmediately: Boolean,
    ): RootfsArchiveStatus {
        val storageDir = getRootfsInitStorageDir(context).apply { mkdirs() }
        val stagingFile = File(storageDir, "$CUSTOM_ROOTFS_ARCHIVE_FILE_NAME.staging")
        val targetArchiveFile = getCustomRootfsArchiveFile(context)
        val metaFile = getCustomRootfsMetaFile(context)

        try {
            stagingFile.outputStream().use { output ->
                inputStream.copyTo(output, bufferSize = 64 * 1024)
            }
            if (!stagingFile.isFile || stagingFile.length() <= 0L) {
                throw IllegalArgumentException("导入的 rootfs 归档文件为空")
            }

            // 预校验归档格式（验证至少包含一个有效 tar 条目）
            val verifyDir = File(context.cacheDir, "rootfs_verify_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val stats = FileInputStream(stagingFile).use { verifyIn ->
                    TarArchiveExtractor.extractTarOrTarGz(verifyIn, verifyDir)
                }
                if (stats.totalEntries <= 0) {
                    throw IllegalArgumentException("所选文件不是有效的 tar / tar.gz 归档或归档内容为空")
                }
            } finally {
                verifyDir.deleteRecursively()
            }

            if (targetArchiveFile.exists()) {
                targetArchiveFile.delete()
            }
            if (!stagingFile.renameTo(targetArchiveFile)) {
                stagingFile.copyTo(targetArchiveFile, overwrite = true)
                stagingFile.delete()
            }
            metaFile.writeText("${displayName.trim().ifEmpty { CUSTOM_ROOTFS_ARCHIVE_FILE_NAME }}\n", Charsets.UTF_8)
        } finally {
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
        }

        if (applyImmediately) {
            resetRootfs(context)
        }
        return getRootfsArchiveStatus(context)
    }

    /**
     * 核心解压与初始化流程：
     * 1. 若存在用户导入的自定义归档 `<filesDir>/rootfs_init/custom_rootfs.tar.gz`，优先解压该归档；
     * 2. 否则若 APK `assets/` 中存在打包的 `rootfs.tar.gz`（或候选路径），从 `assets` 解压；
     * 3. 解压后若顶层仅有单一包裹目录则自动展平，随后补齐缺失的 FHS 标准目录与 `/etc` 基础配置文件。
     */
    private fun extractAndInitializeRootfsLocked(context: Context, rootfsDir: File) {
        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs()
        }

        var sourceDescription = "内置最小 FHS 骨架"
        var extractedStats: TarArchiveExtractor.ExtractStats? = null

        val customArchive = getCustomRootfsArchiveFile(context)
        if (customArchive.isFile && customArchive.length() > 0L) {
            val customName = getCustomRootfsArchiveDisplayName(context) ?: CUSTOM_ROOTFS_ARCHIVE_FILE_NAME
            val customResult = runCatching {
                FileInputStream(customArchive).use { input ->
                    TarArchiveExtractor.extractTarOrTarGz(input, rootfsDir)
                }
            }
            if (customResult.isSuccess && (customResult.getOrNull()?.totalEntries ?: 0) > 0) {
                extractedStats = customResult.getOrNull()
                sourceDescription = "自定义归档 ($customName)"
            }
        }

        if (extractedStats == null) {
            val bundledAssetPath = findBundledRootfsAssetPath(context)
            if (bundledAssetPath != null) {
                val assetResult = runCatching {
                    context.assets.open(bundledAssetPath).use { input ->
                        TarArchiveExtractor.extractTarOrTarGz(input, rootfsDir)
                    }
                }
                if (assetResult.isSuccess && (assetResult.getOrNull()?.totalEntries ?: 0) > 0) {
                    extractedStats = assetResult.getOrNull()
                    sourceDescription = "内置归档 (assets/$bundledAssetPath)"
                }
            }
        }

        if (extractedStats != null) {
            TarArchiveExtractor.flattenSingleTopLevelRootfsDirIfNeeded(rootfsDir)
        }

        ensureBaseFhsAndEtcConfigLocked(
            rootfsDir = rootfsDir,
            sourceDescription = sourceDescription,
            extractedEntries = extractedStats?.totalEntries ?: 0,
        )
    }

    /**
     * 在启动生成或重置（`resetRootfs`）解压完 rootfs 归档后，自动对容器系统进行全套 Android PRoot 适配与初始化配置
     *（而非单纯只做文件解压或在文件缺失时才写默认配置）：
     *
     * 1. **标准目录与 Merged-usr 符号链接修复**：
     *    - 创建缺失的标准目录；对于 Debian/Ubuntu 的 `merged-usr` 结构（若 `usr/bin`、`usr/sbin`、`usr/lib` 存在而顶层 `bin`、`sbin`、`lib` 缺失），优先建立对应的顶层符号链接而非空目录。
     * 2. **DNS 网络解析自动修复 (`/etc/resolv.conf`)**：
     *    - 许多 Linux rootfs 归档（如 `debian-rootfs.tar.zst`）自带 `nameserver 127.0.0.53`（指向宿主机 `systemd-resolved`）或者是悬空符号链接，在 Android PRoot 下会彻底导致无法联网解析域名；
     *    - 自动解除 `/etc/resolv.conf` 的符号链接，并检测内容：若无有效公网 DNS 或仅含 `127.0.0.53` / `127.0.0.1`，自动重写为可靠的公共 DNS（`8.8.8.8`、`1.1.1.1`、`223.5.5.5`）。
     * 3. **主机名与本地环回映射 (`/etc/hostname` & `/etc/hosts`)**：
     *    - 统一配置主机名为 `maid-proot`，并写入包含 `127.0.0.1 localhost maid-proot` 与 IPv6 环回的 `/etc/hosts`，避免 `sudo`、`hostname`、`apt` 报 `unable to resolve host` 警告。
     * 4. **Android 内核无 `setgroups`/沙箱兼容的 APT & DPKG 配置 (`/etc/apt/apt.conf.d/99maid-proot`)**：
     *    - Android 普通应用在 PRoot `-0` 下没有真 `CAP_SETGID`/`CAP_SYS_ADMIN`，Debian `apt` 默认切换到 `_apt` 沙箱用户会因 `setgroups` / `seccomp` 失败而无法 `apt update` / `apt install`；
     *    - 自动写入 `/etc/apt/apt.conf.d/99maid-proot`（配置 `APT::Sandbox::User "root";`、禁用 Seccomp Sandbox 等）及 `/usr/sbin/policy-rc.d`（返回 `101` 阻止包安装时尝试启动后台守护服务失败）。
     * 5. **用户、用户组与 Android 常用 GID 映射 (`/etc/passwd` & `/etc/group` & `/etc/nsswitch.conf`)**：
     *    - 确保 `root`、`_apt`、`nobody` 用户存在，并在 `/etc/group` 中补齐 Android 特有的网络与存储权限组（`aid_inet:3003`、`aid_net_raw:3004`、`aid_sdcard_rw:1015`、`aid_everybody:9997`）。
     * 6. **交互式 Shell 环境、Locale 与命令别名 (`/etc/profile.d/maid-proot.sh` & `/root/.bashrc` & `/root/.profile`)**：
     *    - 自动配置 `HOME=/root`、`USER=root`、`TERM=xterm-256color`、`COLORTERM=truecolor`、`LANG=C.UTF-8`、`LC_ALL=C.UTF-8`、`DEBIAN_FRONTEND=noninteractive`、完整的 `PATH` 以及彩色 `PS1` 提示符与 `ls`/`ll` 别名。
     * 7. **运行时权限与临时目录修复**：
     *    - 确保 `/tmp`、`/var/tmp`、`/run`、`/run/lock`、`/var/lib/dpkg`、`/var/cache/apt/archives/partial` 就绪，并确保 `/bin/sh`、`/bin/bash`、`/usr/bin/env` 等核心入口具备可执行权限。
     */
    private fun ensureBaseFhsAndEtcConfigLocked(
        rootfsDir: File,
        sourceDescription: String,
        extractedEntries: Int,
    ) {
        // 1. Merged-usr 顶层符号链接或标准目录补齐
        ensureMergedUsrOrFhsDirectoriesLocked(rootfsDir)

        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }

        // 2. 配置主机名 (/etc/hostname) 与本地环回解析 (/etc/hosts)
        overwriteRegularFile(
            File(etcDir, "hostname"),
            "maid-proot\n",
        )
        configureHostsFileLocked(File(etcDir, "hosts"), hostname = "maid-proot")

        // 3. 配置 DNS (/etc/resolv.conf)：替换不可在 Android 上工作的 127.0.0.53 (systemd-resolved) 或悬空软链
        configureResolvConfLocked(File(etcDir, "resolv.conf"))

        // 4. 配置 /etc/nsswitch.conf，确保 files dns 优先
        writeFileIfMissing(
            File(etcDir, "nsswitch.conf"),
            buildString {
                appendLine("passwd:         files")
                appendLine("group:          files")
                appendLine("shadow:         files")
                appendLine("gshadow:        files")
                appendLine("hosts:          files dns")
                appendLine("networks:       files")
                appendLine("protocols:      db files")
                appendLine("services:       db files")
                appendLine("ethers:         db files")
                appendLine("rpc:            db files")
            },
        )

        // 5. 配置 /etc/passwd 与 /etc/group（补齐 root / _apt / nobody 及 Android GID 3003/3004 等）
        configurePasswdAndGroupLocked(etcDir, rootfsDir)

        // 6. 配置 Debian / Ubuntu APT & DPKG 在 PRoot 环境下的沙箱与服务自启抑制
        configureAptAndDpkgForProotLocked(rootfsDir, etcDir)

        // 7. 配置全局环境变量、Locale、Shell Profile 与 /root/.bashrc
        configureShellAndEnvironmentLocked(rootfsDir, etcDir)

        // 8. 确保 /bin/sh 及核心二进制可执行权限就绪
        ensureCoreShellAndBinariesExecutableLocked(rootfsDir)

        val now = System.currentTimeMillis()
        File(rootfsDir, ROOTFS_INIT_MARKER_FILE_NAME).writeText(
            buildString {
                appendLine("initializedAtMillis=$now")
                appendLine("source=$sourceDescription")
                appendLine("extractedEntries=$extractedEntries")
                appendLine("systemConfigured=true")
            },
            Charsets.UTF_8,
        )
    }

    private fun ensureMergedUsrOrFhsDirectoriesLocked(rootfsDir: File) {
        // 先确保 usr 及其子目录存在
        listOf("usr", "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/sbin", "usr/local/lib", "usr/share")
            .forEach { rel ->
                val d = File(rootfsDir, rel)
                if (!d.exists() && !java.nio.file.Files.isSymbolicLink(d.toPath())) {
                    d.mkdirs()
                }
            }

        // 若顶层 bin / sbin / lib / lib64 尚未存在，而 usr/<name> 已包含文件，优先创建 merged-usr 软链接
        val mergedCandidates = listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib", "lib64" to "usr/lib64")
        for ((topName, usrTarget) in mergedCandidates) {
            val topFile = File(rootfsDir, topName)
            val usrDir = File(rootfsDir, usrTarget)
            if (!topFile.exists() && !java.nio.file.Files.isSymbolicLink(topFile.toPath())) {
                if (usrDir.isDirectory && !usrDir.list().isNullOrEmpty()) {
                    val linked = runCatching {
                        java.nio.file.Files.createSymbolicLink(topFile.toPath(), File(usrTarget).toPath())
                        true
                    }.getOrDefault(false)
                    if (!linked) {
                        topFile.mkdirs()
                    }
                } else {
                    topFile.mkdirs()
                }
            }
        }

        DEFAULT_ROOTFS_DIRECTORIES.forEach { relativePath ->
            val dir = File(rootfsDir, relativePath)
            if (!dir.exists() && !java.nio.file.Files.isSymbolicLink(dir.toPath())) {
                dir.mkdirs()
            }
        }

        // 确保运行时目录与临时目录存在且可写
        listOf("tmp", "var/tmp", "run", "run/lock", "root", "etc/profile.d").forEach { rel ->
            val dir = File(rootfsDir, rel)
            if (!dir.exists() && !java.nio.file.Files.isSymbolicLink(dir.toPath())) {
                dir.mkdirs()
            }
            runCatching {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            }
        }
    }

    private fun configureHostsFileLocked(hostsFile: File, hostname: String) {
        val existing = readRegularFileOrNull(hostsFile).orEmpty()
        if (existing.isBlank() || !existing.contains(hostname)) {
            val content = buildString {
                appendLine("127.0.0.1\tlocalhost $hostname")
                appendLine("::1\t\tlocalhost ip6-localhost ip6-loopback $hostname")
                appendLine("fe00::0\t\tip6-localnet")
                appendLine("ff00::0\t\tip6-mcastprefix")
                appendLine("ff02::1\t\tip6-allnodes")
                appendLine("ff02::2\t\tip6-allrouters")
            }
            overwriteRegularFile(hostsFile, content)
        }
    }

    private fun configureResolvConfLocked(resolvFile: File) {
        val existing = readRegularFileOrNull(resolvFile).orEmpty()
        val activeNameservers = existing.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("nameserver ") }
            .map { it.removePrefix("nameserver").trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // 若无 nameserver 或仅包含本地 127.x.x.x / ::1（如 systemd-resolved 的 127.0.0.53 在 Android 上不可用），强制重写为公共 DNS
        val hasUsableExternalDns = activeNameservers.any { ns ->
            !ns.startsWith("127.") && ns != "::1" && ns != "0.0.0.0"
        }

        if (!hasUsableExternalDns) {
            overwriteRegularFile(
                resolvFile,
                buildString {
                    appendLine("# Automatically configured by Maid TermuxProotRuntime for Android PRoot")
                    appendLine("nameserver 8.8.8.8")
                    appendLine("nameserver 1.1.1.1")
                    appendLine("nameserver 223.5.5.5")
                    appendLine("nameserver 8.8.4.4")
                },
            )
        }
    }

    private fun configurePasswdAndGroupLocked(etcDir: File, rootfsDir: File) {
        val preferredRootShell = if (
            File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
        ) {
            "/bin/bash"
        } else {
            "/bin/sh"
        }

        val passwdFile = File(etcDir, "passwd")
        val existingPasswd = readRegularFileOrNull(passwdFile).orEmpty()
        if (existingPasswd.isBlank()) {
            overwriteRegularFile(
                passwdFile,
                buildString {
                    appendLine("root:x:0:0:root:/root:$preferredRootShell")
                    appendLine("_apt:x:42:65534::/nonexistent:/usr/sbin/nologin")
                    appendLine("nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin")
                },
            )
        } else {
            val lines = existingPasswd.lines().filter { it.isNotBlank() }.toMutableList()
            if (lines.none { it.startsWith("root:") }) {
                lines.add(0, "root:x:0:0:root:/root:$preferredRootShell")
            }
            if (lines.none { it.startsWith("_apt:") }) {
                lines.add("_apt:x:42:65534::/nonexistent:/usr/sbin/nologin")
            }
            if (lines.none { it.startsWith("nobody:") }) {
                lines.add("nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin")
            }
            overwriteRegularFile(passwdFile, lines.joinToString("\n", postfix = "\n"))
        }

        val groupFile = File(etcDir, "group")
        val existingGroup = readRegularFileOrNull(groupFile).orEmpty()
        val groupLines = existingGroup.lines().filter { it.isNotBlank() }.toMutableList()
        val requiredGroups = listOf(
            "root" to "root:x:0:",
            "nogroup" to "nogroup:x:65534:",
            "aid_inet" to "aid_inet:x:3003:root,_apt",
            "aid_net_raw" to "aid_net_raw:x:3004:root,_apt",
            "aid_sdcard_rw" to "aid_sdcard_rw:x:1015:root",
            "aid_everybody" to "aid_everybody:x:9997:root",
        )
        for ((groupName, groupLine) in requiredGroups) {
            if (groupLines.none { it.startsWith("$groupName:") }) {
                groupLines.add(groupLine)
            }
        }
        overwriteRegularFile(groupFile, groupLines.joinToString("\n", postfix = "\n"))
    }

    private fun configureAptAndDpkgForProotLocked(rootfsDir: File, etcDir: File) {
        // 如果系统包含 apt 或 dpkg，自动写入 PRoot 兼容配置
        val hasAptOrDpkg = File(etcDir, "apt").exists() ||
            File(rootfsDir, "usr/bin/apt").exists() ||
            File(rootfsDir, "usr/bin/dpkg").exists() ||
            File(rootfsDir, "var/lib/dpkg").exists()

        if (hasAptOrDpkg) {
            val aptConfDir = File(etcDir, "apt/apt.conf.d").apply { mkdirs() }
            overwriteRegularFile(
                File(aptConfDir, "99maid-proot"),
                buildString {
                    appendLine("// Configured by Maid TermuxProotRuntime for Android PRoot environment")
                    appendLine("APT::Sandbox::User \"root\";")
                    appendLine("APT::Sandbox::Seccomp \"false\";")
                    appendLine("Acquire::Languages \"none\";")
                    appendLine("Dpkg::Options {")
                    appendLine("   \"--force-confdef\";")
                    appendLine("   \"--force-confold\";")
                    appendLine("};")
                },
            )

            // 确保 apt 与 dpkg 缓存/锁目录存在
            listOf(
                "var/cache/apt/archives/partial",
                "var/lib/apt/lists/partial",
                "var/lib/dpkg/info",
                "var/lib/dpkg/updates",
                "var/lib/dpkg/triggers",
                "var/log/apt",
            ).forEach { rel ->
                File(rootfsDir, rel).mkdirs()
            }
            writeFileIfMissing(File(rootfsDir, "var/lib/dpkg/status"), "")
            writeFileIfMissing(File(rootfsDir, "var/lib/dpkg/available"), "")

            // 写入 /usr/sbin/policy-rc.d 阻止 dpkg 安装软件包时在无 systemd/init 的 PRoot 中启动服务报错
            val policyRcFile = File(rootfsDir, "usr/sbin/policy-rc.d")
            overwriteRegularFile(
                policyRcFile,
                "#!/bin/sh\nexit 101\n",
            )
            runCatching { policyRcFile.setExecutable(true, false) }
        }
    }

    private fun configureShellAndEnvironmentLocked(rootfsDir: File, etcDir: File) {
        val defaultPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin"

        // /etc/environment
        overwriteRegularFile(
            File(etcDir, "environment"),
            buildString {
                appendLine("PATH=\"$defaultPath\"")
                appendLine("LANG=\"C.UTF-8\"")
                appendLine("LC_ALL=\"C.UTF-8\"")
                appendLine("TERM=\"xterm-256color\"")
                appendLine("COLORTERM=\"truecolor\"")
                appendLine("DEBIAN_FRONTEND=\"noninteractive\"")
            },
        )

        // /etc/profile.d/maid-proot.sh
        val profileDDir = File(etcDir, "profile.d").apply { mkdirs() }
        val maidProfileScript = File(profileDDir, "maid-proot.sh")
        overwriteRegularFile(
            maidProfileScript,
            buildString {
                appendLine("# Maid PRoot environment configuration")
                appendLine("export HOME=\"\${HOME:-/root}\"")
                appendLine("export USER=\"\${USER:-root}\"")
                appendLine("export LOGNAME=\"\${LOGNAME:-root}\"")
                appendLine("export TERM=\"\${TERM:-xterm-256color}\"")
                appendLine("export COLORTERM=\"\${COLORTERM:-truecolor}\"")
                appendLine("export LANG=\"\${LANG:-C.UTF-8}\"")
                appendLine("export LC_ALL=\"\${LC_ALL:-C.UTF-8}\"")
                appendLine("export DEBIAN_FRONTEND=\"noninteractive\"")
                appendLine("export TMPDIR=\"/tmp\"")
                appendLine("export PATH=\"$defaultPath\"")
                appendLine("alias ll='ls -la --color=auto 2>/dev/null || ls -la'")
                appendLine("alias ls='ls --color=auto 2>/dev/null || ls'")
            },
        )
        runCatching { maidProfileScript.setExecutable(true, false) }

        // 若 /etc/profile 缺失则补齐基础 profile
        writeFileIfMissing(
            File(etcDir, "profile"),
            buildString {
                appendLine("export HOME=/root")
                appendLine("export USER=root")
                appendLine("export TERM=xterm-256color")
                appendLine("export COLORTERM=truecolor")
                appendLine("export LANG=C.UTF-8")
                appendLine("export LC_ALL=C.UTF-8")
                appendLine("export PATH=$defaultPath")
                appendLine("if [ -d /etc/profile.d ]; then")
                appendLine("  for i in /etc/profile.d/*.sh; do")
                appendLine("    if [ -r \"\$i\" ]; then . \"\$i\"; fi")
                appendLine("  done")
                appendLine("fi")
            },
        )

        // 配置 /root/.bashrc 与 /root/.profile，使其在交互式会话中也自动加载 /etc/profile.d/maid-proot.sh
        val rootDir = File(rootfsDir, "root").apply { mkdirs() }
        val rootBashrc = File(rootDir, ".bashrc")
        val existingBashrc = readRegularFileOrNull(rootBashrc).orEmpty()
        val maidPromptConfigMarker = "# Maid PRoot auto-configuration v3"
        if (!existingBashrc.contains(maidPromptConfigMarker)) {
            val cleanedExisting = existingBashrc
                .replace("# Maid PRoot auto-configuration\n[ -r /etc/profile.d/maid-proot.sh ] && . /etc/profile.d/maid-proot.sh\nPS1='\\[\\033[01;32m\\]root@maid-proot\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]# '", "")
                .replace("# Maid PRoot auto-configuration v2\n[ -r /etc/profile.d/maid-proot.sh ] && . /etc/profile.d/maid-proot.sh\nset +o emacs 2>/dev/null || true\nset +o vi 2>/dev/null || true\nPROMPT_COMMAND='if [ -z \"\${MAID_SILENT_PROMPT:-}\" ]; then printf \"\\033[01;32mroot@maid-proot\\033[00m:\\033[01;34m%s\\033[00m# \" \"\${PWD/#\$HOME/~}\"; fi'\nPS1=''", "")
                .trimEnd()
            val updatedBashrc = buildString {
                if (cleanedExisting.isNotBlank()) {
                    appendLine(cleanedExisting)
                    appendLine()
                }
                appendLine(maidPromptConfigMarker)
                appendLine("[ -r /etc/profile.d/maid-proot.sh ] && . /etc/profile.d/maid-proot.sh")
                appendLine("export PS1='\\[\\033[01;32m\\]root@maid-proot\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]# '")
            }
            overwriteRegularFile(rootBashrc, updatedBashrc)
        }

        val rootProfile = File(rootDir, ".profile")
        val existingRootProfile = readRegularFileOrNull(rootProfile).orEmpty()
        if (!existingRootProfile.contains("/etc/profile.d/maid-proot.sh")) {
            val updatedRootProfile = buildString {
                if (existingRootProfile.isNotBlank()) {
                    appendLine(existingRootProfile.trimEnd())
                    appendLine()
                }
                appendLine("[ -r /etc/profile.d/maid-proot.sh ] && . /etc/profile.d/maid-proot.sh")
            }
            overwriteRegularFile(rootProfile, updatedRootProfile)
        }

        ensureMaidTinyCliInstalledLocked(rootfsDir)
    }

    /**
     * 在 PRoot 容器内安装/更新 `/usr/local/bin/maid-tiny` 命令行包装脚本，
     * 允许任意终端或脚本免授权直接调用宿主端侧 TinyModel。
     */
    internal fun ensureMaidTinyCliInstalledLocked(rootfsDir: File) {
        val localBinDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        val cliFile = File(localBinDir, "maid-tiny")
        val existing = readRegularFileOrNull(cliFile).orEmpty()
        if (existing.contains(MAID_TINY_CLI_VERSION_MARKER)) {
            runCatching { cliFile.setExecutable(true, false) }
            return
        }
        overwriteRegularFile(cliFile, MAID_TINY_CLI_SCRIPT)
        runCatching { cliFile.setExecutable(true, false) }
    }

    private fun ensureCoreShellAndBinariesExecutableLocked(rootfsDir: File) {
        val binSh = File(rootfsDir, "bin/sh")
        if (!binSh.exists() && !java.nio.file.Files.isSymbolicLink(binSh.toPath())) {
            val usrBinSh = File(rootfsDir, "usr/bin/sh")
            val usrBinDash = File(rootfsDir, "usr/bin/dash")
            val usrBinBash = File(rootfsDir, "usr/bin/bash")
            val busybox = File(rootfsDir, "bin/busybox")
            val dash = File(rootfsDir, "bin/dash")
            val bash = File(rootfsDir, "bin/bash")
            val hostSh = File("/system/bin/sh")
            val targetShell = when {
                usrBinDash.exists() -> File("/usr/bin/dash")
                usrBinBash.exists() -> File("/usr/bin/bash")
                usrBinSh.exists() -> File("/usr/bin/sh")
                busybox.exists() -> File("/bin/busybox")
                dash.exists() -> File("/bin/dash")
                bash.exists() -> File("/bin/bash")
                hostSh.exists() -> hostSh
                else -> null
            }
            val linked = runCatching {
                binSh.parentFile?.mkdirs()
                if (targetShell != null) {
                    java.nio.file.Files.createSymbolicLink(binSh.toPath(), targetShell.toPath())
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (!linked && !binSh.exists()) {
                runCatching {
                    binSh.parentFile?.mkdirs()
                    binSh.writeText("#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n", Charsets.UTF_8)
                    binSh.setExecutable(true, false)
                }
            }
        }

        // 确保常见核心二进制具有 +x 权限
        listOf(
            "bin",
            "sbin",
            "usr/bin",
            "usr/sbin",
            "usr/local/bin",
            "usr/local/sbin",
        ).forEach { dirRel ->
            val dir = File(rootfsDir, dirRel)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        runCatching { file.setExecutable(true, false) }
                    }
                }
            }
        }
    }

    private fun readRegularFileOrNull(file: File): String? {
        return runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    private fun overwriteRegularFile(file: File, content: String) {
        val path = file.toPath()
        if (java.nio.file.Files.isSymbolicLink(path) || file.exists()) {
            runCatching { java.nio.file.Files.deleteIfExists(path) }
                .onFailure { file.delete() }
        }
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun clearDirectoryContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            deletePathRecursivelyWithoutFollowingSymlinks(child)
        }
    }

    private fun deletePathRecursivelyWithoutFollowingSymlinks(file: File) {
        val path = file.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            runCatching { java.nio.file.Files.deleteIfExists(path) }
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deletePathRecursivelyWithoutFollowingSymlinks(child)
            }
        }
        runCatching { java.nio.file.Files.deleteIfExists(path) }
            .onFailure { file.delete() }
    }

    private fun openArchiveInputStream(context: Context, rawUri: String): InputStream {
        val trimmed = rawUri.trim()
        return when {
            trimmed.startsWith("content://", ignoreCase = true) -> {
                context.contentResolver.openInputStream(Uri.parse(trimmed))
                    ?: throw IllegalArgumentException("无法打开所选归档文件 URI: $trimmed")
            }

            trimmed.startsWith("file://", ignoreCase = true) -> {
                val file = runCatching { File(URI(trimmed)) }.getOrNull()
                    ?: File(Uri.parse(trimmed).path.orEmpty())
                FileInputStream(file)
            }

            else -> FileInputStream(File(trimmed))
        }
    }

    private fun resolveArchiveDisplayName(context: Context, rawUri: String): String {
        val trimmed = rawUri.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (parsed != null) {
                val queried = runCatching {
                    context.contentResolver.query(
                        parsed,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                if (!queried.isNullOrBlank()) return queried.trim()
            }
        }
        val clean = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        return clean.substringAfterLast('/').takeIf { it.isNotBlank() } ?: CUSTOM_ROOTFS_ARCHIVE_FILE_NAME
    }

    private fun writeFileIfMissing(file: File, content: String) {
        val path = file.toPath()
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(path)) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
    }

    /**
     * 获取已安装的 Termux PRoot 主二进制文件 (`<nativeLibraryDir>/libproot.so`)。
     */
    fun getProotExecutable(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return File(nativeDir, PROOT_BINARY_NAME)
    }

    /**
     * 获取已安装的 Termux PRoot Loader 二进制文件 (`<nativeLibraryDir>/libproot_loader.so`)。
     */
    fun getLoaderExecutable(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return File(nativeDir, PROOT_LOADER_BINARY_NAME)
    }

    /**
     * 获取或创建 PRoot 运行所需的临时交换目录 (`PROOT_TMP_DIR`)。
     */
    fun ensureProotTmpDir(context: Context): File {
        val tmpDir = File(context.cacheDir, PROOT_TMP_DIR_NAME)
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
        }
        return tmpDir
    }

    /**
     * 检查编译打包的 `libproot.so` 与 `libproot_loader.so` 是否均已就绪。
     */
    fun isAvailable(context: Context): Boolean {
        val proot = getProotExecutable(context)
        val loader = getLoaderExecutable(context)
        return proot.isFile && loader.isFile
    }

    /**
     * 构建运行 Termux PRoot 所需的标准环境变量：
     * - `PROOT_LOADER`：指向 `libproot_loader.so`
     * - `PROOT_TMP_DIR`：指向应用缓存下的可写临时目录
     * - `PROOT_ROOTFS_DIR`：指向全局单一 rootfs 目录 (`<filesDir>/rootfs`)
     * - `HOME`：默认 `/root`（PRoot 容器内根目录）
     * - `PATH`：标准 Linux rootfs PATH
     * - `PROOT_NO_SECCOMP=1`（可选，在部分受限 Android 内核上可提高兼容性）
     */
    fun buildDefaultEnvironment(
        context: Context,
        disableSeccomp: Boolean = false,
        extraEnv: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val loaderPath = getLoaderExecutable(context).absolutePath
        val tmpDirPath = ensureProotTmpDir(context).absolutePath
        val rootfsPath = ensureRootfsDir(context).absolutePath
        return buildMap {
            put("PROOT_LOADER", loaderPath)
            put("PROOT_TMP_DIR", tmpDirPath)
            put("PROOT_ROOTFS_DIR", rootfsPath)
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin")
            if (disableSeccomp) {
                put("PROOT_NO_SECCOMP", "1")
            }
            // 无条件注入宿主机端侧 TinyModel 本地桥接环境变量（免费离线，不需要条件授权）
            putAll(AppRuntime.getTinyModelLocalBridgeEnv())
            putAll(extraEnv)
        }
    }

    /**
     * 解析当前 rootfs 中最优的可用交互式 Shell 可执行路径（若用户传入默认 `/bin/sh`，但系统内安装了 `/bin/bash` 或 `/usr/bin/bash`，也可以按需解析）。
     */
    fun resolvePreferredInteractiveShell(
        rootfsDir: File,
        requestedShell: String = "/bin/sh",
    ): String {
        if (requestedShell != "/bin/sh") return requestedShell
        return when {
            File(rootfsDir, "bin/bash").exists() || java.nio.file.Files.isSymbolicLink(File(rootfsDir, "bin/bash").toPath()) -> "/bin/bash"
            File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            else -> "/bin/sh"
        }
    }

    /**
     * 构建在全局单一 rootfs (`<filesDir>/rootfs`) 中启动常驻交互式 PRoot Shell 进程的命令行参数数组（供 [ProotConnection] 持久持有）。
     */
    fun buildProotInteractiveShellArgs(
        context: Context,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
    ): List<String> {
        val prootFile = getProotExecutable(context)
        val rootfsDir = ensureRootfsInitialized(context)

        val args = mutableListOf<String>()
        args += prootFile.absolutePath

        if (fakeRootId) args += "-0"
        if (link2Symlink) args += "--link2symlink"
        if (sysvipc) args += "--sysvipc"
        if (ashmemMemfd) args += "--ashmem-memfd"

        // 统一挂载全局单一 rootfs: <filesDir>/rootfs
        args += listOf("-r", rootfsDir.absolutePath)

        // 默认绑定宿主关键伪文件系统
        listOf("/dev", "/proc", "/sys").forEach { sysMount ->
            args += listOf("-b", sysMount)
        }

        bindMounts.forEach { mountSpec ->
            val trimmed = mountSpec.trim()
            if (trimmed.isNotEmpty()) {
                args += listOf("-b", trimmed)
            }
        }

        if (workingDir.isNotBlank()) {
            args += listOf("-w", workingDir.trim())
        }

        val resolvedShell = resolvePreferredInteractiveShell(rootfsDir, shellExecutable)
        args += resolvedShell
        if (resolvedShell.endsWith("/bash") || resolvedShell == "bash") {
            args += listOf("--rcfile", "/root/.bashrc", "-i")
        }
        return args
    }

    /**
     * 构建在全局单一 rootfs (`<filesDir>/rootfs`) 中执行单次命令的 PRoot 命令行参数数组。
     */
    fun buildProotCommandArgs(
        context: Context,
        command: String,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
    ): List<String> {
        val prootFile = getProotExecutable(context)
        val rootfsDir = ensureRootfsInitialized(context)

        val args = mutableListOf<String>()
        args += prootFile.absolutePath

        if (fakeRootId) args += "-0"
        if (link2Symlink) args += "--link2symlink"
        if (sysvipc) args += "--sysvipc"
        if (ashmemMemfd) args += "--ashmem-memfd"

        args += listOf("-r", rootfsDir.absolutePath)

        listOf("/dev", "/proc", "/sys").forEach { sysMount ->
            args += listOf("-b", sysMount)
        }

        bindMounts.forEach { mountSpec ->
            val trimmed = mountSpec.trim()
            if (trimmed.isNotEmpty()) {
                args += listOf("-b", trimmed)
            }
        }

        if (workingDir.isNotBlank()) {
            args += listOf("-w", workingDir.trim())
        }

        args += listOf(shellExecutable, "-c", command)
        return args
    }

    /**
     * 获取或建立全局单一 rootfs (`<filesDir>/rootfs`) 的持久 [ProotConnection] 实例。
     * 真正持有底层 `proot` 进程连接的对象为 [ProotConnection]（由 [ProotConnectionManager] 全局托管）。
     */
    fun acquirePersistentConnection(
        context: Context,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        disableSeccomp: Boolean = false,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
    ): ProotConnection {
        return ProotConnectionManager.acquireConnection(
            context = context,
            bindMounts = bindMounts,
            fakeRootId = fakeRootId,
            link2Symlink = link2Symlink,
            sysvipc = sysvipc,
            ashmemMemfd = ashmemMemfd,
            disableSeccomp = disableSeccomp,
            workingDir = workingDir,
            shellExecutable = shellExecutable,
        )
    }

    /**
     * 断开当前持有的持久 [ProotConnection] 进程连接。
     */
    fun disconnectPersistentConnection() {
        ProotConnectionManager.disconnect()
    }

    /**
     * 在全局单一 rootfs (`<filesDir>/rootfs`) 的 Termux PRoot 环境中执行指定命令。
     *
     * @param context Android Context
     * @param command 要在 PRoot rootfs 内部执行的 Shell 命令
     * @param persistentSession 是否复用 [ProotConnectionManager] 持有的持久 [ProotConnection] 长连接执行（默认 `true`，跨命令保留 `cd` 与环境变量状态）
     * @param bindMounts 额外挂载路径列表（例如 `listOf("/sdcard")`）
     * @param fakeRootId 是否开启 `-0`（伪装 UID/GID 0，默认开启）
     * @param link2Symlink 是否开启 `--link2symlink`（默认开启）
     * @param sysvipc 是否开启 `--sysvipc`（默认开启）
     * @param ashmemMemfd 是否开启 `--ashmem-memfd`（默认开启）
     * @param workingDir PRoot 内部初始工作目录（`-w`，默认 `/root`）
     * @param shellExecutable rootfs 内使用的 Shell（默认 `/bin/sh`）
     * @param timeoutMillis 执行超时毫秒数
     */
    fun executeInProot(
        context: Context,
        command: String,
        persistentSession: Boolean = true,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        disableSeccomp: Boolean = false,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult {
        if (persistentSession) {
            return ProotConnectionManager.execute(
                context = context,
                command = command,
                bindMounts = bindMounts,
                fakeRootId = fakeRootId,
                link2Symlink = link2Symlink,
                sysvipc = sysvipc,
                ashmemMemfd = ashmemMemfd,
                disableSeccomp = disableSeccomp,
                workingDir = workingDir,
                shellExecutable = shellExecutable,
                timeoutMillis = timeoutMillis,
            )
        }

        val prootFile = getProotExecutable(context)
        if (!prootFile.exists()) {
            return ShellCommandResult.failure("PRoot executable not found at ${prootFile.absolutePath}")
        }

        val args = buildProotCommandArgs(
            context = context,
            command = command,
            bindMounts = bindMounts,
            fakeRootId = fakeRootId,
            link2Symlink = link2Symlink,
            sysvipc = sysvipc,
            ashmemMemfd = ashmemMemfd,
            workingDir = workingDir,
            shellExecutable = shellExecutable,
        )

        val mergedEnv = buildMap {
            putAll(System.getenv())
            putAll(buildDefaultEnvironment(context, disableSeccomp = disableSeccomp))
        }.map { (k, v) -> "$k=$v" }.toTypedArray()

        return ShellCommandExecutor.executeCommand(
            command = args.toTypedArray(),
            env = mergedEnv,
            timeoutMillis = timeoutMillis,
        )
    }
}
