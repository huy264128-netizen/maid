package top.aaa000111.maid.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellCommandExecutorTest {

    @Test
    fun executeCommand_capturesStdoutAndExitCode() {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "printf 'hello world'"),
        )
        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout)
        assertEquals("", result.stderr)
        assertFalse(result.timedOut)
    }

    @Test
    fun executeCommand_capturesStderrAndNonZeroExitCode() {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "printf 'out_msg'; printf 'err_msg' >&2; exit 42"),
        )
        assertFalse(result.isSuccess)
        assertEquals(42, result.exitCode)
        assertEquals("out_msg", result.stdout)
        assertEquals("err_msg", result.stderr)
    }

    @Test
    fun executeCommand_writesAndClosesStdinSafely() {
        val input = "line1\nline2\nmaid-shizuku"
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("cat"),
            stdinData = input,
        )
        assertTrue(result.isSuccess)
        assertEquals(input, result.stdout)
    }

    @Test
    fun executeCommand_appliesWorkingDirAndEnv() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "pwd; printf '%s' \"\$MAID_TEST_ENV\""),
            workingDir = tempDir.absolutePath,
            env = arrayOf("MAID_TEST_ENV=ok_123"),
        )
        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("ok_123"))
    }

    @Test
    fun executeCommand_enforcesTimeoutAndKillsProcess() {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "printf 'before_sleep'; sleep 5"),
            timeoutMillis = 200L,
        )
        assertFalse(result.isSuccess)
        assertTrue(result.timedOut)
        assertNotNull(result.errorMessage)
        assertTrue(result.stdout.contains("before_sleep"))
    }

    @Test
    fun executeCommand_truncatesLargeOutputWithoutDeadlock() {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "head -c 4096 /dev/zero | tr '\\0' 'A'"),
            maxOutputBytes = 1024,
        )
        assertTrue(result.isSuccess)
        assertTrue(result.stdoutTruncated)
        assertEquals(1024, result.stdout.length)
    }

    @Test
    fun executeCommand_rejectsEmptyCommandAndMissingDirectory() {
        val emptyResult = ShellCommandExecutor.executeCommand(emptyArray())
        assertFalse(emptyResult.isSuccess)
        assertNotNull(emptyResult.errorMessage)

        val missingDirResult = ShellCommandExecutor.executeCommand(
            command = arrayOf("sh", "-c", "echo hi"),
            workingDir = "/non/existent/path/maid_test_dir",
        )
        assertFalse(missingDirResult.isSuccess)
        assertNotNull(missingDirResult.errorMessage)
    }
}
