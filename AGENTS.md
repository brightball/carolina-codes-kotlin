# carolina-codes-kotlin

Read-only v1 HTTP API for the Carolina Code Conference polyglot fleet. Kotlin,
[Ktor](https://ktor.io/) CIO, and PostgreSQL JDBC serve the v1 REST contract.
This tree is a finished sibling, not the forkable starter, and it is its own
git remote. Treat **this repo** as the workspace root. The Phoenix CMS is a
different remote (`github.com/brightball/carolina-codes`). Do not assume a
sibling CMS checkout (`../elixir` or otherwise) is present.

The **source of truth** for routes and payloads is the CMS contract:
`priv/api/openapi.yaml` and `priv/api/AGENTS.md`. Those files are not in this
tree. Do **not** implement Ash JSON:API (`application/vnd.api+json`). This
service speaks ordinary JSON over the v1 REST + SQL-view contract.

Before changing toolchain pins, HTTP routes, SQL, or CI, read
[MEMORY.md](MEMORY.md) and [DECISIONS.md](DECISIONS.md). `MEMORY.md` is the
short current-state index. `DECISIONS.md` is the append-only decision log.
When a recorded choice changes, append a new entry and mark the old one
superseded. Do not rewrite history.

Install, run, and check commands are in [README.md](README.md).

## Purpose

The Phoenix app (`Carolina.Polyglot`) keeps **at most one** language API warm
and reads speakers and sponsors from it. With no APIs registered, it falls
back to Ash. This process must:

1. Query PostgreSQL **v1 views**, never Ash resource tables and never base
   tables as the public contract.
2. Expose the routes in the CMS OpenAPI contract.
3. **Register once on boot** with the Elixir site (no heartbeat). If the site
   is not running, or the register call is skipped, log and continue serving.

## Environment

| Variable | Example | Role |
|---|---|---|
| `DATABASE_URL` | `postgres://postgres:postgres@127.0.0.1:5432/carolina_dev` | CMS SQL views |
| `CAROLINA_URL` | `http://127.0.0.1:4000` | Elixir site (optional; register no-ops if unset or down) |
| `POLYGLOT_REGISTER_TOKEN` | `dev` | Bearer token for register |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:4013` | URL Elixir will call |
| `PORT` | `4013` | Listen port for local `./gradlew run` |

`Main.kt` defaults `PORT` to **4013**, matching the README. The container
image sets `PORT=8080`. Handler tests that use a fake catalog do not need
Postgres. For live HTTP, point `DATABASE_URL` at the CMS database that owns
the views.

## SQL views (query these)

`v1_speakers`, `v1_sponsors`, `v1_years`, `v1_talks`, `v1_sponsorships`,
`v1_year_speakers`, `v1_year_sponsors`.

The views live in the CMS database. This repo does not ship `db/*.sql`, a
Compose Postgres, or seed data.

Year-scoped speaker listings include `languages` and `topics` (from
`v1_talks`). Year-scoped sponsor rows include `tier` and `blurb` (from
`v1_year_sponsors`).

Do not `SELECT` from `speakers`, `organizations`, `talks`, or other base
tables as the public contract. Do not query Ash tables. The views are the API.

`photo_path` and `logo_path` are returned as the path strings stored on the
views. This process does not serve image bytes.

## Required HTTP routes

Wrap **list** payloads as `{ "data": [ ... ] }`. Single-resource GETs use
`{ "data": { ... } }`. Unknown slugs and unknown paths return 404
`{ "error": "not_found" }`.

- `GET /health` — liveness (`{ "ok": true }`). Cheap: it does not open JDBC and does not need the database.
- `GET /` — identity (`language`, `language_version`, `api_version`, `framework`, `created_year`, `schema_version`, `endpoints`). Also does not open JDBC.
- `GET /v1/years`
- `GET /v1/speakers` and `GET /v1/speakers?year=2025`
- `GET /v1/speakers/{slug}` and `GET /v1/speakers/{year}/{slug}`
- `GET /v1/sponsors` and `GET /v1/sponsors?year=2025`
- `GET /v1/sponsors/{slug}` and `GET /v1/sponsors/{year}/{slug}`

Catalog routes take a pooled JDBC connection on `Dispatchers.IO`. The pool
warms on a background thread after the server is listening, so a closed
database must not block `GET /health` or `GET /`.

Responses set `X-Polyglot-Language: Kotlin` and `X-Polyglot-Framework: Ktor`.

## Register on boot (once)

`POST {CAROLINA_URL}/internal/api-endpoints/register`

```
Authorization: Bearer {POLYGLOT_REGISTER_TOKEN}
Content-Type: application/json
```

The body is JSON with `language` (`Kotlin`), `language_version` (`2.2.20`),
`api_version` (`0.2.0`), `framework` (`Ktor`), `created_year` (`2026`),
`schema_version` (`1`), `base_url` (`PUBLIC_BASE_URL`, defaulting to
`http://127.0.0.1:{port}`), and `endpoints` (the route list from `Main.kt`:
method, path, and query names).

The call runs **once**, on the daemon thread `polyglot-register`, after the
CIO server has started. Do not heartbeat. Elixir keep-alives the currently
warm API.

If `CAROLINA_URL` or `POLYGLOT_REGISTER_TOKEN` is unset or blank, skip the
call. If the POST fails, log and **keep serving**. A running API is useful
without a live CMS.

## Stack

| Pin | Value | Source |
|---|---|---|
| Kotlin | 2.2.20 | `kotlin("jvm")` plugin and `mise.toml` |
| Ktor | 3.3.1 | `ktorVersion` in `build.gradle.kts` |
| JDK runtime | 27 | `mise.toml` `java = "27"`; `Dockerfile` `openjdk:27-rc` / `openjdk:27-rc-slim`; `.cursor/Dockerfile` `openjdk:27-rc-bookworm` |
| JVM bytecode | 21 | `sourceCompatibility`, `targetCompatibility`, and `jvmTarget` |
| Gradle wrapper | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |

The running JVM is JDK 27. Compiled bytecode stays on the Java 21 target.
Do not collapse those two versions into one, and do not enable preview JEPs.

Direct libraries: PostgreSQL JDBC 42.7.12, `jackson-module-kotlin` 2.18.9,
`slf4j-simple` 2.0.17. Application code is one Kotlin file,
`src/main/kotlin/carolina/Main.kt` (`main` class `carolina.MainKt`).

## Quality gates

Five named checks, each a separate command. Do not bundle them into one script.

| Check | Command |
|---|---|
| `test` | `./gradlew --no-daemon test` |
| `sast` | `./gradlew --no-daemon detekt` |
| `deps` | `./gradlew --no-daemon osvScan` |
| `gitleaks` | `gitleaks detect --source . --verbose` |
| `style` | `./gradlew --no-daemon ktlintCheck` |

Local entry: `make hooks` once, then `pre-commit run --all-files` (also
`mise run check`). Wiring lives in `.pre-commit-config.yaml`, `Makefile`,
`.githooks/pre-commit`, and `.gitea/workflows/precommit.yml`.
`src/test/kotlin/carolina/QualityWiringTest.kt` reads that wiring as text.

SAST is detekt 2.0.0-alpha.6 (`config/detekt/detekt.yml`). Style is ktlint
1.7.1. Secrets scanning is gitleaks 8.30.1. Dependency scanning is
osv-scanner 2.2.3 against the committed `gradle.lockfile` (not a live Maven
resolution). After a dependency bump, regenerate the lockfile with
`./gradlew resolveAndLockAll --write-locks`. Clear a real CVE by bumping the
library and regenerating the lockfile.

Emergency skip: `SKIP=test,sast,deps,gitleaks,style git commit`.

## Layout

| Path | Role |
|---|---|
| `src/main/kotlin/carolina/Main.kt` | Routes, SQL, pool, register-once |
| `src/test/kotlin/carolina/` | JUnit 5 tests via kotlin.test (fake catalog; no Postgres) |
| `build.gradle.kts` | Kotlin, Ktor, libraries, detekt, ktlint, `osvScan`, dependency locking |
| `gradle.lockfile` | Locked graphs scanned by OSV |
| `mise.toml` | Kotlin, JDK, Gradle, gitleaks, and osv-scanner pins |
| `Dockerfile` | Multi-stage image on `openjdk:27-rc` (build) and `openjdk:27-rc-slim` (runtime) |
| `.cursor/Dockerfile` | Cloud-agent image on `openjdk:27-rc-bookworm` |
| `config/detekt/detekt.yml` | detekt 2.x rules |
| `.pre-commit-config.yaml` | The five local checks |
| `.gitea/workflows/precommit.yml` | The same five checks as separate CI jobs |
| `DECISIONS.md` | Append-only decision log |
| `MEMORY.md` | Current pins and pointers |
| `README.md` | Run and check commands |

This tree does not ship `docker-compose.yml`, `db/*.sql`, `images/`,
`tests/test_catalog.py`, or a local `openapi.yaml`. The Dockerfiles are the
runtime images for this API. Do not replace them with a starter placeholder.

## Recording decisions

Kotlin/Gradle pins live in `build.gradle.kts`, `mise.toml`, and the
Dockerfiles. Those files are the machine-readable source. Humans and agents
still need a reason they can find later, so this repo keeps two Markdown
files instead of a `docs/adr/` tree (that tree is more process than a single
Ktor module needs):

- `DECISIONS.md` — one append-only log. Each entry has a status, a decision,
  a reason, and enough context to avoid re-litigating it. Status may become
  `Superseded by Dxxx`. The old text stays.
- `MEMORY.md` — current pins and pointers only, including a pointer at
  `DECISIONS.md`. Update it in the same change as a pin. Do not paste the
  full log into it.

Read both before changing pins, routes, SQL, or CI.

## Checklist

- [ ] Contract paths return 200 with the data envelope (404 on an unknown slug or path)
- [ ] `?year=` speaker rows include `languages` / `topics`; sponsor rows include `tier`
- [ ] Register runs once at process start and does not heartbeat
- [ ] Skipped or failed register still serves HTTP
- [ ] No writes; no Ash table names; no base tables as the public contract
- [ ] `GET /health` and `GET /` do not open JDBC
- [ ] JDK runtime stays 27; bytecode target stays 21; Gradle wrapper stays 9.7.1
- [ ] The five named checks stay separate commands
