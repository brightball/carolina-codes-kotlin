# Decisions

Append-only log for this Kotlin / Ktor service. It is the single-file form of
an architecture decision record: status, context, decision, and reason. A
`docs/adr/` directory would be more process than one Gradle module needs.

Rules:

- Add a new entry when a recorded choice changes. Set the old entry's status
  to `Superseded by Dxxx`. Leave the old decision text in place.
- Do not rewrite history to match a new pin.
- `build.gradle.kts`, `mise.toml`, the wrapper properties, and the Dockerfiles
  are the machine-readable pins. This file is why those pins exist.
- [MEMORY.md](MEMORY.md) lists what is true now. It is not a second copy of
  this log.

Entries below were recorded on 2026-10-07 from choices already in the tree
(initial API 2026-08-28; JDK 27, detekt 2.x, lockfile, quality gates, and
non-blocking `/health` landed by 2026-09-22).

## D001 — Query v1 views, not Ash tables

- Status: Accepted
- Decision: Public SQL reads only the PostgreSQL views `v1_speakers`,
  `v1_sponsors`, `v1_years`, `v1_talks`, `v1_sponsorships`,
  `v1_year_speakers`, and `v1_year_sponsors`. List responses use
  `{ "data": [ ... ] }`. This service does not implement Ash JSON:API and
  does not `SELECT` Ash tables or base tables (`speakers`, `organizations`,
  `talks`, and the rest) as the public contract.
- Reason: The Phoenix site falls back to Ash when no language API is
  registered. The polyglot contract is ordinary JSON over those views. Querying
  Ash or base tables would couple this API to CMS internals and drift from
  `priv/api/openapi.yaml`.
- Context: The views live in the CMS database. This repo does not vendor
  `db/*.sql` or a local OpenAPI file. The contract files stay in the CMS repo.

## D002 — Register once, no heartbeat

- Status: Accepted
- Decision: On boot, after the HTTP server is listening, POST once to
  `{CAROLINA_URL}/internal/api-endpoints/register` with the bearer token
  `POLYGLOT_REGISTER_TOKEN`. Do not send a heartbeat. If `CAROLINA_URL` or the
  token is blank, skip the call. If the POST fails, log and keep serving.
- Reason: Elixir keeps at most one language API warm and keep-alives that
  process itself. A retry loop or heartbeat would fight that ownership. A
  forked or locally run API must still serve HTTP when the CMS is down.
- Context: `register` runs on the daemon thread `polyglot-register` in
  `Main.kt`. The server is started before that thread runs, so a skipped or
  failed register cannot block the listener.

## D003 — JDK 27 runtime, Java 21 bytecode, Gradle 9.7.1

- Status: Accepted
- Decision: Run on JDK 27. Keep `sourceCompatibility`, `targetCompatibility`,
  and Kotlin `jvmTarget` at 21. Keep the Gradle wrapper at 9.7.1. Do not
  enable preview JEPs. Do not treat the runtime pin and the bytecode target
  as one version.
- Reason: JDK 27 is the runtime this repo installs, tests
  (`PerfTest.runningJvmIsFeature27`), and ships in Docker
  (`openjdk:27-rc`, `openjdk:27-rc-slim`, `.cursor/Dockerfile`
  `openjdk:27-rc-bookworm`). Bytecode 21 matches the language level the
  sources were written for. Gradle 9.7.1 already starts on JDK 27 and the
  suite passes on it, so a wrapper bump is not required to stay on this JDK.
- Context: `mise.toml` sets `java = "27"` and `gradle = "9.7.1"`.
  `build.gradle.kts` sets the Java 21 target. Eclipse Temurin 27 images were
  not the published tags used here; the Dockerfiles use the `openjdk:27-rc`
  line.

## D004 — detekt 2.x on JDK 27

- Status: Accepted
- Decision: Static analysis is detekt 2.0.0-alpha.6 (`dev.detekt` plugin and
  `config/detekt/detekt.yml`), failing the build on findings. Do not stay on
  detekt 1.23.x, and do not replace this check with gitleaks or a skipped job.
- Reason: detekt 1.23.x does not run on JDK 27 (its published JDK maximum is
  21). SAST has to be a Kotlin analyzer that actually executes on the pinned
  runtime. The 2.x line is what runs here.
- Context: The `sast` quality check is `./gradlew --no-daemon detekt`. Style
  is a separate ktlint check, not a detekt style ruleset (`style` is off in
  `detekt.yml`).

## D005 — Clear CVEs by bumping and regenerating the lockfile

- Status: Accepted
- Decision: Fix a real dependency CVE by bumping the library and regenerating
  `gradle.lockfile`. Do not suppress the finding to make `osvScan` pass.
  Locked configurations are `compileClasspath`, `runtimeClasspath`,
  `testCompileClasspath`, and `testRuntimeClasspath`.
- Reason: `osvScan` reads the committed lockfile, not a live Maven resolution.
  A suppressed or stale lock hides the vulnerable coordinate while the build
  still resolves it. Bumping and rewriting the lockfile is the only change
  that both removes the CVE and updates the file OSV scans.
- Context: Jackson is `jackson-module-kotlin` 2.18.9 and PostgreSQL JDBC is
  42.7.12. Regenerate with `./gradlew resolveAndLockAll --write-locks`. The
  scanner pin is osv-scanner 2.2.3 in `mise.toml`.

## D006 — Five separate quality checks

- Status: Accepted
- Decision: Keep five distinct checks named `test`, `sast`, `deps`,
  `gitleaks`, and `style`. Each has its own pre-commit hook and its own CI
  job. Do not collapse them into one bundled script or one job that runs the
  others' commands.
- Reason: A single script hides which gate failed and lets a green job mask a
  check that never ran. Separate hooks and jobs make a failure name the gate.
  `QualityWiringTest` reads `.pre-commit-config.yaml` and
  `.gitea/workflows/precommit.yml` and fails if the five commands are not
  distinct.
- Context: Commands are `./gradlew --no-daemon test`,
  `./gradlew --no-daemon detekt`, `./gradlew --no-daemon osvScan`,
  `gitleaks detect --source . --verbose`, and
  `./gradlew --no-daemon ktlintCheck`. Local install is `make hooks`.
  Emergency skip is `SKIP=test,sast,deps,gitleaks,style git commit`.

## D007 — GET /health and GET / must not block on JDBC

- Status: Accepted
- Decision: `GET /health` and `GET /` must respond without opening a JDBC
  connection and without waiting for the pool. Catalog routes may use the
  pool. The pool warms on a background thread after listen.
- Reason: Liveness and identity are how the platform tells that the process
  is up. A closed or slow database must not stall those two responses, or a
  down Postgres looks like a dead API. Health is `{ "ok": true }` and does
  not query.
- Context: `serve` starts the CIO server, then the `polyglot-register` and
  `pool-warm` daemon threads. Root and health handlers call `call.json` only.
  `withDbIo` is reserved for `/v1/*` routes. Tests cover this in
  `PerfTest.healthDoesNotQueryOrConnect` and `StartupTest`.
