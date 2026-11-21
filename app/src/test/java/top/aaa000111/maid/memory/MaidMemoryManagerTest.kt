package top.aaa000111.maid.memory

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.model.MultipleFacts
import ai.koog.agents.memory.model.SingleFact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MaidMemoryManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        MaidMemoryManager.resetForTest()
    }

    @OptIn(InternalAgentsApi::class)
    @Test
    fun subjects_areRegisteredInMemorySubjectRegistry() {
        MaidMemorySubjects.ensureRegistered()

        val registeredNames = MemorySubject.registeredSubjects.map { it.name }.toSet()
        assertTrue(registeredNames.contains(MaidMemorySubjects.User.name))
        assertTrue(registeredNames.contains(MaidMemorySubjects.Device.name))
        assertTrue(registeredNames.contains(MaidMemorySubjects.Workflow.name))
        assertTrue(registeredNames.contains(MemorySubject.Everything.name))
    }

    @Test
    fun saveAndLoadSingleFact_persistsAcrossManagerReinitialization() = runBlocking {
        val rootPath = tempFolder.root.toPath()
        MaidMemoryManager.init(rootPath = rootPath)

        MaidMemoryManager.saveSingleFact(
            concept = MaidMemoryConcepts.deviceEnvironment,
            value = "Android 15 with Shizuku active",
            subject = MaidMemorySubjects.Device,
            timestamp = 1_700_000_000_000L,
        )

        // 模拟重新初始化，验证磁盘持久化读取
        MaidMemoryManager.resetForTest()
        MaidMemoryManager.init(rootPath = rootPath)
        val loaded = MaidMemoryManager.loadFacts(
            concept = MaidMemoryConcepts.deviceEnvironment,
            subject = MaidMemorySubjects.Device,
        )

        assertEquals(1, loaded.size)
        val fact = loaded.single() as SingleFact
        assertEquals("Android 15 with Shizuku active", fact.value)
        assertEquals(1_700_000_000_000L, fact.timestamp)
        assertEquals(MaidMemoryConcepts.deviceEnvironment, fact.concept)

        val allDeviceFacts = MaidMemoryManager.loadAllFacts(subject = MaidMemorySubjects.Device)
        assertEquals(1, allDeviceFacts.size)
    }

    @Test
    fun saveAndLoadMultipleFacts_supportsDescriptionLookup() = runBlocking {
        val rootPath = tempFolder.root.toPath()
        MaidMemoryManager.init(rootPath = rootPath)

        MaidMemoryManager.saveMultipleFacts(
            concept = MaidMemoryConcepts.userPreference,
            values = listOf("Prefer concise Chinese replies", "Dark theme enabled"),
            subject = MaidMemorySubjects.User,
            timestamp = 1_700_000_001_000L,
        )

        val byConcept = MaidMemoryManager.loadFacts(
            concept = MaidMemoryConcepts.userPreference,
            subject = MaidMemorySubjects.User,
        )
        assertEquals(1, byConcept.size)
        val multiple = byConcept.single() as MultipleFacts
        assertEquals(
            listOf("Prefer concise Chinese replies", "Dark theme enabled"),
            multiple.values,
        )

        val byDescription = MaidMemoryManager.loadFactsByDescription(
            description = "response style",
            subject = MaidMemorySubjects.User,
        )
        assertEquals(1, byDescription.size)
    }

    @Test
    fun scopes_isolateFactsBetweenAgentAndProductScopes() = runBlocking {
        val rootPath = tempFolder.root.toPath()
        MaidMemoryManager.init(rootPath = rootPath)
        val agentScope = MemoryScope.Agent("planner")

        MaidMemoryManager.saveSingleFact(
            concept = MaidMemoryConcepts.workflowNote,
            value = "Product level workflow tip",
            subject = MaidMemorySubjects.Workflow,
        )
        MaidMemoryManager.saveSingleFact(
            concept = MaidMemoryConcepts.workflowNote,
            value = "Agent specific workflow tip",
            subject = MaidMemorySubjects.Workflow,
            scope = agentScope,
        )

        val productFacts = MaidMemoryManager.loadAllFacts(subject = MaidMemorySubjects.Workflow)
        val agentFacts = MaidMemoryManager.loadAllFacts(
            subject = MaidMemorySubjects.Workflow,
            scope = agentScope,
        )

        assertEquals(1, productFacts.size)
        assertEquals("Product level workflow tip", (productFacts.single() as SingleFact).value)
        assertEquals(1, agentFacts.size)
        assertEquals("Agent specific workflow tip", (agentFacts.single() as SingleFact).value)
    }

    @Test
    fun configureAgentMemory_populatesProviderAndScopeNames() {
        MaidMemoryManager.init(rootPath = tempFolder.root.toPath())
        val config = AgentMemory.Config()

        MaidMemoryManager.configureAgentMemory(config)

        assertSame(MaidMemoryManager.memoryProvider, config.memoryProvider)
        assertEquals(MaidMemoryManager.DEFAULT_AGENT_NAME, config.agentName)
        assertEquals(MaidMemoryManager.DEFAULT_FEATURE_NAME, config.featureName)
        assertEquals(MaidMemoryManager.DEFAULT_PRODUCT_NAME, config.productName)
        assertEquals(MaidMemoryManager.DEFAULT_ORGANIZATION_NAME, config.organizationName)
    }
}
