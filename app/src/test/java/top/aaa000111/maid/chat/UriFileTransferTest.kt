package top.aaa000111.maid.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

class UriFileTransferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun runLocalHostShell(cmd: String) =
        ShellCommandExecutor.executeCommand(arrayOf("sh", "-c", cmd))

    @Test
    fun localToLocal_supportsCopyMoveDirectoryDefaultFileNameAndOverwriteProtection() = runBlocking {
        val root = tempFolder.newFolder("local_transfer")
        val transfer = DefaultUriFileTransfer(
            internalRootProvider = { root },
            explicitDirectFileAccessChecker = { true },
            privilegedShellRunner = null,
        )

        val src = File(root, "src/notes.txt").apply {
            parentFile?.mkdirs()
            writeText("hello uri transfer", StandardCharsets.UTF_8)
        }
        val copyDir = File(root, "copied_dir").apply { mkdirs() }

        // 1. COPY 到目标目录，自动拼 defaultFileName
        val copyRes = transfer.transfer(
            sourceUri = src.toURI().toString(),
            targetUri = copyDir.absolutePath,
            mode = TransferMode.COPY,
            overwrite = false,
            defaultFileName = "renamed_notes.txt",
        )
        assertTrue(copyRes.success)
        assertFalse(copyRes.sourceDeleted)
        assertTrue(src.exists())
        val copiedFile = File(copyDir, "renamed_notes.txt")
        assertTrue(copiedFile.exists())
        assertEquals("hello uri transfer", copiedFile.readText(StandardCharsets.UTF_8))

        // 2. overwrite=false 拦截重复写入
        val blocked = transfer.transfer(
            sourceUri = src.toURI().toString(),
            targetUri = copiedFile.toURI().toString(),
            mode = TransferMode.COPY,
            overwrite = false,
        )
        assertFalse(blocked.success)
        assertTrue(blocked.message.contains("Target already exists"))

        // 3. MOVE 到新路径，源文件被移走
        val movedTarget = File(root, "moved/final.txt")
        val moveRes = transfer.transfer(
            sourceUri = src.toURI().toString(),
            targetUri = movedTarget.toURI().toString(),
            mode = TransferMode.MOVE,
            overwrite = true,
        )
        assertTrue(moveRes.success)
        assertTrue(moveRes.sourceDeleted)
        assertFalse(src.exists())
        assertEquals("hello uri transfer", movedTarget.readText(StandardCharsets.UTF_8))

        // 4. 源与目标相同路径时安全返回且不删除文件
        val sameRes = transfer.transfer(
            sourceUri = movedTarget.toURI().toString(),
            targetUri = movedTarget.absolutePath,
            mode = TransferMode.MOVE,
            overwrite = true,
        )
        assertTrue(sameRes.success)
        assertFalse(sameRes.sourceDeleted)
        assertTrue(movedTarget.exists())
    }

    @Test
    fun dataUriToLocal_andCrossBackendPrivilegedTransfers_routeByEndpoint() = runBlocking {
        val internalRoot = tempFolder.newFolder("internal_root")
        val externalRoot = tempFolder.newFolder("external_root")

        val transfer = DefaultUriFileTransfer(
            internalRootProvider = { internalRoot },
            explicitDirectFileAccessChecker = { false },
            privilegedShellRunner = { cmd -> runLocalHostShell(cmd) },
        )

        // 1. 验证端点解析：内部目录走 LocalFile，外部目录走 PrivilegedFile
        val internalFile = File(internalRoot, "s1/file_1")
        val externalFile = File(externalRoot, "incoming/report.txt").apply {
            parentFile?.mkdirs()
            writeText("from privileged external storage", StandardCharsets.UTF_8)
        }
        assertTrue(transfer.resolveEndpoint(internalFile.toURI().toString()) is ResolvedUriEndpoint.LocalFile)
        assertTrue(transfer.resolveEndpoint(externalFile.toURI().toString()) is ResolvedUriEndpoint.PrivilegedFile)
        assertTrue(transfer.resolveEndpoint("content://docs/1") is ResolvedUriEndpoint.ContentUri)
        assertTrue(transfer.resolveEndpoint("data:text/plain;base64,aGk=") is ResolvedUriEndpoint.DataUri)

        // 2. 跨后端 MOVE：PrivilegedFile -> LocalFile（自动读出、本地原子写入、特权删源）
        val moveIn = transfer.transfer(
            sourceUri = externalFile.toURI().toString(),
            targetUri = internalFile.toURI().toString(),
            mode = TransferMode.MOVE,
            overwrite = true,
        )
        assertTrue(moveIn.success)
        assertTrue(moveIn.sourceDeleted)
        assertFalse(externalFile.exists())
        assertEquals("from privileged external storage", internalFile.readText(StandardCharsets.UTF_8))

        // 3. 跨后端 COPY：LocalFile -> PrivilegedFile（目录自动拼接 defaultFileName）
        val exportDir = File(externalRoot, "exports")
        val exportOut = transfer.transfer(
            sourceUri = internalFile.toURI().toString(),
            targetUri = "${exportDir.absolutePath}/",
            mode = TransferMode.COPY,
            overwrite = false,
            defaultFileName = "季度汇总.txt",
        )
        assertTrue(exportOut.success)
        assertFalse(exportOut.sourceDeleted)
        val exportedFile = File(exportDir, "季度汇总.txt")
        assertTrue(exportedFile.exists())
        assertEquals("from privileged external storage", exportedFile.readText(StandardCharsets.UTF_8))

        // 4. 同后端快路径：PrivilegedFile -> PrivilegedFile (MOVE)
        val renamedExternal = File(externalRoot, "exports/renamed.txt")
        val privMove = transfer.transfer(
            sourceUri = exportedFile.absolutePath,
            targetUri = renamedExternal.absolutePath,
            mode = TransferMode.MOVE,
            overwrite = true,
        )
        assertTrue(privMove.success)
        assertTrue(privMove.sourceDeleted)
        assertFalse(exportedFile.exists())
        assertEquals("from privileged external storage", renamedExternal.readText(StandardCharsets.UTF_8))

        // 5. data: URI -> LocalFile
        val payload = Base64.getEncoder().encodeToString("data-uri-content".toByteArray(StandardCharsets.UTF_8))
        val dataDest = File(internalRoot, "s1/data_1")
        val dataRes = transfer.transfer(
            sourceUri = "data:text/plain;base64,$payload",
            targetUri = dataDest.toURI().toString(),
            mode = TransferMode.COPY,
        )
        assertTrue(dataRes.success)
        assertEquals("data-uri-content", dataDest.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun chatSession_usesInjectedCustomUriFileTransferForMoveAndExport() = runBlocking {
        val internalRoot = tempFolder.newFolder("custom_transfer_internal")
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)
        try {
            val recordedCalls = mutableListOf<Triple<String, String, TransferMode>>()
            val fakeTransfer = object : UriFileTransfer {
                override suspend fun transfer(
                    sourceUri: String,
                    targetUri: String,
                    mode: TransferMode,
                    overwrite: Boolean,
                    defaultFileName: String?,
                ): SessionFileTransferResult {
                    recordedCalls += Triple(sourceUri, targetUri, mode)
                    val destFile = SessionAttachmentStorage.resolveLocalFileOrNull(targetUri)
                    if (destFile != null) {
                        val finalFile = if (targetUri.endsWith("/") && !defaultFileName.isNullOrBlank()) {
                            File(destFile, defaultFileName)
                        } else {
                            destFile
                        }
                        finalFile.parentFile?.mkdirs()
                        finalFile.writeText("transferred-by-fake", StandardCharsets.UTF_8)
                        return SessionFileTransferResult(
                            success = true,
                            targetPath = finalFile.absolutePath,
                            targetUri = finalFile.toURI().toString(),
                            bytesTransferred = finalFile.length(),
                            sourceDeleted = mode == TransferMode.MOVE,
                        )
                    }
                    return SessionFileTransferResult(
                        success = true,
                        targetPath = targetUri,
                        targetUri = targetUri,
                        bytesTransferred = 19L,
                        sourceDeleted = mode == TransferMode.MOVE,
                    )
                }
            }

            val session = ChatSession(id = "custom_transfer_session")
            val att = session.moveFileToSession(
                uri = "custom://vault/archive.zip",
                description = "自定义协议归档",
                fileTransfer = fakeTransfer,
            )
            assertEquals("file_1", att.id)
            assertEquals("archive.zip", att.fileName)
            assertEquals("自定义协议归档", att.description)

            val exportRes = session.exportFileFromSession(
                attachmentId = "file_1",
                targetUri = "custom://remote/outbox/",
                fileTransfer = fakeTransfer,
            )
            assertTrue(exportRes.success)

            assertEquals(2, recordedCalls.size)
            assertEquals(TransferMode.MOVE, recordedCalls[0].third)
            assertEquals("custom://vault/archive.zip", recordedCalls[0].first)
            assertEquals(TransferMode.COPY, recordedCalls[1].third)
            assertEquals("custom://remote/outbox/", recordedCalls[1].second)
        } finally {
            SessionAttachmentStorage.setCustomInternalRootDirForTest(null)
        }
    }
}
