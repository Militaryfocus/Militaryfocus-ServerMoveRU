# ServerMove RU

Нативное Android-приложение на Kotlin/Jetpack Compose для контролируемой миграции сайтов и баз данных между Linux-серверами по SSH. Телефон выступает защищённым управляющим клиентом и потоковым мостом: гигабайтные архивы не сохраняются в память устройства.

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
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- Jetpack Compose

В репозитории предусмотрен `.github/workflows/android-ci.yml`, который поднимает JDK/Android SDK/Gradle независимо от локальной машины, выполняет unit tests, Android Lint и `assembleDebug`, затем сохраняет APK как Actions artifact.

Локально проект можно открыть в актуальной Android Studio с JDK 17+. Стандартный Gradle wrapper должен быть сгенерирован Gradle 9.6.0 перед тем, как считать локальную CLI-сборку полностью воспроизводимой.

## Ограничения 0.1.0

- В UI пока только password SSH authentication; ключи из Android Keystore — следующий security-контур.
- Телефон сейчас является сетевым relay. На Android 15+ `dataSync` foreground services имеют системный суточный лимит длительности, поэтому многотерабайтные миграции лучше переводить на direct server-to-server transport, где телефон только управляет заданием.
- Нет произвольного byte-resume после гибели процесса; безопасный повтор предполагает новый/пустой target.
- Не копируются автоматически системные пользователи, Docker/Podman volumes, systemd units, firewall, cron, secrets и внешние object-storage ресурсы.
- Не выполняется DNS cutover и переключение Nginx/HAProxy автоматически.
- Симлинки переносятся через `tar`, но checksum-контур 0.1.0 сравнивает обычные файлы.
- Файловый режим предполагает GNU/Linux userland для используемых CLI-параметров.

Перед production cutover обязательна отдельная прикладная проверка сайта/приложения на новом сервере и только затем ручное переключение трафика.
