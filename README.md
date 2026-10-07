# carolina-codes-kotlin

Read-only v1 polyglot API for Carolina Code Conference. It serves the v1 REST
contract from PostgreSQL `v1_*` views using Kotlin, [Ktor](https://ktor.io/)
CIO, and PostgreSQL JDBC.

Language and runtime pins: Kotlin 2.2.20, Ktor 3.3.1, and JDK 27. Compiled
bytecode stays on the Java 21 target (`sourceCompatibility`,
`targetCompatibility`, and `jvmTarget`). Those are different pins. The Gradle
wrapper is 9.7.1.

| Piece | Version |
|---|---|
| Kotlin | 2.2.20 |
| Ktor | 3.3.1 |
| JDK runtime | JDK 27 |
| JVM bytecode | Java 21 |
| Gradle wrapper | 9.7.1 |

Libraries and tools on this build:

- PostgreSQL JDBC 42.7.12
- Jackson (`jackson-module-kotlin` 2.18.9)
- slf4j-simple 2.0.17
- detekt 2.0.0-alpha.6 for Kotlin SAST
- ktlint 1.7.1 for Kotlin style
- OSV Scanner 2.2.3 scans the locked dependency graph in `gradle.lockfile` (`./gradlew --no-daemon osvScan`)
- gitleaks 8.30.1

Pins and the reasons for them are indexed in `MEMORY.md` and `DECISIONS.md`.
The HTTP contract for agents is `AGENTS.md`.

```
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5432/carolina_dev \
CAROLINA_URL=http://127.0.0.1:4000 \
POLYGLOT_REGISTER_TOKEN=dev \
PUBLIC_BASE_URL=http://127.0.0.1:4013 \
PORT=4013 \
./gradlew --no-daemon run
```

Quality gates (named pre-commit checks; Gitea prepares the workspace once, then runs each as its own job):

```
./gradlew --no-daemon test          # application tests
./gradlew --no-daemon detekt        # Kotlin SAST
./gradlew --no-daemon osvScan       # OSV dependency scan of gradle.lockfile
gitleaks detect --source . --verbose
./gradlew --no-daemon ktlintCheck   # Kotlin style
make hooks                          # install pre-commit + in-repo hooksPath
pre-commit run --all-files          # local entry that runs the five checks
mise run check
mise run secrets
```

Emergency skip: `SKIP=test,sast,deps,gitleaks,style git commit`.
