package carolina

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PerfTest {
    @Test
    fun listenHostIsIpv6() {
        assertEquals("::", listenHost())
        val src = Files.readString(Path.of("src/main/kotlin/carolina/Main.kt"))
        assertTrue(!src.contains("\"0.0.0.0\""), "source still binds 0.0.0.0")
        assertTrue(src.contains("listenHost()"), "main should bind listenHost()")
        assertTrue(src.contains("sslmode=disable"), "JDBC should keep sslmode=disable")
        assertTrue(src.contains("ssl=false"), "JDBC should keep ssl=false")
        assertTrue(jdbcUrl().contains("sslmode=disable"), "jdbcUrl includes sslmode=disable")
        assertTrue(jdbcUrl().contains("connectTimeout=10"), "jdbcUrl includes connectTimeout")
        assertTrue(jdbcUrl().contains("socketTimeout=30"), "jdbcUrl includes socketTimeout")
        assertTrue(jdbcUrl().contains("loginTimeout=10"), "jdbcUrl includes loginTimeout")
        assertTrue(src.contains("Dispatchers.IO"), "DB work should leave the CIO event loop")
        assertTrue(src.contains("POOL_WAIT_MS"), "pool acquire should time out")
        assertTrue(src.contains("POOL_WARM"), "pool should warm more than one connection")
        assertTrue(src.contains("yearsFromTalks"), "slug detail should derive years from talks")
        assertTrue(src.contains("writeValueAsBytes"), "JSON should avoid String materialization")
    }

    @Test
    fun registerDoesNotQueryCatalog() {
        val src = Files.readString(Path.of("src/main/kotlin/carolina/Main.kt"))
        val start = src.indexOf("private fun register(")
        assertTrue(start >= 0, "register exists")
        val fn = src.substring(start)
        assertTrue(!fn.contains("openPool()"), "register-once does not open the pool")
        assertTrue(!fn.contains("openConnection()"), "register-once does not open Postgres")
        assertTrue(!fn.contains("query("), "register-once does not run catalog SQL")
        assertTrue(!fn.contains("withDb"), "register-once does not check out a connection")
    }

    @Test
    fun healthDoesNotQueryOrConnect() {
        val sqlBefore = sqlCount.get()
        val connectBefore = connectCount.get()
        testApplication {
            application { carolinaModule() }
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"ok\":true"), "health body $body")
        }
        assertEquals(sqlBefore, sqlCount.get(), "/health ran SQL")
        assertEquals(connectBefore, connectCount.get(), "/health opened Postgres")
    }

    @Test
    fun yearListingSqlBoundedAndYearsDesc() {
        val live = try {
            openPool()
            true
        } catch (exc: Exception) {
            System.err.println("postgres unavailable, using query hook: ${exc.message}")
            connectFn = { throw SQLException("fake") }
            queryFn = { sql, _ ->
                val rows = mutableListOf<MutableMap<String, Any?>>()
                when {
                    sql.contains("FROM v1_speakers") -> {
                        repeat(3) { i ->
                            rows.add(mutableMapOf("slug" to "s$i", "first_name" to "A", "last_name" to "B"))
                        }
                    }
                    sql.contains("ANY(") -> {
                        rows.add(mutableMapOf("speaker_slug" to "s0", "year" to 2026))
                        rows.add(mutableMapOf("speaker_slug" to "s0", "year" to 2024))
                    }
                    sql.contains("FROM v1_talks") -> {
                        rows.add(
                            mutableMapOf(
                                "slug" to "t0",
                                "title" to "Talk",
                                "speaker_slug" to "s0",
                                "year" to 2026,
                                "languages" to listOf("kotlin"),
                                "topics" to emptyList<String>(),
                            ),
                        )
                    }
                }
                rows
            }
            false
        }

        val bootConnects = connectCount.get()
        sqlCount.set(0)

        testApplication {
            application { carolinaModule() }
            val response = client.get("/v1/speakers?year=2026")
            val body = response.bodyAsText()
            val sql = sqlCount.get()
            val payload = mapper.readTree(body)
            val data = payload.get("data")
            val speakers = if (data != null && data.isArray) data.size() else 0
            System.err.println(
                "year list status=${response.status.value} sql=$sql speakers=$speakers connects=${connectCount.get()}",
            )

            if (live && response.status != HttpStatusCode.OK) {
                fail("live year listing status ${response.status.value} body ${body.take(400)}")
            }

            if (response.status == HttpStatusCode.OK) {
                assertTrue(speakers >= 3, "year listing returns N>=3 speakers, got $speakers")
                assertTrue(sql > 0, "listing runs SQL through shipped query wrapper")
                assertTrue(sql < 2 * speakers, "SQL count $sql grew like 2N for N=$speakers")
                assertTrue(sql <= 4, "year listing SQL $sql should be speakers+talks+years")
                assertYearsDescJson(body)
                assertEquals(bootConnects, connectCount.get(), "listing opened a new session")

                val rows = withDb { conn -> listSpeakers(conn, 2026) }
                assertYearsDescMaps(rows)

                sqlCount.set(0)
                val second = client.get("/v1/speakers?year=2026")
                assertEquals(HttpStatusCode.OK, second.status, "second catalog request succeeds")
                assertEquals(bootConnects, connectCount.get(), "second catalog request opened a new session")
            } else {
                assertTrue(sql < 2 * 3, "failed listing did not run per-row SQL for N=3")
            }
        }
    }

    private fun assertYearsDescJson(body: String) {
        val data = mapper.readTree(body).get("data")
        var foundMulti = false
        data.forEach { speaker ->
            val years = speaker.get("years") ?: return@forEach
            if (!years.isArray || years.size() < 2) return@forEach
            foundMulti = true
            var prev = Int.MAX_VALUE
            years.forEach { node ->
                val y = node.asInt()
                if (y > prev) fail("years not DESC for ${speaker.get("slug")}: $years")
                prev = y
            }
        }
        assertTrue(foundMulti, "expected a speaker with >=2 years")
    }

    private fun assertYearsDescMaps(speakers: List<Map<String, Any?>>) {
        var foundMulti = false
        speakers.forEach { sp ->
            val years = (sp["years"] as? List<*>)?.map { (it as Number).toInt() } ?: return@forEach
            if (years.size < 2) return@forEach
            foundMulti = true
            years.zipWithNext().forEach { (a, b) ->
                if (a < b) fail("years not DESC for ${sp["slug"]}: $years")
            }
        }
        assertTrue(foundMulti, "expected a speaker with >=2 years from listSpeakers")
    }
}
