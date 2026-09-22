package carolina

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RouteCoverageTest {
    private val seenSql = mutableListOf<String>()

    @BeforeTest
    fun installFakeCatalog() {
        resetPool()
        resetCounts()
        seenSql.clear()
        connectFn = { fakeConnection() }
        queryFn = ::fakeCatalog
    }

    @AfterTest
    fun removeFakeCatalog() {
        resetPool()
        resetCounts()
    }

    @Test
    fun rootAndHealthSkipSqlAndJdbc() =
        testApplication {
            application { carolinaModule() }
            val health = client.get("/health")
            val healthBody = health.bodyAsText()
            assertEquals(HttpStatusCode.OK, health.status, healthBody)
            assertPolyglot(health)
            assertEquals(true, mapper.readTree(healthBody).get("ok").asBoolean())
            assertTrue(healthBody.contains("\"ok\":true"), healthBody)
            val root = client.get("/")
            val rootBody = root.bodyAsText()
            assertEquals(HttpStatusCode.OK, root.status, rootBody)
            assertPolyglot(root)
            val tree = mapper.readTree(rootBody)
            assertEquals("Kotlin", tree.get("language").asText())
            assertEquals("Ktor", tree.get("framework").asText())
            assertEquals(0, sqlCount.get(), "root or health ran SQL")
            assertEquals(0, connectCount.get(), "root or health opened Postgres")
        }

    @Test
    fun publishedCatalogRoutesReturnDataEnvelope() =
        testApplication {
            application { carolinaModule() }
            val paths =
                listOf(
                    "/v1/years",
                    "/v1/speakers",
                    "/v1/speakers?year=2026",
                    "/v1/speakers/ada",
                    "/v1/speakers/2026/ada",
                    "/v1/sponsors",
                    "/v1/sponsors?year=2026",
                    "/v1/sponsors/acme",
                    "/v1/sponsors/2026/acme",
                )
            for (path in paths) {
                val response = client.get(path)
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, "$path $body")
                assertPolyglot(response)
                assertTrue(mapper.readTree(body).has("data"), "$path $body")
            }
            assertTrue(seenSql.isNotEmpty(), "catalog routes did not query")
            assertTrue(seenSql.all { it.contains("v1_") }, seenSql.toString())
        }

    @Test
    fun speakerAndSponsorDetailsUseShippedShape() =
        testApplication {
            application { carolinaModule() }
            val speaker = client.get("/v1/speakers/ada")
            val speakerBody = speaker.bodyAsText()
            assertEquals(HttpStatusCode.OK, speaker.status, speakerBody)
            assertPolyglot(speaker)
            val speakerData = mapper.readTree(speakerBody).get("data")
            assertEquals("ada", speakerData.get("slug").asText())
            assertEquals(2026, speakerData.get("years").get(0).asInt())
            assertEquals(2024, speakerData.get("years").get(1).asInt())
            assertTrue(speakerData.get("talks").size() >= 2)

            val byYear = client.get("/v1/speakers/2026/ada")
            val byYearBody = byYear.bodyAsText()
            assertEquals(HttpStatusCode.OK, byYear.status, byYearBody)
            assertPolyglot(byYear)
            val yearData = mapper.readTree(byYearBody).get("data")
            assertEquals(2026, yearData.get("year").asInt())
            assertEquals(2026, yearData.get("years").get(0).asInt())
            assertEquals(2024, yearData.get("years").get(1).asInt())
            assertEquals(2024, yearData.get("other_years").get(0).asInt())

            val sponsor = client.get("/v1/sponsors/acme")
            val sponsorBody = sponsor.bodyAsText()
            assertEquals(HttpStatusCode.OK, sponsor.status, sponsorBody)
            assertPolyglot(sponsor)
            assertTrue(
                mapper
                    .readTree(sponsorBody)
                    .get("data")
                    .get("sponsorships")
                    .size() >= 1,
            )

            val sponsorYear = client.get("/v1/sponsors/2026/acme")
            val sponsorYearBody = sponsorYear.bodyAsText()
            assertEquals(HttpStatusCode.OK, sponsorYear.status, sponsorYearBody)
            assertPolyglot(sponsorYear)
            assertEquals(
                "acme",
                mapper
                    .readTree(sponsorYearBody)
                    .get("data")
                    .get("slug")
                    .asText(),
            )
        }

    @Test
    fun unknownSlugAndPathAreNotFound() =
        testApplication {
            application { carolinaModule() }
            val sqlBeforePath = sqlCount.get()
            val connectsBeforePath = connectCount.get()
            val missingPath = client.get("/not-a-real-route")
            assertNotFound(missingPath, missingPath.bodyAsText())
            assertEquals(sqlBeforePath, sqlCount.get(), "unknown path ran SQL")
            assertEquals(connectsBeforePath, connectCount.get(), "unknown path opened Postgres")
            val missing =
                listOf(
                    "/v1/speakers/no-such",
                    "/v1/speakers/2026/no-such",
                    "/v1/sponsors/no-such",
                    "/v1/sponsors/1999/acme",
                    "/v1/nope",
                )
            for (path in missing) {
                val response = client.get(path)
                assertNotFound(response, response.bodyAsText())
            }
        }

    @Test
    fun yearFilteredSpeakersUseBoundedSqlAndDescendingYears() =
        testApplication {
            application { carolinaModule() }
            sqlCount.set(0)
            val first = client.get("/v1/speakers?year=2026")
            val firstSql = sqlCount.get()
            val connects = connectCount.get()
            sqlCount.set(0)
            val second = client.get("/v1/speakers?year=2026")
            val body = second.bodyAsText()
            val secondSql = sqlCount.get()
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertEquals(HttpStatusCode.OK, second.status, body)
            assertPolyglot(second)
            assertEquals(connects, connectCount.get(), "second year listing opened a connection")
            val speakers = mapper.readTree(body).get("data").size()
            assertTrue(speakers >= 3, "expected >=3 speakers, got $speakers")
            assertTrue(firstSql in 1..4, "first SQL $firstSql")
            assertTrue(secondSql in 1..4, "second SQL $secondSql")
            assertTrue(firstSql < 2 * speakers, "first SQL $firstSql grew like 2N for N=$speakers")
            assertTrue(secondSql < 2 * speakers, "second SQL $secondSql grew like 2N for N=$speakers")
            assertYearsDescending(body)
        }

    private fun assertPolyglot(response: HttpResponse) {
        assertEquals("Kotlin", response.headers["X-Polyglot-Language"], response.headers.toString())
        assertEquals("Ktor", response.headers["X-Polyglot-Framework"], response.headers.toString())
    }

    private fun assertNotFound(
        response: HttpResponse,
        body: String,
    ) {
        assertEquals(HttpStatusCode.NotFound, response.status, body)
        assertPolyglot(response)
        assertEquals("""{"error":"not_found"}""", body)
    }

    private fun assertYearsDescending(body: String) {
        val data = mapper.readTree(body).get("data")
        var foundMulti = false
        data.forEach { speaker ->
            val years = speaker.get("years") ?: return@forEach
            if (!years.isArray || years.size() < 2) return@forEach
            foundMulti = true
            var prev = Int.MAX_VALUE
            years.forEach { node ->
                val year = node.asInt()
                if (year > prev) fail("years not DESC: $years")
                prev = year
            }
        }
        assertTrue(foundMulti, "expected a speaker with >=2 years")
    }

    private fun fakeCatalog(
        sql: String,
        args: Array<out Any?>,
    ): MutableList<MutableMap<String, Any?>> {
        seenSql.add(sql)
        val lowered = sql.lowercase()
        assertTrue(sql.contains("v1_"), sql)
        assertTrue(!lowered.contains("insert") && !lowered.contains("update") && !lowered.contains("delete"), sql)
        assertTrue(!lowered.contains("ash_"), sql)
        return when {
            sql.contains("FROM v1_years") -> yearRows()
            sql.contains("FROM v1_year_sponsors") -> yearSponsorRows(args)
            sql.contains("FROM v1_sponsorships") -> sponsorshipRows(args)
            sql.contains("FROM v1_sponsors") -> sponsorRows(sql, args)
            sql.contains("FROM v1_speakers") -> speakerRows(sql, args)
            sql.contains("FROM v1_talks") -> talkRows(sql, args)
            else -> fail("unexpected sql $sql")
        }
    }

    private fun speakerRows(
        sql: String,
        args: Array<out Any?>,
    ): MutableList<MutableMap<String, Any?>> {
        val all = allSpeakers()
        if (sql.contains("slug = ?")) {
            val slug = args[0] as String
            return all.filter { it["slug"] == slug }.toMutableList()
        }
        if (sql.contains("slug IN")) {
            val year = args[0] as Int
            val slugs = talks.filter { it.year == year }.map { it.speaker }.toSet()
            return all.filter { it["slug"] in slugs }.toMutableList()
        }
        return all.toMutableList()
    }

    private fun talkRows(
        sql: String,
        args: Array<out Any?>,
    ): MutableList<MutableMap<String, Any?>> {
        if (sql.contains("ANY(")) return yearsForSlugs(args)
        if (sql.contains("SELECT DISTINCT year")) return distinctYears(args)
        if (sql.contains("speaker_slug = ? AND year = ?")) return talksForSpeakerYear(args)
        if (sql.contains("WHERE year = ?")) return talksForYear(args)
        if (sql.contains("speaker_slug = ?")) return talksForSpeaker(args)
        return mutableListOf()
    }

    private fun yearsForSlugs(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val slugs = (args[0] as Array<*>).map { it.toString() }.toSet()
        return talks
            .filter { it.speaker in slugs }
            .map { it.speaker to it.year }
            .distinct()
            .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenByDescending { it.second })
            .map { (slug, year) -> mutableMapOf<String, Any?>("speaker_slug" to slug, "year" to year) }
            .toMutableList()
    }

    private fun distinctYears(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val slug = args[0] as String
        return talks
            .filter { it.speaker == slug }
            .map { it.year }
            .distinct()
            .sortedDescending()
            .map { year -> mutableMapOf<String, Any?>("year" to year) }
            .toMutableList()
    }

    private fun talksForSpeakerYear(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val slug = args[0] as String
        val year = args[1] as Int
        return talks.filter { it.speaker == slug && it.year == year }.map { it.toRow() }.toMutableList()
    }

    private fun talksForYear(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val year = args[0] as Int
        return talks.filter { it.year == year }.map { it.toRow() }.toMutableList()
    }

    private fun talksForSpeaker(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val slug = args[0] as String
        return talks
            .filter { it.speaker == slug }
            .sortedByDescending { it.year }
            .map { it.toRow() }
            .toMutableList()
    }

    private fun sponsorRows(
        sql: String,
        args: Array<out Any?>,
    ): MutableList<MutableMap<String, Any?>> {
        if (!sql.contains("slug = ?")) return mutableListOf(sponsorRow())
        val slug = args[0] as String
        if (slug != "acme") return mutableListOf()
        return mutableListOf(sponsorRow())
    }

    private fun yearSponsorRows(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        val year = args[0] as Int
        if (args.size > 1 && args[1] != "acme") return mutableListOf()
        if (year != 2026) return mutableListOf()
        return mutableListOf(yearSponsor(year))
    }

    private fun sponsorshipRows(args: Array<out Any?>): MutableList<MutableMap<String, Any?>> {
        if (args[0] != "acme") return mutableListOf()
        return mutableListOf(
            mutableMapOf(
                "sponsor_slug" to "acme",
                "year" to 2026,
                "tier" to "gold",
                "blurb" to "hi",
                "featured" to true,
            ),
            mutableMapOf(
                "sponsor_slug" to "acme",
                "year" to 2024,
                "tier" to "silver",
                "blurb" to "yo",
                "featured" to false,
            ),
        )
    }

    private fun yearRows(): MutableList<MutableMap<String, Any?>> =
        mutableListOf(
            mutableMapOf("year" to 2026, "slug" to "2026", "name" to "2026", "status" to "announced"),
            mutableMapOf("year" to 2025, "slug" to "2025", "name" to "2025", "status" to "past"),
        )

    private fun allSpeakers(): List<MutableMap<String, Any?>> =
        listOf(
            speakerRow("ada", "Ada", "Lovelace"),
            speakerRow("barbara", "Barbara", "Liskov"),
            speakerRow("grace", "Grace", "Hopper"),
            speakerRow("linus", "Linus", "Torvalds"),
        )

    private fun speakerRow(
        slug: String,
        first: String,
        last: String,
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "slug" to slug,
            "first_name" to first,
            "last_name" to last,
            "name" to "$first $last",
            "tagline" to null,
            "bio" to null,
            "company" to null,
            "location" to null,
            "photo_path" to null,
            "twitter_url" to null,
            "linkedin_url" to null,
            "website_url" to null,
            "github_url" to null,
            "featured" to false,
        )

    private fun sponsorRow(): MutableMap<String, Any?> =
        mutableMapOf(
            "slug" to "acme",
            "name" to "Acme",
            "website" to "https://acme.example",
            "logo_path" to null,
            "description" to null,
            "twitter_url" to null,
            "linkedin_url" to null,
            "youtube_url" to null,
            "instagram_url" to null,
            "facebook_url" to null,
        )

    private fun yearSponsor(year: Int): MutableMap<String, Any?> =
        sponsorRow().also { row ->
            row["blurb"] = "hi"
            row["tier"] = "gold"
            row["featured"] = true
            row["year"] = year
        }

    private fun Talk.toRow(): MutableMap<String, Any?> =
        mutableMapOf(
            "slug" to slug,
            "title" to "Talk $slug",
            "description" to null,
            "format" to "talk",
            "youtube_id" to null,
            "year" to year,
            "speaker_slug" to speaker,
            "languages" to listOf("kotlin"),
            "topics" to listOf("jvm"),
        )

    private fun fakeConnection(): Connection {
        val handler =
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method.returnType) {
                    Void.TYPE -> null
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }

    private data class Talk(
        val slug: String,
        val year: Int,
        val speaker: String,
    )

    private val talks =
        listOf(
            Talk("ada-2026", 2026, "ada"),
            Talk("ada-2024", 2024, "ada"),
            Talk("linus-2026", 2026, "linus"),
            Talk("linus-2023", 2023, "linus"),
            Talk("barbara-2026", 2026, "barbara"),
            Talk("grace-2025", 2025, "grace"),
        )
}
