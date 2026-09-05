package carolina

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
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
import java.sql.SQLException
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal const val POOL_SIZE = 8
internal const val POOL_WARM = 4
internal const val POOL_WAIT_MS = 10_000L
internal val mapper = jacksonObjectMapper()
internal val sqlCount = AtomicInteger()
internal val connectCount = AtomicInteger()

private val poolLock = Any()
private val idle = ArrayDeque<Connection>()
private var poolOpened = 0
private var poolReady = false
private var driverLoaded = false

internal var connectFn: (() -> Connection)? = null
internal var queryFn: ((String, Array<out Any?>) -> MutableList<MutableMap<String, Any?>>)? = null

fun main() {
    loadDriver()
    openPool()
    val listenPort = env("PORT", "4013").toInt()
    thread(isDaemon = true, name = "polyglot-register") { register(listenPort) }
    embeddedServer(
        CIO,
        applicationEnvironment(),
        {
            connector {
                host = listenHost()
                port = listenPort
            }
            connectionGroupSize = 1
            workerGroupSize = 1
            callGroupSize = 2
        },
        Application::carolinaModule,
    ).start(wait = true)
}

internal fun listenHost(): String = "::"

internal fun resetCounts() {
    sqlCount.set(0)
    connectCount.set(0)
}

fun Application.carolinaModule() {
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
            val rows = withDbIo { conn ->
                query(conn, "SELECT year, slug, name, status FROM v1_years ORDER BY year DESC")
            }
            call.json(mapOf("data" to rows))
        }
        get("/v1/speakers") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val speakers = withDbIo { conn -> listSpeakers(conn, year) }
            call.json(mapOf("data" to speakers))
        }
        get("/v1/speakers/{year}/{slug}") {
            val year = call.parameters["year"]?.toIntOrNull()
            val slug = call.parameters["slug"] ?: return@get call.notFound()
            if (year == null) return@get call.notFound()
            val speaker = withDbIo { conn ->
                val row = loadSpeaker(conn, slug) ?: return@withDbIo null
                val talks = talksFor(conn, slug, year)
                if (talks.isEmpty()) return@withDbIo null
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
            val speaker = withDbIo { conn ->
                val row = loadSpeaker(conn, slug) ?: return@withDbIo null
                val talks = talksFor(conn, slug, null)
                row["talks"] = talks
                row["years"] = yearsFromTalks(talks)
                row
            } ?: return@get call.notFound()
            call.json(mapOf("data" to speaker))
        }
        get("/v1/sponsors") {
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            val rows = withDbIo { conn ->
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
            val row = withDbIo { conn ->
                val sponsor = queryOne(
                    conn,
                    "SELECT $YEAR_SPONSOR_COLS FROM v1_year_sponsors WHERE year = ? AND slug = ?",
                    year,
                    slug,
                ) ?: return@withDbIo null
                val years = sponsorYears(conn, slug)
                sponsor["years"] = years
                sponsor["other_years"] = years.filter { it != year }
                sponsor
            } ?: return@get call.notFound()
            call.json(mapOf("data" to row))
        }
        get("/v1/sponsors/{slug}") {
            val slug = call.parameters["slug"] ?: return@get call.notFound()
            val row = withDbIo { conn ->
                val sponsor = queryOne(
                    conn,
                    "SELECT $SPONSOR_COLS FROM v1_sponsors WHERE slug = ?",
                    slug,
                ) ?: return@withDbIo null
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
}

private suspend fun ApplicationCall.json(payload: Any, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header("X-Polyglot-Language", LANGUAGE)
    response.header("X-Polyglot-Framework", FRAMEWORK)
    respondBytes(mapper.writeValueAsBytes(payload), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.notFound() {
    json(mapOf("error" to "not_found"), HttpStatusCode.NotFound)
}

internal fun env(key: String, fallback: String): String {
    val value = System.getenv(key)
    return if (value.isNullOrBlank()) fallback else value
}

internal fun jdbcUrl(): String {
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
    var jdbc = "jdbc:postgresql://$host:$port/$db?user=$user&password=$pass$extra"
    if (!jdbc.contains("sslmode=")) jdbc += "&sslmode=disable"
    if (!jdbc.contains("ssl=")) jdbc += "&ssl=false"
    jdbc = appendParam(jdbc, "tcpKeepAlive", "true")
    jdbc = appendParam(jdbc, "connectTimeout", "10")
    jdbc = appendParam(jdbc, "socketTimeout", "30")
    jdbc = appendParam(jdbc, "loginTimeout", "10")
    jdbc = appendParam(jdbc, "ApplicationName", "carolina-codes-kotlin")
    return jdbc
}

internal fun appendParam(jdbc: String, key: String, value: String): String {
    if (jdbc.contains("$key=")) return jdbc
    return "$jdbc&$key=$value"
}

internal fun loadDriver() {
    if (driverLoaded) return
    Class.forName("org.postgresql.Driver")
    DriverManager.setLoginTimeout(10)
    driverLoaded = true
}

internal fun newJdbc(): Connection {
    loadDriver()
    return DriverManager.getConnection(jdbcUrl())
}

internal fun openConnection(): Connection {
    connectCount.incrementAndGet()
    connectFn?.let { return it() }
    return newJdbc()
}

internal fun openPool() {
    synchronized(poolLock) {
        if (poolReady) return
        idle.addLast(openConnection())
        poolOpened = 1
        while (poolOpened < POOL_WARM) {
            try {
                idle.addLast(openConnection())
                poolOpened++
            } catch (_: Exception) {
                break
            }
        }
        poolReady = true
    }
}

internal fun acquire(): Connection {
    synchronized(poolLock) {
        if (!poolReady) openPool()
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(POOL_WAIT_MS)
    while (true) {
        val conn = takeIdleOrGrow()
        if (conn != null) {
            if (usable(conn)) return conn
            discardBroken(conn)
            continue
        }
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs <= 0) {
            throw SQLException("connection pool exhausted")
        }
        synchronized(poolLock) {
            if (idle.isEmpty() && poolOpened >= POOL_SIZE) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (poolLock as Object).wait(remainingMs)
            }
        }
    }
}

internal fun takeIdleOrGrow(): Connection? {
    synchronized(poolLock) {
        if (idle.isNotEmpty()) return idle.removeFirst()
        if (poolOpened >= POOL_SIZE) return null
        poolOpened++
    }
    return try {
        openConnection()
    } catch (exc: Exception) {
        synchronized(poolLock) {
            poolOpened = (poolOpened - 1).coerceAtLeast(0)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (poolLock as Object).notify()
        }
        throw exc
    }
}

internal fun usable(conn: Connection): Boolean {
    if (connectFn != null) return true
    return try {
        !conn.isClosed && conn.isValid(1)
    } catch (_: SQLException) {
        false
    }
}

internal fun discardBroken(conn: Connection?) {
    synchronized(poolLock) {
        poolOpened = (poolOpened - 1).coerceAtLeast(0)
    }
    if (conn != null) {
        try {
            conn.close()
        } catch (_: Exception) {
        }
    }
}

internal fun release(conn: Connection?) {
    if (conn == null) return
    synchronized(poolLock) {
        idle.addLast(conn)
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (poolLock as Object).notify()
    }
}

internal fun <T> withDb(block: (Connection) -> T): T {
    var conn: Connection? = acquire()
    try {
        return block(conn!!)
    } catch (exc: SQLException) {
        discardBroken(conn)
        conn = null
        throw exc
    } finally {
        release(conn)
    }
}

internal suspend fun <T> withDbIo(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) { withDb(block) }

internal fun query(conn: Connection, sql: String, vararg args: Any?): MutableList<MutableMap<String, Any?>> {
    sqlCount.incrementAndGet()
    queryFn?.let { return it(sql, args) }
    conn.prepareStatement(sql).use { stmt ->
        args.forEachIndexed { index, value -> bind(stmt, conn, index + 1, value) }
        stmt.executeQuery().use { rs ->
            val labels = columnLabels(rs)
            val out = mutableListOf<MutableMap<String, Any?>>()
            while (rs.next()) out.add(row(rs, labels))
            return out
        }
    }
}

private fun bind(stmt: java.sql.PreparedStatement, conn: Connection, idx: Int, value: Any?) {
    when (value) {
        is Int -> stmt.setInt(idx, value)
        is Long -> stmt.setLong(idx, value)
        is Array<*> -> stmt.setArray(idx, conn.createArrayOf("text", value))
        is List<*> -> stmt.setArray(idx, conn.createArrayOf("text", value.toTypedArray()))
        else -> stmt.setObject(idx, value)
    }
}

internal fun queryOne(conn: Connection, sql: String, vararg args: Any?): MutableMap<String, Any?>? {
    return query(conn, sql, *args).firstOrNull()
}

private fun columnLabels(rs: ResultSet): Array<String> {
    val md = rs.metaData
    return Array(md.columnCount) { md.getColumnLabel(it + 1) }
}

private fun row(rs: ResultSet, labels: Array<String>): MutableMap<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for (i in labels.indices) {
        out[labels[i]] = clean(rs.getObject(i + 1))
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

internal fun listSpeakers(conn: Connection, year: Int?): MutableList<MutableMap<String, Any?>> {
    if (year == null) {
        return query(conn, "SELECT $SPEAKER_COLS FROM v1_speakers ORDER BY last_name, first_name")
    }
    val speakers = query(
        conn,
        "SELECT $SPEAKER_COLS FROM v1_speakers WHERE slug IN (SELECT speaker_slug FROM v1_talks WHERE year = ?) ORDER BY last_name, first_name",
        year,
    )
    return attachYearTags(conn, speakers, year)
}

internal fun attachYearTags(
    conn: Connection,
    speakers: MutableList<MutableMap<String, Any?>>,
    year: Int,
): MutableList<MutableMap<String, Any?>> {
    if (speakers.isEmpty()) return speakers
    val slugs = speakers.map { it["slug"] as String }
    val talksBy = loadTalksForYear(conn, year)
    val yearsBy = loadYearsForSlugs(conn, slugs)
    speakers.forEach { speaker ->
        val slug = speaker["slug"] as String
        val talks = talksBy[slug] ?: emptyList()
        val years = yearsBy[slug] ?: emptyList()
        speaker["year"] = year
        speaker["talks"] = talks
        speaker["languages"] = uniqTags(talks, "languages")
        speaker["topics"] = uniqTags(talks, "topics")
        speaker["years"] = years
        speaker["other_years"] = years.filter { it != year }
    }
    return speakers
}

internal fun loadTalksForYear(conn: Connection, year: Int): Map<String, List<MutableMap<String, Any?>>> {
    val out = linkedMapOf<String, MutableList<MutableMap<String, Any?>>>()
    query(
        conn,
        "SELECT $TALK_COLS FROM v1_talks WHERE year = ? ORDER BY speaker_slug, year DESC",
        year,
    ).forEach { talk ->
        val slug = talk["speaker_slug"]?.toString() ?: return@forEach
        out.getOrPut(slug) { mutableListOf() }.add(talk)
    }
    return out
}

internal fun loadYearsForSlugs(conn: Connection, slugs: List<String>): Map<String, List<Int>> {
    val out = linkedMapOf<String, MutableList<Int>>()
    if (slugs.isEmpty()) return out
    query(
        conn,
        "SELECT DISTINCT speaker_slug, year FROM v1_talks WHERE speaker_slug = ANY(?::text[]) ORDER BY speaker_slug, year DESC",
        slugs.toTypedArray(),
    ).forEach { row ->
        val slug = row["speaker_slug"]?.toString() ?: return@forEach
        out.getOrPut(slug) { mutableListOf() }.add(asInt(row["year"]))
    }
    return out
}

private fun asInt(value: Any?): Int {
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toInt()
    }
}

internal fun loadSpeaker(conn: Connection, slug: String): MutableMap<String, Any?>? {
    return queryOne(conn, "SELECT $SPEAKER_COLS FROM v1_speakers WHERE slug = ?", slug)
}

internal fun talksFor(conn: Connection, slug: String, year: Int?): List<MutableMap<String, Any?>> {
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

internal fun talkYears(conn: Connection, slug: String): List<Int> {
    return query(conn, "SELECT DISTINCT year FROM v1_talks WHERE speaker_slug = ? ORDER BY year DESC", slug)
        .map { asInt(it["year"]) }
}

internal fun yearsFromTalks(talks: List<Map<String, Any?>>): List<Int> {
    val seen = linkedSetOf<Int>()
    talks.forEach { seen.add(asInt(it["year"])) }
    return seen.toList()
}

internal fun sponsorYears(conn: Connection, slug: String): List<Int> {
    return query(
        conn,
        "SELECT DISTINCT year FROM v1_sponsorships WHERE sponsor_slug = ? ORDER BY year DESC",
        slug,
    ).map { asInt(it["year"]) }
}

@Suppress("UNCHECKED_CAST")
internal fun uniqTags(talks: List<Map<String, Any?>>, key: String): List<String> {
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
