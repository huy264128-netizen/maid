package top.aaa000111.maid.runtime

import MaidValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.agent.ScheduledTaskToolSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduledTaskToolSetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixedNow = Instant.parse("2026-11-22T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private lateinit var taskStore: MaidLangTaskStore
    private lateinit var fakeAlarmGateway: FakeAlarmGateway
    private lateinit var fakeWorkGateway: FakeWorkGateway
    private lateinit var toolSet: ScheduledTaskToolSet

    @Before
    fun setUp() {
        MaidLangNativeRegistry.resetToDefaults()
        taskStore = MaidLangTaskStore(
            rootDir = tempFolder.newFolder("maidlang_tasks"),
            privilegesProvider = { MaidLangNativeRegistry.currentAvailablePrivileges() },
            clock = fixedClock,
        )
        fakeAlarmGateway = FakeAlarmGateway()
        fakeWorkGateway = FakeWorkGateway()
        toolSet = ScheduledTaskToolSet(
            taskStore = taskStore,
            alarmGateway = fakeAlarmGateway,
            workGateway = fakeWorkGateway,
            clock = fixedClock,
        )
    }

    @After
    fun tearDown() {
        MaidLangNativeRegistry.resetToDefaults()
    }

    @Test
    fun makeTask_compilesValidMaidLangAndExecutesByTaskId() = runBlocking {
        val output = toolSet.makeTask(
            code = """
                fun add(a, b) {
                    return a + b;
                }
                add(19, 23);
            """.trimIndent(),
            name = "sum_demo",
        )

        assertTrue(output.contains("Compiled MaidLang task successfully."))
        val taskId = extractTaskId(output)
        assertNotNull(taskId)
        assertTrue(taskStore.hasTask(taskId!!))

        val execResult = taskStore.executeTask(taskId)
        assertTrue(execResult is MaidLangExecutionResult.Success)
        assertEquals("42", (execResult as MaidLangExecutionResult.Success).value)
    }

    @Test
    fun makeTask_autoImportsRegisteredNativeAndRespectsExplicitDeclarations() = runBlocking {
        MaidLangNativeRegistry.register(
            name = "testPing",
            paramTypes = listOf(MaidLangNativeType.STRING),
            returnType = MaidLangNativeType.STRING,
            description = "Echo test native",
            toolsetId = null,
            requiredPrivileges = emptySet(),
        ) { args ->
            val input = (args[0] as MaidValue.StringVal).value
            MaidValue.StringVal("pong:$input")
        }

        // 未显式声明 external fun 时自动引入注册表声明
        val implicitOutput = toolSet.makeTask(code = """testPing("auto");""")
        val implicitTaskId = extractTaskId(implicitOutput)
        assertNotNull(implicitTaskId)
        val implicitExec = taskStore.executeTask(implicitTaskId!!)
        assertTrue(implicitExec is MaidLangExecutionResult.Success)
        assertEquals("pong:auto", (implicitExec as MaidLangExecutionResult.Success).value)

        // 已显式声明 external fun 时不重复前置，仍可正常编译执行
        val explicitOutput = toolSet.makeTask(
            code = """
                external fun testPing(string) -> string;
                testPing("explicit");
            """.trimIndent(),
        )
        val explicitTaskId = extractTaskId(explicitOutput)
        assertNotNull(explicitTaskId)
        val explicitExec = taskStore.executeTask(explicitTaskId!!)
        assertTrue(explicitExec is MaidLangExecutionResult.Success)
        assertEquals("pong:explicit", (explicitExec as MaidLangExecutionResult.Success).value)
    }

    @Test
    fun makeTask_returnsErrorAndDoesNotPersistOnInvalidCode() = runBlocking {
        val syntaxErr = toolSet.makeTask(code = "var x = ;")
        assertTrue(syntaxErr.startsWith("Compilation failed"))
        assertTrue(taskStore.listTasks().isEmpty())

        val unknownFnErr = toolSet.makeTask(code = "nonExistentNative(1, 2);")
        assertTrue(unknownFnErr.startsWith("Compilation failed (compiler):"))
        assertTrue(taskStore.listTasks().isEmpty())

        val blankErr = toolSet.makeTask(code = "   ")
        assertTrue(blankErr.startsWith("Compilation failed (validation):"))
        assertTrue(taskStore.listTasks().isEmpty())
    }

    @Test
    fun executeTask_checksToolsetPermissionsAndReportsMissingToolsets() {
        // 参数个数不匹配时编译阶段直接拦截
        val wrongArity = taskStore.makeTask(source = "tap(100, 200);")
        assertTrue(wrongArity is MaidLangCompileResult.Failure)

        // 默认权限组为 NONE，tap 属于 input 工具集（需 SHIZUKU/ACCESSIBILITY/ROOT）
        val compiled = taskStore.makeTask(source = "tap(100.0, 200.0, 0);")
        assertTrue(compiled is MaidLangCompileResult.Success)
        val success = compiled as MaidLangCompileResult.Success
        assertEquals(listOf("input"), success.meta.requiredToolsets)
        assertEquals(1, success.missingToolsets.size)
        assertEquals("input", success.missingToolsets.first().toolsetId)

        val execution = taskStore.executeTask(success.meta.taskId)
        assertTrue(execution is MaidLangExecutionResult.Failure)
        val failure = execution as MaidLangExecutionResult.Failure
        assertTrue(failure.errorMessage.contains("Missing required toolset permissions"))
        assertEquals(listOf("input"), failure.missingToolsets.map { it.toolsetId })
    }

    @Test
    fun executeTask_enforcesInstructionLimitAndRejectsInvalidTaskIds() {
        val compiled = taskStore.makeTask(
            source = """
                var i = 0;
                while (true) {
                    i = i + 1;
                }
            """.trimIndent(),
        )
        assertTrue(compiled is MaidLangCompileResult.Success)
        val taskId = (compiled as MaidLangCompileResult.Success).meta.taskId

        val limited = taskStore.executeTask(taskId, maxInstructions = 500L)
        assertTrue(limited is MaidLangExecutionResult.Failure)

        val missing = taskStore.executeTask("mlt_does_not_exist")
        assertTrue(missing is MaidLangExecutionResult.Failure)
        assertTrue((missing as MaidLangExecutionResult.Failure).errorMessage.contains("not found"))

        val traversal = taskStore.executeTask("../escape")
        assertTrue(traversal is MaidLangExecutionResult.Failure)
        assertTrue((traversal as MaidLangExecutionResult.Failure).errorMessage.contains("Invalid"))
    }

    @Test
    fun scheduledAlarmTools_scheduleSessionAndCompiledScriptAndCancel() = runBlocking {
        // 1. 启动带提示词的 session（相对延迟）
        val alarmSessionMsg = toolSet.scheduleAlarmSession(
            alarmId = "morning_brief",
            prompt = "帮我总结今天的待办事项",
            delaySeconds = 300,
            sessionTitle = "每日晨报",
            allowWhileIdle = true,
        )
        assertTrue(alarmSessionMsg.contains("Scheduled AlarmManager prompt session 'morning_brief'"))
        val recordedSession = fakeAlarmGateway.scheduled["morning_brief"]
        assertNotNull(recordedSession)
        assertEquals(fixedNow.plusSeconds(300), recordedSession!!.triggerAt)
        assertTrue(recordedSession.allowWhileIdle)
        val sessionAction = recordedSession.payload.action as ScheduledTaskAction.StartPromptSession
        assertEquals("帮我总结今天的待办事项", sessionAction.prompt)
        assertEquals("每日晨报", sessionAction.sessionTitle)

        // 2. 安排执行已编译脚本：未知 taskId 应直接报错
        val unknownScriptMsg = toolSet.scheduleAlarmScript(
            alarmId = "bad_script",
            taskId = "mlt_unknown1234",
            delaySeconds = 60,
        )
        assertTrue(unknownScriptMsg.startsWith("Error:"))
        assertNull(fakeAlarmGateway.scheduled["bad_script"])

        // 先 makeTask 编译脚本，再按 taskId + ISO 时间戳安排 Alarm
        val compiledOut = toolSet.makeTask(code = "1 + 2;", name = "quick_calc")
        val taskId = extractTaskId(compiledOut)!!
        val alarmScriptMsg = toolSet.scheduleAlarmScript(
            alarmId = "calc_alarm",
            taskId = taskId,
            triggerAtIso = "2026-11-22T12:30:00Z",
            allowWhileIdle = false,
        )
        assertTrue(alarmScriptMsg.contains("Scheduled AlarmManager compiled script 'calc_alarm'"))
        val recordedScript = fakeAlarmGateway.scheduled["calc_alarm"]
        assertNotNull(recordedScript)
        assertEquals(Instant.parse("2026-11-22T12:30:00Z"), recordedScript!!.triggerAt)
        val scriptAction = recordedScript.payload.action as ScheduledTaskAction.RunCompiledScript
        assertEquals(taskId, scriptAction.taskId)

        // 3. 过去时间 / 同时传两个时间参数应报错
        assertTrue(
            toolSet.scheduleAlarmSession(
                alarmId = "past",
                prompt = "hi",
                triggerAtIso = "2026-11-22T09:00:00Z",
            ).startsWith("Error:"),
        )
        assertTrue(
            toolSet.scheduleAlarmSession(
                alarmId = "both",
                prompt = "hi",
                delaySeconds = 10,
                triggerAtIso = "2026-11-22T11:00:00Z",
            ).startsWith("Error:"),
        )

        // 4. 取消 Alarm
        val cancelMsg = toolSet.cancelAlarmTask("calc_alarm")
        assertEquals("Canceled AlarmManager task 'calc_alarm'.", cancelMsg)
        assertFalse("calc_alarm" in fakeAlarmGateway.scheduled)
        val cancelAgainMsg = toolSet.cancelAlarmTask("calc_alarm")
        assertTrue(cancelAgainMsg.contains("No pending AlarmManager task"))
    }

    @Test
    fun scheduledWorkTools_supportOneTimeAndPeriodicForSessionAndScript() = runBlocking {
        // 1. 周期性 prompt session（合法间隔 >= 15 分钟）
        val periodicSessionMsg = toolSet.scheduleWorkSession(
            workName = "hourly_digest",
            prompt = "检查未读消息并汇总",
            delaySeconds = 120,
            repeatIntervalMinutes = 60,
            sessionTitle = "整点汇总",
            requireNetwork = true,
            replaceExisting = true,
        )
        assertTrue(periodicSessionMsg.contains("Scheduled periodic WorkManager prompt session 'hourly_digest'"))
        val work1 = fakeWorkGateway.requests["hourly_digest"]
        assertNotNull(work1)
        assertEquals(60L, work1!!.repeatIntervalMinutes)
        assertEquals(120L, work1.delaySeconds)
        assertTrue(work1.requireNetwork)
        assertTrue(work1.replaceExisting)
        assertEquals(
            ScheduledTaskAction.StartPromptSession("检查未读消息并汇总", "整点汇总"),
            work1.payload.action,
        )

        // 2. 周期小于 15 分钟应拒绝
        val tooShort = toolSet.scheduleWorkSession(
            workName = "too_frequent",
            prompt = "ping",
            repeatIntervalMinutes = 5,
        )
        assertTrue(tooShort.startsWith("Error:"))
        assertTrue(tooShort.contains("15 minutes"))
        assertNull(fakeWorkGateway.requests["too_frequent"])

        // 3. 一次性 compiled script（先编译获得 taskId）
        val compiledOut = toolSet.makeTask(code = "var a = 6 * 7; a;", name = "meaning")
        val taskId = extractTaskId(compiledOut)!!
        val oneTimeScriptMsg = toolSet.scheduleWorkScript(
            workName = "run_meaning",
            taskId = taskId,
            delaySeconds = 30,
            repeatIntervalMinutes = 0,
            requireNetwork = false,
            replaceExisting = false,
        )
        assertTrue(oneTimeScriptMsg.contains("Scheduled one-time WorkManager compiled script 'run_meaning'"))
        val work2 = fakeWorkGateway.requests["run_meaning"]
        assertNotNull(work2)
        assertEquals(0L, work2!!.repeatIntervalMinutes)
        assertEquals(30L, work2.delaySeconds)
        assertFalse(work2.requireNetwork)
        assertEquals(ScheduledTaskAction.RunCompiledScript(taskId), work2.payload.action)

        // 4. 状态查询与取消
        val statusText = toolSet.getWorkTaskStatus("run_meaning")
        assertTrue(statusText.contains("ENQUEUED"))
        val cancelText = toolSet.cancelWorkTask("run_meaning")
        assertTrue(cancelText.contains("Requested cancellation"))
        assertTrue("run_meaning" in fakeWorkGateway.canceled)
    }

    @Test
    fun executor_runsPromptSessionAndCompiledScriptPayloads() = runBlocking {
        val launchedSessions = mutableListOf<Pair<String, String>>()
        val executor = ScheduledTaskExecutor(
            taskStoreProvider = { taskStore },
            sessionLauncher = { prompt, title ->
                launchedSessions.add(prompt to title)
                ScheduledSessionLaunchResult(
                    sessionId = "sess_1",
                    launched = true,
                    summary = "Launched session sess_1",
                )
            },
        )

        // 1. 执行 prompt session 载荷
        val promptPayload = ScheduledTaskPayload(
            scheduleId = "daily_check",
            action = ScheduledTaskAction.StartPromptSession(
                prompt = "请汇报今日天气",
                sessionTitle = "",
            ),
        )
        val promptRoundTrip = ScheduledTaskPayload.fromKeyValueMap(promptPayload.toKeyValueMap())
        assertEquals(promptPayload, promptRoundTrip)

        val promptOutcome = executor.execute(promptRoundTrip!!)
        assertTrue(promptOutcome is ScheduledExecutionOutcome.Success)
        assertEquals(listOf("请汇报今日天气" to "定时任务: daily_check"), launchedSessions)

        // 2. 执行 compiled script 载荷
        val compiled = taskStore.makeTask(source = "100 + 23;") as MaidLangCompileResult.Success
        val scriptPayload = ScheduledTaskPayload(
            scheduleId = "calc_job",
            action = ScheduledTaskAction.RunCompiledScript(taskId = compiled.meta.taskId),
        )
        val scriptRoundTrip = ScheduledTaskPayload.fromKeyValueMap(scriptPayload.toKeyValueMap())
        assertEquals(scriptPayload, scriptRoundTrip)

        val scriptOutcome = executor.execute(scriptRoundTrip!!)
        assertTrue(scriptOutcome is ScheduledExecutionOutcome.Success)
        assertTrue(scriptOutcome.summary.contains("result=123"))
    }

    @Test
    fun listAndDeleteCompiledTasks_andListMaidLangNatives() = runBlocking {
        assertEquals("No compiled MaidLang tasks are stored.", toolSet.listCompiledTasks())

        val out = toolSet.makeTask(code = "1 + 1;", name = "demo")
        val taskId = extractTaskId(out)!!
        val listed = toolSet.listCompiledTasks()
        assertTrue(listed.contains(taskId))
        assertTrue(listed.contains("demo"))

        val deleteMsg = toolSet.deleteCompiledTask(taskId)
        assertTrue(deleteMsg.contains("Deleted compiled MaidLang task '$taskId'"))
        assertFalse(taskStore.hasTask(taskId))

        val inputNatives = toolSet.listMaidLangNatives(toolsetId = "input")
        assertTrue(inputNatives.contains("external fun tap("))
        assertTrue(inputNatives.contains("[input |"))
    }

    private fun extractTaskId(makeTaskOutput: String): String? {
        return makeTaskOutput.lineSequence()
            .firstOrNull { it.startsWith("taskId: ") }
            ?.removePrefix("taskId: ")
            ?.trim()
    }

    private data class RecordedAlarm(
        val alarmId: String,
        val triggerAt: Instant,
        val payload: ScheduledTaskPayload,
        val allowWhileIdle: Boolean,
    )

    private class FakeAlarmGateway : ScheduledAlarmGateway {
        val scheduled = LinkedHashMap<String, RecordedAlarm>()

        override fun schedule(
            alarmId: String,
            triggerAt: Instant,
            payload: ScheduledTaskPayload,
            allowWhileIdle: Boolean,
        ) {
            scheduled[alarmId] = RecordedAlarm(alarmId, triggerAt, payload, allowWhileIdle)
        }

        override fun cancel(alarmId: String): Boolean = scheduled.remove(alarmId) != null
    }

    private data class RecordedWork(
        val workName: String,
        val payload: ScheduledTaskPayload,
        val delaySeconds: Long,
        val repeatIntervalMinutes: Long,
        val requireNetwork: Boolean,
        val replaceExisting: Boolean,
    )

    private class FakeWorkGateway : ScheduledWorkGateway {
        val requests = LinkedHashMap<String, RecordedWork>()
        val canceled = LinkedHashSet<String>()

        override fun scheduleOneTime(
            workName: String,
            payload: ScheduledTaskPayload,
            delaySeconds: Long,
            requireNetwork: Boolean,
            replaceExisting: Boolean,
        ) {
            requests[workName] = RecordedWork(
                workName = workName,
                payload = payload,
                delaySeconds = delaySeconds,
                repeatIntervalMinutes = 0L,
                requireNetwork = requireNetwork,
                replaceExisting = replaceExisting,
            )
        }

        override fun schedulePeriodic(
            workName: String,
            payload: ScheduledTaskPayload,
            repeatIntervalMinutes: Long,
            initialDelaySeconds: Long,
            requireNetwork: Boolean,
            replaceExisting: Boolean,
        ) {
            requests[workName] = RecordedWork(
                workName = workName,
                payload = payload,
                delaySeconds = initialDelaySeconds,
                repeatIntervalMinutes = repeatIntervalMinutes,
                requireNetwork = requireNetwork,
                replaceExisting = replaceExisting,
            )
        }

        override fun cancel(workName: String) {
            canceled.add(workName)
            requests.remove(workName)
        }

        override suspend fun getStatus(workName: String): List<ScheduledWorkStatusEntry> {
            return if (workName in requests) {
                listOf(
                    ScheduledWorkStatusEntry(
                        id = "req_$workName",
                        state = "ENQUEUED",
                        runAttemptCount = 0,
                    ),
                )
            } else {
                emptyList()
            }
        }
    }
}
