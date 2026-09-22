package carolina

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class StartupTest {
    @Test
    fun healthAndIndexAnswerWhileJdbcConnectBlocks() {
        resetPool()
        resetCounts()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        connectFn = {
            entered.countDown()
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw SQLException("warm release timed out")
            }
            throw SQLException("database port closed")
        }
        val port = freePort()
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(5)
        try {
            val server = serve(port)
            try {
                assertTrue(entered.await(4, TimeUnit.SECONDS), "pool warm did not reach JDBC connect")
                val connects = connectCount.get()
                val sql = sqlCount.get()
                val health = getUntil(port, "/health", deadline)
                val root = getUntil(port, "/", deadline)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(elapsedMs < 5_000, "responses took ${elapsedMs}ms")
                assertEquals(200, health.statusCode(), health.body())
                assertEquals(200, root.statusCode(), root.body())
                assertTrue(health.body().contains("\"ok\":true"), health.body())
                assertEquals(true, mapper.readTree(health.body()).get("ok").asBoolean())
                val tree = mapper.readTree(root.body())
                assertEquals("Kotlin", tree.get("language").asText(), root.body())
                assertEquals("Ktor", tree.get("framework").asText(), root.body())
                assertHeader(health, "X-Polyglot-Language", "Kotlin")
                assertHeader(health, "X-Polyglot-Framework", "Ktor")
                assertHeader(root, "X-Polyglot-Language", "Kotlin")
                assertHeader(root, "X-Polyglot-Framework", "Ktor")
                assertEquals(connects, connectCount.get(), "health or index opened a connection")
                assertEquals(sql, sqlCount.get(), "health or index ran SQL")
                assertEquals(0, sqlCount.get(), "health or index ran SQL")
                val mainFn = mainBody()
                assertTrue(mainFn.contains("serve("), mainFn)
                assertTrue(!mainFn.contains("openPool"), mainFn)
                assertTrue(!mainFn.contains("loadDriver"), mainFn)
                assertTrue(!mainFn.contains("newJdbc"), mainFn)
            } finally {
                release.countDown()
                joinPoolWarm()
                server.stop(200, 2_000)
            }
        } finally {
            resetPool()
            resetCounts()
        }
    }

    @Test
    fun flySuspendsAndImageSharesJvmAccelerator() {
        val fly = Files.readString(Path.of("fly.toml"))
        val docker = Files.readString(Path.of("Dockerfile"))
        assertTrue(fly.contains("auto_stop_machines = \"suspend\""), fly)
        assertTrue(fly.contains("auto_start_machines = true"), fly)
        assertTrue(fly.contains("min_machines_running = 0"), fly)
        assertTrue(fly.contains("primary_region = \"iad\""), fly)
        assertTrue(fly.contains("memory = \"512mb\""), fly)
        assertTrue(fly.contains("cpu_kind = \"shared\""), fly)
        assertTrue(fly.contains("cpus = 1"), fly)
        assertTrue(fly.contains("path = \"/health\""), fly)
        assertTrue(fly.contains("grace_period = \"90s\""), fly)
        val opts = javaOpts(fly)
        assertEquals(opts, javaOpts(docker), "fly.toml and Dockerfile JAVA_OPTS differ")
        assertTrue(opts.contains("-XX:+ExitOnOutOfMemoryError"), opts)
        assertTrue(opts.contains("-XX:ActiveProcessorCount=1"), opts)
        assertTrue(opts.contains("-XX:TieredStopAtLevel=1"), opts)
        val percent =
            Regex("""MaxRAMPercentage=([0-9.]+)""")
                .find(opts)
                ?.groupValues
                ?.get(1)
                ?.toDouble()
                ?: fail("MaxRAMPercentage missing from $opts")
        assertTrue(percent <= 55.0, "MaxRAMPercentage $percent")
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = mutableListOf(javaBin)
        opts.split(' ').forEach { command.add(it) }
        command.add("-XX:+PrintFlagsFinal")
        command.add("-version")
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        assertTrue(Regex("""TieredStopAtLevel\s*=\s*1\b""").containsMatchIn(out), tail(out))
        assertTrue(Regex("""ActiveProcessorCount\s*=\s*1\b""").containsMatchIn(out), tail(out))
        assertTrue(Regex("""ExitOnOutOfMemoryError\s*=\s*true""").containsMatchIn(out), tail(out))
        val ram =
            Regex("""MaxRAMPercentage\s*=\s*([0-9.]+)""")
                .find(out)
                ?.groupValues
                ?.get(1)
                ?.toDouble()
                ?: fail("PrintFlagsFinal missing MaxRAMPercentage\n${tail(out)}")
        assertTrue(ram <= 55.0, tail(out))
        assertTrue(!out.contains("unusable", ignoreCase = true), tail(out))
    }

    private fun mainBody(): String {
        val src = Files.readString(Path.of("src/main/kotlin/carolina/Main.kt"))
        return src.substringAfter("fun main()").substringBefore("\ninternal fun serve")
    }

    private fun assertHeader(
        response: HttpResponse<String>,
        name: String,
        value: String,
    ) {
        assertEquals(value, response.headers().firstValue(name).orElse(""), response.headers().toString())
    }

    private fun javaOpts(text: String): String =
        Regex("""JAVA_OPTS\s*=\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: fail("JAVA_OPTS missing")

    private fun tail(out: String): String = out.lines().takeLast(40).joinToString("\n")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun getUntil(
        port: Int,
        path: String,
        deadlineNs: Long,
    ): HttpResponse<String> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build()
        var last = "no response"
        while (System.nanoTime() < deadlineNs) {
            for (base in listOf("http://[::1]:$port", "http://127.0.0.1:$port")) {
                if (System.nanoTime() >= deadlineNs) break
                try {
                    val request =
                        HttpRequest
                            .newBuilder(URI.create("$base$path"))
                            .timeout(Duration.ofMillis(400))
                            .GET()
                            .build()
                    return client.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (exc: java.io.IOException) {
                    last = exc.toString()
                } catch (exc: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail("interrupted requesting $path: $exc")
                }
            }
            try {
                Thread.sleep(30)
            } catch (exc: InterruptedException) {
                Thread.currentThread().interrupt()
                fail("interrupted requesting $path: $exc")
            }
        }
        fail("no response for $path within deadline ($last)")
    }

    private fun joinPoolWarm() {
        Thread.getAllStackTraces().keys.filter { it.name == "pool-warm" }.forEach { thread ->
            thread.join(15_000)
            assertTrue(!thread.isAlive, "pool warm still running")
        }
    }
}
