# Деплой family-tracker (Docker Compose + Caddy + ntfy)

Три контейнера в одной сети:

| Сервис | Роль | Наружу |
| --- | --- | --- |
| `caddy` | TLS (Let's Encrypt), единственный ingress | `80/tcp`, `443/tcp`, `443/udp` |
| `ktor` | бэкенд, SQLite в `/data` | нет, только `expose: 8080` |
| `ntfy` | push-сервер, auth-файл в volume | нет, только `expose: 8585` |

`api.<домен>` → `ktor:8080`, `ntfy.<домен>` → `ntfy:8585`.

Caddy можно заменить на Nginx Proxy Manager (NPM), если он уже работает в вашей сети — см. раздел [«Вместо Caddy: Nginx Proxy Manager»](#вместо-caddy-nginx-proxy-manager). Если Caddy уже установлен на хосте, используйте `compose.host-caddy.yml` и раздел [«Caddy уже установлен на хосте»](#caddy-уже-установлен-на-хосте). Для управления стеком из Portainer есть готовый `compose.portainer.yml` в корне репозитория и раздел [«Portainer»](#portainer).

## Требования

- Debian 12 (или другой Linux) с Docker Engine и плагином `docker compose` (Compose ≥ 2.24.4 — нужен тег `!override` в локальном override).
- Caddy **2.10+** — и контейнер, и системный: директива `request_body` появилась в Caddy 2.10. В Docker-варианте нужная версия уже закреплена в `docker-compose.yml`.
- DNS: A/AAAA-записи `api.<домен>` и `ntfy.<домен>` указывают на сервер. Если домен за CDN-прокси, убедитесь, что ACME-валидация проходит.
- Открыты порты `80/tcp`, `443/tcp`, `443/udp` (HTTP-01/HTTPS-01 и HTTP/3). Для Let's Encrypt нужен доступ из интернета.
- Свободные имена `api`/`ntfy` в DNS и валидный e-mail для ACME.

## Настройка

Все команды ниже — из каталога `deploy/`, кроме раздела «Установка APK»: его команды выполняются из корня репозитория.

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

Внутри compose `ktor` публикует в ntfy по внутреннему адресу `http://ntfy:8585` (`NTFY_URL`), а телефонам в ответе `/pair/parent` отдаётся публичный `https://$NTFY_DOMAIN` (`NTFY_PUBLIC_URL`, выводится из `NTFY_DOMAIN`). Отдельно задавать его в `.env` не нужно.

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

## Вместо Caddy: Nginx Proxy Manager

Если в локальной сети уже есть NPM, он может выпускать сертификаты и проксировать запросы. Caddy в этом варианте **не запускается**: порты 80/443 остаются за NPM.

Схема:

```text
Интернет → NPM :443 (TLS)
              ├─ api.<домен>  → <LAN-IP>:8080
              └─ ntfy.<домен> → <LAN-IP>:8585
```

DNS-записи `api.<домен>` и `ntfy.<домен>` должны указывать на IP NPM, а не на этот сервер.

### Запуск

Добавьте в `deploy/.env` LAN-адрес сервера, на котором работают контейнеры:

```env
BIND_ADDRESS=192.168.1.10
```

Не используйте `0.0.0.0`, если у сервера есть публичный IP: порты 8080/8585 должны быть доступны только из локальной сети. Docker публикует порты в обход UFW, поэтому привязка к LAN-IP здесь важнее правил файрвола.

Запуск с override-файлом:

```bash
docker compose -f docker-compose.yml -f compose.npm.yml up -d --build
docker compose -f docker-compose.yml -f compose.npm.yml ps
```

В `compose.npm.yml` сервис Caddy переведён в профиль `caddy`, поэтому при обычном `up` он пропускается. Проверить конфигурацию без запуска:

```bash
docker compose -f docker-compose.yml -f compose.npm.yml config
```

### Настройка NPM

Для каждого домена: **Hosts → Proxy Hosts → Add Proxy Host**.

| Поле | `api.<домен>` | `ntfy.<домен>` |
| --- | --- | --- |
| Scheme | `http` | `http` |
| Forward Hostname / IP | LAN-IP сервера | LAN-IP сервера |
| Forward Port | `8080` | `8585` |
| Websockets Support | включить | включить |
| Block Common Exploits | можно включить | можно включить |
| SSL | Request a new certificate, Force SSL | Request a new certificate, Force SSL |

В **Advanced** у обоих хостов:

```nginx
client_max_body_size 256k;
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
```

Для `ntfy.<домен>` дополнительно:

```nginx
proxy_buffering off;
```

Почему это нужно:

- `client_max_body_size 256k` заменяет лимит тела, который в проекте делает Caddy. В Ktor 3.0.1 собственного лимита нет.
- Долгий `proxy_read_timeout` нужен для WebSocket `/live` и ntfy `/ws`.
- `proxy_buffering off` не задерживает поток ntfy в nginx.

### Проверка

```bash
curl https://api.<домен>/health
# ok

curl https://ntfy.<домен>/v1/health
# {"healthy":true}
```

Проверка `NTFY_TOKEN` из `.env` — публикацией и чтением, как в разделе [«Проверка самого NTFY_TOKEN»](#проверка-самого-ntfy_token):

```bash
set -a; . ./.env; set +a

curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  -d "healthcheck" "https://$NTFY_DOMAIN/family-healthcheck"

curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  "https://$NTFY_DOMAIN/family-healthcheck/json?poll=1"
```

### Ограничения

- NPM должен добавлять `X-Forwarded-For` (он делает это по умолчанию). Ktor берёт правый хоп заголовка для rate limit привязки. Если перед NPM стоит Cloudflare, справа окажется IP Cloudflare, и лимит станет общим для всех клиентов — нужен real-IP или отказ от CDN.
- Приложение принимает только доверенный HTTPS. Самоподписанный сертификат NPM не подойдёт без установки CA на телефоны.
- Если NPM работает в контейнере на том же хосте, указывайте LAN-IP хоста, а не `127.0.0.1`. Если NPM установлен на хосте напрямую, `127.0.0.1` тоже подойдёт.
- Не пробрасывайте 8080/8585 на роутере и не публикуйте их в интернет.

## Caddy уже установлен на хосте

Если Caddy уже работает на Debian как системный сервис, контейнер Caddy из проекта запускать не нужно: порты 80/443 заняты. Используйте `compose.host-caddy.yml` — он переводит Caddy в профиль `caddy` и публикует Ktor/ntfy только на `127.0.0.1`.

Схема:

```text
Интернет → системный Caddy :443
              ├─ api.<домен>  → 127.0.0.1:8080
              └─ ntfy.<домен> → 127.0.0.1:8585
```

### 1. Проверить Caddy и Docker

```bash
systemctl status caddy --no-pager
caddy version
docker compose version
```

Нужен Caddy **2.10+**: директива `request_body` появилась только в 2.10. Если версия старше, обновите Caddy из официального репозитория:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install --only-upgrade caddy
caddy version
```

Caddy из apt обычно читает `/etc/caddy/Caddyfile`. Если Caddy запущен в контейнере, команды отличаются — см. примечание в конце раздела.

### 2. Подготовить `.env`

```bash
cd family-tracker/deploy
cp .env.example .env
chmod 600 .env
nano .env
```

```env
API_DOMAIN=api.example.com
NTFY_DOMAIN=ntfy.example.com
ACME_EMAIL=you@example.com
NTFY_TOKEN=tk_placeholder
```

`ACME_EMAIL` здесь не используется системным Caddy, но требуется подстановкой Compose. DNS-записи `api.*` и `ntfy.*` должны указывать на этот сервер.

### 3. Создать пользователя ntfy

Поднимите только ntfy:

```bash
docker compose -f docker-compose.yml -f compose.host-caddy.yml up -d ntfy
docker compose -f docker-compose.yml -f compose.host-caddy.yml logs -f ntfy
```

В другом терминале:

```bash
docker compose -f docker-compose.yml -f compose.host-caddy.yml \
  exec ntfy ntfy user add --role=user tracker

docker compose -f docker-compose.yml -f compose.host-caddy.yml \
  exec ntfy ntfy access tracker 'family-*' read-write

docker compose -f docker-compose.yml -f compose.host-caddy.yml \
  exec ntfy ntfy token add --label=family-tracker tracker
```

Скопируйте `tk_...` в `NTFY_TOKEN` в `.env`.

### 4. Добавить сайты в Caddyfile

Сделайте резервную копию и откройте конфиг:

```bash
sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak.$(date +%F-%H%M)
sudo nano /etc/caddy/Caddyfile
```

Добавьте два блока, не удаляя существующие сайты:

```caddyfile
api.example.com {
    request_body {
        max_size 256KB
    }
    reverse_proxy 127.0.0.1:8080
}

ntfy.example.com {
    request_body {
        max_size 256KB
    }
    reverse_proxy 127.0.0.1:8585
}
```

Проверьте и перезагрузите Caddy:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
sudo journalctl -u caddy -n 50 --no-pager
```

Caddy сам выпустит сертификаты и добавит `X-Forwarded-For`. `request_body` заменяет лимит тела, который в Docker-варианте делал контейнер Caddy; в Ktor 3.0.1 своего лимита нет.

### 5. Запустить Ktor и ntfy

```bash
docker compose -f docker-compose.yml -f compose.host-caddy.yml up -d --build
docker compose -f docker-compose.yml -f compose.host-caddy.yml ps
```

Проверки:

```bash
curl https://api.example.com/health
# ok

curl https://ntfy.example.com/v1/health
# {"healthy":true}
```

Проверка токена:

```bash
set -a; . ./.env; set +a

curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  -d "healthcheck" "https://$NTFY_DOMAIN/family-healthcheck"

curl -fsS -H "Authorization: Bearer $NTFY_TOKEN" \
  "https://$NTFY_DOMAIN/family-healthcheck/json?poll=1"
```

После этого сразу привяжите семью из приложения.

### Примечания

- Контейнер `caddy` из проекта не запускается; не выполняйте `docker compose up` без override, иначе будет конфликт за 80/443.
- Если системный Caddy включает access-логи, токен из `/live?token=...` может попасть в лог. Отключите логи для `api.*` или настройте редактирование query.
- Если Caddy сам работает в Docker, `127.0.0.1` из его контейнера указывает на сам контейнер. Тогда публикуйте Ktor/ntfy на LAN-IP и проксируйте на него либо подключите Caddy к сети `family-tracker_default` и используйте имена `ktor:8080`/`ntfy:8585`.

## Portainer

Если стеком управляет Portainer, используйте готовый `compose.portainer.yml` **в корне репозитория**: он содержит только `ktor` и `ntfy`, без Caddy, и публикует их на LAN-IP для NPM.

### Создание стека

**Stacks → Add stack → Repository**:

| Поле | Значение |
| --- | --- |
| Name | `family-tracker` |
| Repository URL | `https://github.com/itdevlog/family-tracker` |
| Repository reference | `refs/heads/master` |
| Compose path | `compose.portainer.yml` |
| Authentication | включить, если репозиторий private |

Для private-репозитория нужен GitHub Personal Access Token с правом чтения репозитория; в поле пароля укажите токен, в поле пользователя — `itdevlog`.

Имя стека должно быть `family-tracker`: от него зависят имена volume'ов (`family-tracker_tracker_data`, `family-tracker_ntfy_lib`), которые используются в командах бэкапа.

В разделе **Environment variables** добавьте:

```env
NTFY_DOMAIN=ntfy.example.com
NTFY_TOKEN=tk_placeholder
BIND_ADDRESS=192.168.1.10
```

`BIND_ADDRESS` — LAN-IP хоста, где Portainer запускает контейнеры. Не указывайте `0.0.0.0`, если у хоста есть публичный IP.

Нажмите **Deploy the stack**. Portainer склонирует репозиторий, соберёт образ `ktor` из `server/` и запустит оба сервиса.

Стек намеренно лежит в корне репозитория: в Portainer относительные пути надёжно считаются от корня, поэтому `build: ./server` не зависит от того, как Portainer трактует каталог compose-файла. Конфиг ntfy задан переменными `NTFY_*`, без bind-mount `server.yml`, — это убирает ошибку вида:

```text
read /etc/ntfy/server.yml: is a directory
```

Если такая ошибка уже появилась, значит стек собран из старого файла `deploy/compose.portainer.yml` или через Web editor без файлов репозитория. Измените Compose path на `compose.portainer.yml` и переразверните стек; volumes с данными сохранятся.

> Не создавайте этот стек через **Web editor**: `build: ./server` требует исходников репозитория рядом с compose-файлом. Web editor подходит только если вы заранее собрали образ и заменили `build` на `image`.

### Пользователь и токен ntfy в Portainer

Стек уже запущен с заглушкой `NTFY_TOKEN`, поэтому создайте пользователя через консоль контейнера:

**Containers → ntfy → Console → `/bin/sh`**:

```bash
ntfy user add --role=user tracker
ntfy access tracker 'family-*' read-write
ntfy token add --label=family-tracker tracker
```

Скопируйте `tk_...`, вернитесь в **Stacks → family-tracker → Editor** и замените `NTFY_TOKEN` на настоящий. Нажмите **Update the stack** с включённым **Re-pull image and redeploy** (или просто redeploy — образ ktor пересоберётся при изменении переменных).

### NPM

Настройка NPM не меняется: оба Proxy Host указывают на `BIND_ADDRESS`, порты `8080` и `8585`, WebSockets включены. См. раздел [«Вместо Caddy: Nginx Proxy Manager»](#вместо-caddy-nginx-proxy-manager).

### Если Portainer не собирает образ

Некоторые версии Portainer не поддерживают `build` в Git-стеке. Тогда соберите образ на хосте:

```bash
git clone https://github.com/itdevlog/family-tracker.git
cd family-tracker
docker build -t family-tracker-server:local server
```

В Portainer создайте стек через **Web editor** на основе корневого `compose.portainer.yml`, заменив:

```yaml
build: ./server
```

на:

```yaml
image: family-tracker-server:local
```

Образ должен быть собран на том же Docker-хосте, которым управляет Portainer.

### Обновление

**Stacks → family-tracker → Pull and redeploy**. Перед обновлением сделайте бэкап volume'ов по разделу [«Бэкап»](#бэкап).

## Установка APK

Команды этого раздела выполняются из корня репозитория; остальные команды README — из каталога `deploy/`.

APK собирается из каталога `app/`:

```bash
(cd app && ./gradlew assembleRelease)
```

Готовый файл: `app/build/outputs/apk/release/app-release.apk` внутри каталога `app/` (от корня репозитория — `app/app/build/outputs/apk/release/app-release.apk`). Один и тот же APK ставится на телефон родителя и на телефон ребёнка; роль выбирается при первом запуске.

Установка по USB (из корня репозитория):

```bash
adb install -r app/app/build/outputs/apk/release/app-release.apk
```

Либо передайте файл на телефон (кабель, облако, мессенджер) и откройте его; для файлового менеджера/мессенджера разрешите «Неизвестные источники». Для обновления ставьте новый APK поверх старого — данные приложения (токен и привязка) сохраняются. При удалении приложения токен и пара теряются, потребуется привязка заново.

Release подписан debug-ключом: это годится только для sideload-установки, но не для Google Play. При смене ключа подписи APK нужно удалить старую версию и установить заново.

После установки пройдите сценарии из [`docs/checklist.md`](../docs/checklist.md).

## Первичная привязка

> **Сразу после деплоя выполните первичную привязку.** Пока семьи нет, `POST /pair/parent` открыт без авторизации: любой, кто узнал адрес сервера, может создать семью и занять единственный слот. Не оставляйте сервер развёрнутым без привязки.

> **`POST /pair/parent` — это замена первого запуска приложения, а не подготовка к нему.** Если выполнить curl ниже, семья и родительское устройство уже созданы; приложение при первом запуске попытается зарегистрироваться само и получит `409 Conflict`. Выберите один путь: либо curl, либо первый запуск приложения. Если curl уже выполнен, а приложение ещё не настроено, сбросьте семью (раздел «Сброс семьи») и пройдите привязку в приложении.

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

## Сброс семьи

Семья — это одна запись в SQLite; отдельной «удалить семью» API нет. Сброс выполняется удалением файлов БД (с бэкапом), после чего приложение снова проходит первичную привязку. Все выданные ранее токены (родителя и детей) станут недействительны, детей нужно привязать заново.

```bash
# 1. Остановить стек, чтобы SQLite не держала открытые файлы.
docker compose down

# 2. Бэкап текущего volume (на всякий случай; см. также раздел «Бэкап»).
mkdir -p backup
docker run --rm \
  -v family-tracker_tracker_data:/data:ro \
  -v "$PWD/backup:/backup" \
  alpine sh -c 'tar czf /backup/tracker-before-reset-$(date +%F-%H%M).tgz -C /data .'

# 3. Удалить БД и её WAL/SHM-файлы. Остальное содержимое volume не трогаем.
docker run --rm \
  -v family-tracker_tracker_data:/data \
  alpine sh -c 'rm -f /data/tracker.db /data/tracker.db-wal /data/tracker.db-shm'

# 4. Запустить стек и выполнить первичную привязку заново.
docker compose up -d
curl https://api.example.com/health
```

Volume `ntfy_lib` (пользователи/токены ntfy) при сбросе семьи можно не удалять: он не содержит данных семьи. Если нужно сбросить и ntfy-доступ, удалите его отдельно и заново создайте пользователя и токен по разделу выше.

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

Сначала **проверьте архивы, ничего не останавливая и не удаляя**. `tar tzf` должен прочитать архив, а внутри должны быть нужные файлы (`tracker.db` для БД, `user.db` для ntfy auth/ACL). Если проверка упала — не выполняйте команды восстановления: volume останется нетронутым.

```bash
mkdir -p backup

# tracker_data: архив читается и содержит tracker.db
docker run --rm -v "$PWD/backup:/backup:ro" alpine sh -c '
  set -eu
  tar tzf /backup/tracker-YYYY-MM-DD.tgz >/dev/null
  tar tzf /backup/tracker-YYYY-MM-DD.tgz | grep -Eq "(^|/)tracker\.db$"
  echo "tracker archive OK"
'

# ntfy_lib: архив читается и содержит user.db
docker run --rm -v "$PWD/backup:/backup:ro" alpine sh -c '
  set -eu
  tar tzf /backup/ntfy-lib-YYYY-MM-DD.tgz >/dev/null
  tar tzf /backup/ntfy-lib-YYYY-MM-DD.tgz | grep -Eq "(^|/)user\.db$"
  echo "ntfy archive OK"
'
```

Только после успешной проверки остановите стек и распакуйте архивы. Распаковка идёт в `/tmp` внутри контейнера, проверяется ещё раз, и лишь затем заменяет содержимое volume — ошибка чтения архива не оставит пустой volume:

```bash
docker compose down

# tracker_data (файлы БД)
docker run --rm \
  -v family-tracker_tracker_data:/data \
  -v "$PWD/backup:/backup:ro" \
  alpine sh -c '
    set -eu
    rm -rf /tmp/restore && mkdir -p /tmp/restore
    tar xzf /backup/tracker-YYYY-MM-DD.tgz -C /tmp/restore
    test -f /tmp/restore/tracker.db
    rm -rf /data/*
    cp -a /tmp/restore/. /data/
  '

# ntfy auth/ACL
docker run --rm \
  -v family-tracker_ntfy_lib:/lib \
  -v "$PWD/backup:/backup:ro" \
  alpine sh -c '
    set -eu
    rm -rf /tmp/restore && mkdir -p /tmp/restore
    tar xzf /backup/ntfy-lib-YYYY-MM-DD.tgz -C /tmp/restore
    test -f /tmp/restore/user.db
    rm -rf /lib/*
    cp -a /tmp/restore/. /lib/
  '

# опционально — кэш сообщений
docker run --rm \
  -v family-tracker_ntfy_cache:/cache \
  -v "$PWD/backup:/backup:ro" \
  alpine sh -c '
    set -eu
    rm -rf /tmp/restore && mkdir -p /tmp/restore
    tar xzf /backup/ntfy-cache-YYYY-MM-DD.tgz -C /tmp/restore
    test -f /tmp/restore/cache.db
    rm -rf /cache/*
    cp -a /tmp/restore/. /cache/
  '

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
- Размер тела запроса ограничен 256 KB на ingress (директива Caddy `request_body`) для API и ntfy. В Ktor 3.0.1 встроенного лимита тела нет, поэтому это единственная линия защиты; не публикуйте `ktor:8080` или `ntfy:8585` напрямую.
- Повторный `POST /sos` от одного и того же ребёнка в течение 30 секунд подтверждается `204`, но не создаёт событие, WS-пуш или ntfy-уведомление — это гасит retry-циклы клиента и спам украденным детским токеном.
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
