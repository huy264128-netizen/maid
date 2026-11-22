package top.aaa000111.maid.runtime

import BytecodeFile
import Compiler
import Interpreter
import MaidValue
import VM
import lexer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import parser
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog

class MaidLangIntegrationTest {
    @Before
    fun setUp() {
        MaidLangNativeRegistry.resetToDefaults()
    }

    @After
    fun tearDown() {
        MaidLangNativeRegistry.resetToDefaults()
    }

    @Test
    fun bundledLanguageEnginesEvaluateScriptOnJvm() {
        val program = parser(lexer("int value = 6; value * 7;")).program()

        assertEquals(MaidValue.IntVal(42), Interpreter().interpret(program))
        val encoded = BytecodeFile.encode(Compiler().compile(program))
        assertEquals(MaidValue.IntVal(42), VM().execute(BytecodeFile.decode(encoded)))
    }

    @Test
    fun builtInAndroidToolsArePreRegisteredWithToolsetsAndValidDeclarations() {
        val expectedToolsets = ToolsetPermissionCatalog.allSpecs.map { it.toolsetId }.toSet()
        assertEquals(expectedToolsets, MaidLangNativeRegistry.registeredToolsetIds())

        for (spec in ToolsetPermissionCatalog.allSpecs) {
            val entries = MaidLangNativeRegistry.entriesForToolset(spec.toolsetId)
            assertTrue("Expected built-in tools for toolset ${spec.toolsetId}", entries.isNotEmpty())
            for (entry in entries) {
                assertEquals(spec.toolsetId, entry.toolsetId)
                assertEquals(spec.requiredPrivileges.toSet(), entry.requiredPrivileges)
            }
        }

        // 所有预注册内置工具生成的 external fun 声明都必须能被 MaidLang 正常解析与编译
        val allDeclarations = MaidLangNativeRegistry.buildExternalDeclarations()
        val program = parser(lexer("$allDeclarations\nreturn 1;")).program()
        val compiled = BytecodeFile.decode(BytecodeFile.encode(Compiler().compile(program)))
        assertEquals(MaidValue.IntVal(1), MaidLangNativeRegistry.createVm().execute(compiled))
    }

    @Test
    fun checkScansTokensWithSingleLexerPassAndReportsMissingToolsets() {
        val script = """
            // 注释里的 lockScreenNow 不应被算作引用
            string label = "executeRootShell";
            string vol = getStreamVolumes();
            string tapped = tap(100.0, 200.0, 0);
            string sh = executeShell("id", "", 5000, false);
            string locked = lockScreenNow();
        """.trimIndent()

        // 默认 NONE 权限组：audio 无需特殊权限，input/shell/device_policy 缺失
        val noneResult = MaidLangNativeRegistry.check(script)
        assertFalse(noneResult.isSatisfied)
        assertEquals(
            setOf("getStreamVolumes", "tap", "executeShell", "lockScreenNow"),
            noneResult.referencedNatives,
        )
        assertEquals(
            setOf("audio", "input", "shell", "device_policy"),
            noneResult.requiredToolsets,
        )
        assertEquals(
            setOf("input", "shell", "device_policy"),
            noneResult.missingToolsetIds,
        )
        assertEquals(
            setOf("tap", "executeShell", "lockScreenNow"),
            noneResult.missingNatives,
        )

        // 无障碍权限组：解锁 input，仍缺 shell 与 device_policy
        MaidLangNativeRegistry.setPermissionGroup(MaidLangPermissionGroup.ACCESSIBILITY)
        val a11yResult = MaidLangNativeRegistry.check(script)
        assertEquals(setOf("shell", "device_policy"), a11yResult.missingToolsetIds)

        // Shizuku 权限组：解锁 input 与 shell，仍缺 device_policy
        val shizukuResult = MaidLangNativeRegistry.check(script, MaidLangPermissionGroup.SHIZUKU)
        assertEquals(setOf("device_policy"), shizukuResult.missingToolsetIds)
        val missingPolicy = shizukuResult.missingToolsets.single()
        assertEquals("device_policy", missingPolicy.toolsetId)
        assertEquals(setOf(AndroidPrivilege.DHIZUKU), missingPolicy.requiredPrivileges)
        assertEquals(listOf("lockScreenNow"), missingPolicy.referencedNatives)

        // 自定义组合（Shizuku + Dhizuku）：全部满足
        MaidLangNativeRegistry.setAvailablePrivileges(
            setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.DHIZUKU),
        )
        val satisfiedResult = MaidLangNativeRegistry.check(script)
        assertTrue(satisfiedResult.isSatisfied)
        assertTrue(satisfiedResult.missingToolsets.isEmpty())
        assertTrue(satisfiedResult.missingNatives.isEmpty())
    }

    @Test
    fun checkOnlyNeedsLexingAndWorksWithoutFullParsing() {
        // 即使源码缺少分号或语法不完整，只要词法合法即可完成 token 扫描
        val fragment = "tap(1, 2, 0) + executeRootShell("
        val result = MaidLangNativeRegistry.check(fragment, MaidLangPermissionGroup.ACCESSIBILITY)

        assertEquals(setOf("tap", "executeRootShell"), result.referencedNatives)
        assertEquals(setOf("input", "root_system"), result.requiredToolsets)
        assertEquals(setOf("root_system"), result.missingToolsetIds)
    }

    @Test
    fun permissionFilteringRestrictsSnapshotsAndDeclarations() {
        MaidLangNativeRegistry.setPermissionGroup(MaidLangPermissionGroup.ACCESSIBILITY)

        val allowedSnapshot = MaidLangNativeRegistry.snapshotAllowedNativeRegistry()
        assertTrue("getStreamVolumes" in allowedSnapshot)
        assertTrue("tap" in allowedSnapshot)
        assertTrue("listDirectory" in allowedSnapshot)
        assertFalse("executeShell" in allowedSnapshot)
        assertFalse("lockScreenNow" in allowedSnapshot)

        val filteredDecls = MaidLangNativeRegistry.buildExternalDeclarationsForPrivileges(
            availablePrivileges = setOf(AndroidPrivilege.SHIZUKU),
            names = listOf("tap", "executeShell", "lockScreenNow"),
        )
        assertTrue(filteredDecls.contains("external fun tap("))
        assertTrue(filteredDecls.contains("external fun executeShell("))
        assertFalse(filteredDecls.contains("lockScreenNow"))
    }

    @Test
    fun singletonRegistersNativesAndGeneratesExecutableDeclarations() {
        MaidLangNativeRegistry.clear()
        val logBuffer = mutableListOf<String>()
        MaidLangNativeRegistry.register(
            name = "addScaled",
            paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.INT),
            returnType = MaidLangNativeType.INT,
            description = "计算 (a + b) * 2",
        ) { args ->
            MaidValue.IntVal((args[0].asInt() + args[1].asInt()) * 2)
        }
        MaidLangNativeRegistry.register(
            name = "record",
            paramTypes = listOf(MaidLangNativeType.STRING),
            returnType = MaidLangNativeType.VOID,
            description = "记录一条日志",
        ) { args ->
            logBuffer.add((args[0] as MaidValue.StringVal).value)
            MaidValue.NullVal
        }
        MaidLangNativeRegistry.register(
            name = "isPositive",
            paramTypes = listOf(MaidLangNativeType.INT),
            returnType = MaidLangNativeType.BOOL,
        ) { args ->
            MaidValue.IntVal(if (args[0].asInt() > 0) 1 else 0)
        }

        assertEquals(
            """
            external fun addScaled(int, int) -> int;
            external fun record(string) -> void;
            external fun isPositive(int) -> bool;
            """.trimIndent(),
            MaidLangNativeRegistry.buildExternalDeclarations(),
        )

        assertEquals(
            """
            // 计算 (a + b) * 2
            external fun addScaled(int, int) -> int;
            // 记录一条日志
            external fun record(string) -> void;
            """.trimIndent(),
            MaidLangNativeRegistry.buildDocumentedDeclarations(listOf("addScaled", "record", "addScaled")),
        )

        val script = MaidLangNativeRegistry.prependDeclarations(
            source = """
                int result = addScaled(10, 11);
                if (isPositive(result)) {
                    record("ok:" + result);
                }
                return result;
            """.trimIndent(),
        )
        val program = parser(lexer(script)).program()

        val interpResult = MaidLangNativeRegistry.createInterpreter().interpret(program)
        assertEquals(MaidValue.IntVal(42), interpResult)

        val compiledBytes = BytecodeFile.encode(Compiler().compile(program))
        val vmResult = MaidLangNativeRegistry.createVm().execute(BytecodeFile.decode(compiledBytes))
        assertEquals(MaidValue.IntVal(42), vmResult)
        assertEquals(listOf("ok:42", "ok:42"), logBuffer)
    }

    @Test
    fun subsetIntroductionIsolatesDeclarationsAndSnapshot() {
        MaidLangNativeRegistry.clear()
        MaidLangNativeRegistry.register(
            name = "allowed",
            paramTypes = listOf(MaidLangNativeType.INT),
            returnType = MaidLangNativeType.INT,
        ) { args ->
            MaidValue.IntVal(args[0].asInt() + 1)
        }
        MaidLangNativeRegistry.register(
            name = "secret",
            paramTypes = emptyList(),
            returnType = MaidLangNativeType.INT,
        ) {
            MaidValue.IntVal(999)
        }

        val subset = listOf("allowed")
        assertEquals("external fun allowed(int) -> int;", MaidLangNativeRegistry.buildExternalDeclarations(subset))

        val vm = MaidLangNativeRegistry.createVm(subset)
        val snapshot = MaidLangNativeRegistry.snapshotNativeRegistry(subset)
        assertTrue("allowed" in snapshot)
        assertFalse("secret" in snapshot)

        // 卸载单例中的函数不应影响已创建的 VM 快照
        MaidLangNativeRegistry.unregister("allowed")
        assertFalse("allowed" in MaidLangNativeRegistry)

        val okProgram = parser(lexer("external fun allowed(int) -> int; return allowed(41);")).program()
        assertEquals(MaidValue.IntVal(42), vm.execute(Compiler().compile(okProgram)))

        val deniedProgram = parser(lexer("external fun secret() -> int; return secret();")).program()
        val error = assertThrows(IllegalStateException::class.java) {
            vm.execute(Compiler().compile(deniedProgram))
        }
        assertTrue(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun rejectsInvalidDeclarationsAndUnregisteredNames() {
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeDeclaration(name = "", paramTypes = emptyList(), returnType = MaidLangNativeType.VOID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeDeclaration(name = "1bad", paramTypes = emptyList(), returnType = MaidLangNativeType.VOID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeDeclaration(name = "return", paramTypes = emptyList(), returnType = MaidLangNativeType.VOID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeDeclaration(name = "bad-name", paramTypes = emptyList(), returnType = MaidLangNativeType.VOID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeDeclaration(
                name = "badParam",
                paramTypes = listOf(MaidLangNativeType.VOID),
                returnType = MaidLangNativeType.INT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeRegistry.buildExternalDeclarations(listOf("missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeRegistry.snapshotNativeRegistry(listOf("missing"))
        }
        assertEquals(MaidLangNativeType.STRING, MaidLangNativeType.fromKeyword("string"))
        assertThrows(IllegalArgumentException::class.java) {
            MaidLangNativeType.fromKeyword("unknown")
        }
    }
}
