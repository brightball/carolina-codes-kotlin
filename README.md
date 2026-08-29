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
