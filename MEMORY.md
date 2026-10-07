# Memory

Current pins and pointers for agents working in this Kotlin / Ktor repo.
Reasons and history are in [DECISIONS.md](DECISIONS.md). If a pin here
disagrees with the build files, trust `build.gradle.kts`, `mise.toml`,
`gradle/wrapper/gradle-wrapper.properties`, and the Dockerfiles, then update
this note in the same change.

Read [AGENTS.md](AGENTS.md) for the HTTP contract and workflow. Read
[DECISIONS.md](DECISIONS.md) before changing toolchain pins, routes, SQL, or CI.

## Active pins

- Kotlin 2.2.20 (`kotlin("jvm")`, `mise.toml`, `LANGUAGE_VERSION` in `Main.kt`)
- Ktor 3.3.1 (`ktorVersion`)
- JDK runtime 27 (`mise.toml` `java = "27"`; `Dockerfile` `openjdk:27-rc` and `openjdk:27-rc-slim`; `.cursor/Dockerfile` `openjdk:27-rc-bookworm`)
- JVM bytecode target 21 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget`)
- Gradle wrapper 9.7.1
- PostgreSQL JDBC 42.7.12
- Jackson `jackson-module-kotlin` 2.18.9
- slf4j-simple 2.0.17
- detekt 2.0.0-alpha.6
- ktlint 1.7.1
- gitleaks 8.30.1
- osv-scanner 2.2.3 scanning `gradle.lockfile` (`./gradlew --no-daemon osvScan`)
- API `0.2.0`, language `Kotlin`, framework `Ktor`, `schema_version` 1, `created_year` 2026
- Local listen port 4013 (`PORT` default in `Main.kt` and the README). Container `PORT` is 8080.

## Do not re-open without a new decision

- Query only the `v1_*` views. No Ash tables. No base tables as the public contract. See D001.
- Register once on boot. No heartbeat. Keep serving if register is skipped or fails. See D002.
- JDK 27 runtime, bytecode 21, Gradle 9.7.1. See D003.
- detekt 2.x, not 1.23.x. See D004.
- Clear CVEs by bumping libraries and regenerating `gradle.lockfile`. See D005.
- Five separate checks: `test`, `sast`, `deps`, `gitleaks`, `style`. See D006.
- `GET /health` and `GET /` must not block on JDBC. See D007.
