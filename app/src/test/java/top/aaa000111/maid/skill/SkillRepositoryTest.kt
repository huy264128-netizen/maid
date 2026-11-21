package top.aaa000111.maid.skill

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.learning.TaskEvidenceRepository
import top.aaa000111.maid.learning.TaskRecorder
import java.nio.file.Files

class SkillRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val root get() = temporary.root.toPath().resolve("skills")
    private fun files(body: String) = mapOf(
        "SKILL.md" to "---\nname: sample\ndescription: Device verification workflow\n---\n$body",
        "references/check.md" to "Check the observable result: $body",
    )

    @Test fun wholePackageIsVersionedAndPinnedBeforeFirstRead() = runBlocking {
        val repository = SkillRepository(root)
        val old = repository.install(files("old"))
        val evidence = TaskEvidenceRepository(temporary.root.toPath().resolve("tasks"))
        TaskRecorder(evidence, repository).run("session") {
            val context = currentCoroutineContext()[top.aaa000111.maid.learning.TaskExecutionContext]!!
            val tools = SkillToolSet(repository, context.skillContext)
            val fresh = repository.install(files("new"), expectedVersion = old.versionHash)
            assertNotEquals(old.versionHash, fresh.versionHash)
            assertTrue(tools.readSkill("sample").contains("old"))
            assertFalse(tools.readSkill("sample", "references/check.md").contains("new"))
            tools.readSkill("sample")
        }
        assertEquals(2, evidence.list().single().skills.size) // 主文件重复读取去重，参考文件独立计数。
        assertEquals(old.versionHash, evidence.list().single().skills.first().versionHash)
        val reopened = SkillRepository(root)
        val active = reopened.snapshot().catalog.getValue("sample")
        assertTrue(reopened.read("sample", active.versionHash).contains("new"))
        assertTrue(reopened.read("sample", old.versionHash).contains("old"))
    }

    @Test fun supportingFileChangesAffectHashAndFileOrderDoesNot() = runBlocking {
        val repository = SkillRepository(root)
        val original = files("body")
        val first = repository.install(original)
        val same = repository.install(original.toList().reversed().toMap())
        assertEquals(first.versionHash, same.versionHash)
        val changed = repository.install(original + ("references/check.md" to "Different reference"))
        assertNotEquals(first.versionHash, changed.versionHash)
    }

    @Test fun invalidPackagesAndStaleWritersLeaveActiveVersionUntouched() = runBlocking {
        val repository = SkillRepository(root)
        val original = repository.install(files("body"))
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
        rejected { repository.install(files("bad") + ("references/../escape" to "bad")) }
        rejected { repository.install(files("bad") + ("/absolute" to "bad")) }
        rejected { repository.read("sample", original.versionHash, "../active.json") }
        rejected { repository.install(mapOf("SKILL.md" to "missing frontmatter")) }
        try {
            repository.install(files("stale"), expectedVersion = "0".repeat(64))
            fail("Expected version conflict")
        } catch (_: IllegalStateException) { }
        assertEquals(original, repository.snapshot().catalog.getValue("sample"))
    }

    @Test fun corruptImmutablePackageFailsIntegrityCheck() = runBlocking {
        val repository = SkillRepository(root)
        val descriptor = repository.install(files("body"))
        val path = root.resolve("versions/sample-${descriptor.versionHash}.json")
        Files.write(path, """{"files":{"SKILL.md":"tampered"}}""".toByteArray())
        try {
            repository.read("sample", descriptor.versionHash)
            fail("Expected integrity failure")
        } catch (_: IllegalStateException) { }
    }

    @Test fun explicitIdAndDescriptionSearchReturnOnlyMetadata() = runBlocking {
        val repository = SkillRepository(root)
        repository.install(files("private-body"))
        val context = repository.snapshot()
        assertEquals("sample", SkillResolver.resolve(context, "sample").single().id)
        assertEquals("sample", SkillResolver.resolve(context, "device verification").single().id)
        assertTrue(SkillResolver.resolve(context, "unrelated").isEmpty())
        val tools = SkillToolSet(repository, context)
        assertFalse(tools.listSkills().contains("private-body"))
        assertTrue(tools.readSkill("absent").contains("not found"))
    }
}
