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
$EDITOR .env
```

`.env` (в git не попадает):

```
API_DOMAIN=api.example.com
NTFY_DOMAIN=ntfy.example.com
ACME_EMAIL=you@example.com
NTFY_TOKEN=tk_...
```

`NTFY_TOKEN` появится на следующем шаге.

## Пользователь и токен ntfy

ntfy настроен как приватный (`auth-default-access: deny-all`, `enable-signup: false`), поэтому без токена не читается и не пишется ни одна тема.

Поднимите только ntfy (остальное можно позже):

```bash
docker compose up -d ntfy
docker compose logs -f ntfy
```

Создайте пользователя (пароль вводится интерактивно, дважды):

```bash
docker compose exec ntfy ntfy user add --role=admin tracker
```

Выпустите токен для этого пользователя и скопируйте `tk_...` в `.env` как `NTFY_TOKEN`:

```bash
docker compose exec ntfy ntfy token add --label=ktor tracker
```

Явно выдайте доступ к темам семьи (кавычки обязательны, иначе сработает glob shell):

```bash
docker compose exec ntfy ntfy access tracker 'family-*' read-write
```

Роль `admin` и так даёт доступ ко всем темам; запись `family-*` оставлена как явная декларация (и продолжит работать, если роль понизят). Один и тот же токен используют ktor (публикация SOS) и приложение родителя (подписка на `family-<family_id>-sos`).

Проверка, что ntfy жив:

```bash
curl -u tracker 'https://ntfy.example.com/v1/health'
# {"healthy":true}
```

## Запуск

```bash
docker compose up -d --build
docker compose logs -f ktor
```

Проверки:

```bash
curl https://api.example.com/health        # ok
curl -u tracker https://ntfy.example.com/v1/health
docker compose ps                          # caddy healthy, ktor/ntfy running
```

Если Caddy не получает сертификат, смотрите `docker compose logs caddy`: чаще всего это DNS или закрытый 80/443.

## Первичная привязка

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

## Обновление

```bash
git pull
docker compose up -d --build
docker compose ps
curl https://api.example.com/health
```

## Безопасность

- Наружу опубликован только Caddy. Порты `8080` (ktor) и `8585` (ntfy) доступны лишь внутри compose-сети — в `docker-compose.yml` у них нет `ports:`, только `expose:`.
- Caddy — единственный ingress и сам добавляет/дополняет `X-Forwarded-For`. Rate limiter в ktor берёт **правый** хоп заголовка (его пишет Caddy), левые значения клиент может подделать.
- `auth-default-access: deny-all`: анонимно нельзя ни читать, ни публиковать; вход только по логину/паролю или токену.
- Токены и пароли — только в `deploy/.env` (в репозиторий не коммитится) и в ntfy volume. Не передавайте их в git, логи или скриншоты.
- Приложение обязано работать по HTTPS; HTTP-режим ниже — только для локальной отладки.

## Локальный HTTP-прогон (без TLS и домена)

Нужны те же `.env` (домены можно фиктивные, например `api.localhost`/`ntfy.localhost`), но проверяется только `/health` через Caddy на `:80`. ntfy в этом режиме не обязателен.

```bash
docker compose -f docker-compose.yml -f compose.local.yml up -d --build
curl http://localhost/health          # ok
docker compose -f docker-compose.yml -f compose.local.yml logs -f caddy
```

Локальный override монтирует `Caddyfile.local` (`auto_https off`, `:80` → `ktor:8080`) и через `ports: !override` публикует только `80:80`.

Остановка:

```bash
docker compose -f docker-compose.yml -f compose.local.yml down
```

TLS, сертификаты, ntfy auth и привязка устройств проверяются только на реальном сервере с доменами: локальный режим покрывает лишь маршрутизацию Caddy → ktor.
