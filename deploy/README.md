# Деплой family-tracker (Docker Compose + Caddy + ntfy)

Три контейнера в одной сети:

| Сервис | Роль | Наружу |
| --- | --- | --- |
| `caddy` | TLS (Let's Encrypt), единственный ingress | `80/tcp`, `443/tcp`, `443/udp` |
| `ktor` | бэкенд, SQLite в `/data` | нет, только `expose: 8080` |
| `ntfy` | push-сервер, auth-файл в volume | нет, только `expose: 8585` |

`api.<домен>` → `ktor:8080`, `ntfy.<домен>` → `ntfy:8585`.

## Требования

- Debian 12 (или другой Linux) с Docker Engine и плагином `docker compose` (Compose ≥ 2.24.4 — нужен тег `!override` в локальном override).
- DNS: A/AAAA-записи `api.<домен>` и `ntfy.<домен>` указывают на сервер. Если домен за CDN-прокси, убедитесь, что ACME-валидация проходит.
- Открыты порты `80/tcp`, `443/tcp`, `443/udp` (HTTP-01/HTTPS-01 и HTTP/3). Для Let's Encrypt нужен доступ из интернета.
- Свободные имена `api`/`ntfy` в DNS и валидный e-mail для ACME.

## Настройка

Все команды ниже — из каталога `deploy/`.

```bash
cp .env.example .env
chmod 600 .env
$EDITOR .env
```

`.env` (в git не попадает):

```
API_DOMAIN=api.example.com
NTFY_DOMAIN=ntfy.example.com
ACME_EMAIL=you@example.com
NTFY_TOKEN=tk_...
```

Все четыре переменные обязательны: compose-подстановки используют `${VAR:?…}` и падают с понятной ошибкой, если значение не задано. На первом шаге `NTFY_TOKEN` может остаться заглушкой `tk_...` из примера — настоящий токен вы выпустите ниже и замените значение в `.env`.

## Пользователь и токен ntfy

ntfy настроен как приватный (`auth-default-access: deny-all`, `enable-signup: false`), поэтому без токена не читается и не пишется ни одна тема.

Нужны **две разные учётные записи**:

- `tracker` — роль `user` (не admin), только ACL на `family-*` read-write. Её токен идёт в `NTFY_TOKEN`: ktor публикует SOS, приложение родителя читает эти же темы. Скомпрометированный телефон получает доступ только к темам семьи.
- `admin` — роль `admin`, только для входа в веб-UI/администрирования. **Токен admin в `.env` не попадает и приложению не передаётся.**

Поднимите только ntfy (остальное можно позже):

```bash
docker compose up -d ntfy
docker compose logs -f ntfy
```

Создайте рабочего пользователя (пароль вводится интерактивно, дважды):

```bash
docker compose exec ntfy ntfy user add --role=user tracker
```

Выдайте ему доступ к темам семьи (кавычки обязательны, иначе сработает glob shell):

```bash
docker compose exec ntfy ntfy access tracker 'family-*' read-write
```

Read-write нужен обоим: ktor публикует (`/sos`), parent-приложение подписывается на `family-<family_id>-sos`.

Выпустите токен и скопируйте `tk_...` в `.env` как `NTFY_TOKEN`:

```bash
docker compose exec ntfy ntfy token add --label=family-tracker tracker
```

При необходимости создайте отдельного admin для веб-UI — **его токен никуда не копируйте**, входите паролем:

```bash
docker compose exec ntfy ntfy user add --role=admin admin
```

Проверка, что ntfy жив (не проверяет токен):

```bash
curl -u tracker 'https://ntfy.example.com/v1/health'
# {"healthy":true}
```

### Проверка самого NTFY_TOKEN

`/v1/health` отвечает и без авторизации, поэтому ошибку в `NTFY_TOKEN` он не покажет. Проверьте публикацию и чтение именно тем токеном из `.env`:

```bash
set -a; . ./.env; set +a

# публикация (ACL family-* покрывает topic)
curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  -d "healthcheck" "https://$NTFY_DOMAIN/family-healthcheck"
# {"id":"...","time":...,"event":"message","topic":"family-healthcheck","message":"healthcheck"}

# чтение из кэша
curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  "https://$NTFY_DOMAIN/family-healthcheck/json?poll=1"
# строка JSON с "message":"healthcheck"
```

Topic намеренно называется `family-healthcheck`: он попадает под ACL `family-*`, поэтому проверяет ровно те права, которые нужны ktor и приложению. Если вторая команда вернула `403`, проверьте ACL; если первая — `401`, токен в `.env` неверный. Тестовое сообщение живёт в кэше ntfy ограниченное время (по умолчанию 12 ч) и уходит само.

## Запуск

```bash
docker compose up -d --build
docker compose logs -f ktor
```

Проверки:

```bash
curl https://api.example.com/health        # ok
curl -u tracker https://ntfy.example.com/v1/health
docker compose ps                          # у всех сервисов State: Up (healthcheck'ов нет)
```

Если Caddy не получает сертификат, смотрите `docker compose logs caddy`: чаще всего это DNS или закрытый 80/443.

## Первичная привязка

> **Сразу после деплоя выполните первичную привязку.** Пока семьи нет, `POST /pair/parent` открыт без авторизации: любой, кто узнал адрес сервера, может создать семью и занять единственный слот. Не оставляйте сервер развёрнутым без привязки.

`POST /pair/parent` работает только пока семьи нет (повторный вызов — `409`):

```bash
curl -sS -X POST 'https://api.example.com/pair/parent' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Мама"}'
```

Ответ содержит `device_id`, `family_id`, `token` (токен устройства родителя) и `ntfy`:

```json
{
  "device_id": "...",
  "family_id": "...",
  "token": "...",
  "ntfy": {
    "url": "https://ntfy.example.com",
    "topic": "family-<family_id>-sos",
    "token": "tk_..."
  }
}
```

Код для ребёнка (нужен `Authorization: Bearer <token>` родителя):

```bash
curl -sS -X POST 'https://api.example.com/pair/code' \
  -H "Authorization: Bearer <token>"
# {"code":"...","expires_at":"..."}
```

В приложении ребёнка укажите base URL `https://api.example.com` и отсканируйте QR / введите код. Код одноразовый и короткоживущий.

## Бэкап

SQLite и auth-файл ntfy нельзя копировать «на живую»: сначала остановите сервисы.

```bash
docker compose stop ktor ntfy
mkdir -p backup

docker run --rm \
  -v family-tracker_tracker_data:/data:ro \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'tar czf /backup/tracker-$(date +%F).tgz -C /data .'

docker run --rm \
  -v family-tracker_ntfy_lib:/lib:ro \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'tar czf /backup/ntfy-lib-$(date +%F).tgz -C /lib .'

# при желании — кэш сообщений ntfy
docker run --rm \
  -v family-tracker_ntfy_cache:/cache:ro \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'tar czf /backup/ntfy-cache-$(date +%F).tgz -C /cache .'

docker compose start
```

В `tracker_data` лежат `tracker.db` и WAL-файлы (`tracker.db-wal`, `tracker.db-shm`), поэтому архивируется весь каталог. Альтернатива без остановки — `sqlite3 .../tracker.db ".backup '/backup/tracker.db'"` на хосте (нужен пакет `sqlite3`, путь к `_data` тома зависит от Docker root).

## Восстановление

Стек должен быть остановлен, чтобы не перезаписать открытую БД:

```bash
docker compose down
mkdir -p backup

# tracker_data (файлы БД)
docker run --rm \
  -v family-tracker_tracker_data:/data \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'rm -rf /data/* && tar xzf /backup/tracker-YYYY-MM-DD.tgz -C /data'

# ntfy auth/ACL
docker run --rm \
  -v family-tracker_ntfy_lib:/lib \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'rm -rf /lib/* && tar xzf /backup/ntfy-lib-YYYY-MM-DD.tgz -C /lib'

# опционально — кэш сообщений
docker run --rm \
  -v family-tracker_ntfy_cache:/cache \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'rm -rf /cache/* && tar xzf /backup/ntfy-cache-YYYY-MM-DD.tgz -C /cache'

docker compose up -d
curl https://api.example.com/health
```

`rm -rf` внутри volume удаляет только содержимое тома. Перед восстановлением убедитесь, что архивы на месте и вы восстанавливаете именно volume этого проекта.

## Обновление

Сначала бэкап (раздел выше), только потом обновление кода:

```bash
# 1. бэкап: docker compose stop ktor ntfy + архивы, как выше
# 2. обновление
git pull
docker compose up -d --build
docker compose ps
curl https://api.example.com/health
```

## Безопасность

- Наружу опубликован только Caddy. Порты `8080` (ktor) и `8585` (ntfy) доступны лишь внутри compose-сети — в `docker-compose.yml` у них нет `ports:`, только `expose:`.
- Caddy — единственный ingress и сам добавляет/дополняет `X-Forwarded-For`. Rate limiter в ktor берёт **правый** хоп заголовка (его пишет Caddy), левые значения клиент может подделать.
- `auth-default-access: deny-all`: анонимно нельзя ни читать, ни публиковать; вход только по логину/паролю или токену.
- `NTFY_TOKEN` принадлежит non-admin пользователю `tracker` с ACL только на `family-*`. Admin-аккаунт нужен лишь для веб-UI; его токен не передаётся в `.env`, ktor и приложение.
- Токены и пароли — только в `deploy/.env` (в репозиторий не коммитится) и в ntfy volume. Не передавайте их в git, логи или скриншоты.
- Приложение обязано работать по HTTPS; HTTP-режим ниже — только для локальной отладки.

## Локальный HTTP-прогон (без TLS и домена)

> **Не запускать на production-хосте.** Локальный стек публикует `:80` (конфликт с prod-Caddy) и поднимает собственные контейнеры. Отдельное имя проекта защищает только volumes от prod-данных, но не порты. Это режим для локальной машины/стенда.

Нужны те же четыре переменные `.env` (домены можно фиктивные, например `api.localhost`/`ntfy.localhost`; `NTFY_TOKEN` может быть любым непустым, например `tk_local` — для `/health` он не используется). Все `${VAR:?}`-подстановки обязательны и здесь.

`-p family-tracker-local` даёт отдельные volume'ы `family-tracker-local_*`, не пересекающиеся с production `family-tracker_*`:

```bash
docker compose -p family-tracker-local \
  -f docker-compose.yml -f compose.local.yml up -d --build

curl http://localhost/health          # ok

docker compose -p family-tracker-local \
  -f docker-compose.yml -f compose.local.yml ps
```

Локальный override монтирует `Caddyfile.local` (`auto_https off`, `:80` → `ktor:8080`) и через `ports: !override` публикует только `80:80`.

Запускаются все три сервиса, включая ntfy: `ktor` объявляет `depends_on: ntfy`, поэтому ntfy поднимается всегда. Для самой проверки `/health` ntfy не нужен, но контейнер будет запущен.

Остановка:

```bash
docker compose -p family-tracker-local \
  -f docker-compose.yml -f compose.local.yml down
```

TLS, сертификаты, ntfy auth и привязка устройств проверяются только на реальном сервере с доменами: локальный режим покрывает лишь маршрутизацию Caddy → ktor.
