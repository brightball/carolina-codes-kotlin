# carolina-codes-kotlin

Read-only v1 polyglot API for Carolina Code Conference. Queries `v1_*` SQL views
using Kotlin, [Ktor](https://ktor.io/) CIO, and PostgreSQL JDBC.

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
