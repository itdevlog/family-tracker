# Family Tracker Server — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ktor-бэкенд на Debian: семьи/пары, батчи координат, WS живого канала, SOS → ntfy, ретеншн; деплой через Docker Compose за Caddy.

**Architecture:** Один jar (Ktor 3.x + Netty), SQLite через JDBC, токен-аутентификация Bearer, WS-бродкаст на семью, ntfy через HTTP. Тесты — `testApplication` Ktor.

**Tech Stack:** Kotlin/JVM 17, Ktor 3, kotlinx-serialization, sqlite-jdbc (+ HikariCP), Docker Compose (caddy + ktor + ntfy).

**Spec:** `docs/superpowers/specs/2026-10-20-family-tracker-design.md`

## Global Constraints

- Kotlin, JVM 17; один Gradle-проект в `server/`
- Токены: 32 байта random → base64url; в БД только SHA-256 hex хеш
- Пары: 6 цифр, живут 5 минут, одноразовые
- `POST /locations`: батч ≤ 100 точек; дедуп `UNIQUE(device_id, recorded_at)`
- Ретеншн: локации старше 30 суток, чистка раз в сутки
- Точность координат: валидация lat ∈ [-90,90], lon ∈ [-180,180], иначе 422
- Ntfy topic: `family-<family_uuid>-sos`, token из env; publish priority=high
- Одна семья на сервер: `/pair/parent` работает только пока семьи нет
- Все даты в API — ISO-8601 UTC строки (`recorded_at` также приходит от ребёнка)

## Review Focus

1. **Повторная выдача кода ребёнку** — второй вызов `/pair/child` с тем же кодом должен вернуть 410 Gone. Тест в Task 5.
2. **Чужую семью не видно** — родитель запрашивает `/locations?device=<чужой ребён>` → 403. Тест в Task 8.
3. **Повторная отправка батча при оффлайне ребёнка** — тот же батч дважды не создаёт дубликатов: `INSERT OR IGNORE` по `UNIQUE(device_id, recorded_at)`. Тест в Task 6.
4. **WS с неверным/чужим токеном** — должен закрываться кодом 4401, не сливать события. Тест в Task 9.
5. **Перебор кодов** — >5 неудачных `/pair/child` с одного IP за минуту → 429. Тест в Task 5.

---

### Task 1: Каркас сервера + `/health`

**Files:**
- Create: `server/build.gradle.kts`, `server/settings.gradle.kts`
- Create: `server/src/main/kotlin/Main.kt` (Application module)
- Test: `server/src/test/kotlin/HealthTest.kt`

**Interfaces:**
- Produces: `fun Application.module(config: Config)` — единственная точка входа тестов; `Config(dbPath: String, ntfyUrl: String, ntfyToken: String)` с полями из env `DB_PATH`, `NTFY_URL`, `NTFY_TOKEN`

- [ ] **Step 1:** Scaffolding `settings.gradle.kts` (rootProject.name="family-tracker-server"), `build.gradle.kts`: plugins `kotlin("jvm")`, `ktor` 3.x (`ktor-server-netty`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`), `sqlite-jdbc`, тестовый `ktor-server-test-host`, `kotlin-test`
- [ ] **Step 2:** Failing test: `testApplication { application { module(Config(":memory:", "*", "*")) }; client.get("/health") }` → 200, body `"ok"`
- [ ] **Step 3:** `/health` route
- [ ] **Step 4:** `cd server && ./gradlew test` → PASS
- [ ] **Step 5:** `cd server && git add -A && git commit -m "server: scaffold with /health"`

### Task 2: SQLite схема + Dao

**Files:**
- Create: `server/src/main/kotlin/db/Schema.kt`, `db/Dao.kt`
- Test: `server/src/test/kotlin/DaoTest.kt`

**Interfaces:**
- Produces (все функции в `Dao`, DB через `Connection`, все принимает `conn`):
  - `fun createFamilyWithParent(conn, name: String): ParentRegistration` (data class: `deviceId: UUID, familyId: UUID, token: String, ntfyTopic: String`)
  - `fun familyExists(conn): Boolean`
  - `fun storePairCode(conn, familyId: UUID): PairCode(code, expiresAt)` — 6 цифр, +5 минут
  - `fun redeemChildCode(conn, code: String, name: String): ChildRegistration?`
  - `fun insertLocations(conn, deviceId: UUID, points: List<PointIn>): Int` — возвращает count вставленных (dedup по recorded_at)
  - `fun childrenWithLastLocation(conn, familyId: UUID): List<DeviceSummary>`
  - `fun locationHistory(conn, deviceId: UUID, from: Long, to: Long): List<PointIn>`
  - `fun insertSos(conn, childId: UUID, lat: Double, lon: Double): SosEvent`
  - `fun ackSos(conn, eventId: UUID, familyId: UUID): Boolean`
  - `fun listSos(conn, familyId: UUID, since: Long): List<SosEvent>`
  - `fun deleteOldLocations(conn, cutoffMs: Long): Int`
  - Токены: `fun tokenHash(conn, deviceRole: ..., raw: String): Device?` — поиск устройства по SHA-256 хешу
- Точка `PointIn(lat: Double, lon: Double, accuracy: Float?, speed: Float?, recordedAt: Long)` (epoch ms)

- [ ] **Step 1:** Failing test: `createFamilyWithParent` → `familyExists()==true`; токены двух регистраций разные; хеш стабилен
- [ ] **Step 2:** Реализация схемы (families/devices/pair_codes/locations/sos_events + индексы)
- [ ] **Step 3:** `cd server && ./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 3: `/pair/parent` и `/pair/code`

**Files:**
- Create: `server/src/main/kotlin/routes/PairRoutes.kt`
- Test: `server/src/test/kotlin/PairRoutesTest.kt`

**Interfaces:**
- Consumes: `Dao` (Task 2), `Config`
- Produces: `POST /pair/parent {name}` → `{device_id, family_id, token, ntfy: {url, topic, token}}`; ответ 409 если семья существует; `POST /pair/code` (Bearer parent) → `{code, expires_at}`

- [ ] **Step 1:** Тест: первый `/pair/parent` → 200 с токеном; повторный → 409; `/pair/code` без Bearer → 401; с валидным → 200 + 6 цифр; `/pair/parent` токен работает как Bearer
- [ ] **Step 2:** Реализация: auth-плагин Bearer (hash lookup)
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 4: `/pair/child` с rate limit

**Files:**
- Create: `server/src/main/kotlin/routes/ChildPairRoute.kt`
- Test: `server/src/test/kotlin/ChildPairTest.kt`

**Interfaces:**
- Consumes: `Dao.redeemChildCode`, rate-limit (in-memory `MutableMap<ip, timestamps>`, лимит 5 неудач/мин)
- Produces: `POST /pair/child {code, name}` → 200 `{device_id, token}` | 410 если уже использован | 404 если не верный/истёк | 429 >5 неудач/мин/IP

- [ ] **Step 1:** Тест: валидный код → 200; тот же код повторно → **410**; несуществующий → 404; 5 неудач → потом 429 (не проверяя код)
- [ ] **Step 2:** Реализация маршрута + rate limit
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 5: `POST /locations` c dedup

**Files:**
- Create: `server/src/main/kotlin/routes/LocationsRoute.kt`
- Test: `server/src/test/kotlin/LocationsRouteTest.kt`

**Interfaces:**
- Consumes: `Dao.insertLocations`; auth child-роль
- Produces: `POST /locations` (Bearer child) `{points: [PointIn]}` → 204; 422 если lat/lon вне диапазона; 413 если >100 точек

- [ ] **Step 1:** Тест: батч из 3 точек → 204; те же 3 точки ещё раз → повторный 204, но в БД по-прежнему 3 записи (dedup); >100 → 413; lat=95 → 422; Bearer ребёнка второй семьи не видит чужого device
- [ ] **Step 2:** Реализация валидации + маршрута
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 6: WS `/live` + бродкаст

**Files:**
- Create: `server/src/main/kotlin/routes/LiveRoute.kt`
- Test: `server/src/test/kotlin/LiveTest.kt`

**Interfaces:**
- Consumes: `Config`; auth через query `?token=` (WS); `MutableMap<UUID /*familyId*/, MutableSet<WebSocketSession>>`
- Produces: `WS /live?token=…` — сервер пушит JSON-события: `{"type":"location","child":<deviceId>,"lat":…,"lon":…,"recorded_at":…}` и `{"type":"sos","child":…,"lat":…,"lon":…,"event_id":…}`; публикация в все WS той же семьи на `/locations` и `/sos`

- [ ] **Step 1:** Тест: два WS родителя одной семьи; ребёнок знает девайс;POST точки → оба WS получают событие; WS с невалидным токеном → закрыт с 4401
- [ ] **Step 2:** Реализация маршрута + pub/sub на вставку точек (единая функция `broadcastLocation(familyId, point)`)
- [ ] **Step 3:** Тест 2: запрос device чужой семьи — event не уходит (защита в payload — берём из подтверждённого insert)
- [ ] **Step 4:** `./gradlew test` → PASS
- [ ] **Step 5:** Commit

### Task 7: GET `/devices`, GET `/locations` (история), GET `/sos` + `/sos/ack`

**Files:**
- Create: `server/src/main/kotlin/routes/ParentReads.kt`
- Test: `server/src/test/kotlin/ParentReadsTest.kt`

**Interfaces:**
- Consumes: `Dao.childrenWithLastLocation`, `locationHistory`, `listSos`, `ackSos`; Unit тесты по строковым `from`/`to` ISO в epoch ms
- Produces: `GET /devices` (Bearer parent) → `[{device_id, name, last_location: {lat, lon, recorded_at}|null}]`; `GET /locations?device=&from=&to=` → `[PointIn…]`; `GET /sos?since=…` → массив `[{event_id, child, lat, lon, created_at, acknowledged}]`; `POST /sos/ack {event_id}` → 204
- **Test:** ребёнок B запрашивает историю другого ребёнка другой семьи → 403; чужой device → 403; from > to → 422; `/sos` только своей семьи

- [ ] **Step 1:** Тесты (выше 6 строк)
- [ ] **Step 2:** Реализация
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 8: `/sos` → ntfy publish

**Files:**
- Create: `server/src/main/kotlin/ntfy/NtfyPublisher.kt`, его HTTP-имплементация (`java.net.http.HttpClient`)
- Modify: `server/src/main/kotlin/routes/SosRoute.kt`
- Test: `server/src/test/kotlin/SosRouteTest.kt`

**Interfaces:**
- Consumes: `Dao.insertSos`; `NtfyPublisher.publish(topic: String, title: String, body: String, priority: Int)` — интерфейс; HTTP-имплементация при вызове `/sos` — POST на `$ntfyUrl` c `Authorization: Bearer $ntfyToken`, JSON `{topic, title, message, priority}`
- Produces: `POST /sos` (Bearer child) `{lat, lon}` → 204; событие наследуется от `Dao`; ntfy вызван ровно 1 раз с правильным topic/priority

- [ ] **Step 1:** Тест c интерфейс-фейком `RecordingNtfy`, инъекция в `module(config, ntfy)`; проверка ровно 1 publish и приоритета 5; события WS родителя (из Task 6) тоже получает это событие
- [ ] **Step 2:** Реализация маршрута + HTTP-имплементация (`NtfyPublisher` — sepер-interface, разный конструктор)
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** Commit

### Task 9: Ретеншн + main entrypoint

**Files:**
- Create: `server/src/main/kotlin/RetentionJob.kt`
- Modify: `server/src/main/kotlin/Main.kt`
- Test: `server/src/test/kotlin/RetentionTest.kt`

**Interfaces:**
- Consumes: `Dao.deleteOldLocations(cutoffMs)`
- Produces: `scheduleRetention(conn, period = 24h, path)` — корончатый daemon-pool поток,调用 `deleteOldLocations(now - 30 суток)`; при старте тоже вызвать в один раз

- [ ] **Step 1:** Тест: 31-дневная точка удаляется, точка 29-дневная остаётся; повторный вызов не ошибка
- [ ] **Step 2:** Реализация; `main.kt` читает env и запускает всё
- [ ] **Step 3:** `./gradlew test` → PASS; `./gradlew installDist` — дистрибутив собирается без ошибок
- [ ] **Step 4:** Commit

### Task 10: Docker Compose + Caddy + ntfy

**Files:**
- Create: `server/Dockerfile`, `deploy/docker-compose.yml`, `deploy/Caddyfile`, `deploy/ntfy/config.yml`, `deploy/README.md`
- Test: интеграционный прогон

**Interfaces:**
- Consumes: билд Task 9 (`installDist`) → образ
- Produces: `docker compose up` на Debian: `api.<doмейн>` → ktor:8080, `ntfy.<домен>` → ntfy:8585; env: `NTFY_URL=https://ntfy.<домен>`, `NTFY_TOKEN=<сгенерировать командой `ntfy token add`>`, `DB_PATH=/data/tracker.db`; Caddy автоматически получает TLS

- [ ] **Step 1:** `Dockerfile` (gradle → installDist → JRE 17 slim)
- [ ] **Step 2:** `docker-compose.yml`: caddy (443/80), ktor (net), ntfy (+ volume); Caddyfile с reverse_proxy
- [ ] **Step 3:** README: однострочник `docker compose up -d`, способ создать `ntfy token add`, проверить вход админ-токеном
- [ ] **Step 4:** Локальный тест (HTTP без TLS): `docker compose config` → валидно; `docker compose up` локально → `/health` 200 через caddy (`curl http://localhost/health`)
- [ ] **Step 5:** Commit

---

## Проверка после всех задач

1. docker compose на Debian, домен в DNS, `curl https://api.<домен>/health` → ok
2. Тест два устройства из приложения (роль parent/child по спеке) — происходит POST/GET/WS/SOS round-trip
