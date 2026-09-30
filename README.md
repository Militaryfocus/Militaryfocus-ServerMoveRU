# ServerMove RU

Нативное Android-приложение на Kotlin/Jetpack Compose для контролируемой миграции сайтов, баз данных и self-hosted Supabase между Linux-серверами по SSH. Телефон выступает защищённым управляющим клиентом и потоковым мостом: гигабайтные архивы не сохраняются в память устройства.

## Реализовано в 0.1.0

- Два независимых SSH-подключения: источник и назначение.
- Обязательная проверка SSH host key по SHA-256 fingerprint; отключение `StrictHostKeyChecking` не используется.
- SSH-пароли живут только в памяти процесса и приложением не сохраняются.
- `FLAG_SECURE`: экраны с учётными данными не попадают в обычные скриншоты/preview последних приложений.
- Потоковый перенос каталога `tar -> SSH -> Android -> SSH -> tar` без локального архива.
- PostgreSQL: `pg_dump --format=custom -> pg_restore`.
- MySQL/MariaDB: `mysqldump -> mysql` с routines/events/triggers.
- Fail-closed preflight: инструменты, пути, права, свободное место, наличие и пустота назначения.
- Непустой целевой каталог блокирует перенос; существующая непустая БД также блокируется. Автоматический destructive cleanup намеренно отсутствует.
- Защита от очевидного переноса каталога самого в себя на одном SSH endpoint.
- Опциональная агрегированная SHA-256 проверка обычных файлов после переноса.
- Foreground Service, системное уведомление, отмена операции и корректная обработка Android `dataSync` timeout.
- Скорость передачи, объём, процент и ETA.
- Троттлинг progress callbacks, чтобы уведомления Android не обновлялись на каждый блок сетевого потока.
- Русский интерфейс и журнал этапов с временными метками.
- Unit-тесты ранней валидации и Shell escaping.
- GitHub Actions: unit tests + Android Lint + сборка debug APK + публикация APK как workflow artifact.

## Supabase: контур 0.2.0

В ветке 0.2 добавлен отдельный режим **self-hosted Supabase → self-hosted Supabase (cold snapshot)**. Он не смешивается с обычным переносом файлов/PostgreSQL/MySQL.

Переносятся целиком данные официального Docker-развёртывания из указанного Supabase root:

- `docker-compose*.yml`, `.env`, `.supabase-version` и прочая конфигурация;
- физический PostgreSQL data directory `volumes/db/data` вместе со служебными файлами Supabase;
- локальный Storage `volumes/storage`;
- Functions и другие файлы, расположенные внутри Supabase Docker root;
- владельцы файлов, ACL и xattrs через GNU `tar`.

### Fail-closed правила Supabase

- source stack **должен быть заранее остановлен вручную**; приложение само его не останавливает и не удаляет;
- target Supabase root должен отсутствовать или быть пустым;
- на target не должно быть существующих контейнеров `supabase-*`;
- архитектура CPU source и target (`uname -m`) должна совпадать, потому что переносится физический PostgreSQL data directory;
- требуются `docker compose`, GNU `tar` с поддержкой `--xattrs`/`--acls`, `sha256sum` и passwordless `sudo -n` для migration-пользователя;
- принимается только локальный `STORAGE_BACKEND=file`; внешний S3/object storage пока блокируется;
- до копирования проверяется свободное место;
- после копирования можно выполнить агрегированный SHA-256 всех обычных файлов snapshot;
- если включён автозапуск target, preflight заранее проверяет поддержку `docker compose up --wait`;
- опционально после успешной checksum-проверки target запускается через `docker compose up -d --wait`;
- DNS/Nginx/HAProxy cutover автоматически не выполняется.

Cold snapshot выбран намеренно: обычный `pg_dump` недостаточен для полного клонирования self-hosted Supabase, потому что кроме пользовательских таблиц есть Auth, Storage metadata, системные роли/расширения, локальные Storage-объекты и конфигурация сервисов. Физический snapshot разрешён только при остановленном source stack, чтобы Postgres data и Storage оставались согласованными.

## Модель безопасности

Источник не удаляется и не очищается. Приложение не выполняет автоматический DNS/Nginx cutover, не делает `DROP DATABASE`, `pg_restore --clean` и не очищает целевой каталог. Если target уже содержит данные, операция останавливается до передачи первого байта.

SSH host key должен быть заранее получен по доверенному каналу и введён как fingerprint. Это защищает подключение от незаметной подмены сервера/MITM.

Для production рекомендуется отдельный SSH-пользователь миграции с минимально необходимыми правами, а не постоянная работа через `root`.

## Требования к серверам

Приложение ориентировано на Linux-серверы, доступные по SSH.

Для файлов нужны `tar`, `du`, `find`, `sort`, `xargs`, `sha256sum`, `df`.

Для PostgreSQL текущий SSH-пользователь должен иметь возможность без интерактивного ввода пароля выполнять только необходимые команды, например:

```bash
sudo -n -u postgres psql ...
sudo -n -u postgres pg_dump ...
sudo -n -u postgres pg_restore ...
sudo -n -u postgres createdb ...
```

Для MySQL/MariaDB текущая реализация ожидает доступ:

```bash
sudo -n mysql ...
sudo -n mysqldump ...
```

Для Supabase cold migration нужны Docker Engine + Docker Compose и разрешённые `sudo -n` операции для чтения snapshot, запуска Docker и записи target-каталога. Секреты `.env` не выводятся в журнал приложения.

Для production лучше оформить узкий `sudoers` policy для migration-пользователя, а не выдавать полный root-доступ.

## SSH fingerprint

На каждом сервере получите fingerprint по доверенному административному каналу. Для ED25519 host key:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

В приложение вставляется значение вида `SHA256:...`. Значение валидируется до запуска foreground-службы.

## Android / сборка

Текущий toolchain проекта:

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17+
- `compileSdk = 37`
- `targetSdk = 36`
- `minSdk = 26`
- Android SDK Platform package `platforms;android-37.0`
- Android Build Tools `37.0.0`
- Jetpack Compose

`compileSdk` 37 нужен современным Compose/Core/Lifecycle зависимостям, а `targetSdk` пока остаётся 36, чтобы не включать новые Android 17 runtime-behavior без отдельной acceptance-проверки.

В репозитории предусмотрен `.github/workflows/android-ci.yml`, который поднимает JDK/Android SDK/Gradle независимо от локальной машины, выполняет unit tests, Android Lint и `assembleDebug`, затем сохраняет APK как Actions artifact.

Локально проект можно открыть в актуальной Android Studio с JDK 17+. Стандартный Gradle wrapper должен быть сгенерирован Gradle 9.6.0 перед тем, как считать локальную CLI-сборку полностью воспроизводимой.

## Ограничения

- В UI пока только password SSH authentication; ключи из Android Keystore — следующий security-контур.
- Телефон сейчас является сетевым relay. На Android 15+ `dataSync` foreground services имеют системный суточный лимит длительности, поэтому многотерабайтные миграции лучше переводить на direct server-to-server transport, где телефон только управляет заданием.
- Нет произвольного byte-resume после гибели процесса; безопасный повтор предполагает новый/пустой target.
- Обычный режим 0.1.0 не копирует автоматически системных пользователей, Docker/Podman volumes, systemd units, firewall, cron, secrets и внешние object-storage ресурсы.
- Supabase 0.2 переносит только self-hosted Docker deployment с локальным file Storage. Supabase Cloud, внешний S3/R2/MinIO и Kubernetes/Helm пока не входят в этот контур.
- Не выполняется DNS cutover и переключение Nginx/HAProxy автоматически.
- Симлинки переносятся через `tar`, но checksum-контур сравнивает обычные файлы.
- Файловый и Supabase-режимы предполагают GNU/Linux userland для используемых CLI-параметров.

Перед production cutover обязательна отдельная прикладная проверка сайта/приложения на новом сервере и только затем ручное переключение трафика.
