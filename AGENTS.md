# AGENTS.md — памятка для ИИ-агентов, продолжающих работу над ZI Git

> Прочитай этот файл целиком, прежде чем менять код. Здесь — всё, что нужно,
> чтобы продолжить разработку с текущего места и ничего не сломать.

## Что это за проект

**ZI Git** — Android-приложение (чистая Java, **без Gradle**), загрузчик готовых
сборок из GitHub Actions + работа с релизами, репозиториями, логами сборки.
Пакет `com.zigit.app`, minSdk 26, target 34. Весь код — в `ghactions-app/`.

Разработчик: Захаревич Игорь · ziv@csl.by.
Язык проекта, коммитов и интерфейса — **русский**.

## ⚠️ Главное правило: где лежит актуальный код

- **Ветка `main` ОТСТАЁТ** (застряла на древнем коммите «1», версия 2.1) — **не бери код из неё**.
- Источник истины — **тег последнего релиза** (`git fetch --tags`; релиз Latest в
  GitHub Releases) и ветки `arena/*` последних сессий.
- У пользователя установлена версия из **последнего релиза** (`gh api repos/zigorminsk-debug/ZI-GITHUB/releases/latest --jq .tag_name`).
- Перед началом работы сверь `android:versionName` в `ghactions-app/AndroidManifest.xml`
  своего чекаута с тегом последнего релиза. Если чекаут старее — `git reset --hard vX.Y`
  на свою рабочую ветку и продолжай от него.

## Текущее состояние (2026-10-05)

- Актуальная версия: **2.9** (`versionCode 13`), релиз `v2.9` = Latest,
  собран из этой ветки (`arena/01a10a68-zi-github`).
- История версий: теги `v2.2 … v2.9`. На каждый тег есть релиз с APK.
- Новое в 2.9: панель **«Подключение другой программы»** на главном экране
  (появляется при открытии репозитория): кнопки «Скопировать адрес»
  (git-URL; long-press — URL со встроенным токеном) и «Скопировать токен».
  Код: `MainActivity.copyRepoUrl()` / `copyToken()`, разметка — `connectRow`
  в `activity_main.xml`.

## Как собирается и выпускается APK (ничего не делать руками!)

1. Любой `git push` в репозиторий запускает workflow **`.github/workflows/build-apk.yml`**.
2. Он вызывает `./setup-android-sdk.sh` и `./build.sh`
   (aapt2 → javac → d8/R8 → zipalign → apksigner), подписывает APK
   **постоянным ключом** `keystore/zigit-release.jks` (пароль — в `keystore/README.txt` и в `build.sh`).
3. Затем публикует/обновляет релиз `v<versionName>` с двумя ассетами:
   `ZI-Git.apk` (стабильное имя) и `ZI-Git-v<versionName>.apk`, ставит его **Latest**.
4. Установленное приложение **само находит обновление**: `MainActivity.checkForUpdate()`
   опрашивает `releases/latest` репозитория `UPDATE_REPO = zigorminsk-debug/ZI-GITHUB`
   и качает ассет **строго с именем `ZI-Git.apk`** (не переименовывать!).

### Чек-лист при любом изменении кода приложения

1. Внеси правки в `ghactions-app/`.
2. **Обязательно** подними версию в `ghactions-app/AndroidManifest.xml`:
   `versionCode` +1 и `versionName` +0.1 (например 2.9 → 2.10, code 13 → 14).
   Без этого workflow перезапишет СТАРЫЙ релиз, и приложение у пользователя
   не увидит обновления.
3. Обнови README.md (разделы про экраны/кнопки) и при необходимости `ui-preview.html`.
4. `git push` в свою рабочую ветку → дождись успеха workflow «Build APK»
   (`gh run watch <id> --exit-status`).
5. Проверь: `gh api repos/zigorminsk-debug/ZI-GITHUB/releases/latest --jq .tag_name`
   должен вернуть новый тег.

## Ограничения песочницы Arena (проверено)

- Сеть открыта **только** на `github.com`, `api.github.com`, `codeload.github.com`.
- **Недоступны**: `dl.google.com`, `maven.google.com`, `repo1.maven.org`,
  `objects.githubusercontent.com`, apt-зеркала → **локально собрать APK нельзя**
  (нет JDK и Android SDK, и их не скачать) и **нельзя скачать ассеты релизов**.
- Поэтому: собирай только через GitHub Actions (push → workflow), а готовый APK
  проверяй по логам workflow и метаданным релиза.

## Карта репозитория

| Путь | Назначение |
|------|------------|
| `ghactions-app/src/com/zigit/app/` | Весь Java-код (16 классов; главный — `MainActivity.java`) |
| `ghactions-app/res/` | Ресурсы (layout/values/drawable) |
| `ghactions-app/AndroidManifest.xml` | **Версия приложения — поднимать при каждом релизе** |
| `ghactions-app/lib/r8.jar` | Dex-компилятор. **Не удалять**: maven недоступен из песочницы |
| `build.sh` | Сборка без Gradle; пути SDK/JDK берёт из окружения |
| `setup-android-sdk.sh` | Установка SDK/JDK (работает только в Actions-раннере) |
| `.github/workflows/build-apk.yml` | CI: сборка + подпись + публикация релиза |
| `keystore/zigit-release.jks` | **Постоянный ключ подписи до 2056 г. Не удалять, не перегенерировать!** |
| `example-workflow/android-build.yml` | Пример workflow для ЧУЖИХ проектов (чтобы их сборки видело приложение) |
| `ui-preview.html`, `icon-preview.png` | Статические превью интерфейса и иконки |
| `README.md` | Пользовательская документация (экраны, токен, обновления) |

## Что уже вычищено (не возвращать)

- `.android/` (кэш sdkmanager), `.sudo_as_admin_successful`, `uploads/` со случайными
  скриншотами, устаревшие `ZI-Git-vX.Y.apk` в корне репозитория — удалены в 2.9.
  APK в git не хранится: готовые сборки живут в GitHub Releases, `.gitignore` уже настроен.

## Типичные ошибки прошлых сессий — не повторять

1. **Сборка от `main`** → получилась «новая» версия 2.2 при установленной у
   пользователя 2.8. Всегда начинай от тега последнего релиза.
2. Забытый bump `versionCode`/`versionName` → приложение не предлагает обновление.
3. Попытка ставить SDK/JDK в песочнице → трата времени, сеть закрыта.
