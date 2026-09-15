package com.scraper.classroomcapture.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Model install + verification (P7.4, D15). No network, no bundling: the
// operator sideloads a file, the app copies + SHA-verifies into app-private
// storage.
class ModelRegistryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `install verifies sha and resolves`() {
        val registry = FileModelRegistry(File(tmp.root, "models"))
        val source = File(tmp.root, "tiny.bin").apply { writeBytes("model-bytes".toByteArray()) }
        val sha = FileModelRegistry.sha256Of(source)
        val info = registry.installFromFile(source, "tiny", "2026-09-15", sha, "MIT")
        assertEquals("tiny", info.modelId)
        assertTrue(registry.resolve("tiny")?.isFile == true)
        val status = registry.status("tiny")
        assertTrue(status is ModelStatus.Installed)
    }

    @Test
    fun `wrong sha refuses install`() {
        val registry = FileModelRegistry(File(tmp.root, "models"))
        val source = File(tmp.root, "m.bin").apply { writeBytes("x".toByteArray()) }
        try {
            registry.installFromFile(source, "tiny", "v1", "0".repeat(64), "MIT")
            fail("expected mismatch")
        } catch (e: ModelInstallException) {
            assertTrue(registry.resolve("tiny") == null)
        }
    }

    @Test
    fun `tampered model reports corrupt`() {
        val registry = FileModelRegistry(File(tmp.root, "models"))
        val source = File(tmp.root, "m.bin").apply { writeBytes("good".toByteArray()) }
        val sha = FileModelRegistry.sha256Of(source)
        registry.installFromFile(source, "base", "v1", sha, "MIT")
        // Tamper after install.
        File(tmp.root, "models/base.bin").writeBytes("evil".toByteArray())
        val status = registry.status("base")
        assertTrue(status is ModelStatus.Corrupt)
        assertTrue(registry.resolve("base") == null)
    }

    @Test
    fun `missing model reports missing`() {
        val registry = FileModelRegistry(File(tmp.root, "models"))
        assertTrue(registry.status("small") is ModelStatus.Missing)
        assertTrue(registry.installedModels().isEmpty())
    }
}
