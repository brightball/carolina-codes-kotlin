package carolina

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class QualityWiringTest {
    @Test
    fun precommitAndGiteaWireFiveDistinctChecks() {
        val precommit = Files.readString(Path.of(".pre-commit-config.yaml"))
        val workflow = Files.readString(Path.of(".gitea/workflows/precommit.yml"))
        val required = listOf("test", "sast", "deps", "gitleaks", "style")
        val commands =
            mapOf(
                "test" to "./gradlew --no-daemon test",
                "sast" to "./gradlew --no-daemon detekt",
                "deps" to "./gradlew --no-daemon osvScan",
                "gitleaks" to "gitleaks detect --source . --verbose",
                "style" to "./gradlew --no-daemon ktlintCheck",
            )

        val hooks = hookEntries(precommit)
        required.forEach { id ->
            assertTrue(hooks.containsKey(id), "missing pre-commit hook id $id in ${hooks.keys}")
        }
        assertEquals(hooks.size, hooks.values.toSet().size, "hook entries must be unique, got $hooks")
        commands.forEach { (id, command) ->
            assertEquals(command, hooks.getValue(id), "pre-commit hook $id must be a distinct command")
        }

        val jobs = jobBodies(workflow)
        required.forEach { id ->
            assertTrue(jobs.containsKey(id), "missing Gitea job $id in ${jobs.keys}")
        }

        val prepIds = jobs.keys.filter { it !in required }
        assertEquals(
            1,
            prepIds.size,
            "workflow must have one environment-prep job besides $required, got ${jobs.keys}",
        )
        val prepId = prepIds.single()
        val prepBody = jobs.getValue(prepId)
        assertPrepJob(prepId, prepBody, jobs)
        required.forEach { id -> assertCheckJob(id, jobs, commands, required, prepId) }

        val perf = Files.readString(Path.of("src/test/kotlin/carolina/PerfTest.kt"))
        assertTrue(perf.contains("healthDoesNotQueryOrConnect"), "health test must remain")
        assertTrue(perf.contains("yearListingSqlBoundedAndYearsDesc"), "catalog test must remain")
        assertTrue(perf.contains("runningJvmIsFeature27"), "JDK assertion must remain")
    }

    @Test
    fun gitleaksPinMatchesWorkflowAndDetektLimitsStay() {
        val mise = Files.readString(Path.of("mise.toml"))
        val workflow = Files.readString(Path.of(".gitea/workflows/precommit.yml"))
        assertTrue(mise.contains("gitleaks = \"8.30.1\""), "mise gitleaks pin must be 8.30.1\n$mise")
        assertTrue(!mise.contains("gitleaks = \"latest\""), "mise gitleaks must not track latest")
        assertTrue(workflow.contains("v8.30.1"), "workflow must install gitleaks v8.30.1")
        val detekt = Files.readString(Path.of("config/detekt/detekt.yml"))
        assertTrue(Regex("""LongMethod:\s*\n\s*active:\s*true\s*\n\s*allowedLines:\s*90""").containsMatchIn(detekt))
        assertTrue(Regex("""allowedFunctionsPerFile:\s*50""").containsMatchIn(detekt))
        assertTrue(Regex("""allowedFunctionsPerClass:\s*50""").containsMatchIn(detekt))
        assertTrue(Regex("""LargeClass:\s*\n\s*active:\s*true\s*\n\s*allowedLines:\s*900""").containsMatchIn(detekt))
        assertTrue(Regex("""allowedComplexity:\s*20""").containsMatchIn(detekt))
    }

    @Test
    fun prepTarballKeepsScrubbedGitSoGitleaksDetectsHistory() {
        val workflow = Files.readString(Path.of(".gitea/workflows/precommit.yml"))
        val jobs = jobBodies(workflow)
        val required = listOf("test", "sast", "deps", "gitleaks", "style")
        val prepBody = jobs.getValue(jobs.keys.single { it !in required })
        val detect = "gitleaks detect --source . --verbose"
        assertTrue(jobs.getValue("gitleaks").contains(detect), "must drive the shipped gitleaks command")
        assertTrue(!jobs.getValue("gitleaks").contains("--no-git"))

        val excludes = tarExcludeFlags(prepBody)
        assertTrue(
            excludes.none { it.removePrefix("./").trimEnd('/') == ".git" },
            "shipped tar must not exclude .git, got $excludes",
        )
        val setUrl =
            Regex("""git remote set-url origin\s+(\S+)""")
                .find(prepBody)
                ?.groupValues
                ?.get(1)
                ?: fail("shipped prep must scrub remote.origin.url")

        val work = Files.createTempDirectory("carolina-prep-git")
        val repo = work.resolve("repo")
        val unpacked = work.resolve("unpacked")
        val tarFile = work.resolve("prep-workspace.tar.gz")
        Files.createDirectories(repo)
        Files.createDirectories(unpacked)
        try {
            val gitEnv =
                mapOf(
                    "GIT_AUTHOR_NAME" to "ci",
                    "GIT_AUTHOR_EMAIL" to "ci@example.com",
                    "GIT_COMMITTER_NAME" to "ci",
                    "GIT_COMMITTER_EMAIL" to "ci@example.com",
                )
            run(repo, gitEnv, "git", "-c", "init.defaultBranch=main", "init", "-q")
            Files.writeString(repo.resolve("tracked.txt"), "carolina-codes-kotlin prep artifact\n")
            run(repo, gitEnv, "git", "add", "tracked.txt")
            run(repo, gitEnv, "git", "commit", "-q", "-m", "tracked")
            run(
                repo,
                gitEnv,
                "git",
                "remote",
                "add",
                "origin",
                "https://x-access-token:fake-job-token@example.invalid/org/repo",
            )
            val scrubEnv =
                gitEnv +
                    mapOf(
                        "GITHUB_SERVER_URL" to "https://example.invalid",
                        "GITHUB_REPOSITORY" to "org/repo",
                    )
            run(repo, scrubEnv, "bash", "-lc", "git remote set-url origin $setUrl")
            val origin = run(repo, gitEnv, "git", "remote", "get-url", "origin").trim()
            assertTrue(!origin.contains("x-access-token"), "scrubbed origin leaked token: $origin")
            assertTrue(!origin.contains("fake-job-token"), "scrubbed origin leaked token: $origin")

            val tarArgs = mutableListOf("tar", "-czf", tarFile.toString())
            excludes.forEach { tarArgs.add("--exclude=$it") }
            tarArgs.add(".")
            run(repo, emptyMap(), *tarArgs.toTypedArray())
            val listing = run(repo, emptyMap(), "tar", "-tzf", tarFile.toString())
            assertTrue(
                listing.lineSequence().any { it.contains(".git/") },
                "artifact packed with shipped tar flags must contain .git\n$listing",
            )

            run(unpacked, emptyMap(), "tar", "-xzf", tarFile.toString(), "--no-same-owner")
            val packedConfig = Files.readString(unpacked.resolve(".git/config"))
            assertTrue(
                !packedConfig.contains("x-access-token") && !packedConfig.contains("fake-job-token"),
                "packed .git/config must not embed the clone token\n$packedConfig",
            )

            val packedScan = gitleaksDetect(unpacked)
            val packedCommits = commitsScanned(packedScan)
            assertTrue(
                packedCommits > 0,
                "shipped gitleaks detect must scan packed git history, got $packedCommits commits\n$packedScan",
            )

            assertMissingGitScansNoCommits(work, tarFile)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun assertPrepJob(
        prepId: String,
        prepBody: String,
        jobs: Map<String, String>,
    ) {
        assertTrue(prepBody.contains("apt-get"), "prep job $prepId must install packages\n$prepBody")
        assertTrue(prepBody.contains("git clone"), "prep job $prepId must token-clone the repo\n$prepBody")
        assertTrue(prepBody.contains("GITHUB_SHA"), "prep job $prepId must check out GITHUB_SHA\n$prepBody")
        assertTrue(prepBody.contains("gitleaks"), "prep job $prepId must install gitleaks\n$prepBody")
        assertTrue(prepBody.contains("osv-scanner"), "prep job $prepId must install osv-scanner\n$prepBody")
        assertTrue(
            prepBody.contains("actions/upload-artifact"),
            "prep job $prepId must upload the prepared environment\n$prepBody",
        )
        assertTrue(
            prepBody.contains("git remote set-url origin"),
            "prep job $prepId must scrub the clone token from remote.origin.url\n$prepBody",
        )
        val originUrl =
            Regex("""git remote set-url origin\s+(\S+)""")
                .find(prepBody)
                ?.groupValues
                ?.get(1)
                ?: fail("prep job $prepId must set origin to a token-free URL")
        assertTrue(
            !originUrl.contains("x-access-token") && !originUrl.contains("\${token}"),
            "scrubbed origin must not embed the job token: $originUrl",
        )
        val tarExcludes = tarExcludeFlags(prepBody)
        assertTrue(
            tarExcludes.none { it.removePrefix("./").trimEnd('/') == ".git" },
            "prep tarball must keep .git so gitleaks can scan, got $tarExcludes",
        )
        assertTrue(
            !jobs.getValue("gitleaks").contains("--no-git"),
            "gitleaks job must keep detect --source . --verbose (no --no-git)",
        )
        assertTrue(jobNeeds(prepBody).isEmpty(), "prep job $prepId must not need a check job")
    }

    private fun assertCheckJob(
        id: String,
        jobs: Map<String, String>,
        commands: Map<String, String>,
        required: List<String>,
        prepId: String,
    ) {
        val body = jobs.getValue(id)
        val command = commands.getValue(id)
        assertTrue(body.contains(command), "Gitea job $id must run $command\n$body")
        required.filter { it != id }.forEach { other ->
            val otherCommand = commands.getValue(other)
            assertTrue(!body.contains(otherCommand), "Gitea job $id must not also run $otherCommand")
        }
        assertEquals(
            listOf(prepId),
            jobNeeds(body),
            "Gitea check job $id must need only the prep job $prepId, got ${jobNeeds(body)}",
        )
        jobNeeds(body).forEach { needed ->
            assertTrue(needed !in required, "Gitea check job $id must not need another check job $needed")
        }
        assertTrue(
            body.contains("actions/download-artifact"),
            "Gitea check job $id must consume the prepared environment via download-artifact\n$body",
        )
        val uses =
            Regex("""(?m)^\s+-?\s*uses:\s*(\S+)\s*$""")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(
            listOf("actions/download-artifact@v3"),
            uses,
            "Gitea check job $id must only use download-artifact, got $uses",
        )
        assertTrue(
            !Regex("""<<:|\*[A-Za-z_]""").containsMatchIn(body),
            "Gitea check job $id must not hide setup behind YAML anchors\n$body",
        )
        assertTrue(!body.contains("apt-get"), "Gitea check job $id must not apt-get; setup belongs in $prepId\n$body")
        assertTrue(
            !body.contains("git clone"),
            "Gitea check job $id must not clone; $prepId already checked out GITHUB_SHA\n$body",
        )
        assertTrue(
            !body.contains("GITHUB_SHA"),
            "Gitea check job $id must not check out GITHUB_SHA; consume $prepId instead\n$body",
        )
        assertTrue(
            !body.contains("git fetch"),
            "Gitea check job $id must not git fetch; consume $prepId instead\n$body",
        )
        assertTrue(
            !body.contains("git checkout"),
            "Gitea check job $id must not git checkout; consume $prepId instead\n$body",
        )
        assertTrue(
            !body.contains("curl"),
            "Gitea check job $id must not curl tool bootstraps; $prepId already installed them\n$body",
        )
    }

    private fun assertMissingGitScansNoCommits(
        work: Path,
        tarFile: Path,
    ) {
        val withoutGit = work.resolve("without-git")
        Files.createDirectories(withoutGit)
        run(withoutGit, emptyMap(), "tar", "-xzf", tarFile.toString(), "--no-same-owner")
        withoutGit.resolve(".git").toFile().deleteRecursively()
        val skippedScan = gitleaksDetect(withoutGit)
        assertEquals(
            0,
            commitsScanned(skippedScan),
            "control without .git must scan 0 commits (the excluded-.git no-op)\n$skippedScan",
        )
    }

    private fun hookEntries(precommit: String): Map<String, String> {
        val chunks = precommit.split(Regex("(?m)^\\s+- id:\\s+"))
        val out = linkedMapOf<String, String>()
        chunks.drop(1).forEach { chunk ->
            val id = chunk.substringBefore('\n').trim()
            val entry =
                Regex("""(?m)^\s+entry:\s*(.+)$""")
                    .find(chunk)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.trim('"', '\'')
                    ?: fail("pre-commit hook $id is missing entry")
            assertTrue(id !in out, "duplicate pre-commit hook id $id")
            out[id] = entry
        }
        return out
    }

    private fun jobBodies(workflow: String): Map<String, String> {
        val jobsIdx = workflow.indexOf("\njobs:")
        assertTrue(jobsIdx >= 0, "workflow must declare jobs:")
        val jobsBlock = workflow.substring(jobsIdx + 1)
        val header = Regex("(?m)^  ([A-Za-z0-9_-]+):\\s*$")
        val matches = header.findAll(jobsBlock).toList()
        assertTrue(matches.isNotEmpty(), "workflow jobs: has no job keys")
        val out = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val name = match.groupValues[1]
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else jobsBlock.length
            out[name] = jobsBlock.substring(start, end)
        }
        return out
    }

    private fun jobNeeds(body: String): List<String> {
        val lines = body.lineSequence().toList()
        val idx = lines.indexOfFirst { it.matches(Regex("""^\s+needs:\s*.*$""")) }
        if (idx < 0) return emptyList()
        val line = lines[idx]
        val afterColon = line.substringAfter("needs:").trim()
        if (afterColon.isNotEmpty()) {
            return afterColon
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }
        }
        val needsIndent = line.takeWhile { it == ' ' }.length
        val items = mutableListOf<String>()
        for (i in (idx + 1) until lines.size) {
            val l = lines[i]
            if (l.isBlank()) continue
            val indent = l.takeWhile { it == ' ' }.length
            if (indent <= needsIndent) break
            val item = Regex("""^-\s+(\S+)""").find(l.trim()) ?: break
            items.add(item.groupValues[1].trim().trim('"', '\''))
        }
        return items
    }

    private fun tarExcludeFlags(prepBody: String): List<String> =
        Regex("""--exclude=(\S+)""")
            .findAll(prepBody)
            .map { it.groupValues[1].trim('"', '\'') }
            .toList()

    private fun gitleaksDetect(dir: Path): String {
        val proc =
            ProcessBuilder("gitleaks", "detect", "--source", ".", "--verbose")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            fail("gitleaks timed out\n$out")
        }
        return out
    }

    private fun commitsScanned(gitleaksOutput: String): Int {
        val match = Regex("""(\d+) commits scanned""").find(gitleaksOutput)
        return match?.groupValues?.get(1)?.toInt()
            ?: fail("gitleaks output missing commits scanned\n$gitleaksOutput")
    }

    private fun run(
        dir: Path,
        extraEnv: Map<String, String>,
        vararg command: String,
    ): String {
        val pb = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true)
        pb.environment().putAll(extraEnv)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            fail("timed out: ${command.joinToString(" ")}\n$out")
        }
        if (proc.exitValue() != 0) {
            fail("exit ${proc.exitValue()}: ${command.joinToString(" ")}\n$out")
        }
        return out
    }
}
