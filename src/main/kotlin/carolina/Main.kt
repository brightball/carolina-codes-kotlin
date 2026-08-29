package carolina

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import kotlin.concurrent.thread

const val LANGUAGE = "Kotlin"
const val API_VERSION = "0.2.0"
const val FRAMEWORK = "Ktor"
const val CREATED_YEAR = 2026
const val SCHEMA_VERSION = 1
const val LANGUAGE_VERSION = "2.2.20"

val ENDPOINTS = listOf(
    mapOf("method" to "GET", "path" to "/", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/health", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/v1/years", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/v1/speakers", "query" to listOf("year")),
    mapOf("method" to "GET", "path" to "/v1/speakers/:slug", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/v1/speakers/:year/:slug", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/v1/sponsors", "query" to listOf("year")),
    mapOf("method" to "GET", "path" to "/v1/sponsors/:slug", "query" to emptyList<String>()),
    mapOf("method" to "GET", "path" to "/v1/sponsors/:year/:slug", "query" to emptyList<String>()),
)

const val SPEAKER_COLS =
    "slug, first_name, last_name, name, tagline, bio, company, location, photo_path, twitter_url, linkedin_url, website_url, github_url, featured"
const val TALK_COLS =
    "slug, title, description, format, youtube_id, year, speaker_slug, languages, topics"
const val YEAR_SPONSOR_COLS =
    "slug, name, website, logo_path, description, blurb, tier, featured, year, twitter_url, linkedin_url, youtube_url, instagram_url, facebook_url"
const val SPONSOR_COLS =
    "slug, name, website, logo_path, description, twitter_url, linkedin_url, youtube_url, instagram_url, facebook_url"

val mapper = jacksonObjectMapper()

fun main() {
    val port = env("PORT", "4013").toInt()
    thread(isDaemon = true, name = "polyglot-register") { register(port) }
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.json(mapOf("error" to (cause.message ?: "internal_error")), HttpStatusCode.InternalServerError)
            }
        }
        routing {
            get("/") {
                call.json(
                    mapOf(
                        "language" to LANGUAGE,
                        "language_version" to LANGUAGE_VERSION,
                        "api_version" to API_VERSION,
                        "framework" to FRAMEWORK,
                        "created_year" to CREATED_YEAR,
                        "schema_version" to SCHEMA_VERSION,
                        "endpoints" to ENDPOINTS,
                    ),
                )
            }
            get("/health") { call.json(mapOf("ok" to true)) }
            get("/v1/years") {
                val rows = withDb { conn ->
                    query(conn, "SELECT year, slug, name, status FROM v1_years ORDER BY year DESC")
                }
                call.json(mapOf("data" to rows))
            }
            get("/v1/speakers") {
                val year = call.request.queryParameters["year"]?.toIntOrNull()
                val speakers = withDb { conn ->
                    if (year != null) {
                        val rows = query(
                            conn,
                            "SELECT $SPEAKER_COLS FROM v1_speakers WHERE slug IN (SELECT speaker_slug FROM v1_talks WHERE year = ?) ORDER BY last_name, first_name",
                            year,
                        )
                        rows.forEach { speaker ->
                            val slug = speaker["slug"] as String
                            val talks = talksFor(conn, slug, year)
                            val years = talkYears(conn, slug)
                            speaker["year"] = year
                            speaker["talks"] = talks
                            speaker["languages"] = uniqTags(talks, "languages")
                            speaker["topics"] = uniqTags(talks, "topics")
                            speaker["years"] = years
                            speaker["other_years"] = years.filter { it != year }
                        }
                        rows
                    } else {
                        query(conn, "SELECT $SPEAKER_COLS FROM v1_speakers ORDER BY last_name, first_name")
                    }
                }
                call.json(mapOf("data" to speakers))
            }
            get("/v1/speakers/{year}/{slug}") {
                val year = call.parameters["year"]?.toIntOrNull()
                val slug = call.parameters["slug"] ?: return@get call.notFound()
                if (year == null) return@get call.notFound()
                val speaker = withDb { conn ->
                    val row = loadSpeaker(conn, slug) ?: return@withDb null
                    val talks = talksFor(conn, slug, year)
                    if (talks.isEmpty()) return@withDb null
                    val years = talkYears(conn, slug)
                    row["year"] = year
                    row["years"] = years
                    row["other_years"] = years.filter { it != year }
                    row["talks"] = talks
                    row["languages"] = uniqTags(talks, "languages")
                    row["topics"] = uniqTags(talks, "topics")
                    row
                } ?: return@get call.notFound()
                call.json(mapOf("data" to speaker))
            }
            get("/v1/speakers/{slug}") {
                val slug = call.parameters["slug"] ?: return@get call.notFound()
                val speaker = withDb { conn ->
                    val row = loadSpeaker(conn, slug) ?: return@withDb null
                    row["talks"] = talksFor(conn, slug, null)
                    row["years"] = talkYears(conn, slug)
                    row
                } ?: return@get call.notFound()
                call.json(mapOf("data" to speaker))
            }
            get("/v1/sponsors") {
                val year = call.request.queryParameters["year"]?.toIntOrNull()
                val rows = withDb { conn ->
                    if (year != null) {
                        query(
                            conn,
                            "SELECT $YEAR_SPONSOR_COLS FROM v1_year_sponsors WHERE year = ? ORDER BY name",
                            year,
                        )
                    } else {
                        query(conn, "SELECT $SPONSOR_COLS FROM v1_sponsors ORDER BY name")
                    }
                }
                call.json(mapOf("data" to rows))
            }
            get("/v1/sponsors/{year}/{slug}") {
                val year = call.parameters["year"]?.toIntOrNull()
                val slug = call.parameters["slug"] ?: return@get call.notFound()
                if (year == null) return@get call.notFound()
                val row = withDb { conn ->
                    val sponsor = queryOne(
                        conn,
                        "SELECT $YEAR_SPONSOR_COLS FROM v1_year_sponsors WHERE year = ? AND slug = ?",
                        year,
                        slug,
                    ) ?: return@withDb null
                    val years = sponsorYears(conn, slug)
                    sponsor["years"] = years
                    sponsor["other_years"] = years.filter { it != year }
                    sponsor
                } ?: return@get call.notFound()
                call.json(mapOf("data" to row))
            }
            get("/v1/sponsors/{slug}") {
                val slug = call.parameters["slug"] ?: return@get call.notFound()
                val row = withDb { conn ->
                    val sponsor = queryOne(
                        conn,
                        "SELECT $SPONSOR_COLS FROM v1_sponsors WHERE slug = ?",
                        slug,
                    ) ?: return@withDb null
                    sponsor["sponsorships"] = query(
                        conn,
                        "SELECT sponsor_slug, year, tier, blurb, featured FROM v1_sponsorships WHERE sponsor_slug = ? ORDER BY year DESC",
                        slug,
                    )
                    sponsor
                } ?: return@get call.notFound()
                call.json(mapOf("data" to row))
            }
            get("{path...}") { call.notFound() }
        }
    }.start(wait = true)
}

private suspend fun ApplicationCall.json(payload: Any, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header("X-Polyglot-Language", LANGUAGE)
    response.header("X-Polyglot-Framework", FRAMEWORK)
    respondText(mapper.writeValueAsString(payload), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.notFound() {
    json(mapOf("error" to "not_found"), HttpStatusCode.NotFound)
}

private fun env(key: String, fallback: String): String {
    val value = System.getenv(key)
    return if (value.isNullOrBlank()) fallback else value
}

private fun jdbcUrl(): String {
    val raw = env("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:5432/carolina_dev")
    val normalized = raw.replace(Regex("^postgres://"), "postgresql://")
    val uri = URI(normalized)
    val userInfo = uri.userInfo?.split(":", limit = 2)
    val user = URLEncoder.encode(userInfo?.getOrNull(0) ?: "postgres", StandardCharsets.UTF_8)
    val pass = URLEncoder.encode(userInfo?.getOrNull(1) ?: "postgres", StandardCharsets.UTF_8)
    val host = uri.host ?: "127.0.0.1"
    val port = if (uri.port > 0) uri.port else 5432
    val db = uri.path.trimStart('/')
    val extra = uri.rawQuery?.let { "&$it" } ?: ""
    return "jdbc:postgresql://$host:$port/$db?user=$user&password=$pass$extra"
}

private inline fun <T> withDb(block: (Connection) -> T): T {
    return DriverManager.getConnection(jdbcUrl()).use(block)
}

private fun query(conn: Connection, sql: String, vararg args: Any?): MutableList<MutableMap<String, Any?>> {
    conn.prepareStatement(sql).use { stmt ->
        args.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
        stmt.executeQuery().use { rs ->
            val out = mutableListOf<MutableMap<String, Any?>>()
            while (rs.next()) out.add(row(rs))
            return out
        }
    }
}

private fun queryOne(conn: Connection, sql: String, vararg args: Any?): MutableMap<String, Any?>? {
    return query(conn, sql, *args).firstOrNull()
}

private fun row(rs: ResultSet): MutableMap<String, Any?> {
    val md = rs.metaData
    val out = linkedMapOf<String, Any?>()
    for (i in 1..md.columnCount) {
        val name = md.getColumnLabel(i)
        out[name] = clean(rs.getObject(i))
    }
    return out
}

private fun clean(value: Any?): Any? {
    return when (value) {
        null -> null
        is java.sql.Array -> {
            val arr = value.array as Array<*>
            arr.map { it?.toString() }.filterNot { it.isNullOrEmpty() }
        }
        is java.sql.Date -> value.toString()
        is java.sql.Timestamp -> value.toString()
        else -> value
    }
}

private fun loadSpeaker(conn: Connection, slug: String): MutableMap<String, Any?>? {
    return queryOne(conn, "SELECT $SPEAKER_COLS FROM v1_speakers WHERE slug = ?", slug)
}

private fun talksFor(conn: Connection, slug: String, year: Int?): List<MutableMap<String, Any?>> {
    return if (year == null) {
        query(conn, "SELECT $TALK_COLS FROM v1_talks WHERE speaker_slug = ? ORDER BY year DESC", slug)
    } else {
        query(
            conn,
            "SELECT $TALK_COLS FROM v1_talks WHERE speaker_slug = ? AND year = ? ORDER BY year DESC",
            slug,
            year,
        )
    }
}

private fun talkYears(conn: Connection, slug: String): List<Int> {
    return query(conn, "SELECT DISTINCT year FROM v1_talks WHERE speaker_slug = ? ORDER BY year DESC", slug)
        .map { (it["year"] as Number).toInt() }
}

private fun sponsorYears(conn: Connection, slug: String): List<Int> {
    return query(
        conn,
        "SELECT DISTINCT year FROM v1_sponsorships WHERE sponsor_slug = ? ORDER BY year DESC",
        slug,
    ).map { (it["year"] as Number).toInt() }
}

@Suppress("UNCHECKED_CAST")
private fun uniqTags(talks: List<Map<String, Any?>>, key: String): List<String> {
    val seen = linkedSetOf<String>()
    talks.forEach { talk ->
        val values = talk[key] as? List<*> ?: emptyList<Any>()
        values.forEach { value ->
            val text = value?.toString().orEmpty()
            if (text.isNotEmpty()) seen.add(text)
        }
    }
    return seen.toList()
}

private fun register(port: Int) {
    val url = System.getenv("CAROLINA_URL") ?: return
    val token = System.getenv("POLYGLOT_REGISTER_TOKEN") ?: return
    if (url.isBlank() || token.isBlank()) return
    val base = env("PUBLIC_BASE_URL", "http://127.0.0.1:$port")
    val body = mapper.writeValueAsString(
        mapOf(
            "language" to LANGUAGE,
            "language_version" to LANGUAGE_VERSION,
            "api_version" to API_VERSION,
            "framework" to FRAMEWORK,
            "created_year" to CREATED_YEAR,
            "schema_version" to SCHEMA_VERSION,
            "base_url" to base,
            "endpoints" to ENDPOINTS,
        ),
    )
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url.trimEnd('/') + "/internal/api-endpoints/register"))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    try {
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        System.err.println("registered with elixir: ${response.statusCode()}")
    } catch (exc: Exception) {
        System.err.println("register: $exc")
    }
}
