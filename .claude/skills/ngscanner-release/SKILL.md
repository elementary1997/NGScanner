---
name: ngscanner-release
description: >-
  Собрать и выкатить релиз Android-приложения NGScanner (elementary1997/NGScanner на
  GitHub). Поднимает версию, проверяет сборку и тесты локально, коммитит, ставит тег
  vX.Y.Z и пушит по SSH — GitHub Actions (.github/workflows/release.yml) сам собирает
  APK и публикует GitHub Release, откуда встроенное автообновление приложения его
  забирает. Используй, когда просят «выкати релиз», «сделай релиз», «собери и
  опубликуй новую версию», «зарелизь X.Y.Z», «release NGScanner». НЕ для Bitbucket/
  NOVA (там nova-bitbucket) и не для обычных коммитов без релиза.
---

# Выкатка релиза NGScanner

Релиз публикует **GitHub Actions по push тега** — прямой доступ к GitHub API из
окружения не нужен (`gh`/токена нет; внутри Actions есть автоматический
`GITHUB_TOKEN`). Твоя задача — подготовить версию и **запушить тег `vX.Y.Z`**.

## Предпосылки (проверь до начала)

1. Ветка `main`, дерево без незакоммиченного мусора (или только те правки, что войдут
   в релиз). `git status` — покажи пользователю, что войдёт.
2. Push по SSH настроен: `git@github.com:elementary1997/NGScanner.git`, ключ
   `~/.ssh/id_rsa` (проверка: `ssh -T git@github.com` → «Hi elementary1997»).
3. Окружение сборки (java не в PATH):
   ```bash
   export ANDROID_HOME=$HOME/android-sdk
   export JAVA_HOME=$HOME/toolchain/jdk-17.0.19+10
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

## Шаги

1. **Версия.** Спроси у пользователя номер (или предложи bump). SemVer `X.Y.Z`.
   В [app/build.gradle.kts](../../../app/build.gradle.kts):
   - `versionName = "X.Y.Z"`;
   - `versionCode` — **строго увеличить** (обычно +1; Android сравнивает именно его).
   Тег и `versionName` должны совпадать (тег = `v` + versionName).

2. **Заметки о релизе.** Обнови `CHANGELOG.md` (создай, если нет): секция `## vX.Y.Z`
   — короткий список изменений на русском, лаконично, по конвенции коммитов проекта.

3. **Проверь локально** (обязательно, до пуша):
   ```bash
   ./gradlew --offline testDebugUnitTest lintDebug assembleDebug
   ```
   Красный билд — не релизим.

4. **Коммит + тег + пуш** (это outward-facing — подтверди у пользователя перед пушем):
   ```bash
   git add -A
   git commit -m "Release vX.Y.Z"
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin main
   git push origin vX.Y.Z
   ```

5. **Дальше — CI.** Push тега запускает `release.yml`: сборка APK → публикация
   GitHub Release с приложенным `ngscanner-vX.Y.Z.apk`. Прямо следить за Actions из
   окружения нельзя (нет токена) — сообщи пользователю ссылки:
   - Actions: `https://github.com/elementary1997/NGScanner/actions`
   - Релиз:   `https://github.com/elementary1997/NGScanner/releases/tag/vX.Y.Z`
   Встроенное автообновление ([update/AppUpdater.kt](../../../app/src/main/java/ru/ngscanner/update/AppUpdater.kt))
   берёт `releases/latest` и первый `.apk`-ассет — как только релиз опубликован,
   пользователи увидят обновление.

## Подпись APK (важно для «install over»)

Автообновление ставит новый APK **поверх** старого только при **той же подписи**.
Настрой один раз секреты репозитория (Settings → Secrets and variables → Actions):

```bash
# 1) создать release-keystore (один раз, хранить надёжно, НЕ коммитить):
keytool -genkeypair -v -keystore release.jks -alias ngscanner \
  -keyalg RSA -keysize 2048 -validity 10000
# 2) получить base64 для секрета:
base64 -w0 release.jks
```
Секреты: `RELEASE_KEYSTORE_B64` (вывод base64), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` (`ngscanner`), `RELEASE_KEY_PASSWORD`. Пока секретов нет, CI
собирает debug-APK (для теста годится; для стабильного «install over» задай секреты).

Первый переход debug→release-подпись потребует один раз переустановить приложение
(смена подписи), дальше обновления встают поверх.

## Границы

- Не публикуй релиз с красными тестами/линтом.
- `versionCode` только увеличивать — иначе Android откажется ставить обновление.
- Тег неизменяем: если ошибся — новый патч-релиз, а не переписывание тега.
- Не коммить keystore и пароли в репозиторий (только через секреты Actions).
