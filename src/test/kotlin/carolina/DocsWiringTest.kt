package carolina

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DocsWiringTest {
    @Test
    fun readmePinsKotlinKtorAndJdk27() {
        val readme = Files.readString(Path.of("README.md"))
        assertTrue(readme.contains("2.2.20"), "README must pin Kotlin 2.2.20\n$readme")
        assertTrue(readme.contains("3.3.1"), "README must pin Ktor 3.3.1\n$readme")
        assertTrue(readme.contains("JDK 27"), "README must pin the JDK 27 runtime\n$readme")
    }

    @Test
    fun agentsPointsAtDecisionMemory() {
        val agents = Files.readString(Path.of("AGENTS.md"))
        assertTrue(agents.contains("DECISIONS.md"), "AGENTS.md must reference DECISIONS.md\n$agents")
        assertTrue(agents.contains("MEMORY.md"), "AGENTS.md must reference MEMORY.md\n$agents")
    }
}
