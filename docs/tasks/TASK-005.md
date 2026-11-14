# TASK-005: Суть из сохранённого WAV, независимо от сервиса перевода

## Метаданные задачи
- Дата: 2026-11-14
- Статус: Ready for Worker
- Мастер: Codex
- Исполнитель: Antigravity
- Файл отчета воркера: `docs/tasks/TASK-005-REPORT.md`

## 1. Контекст и архитектурная цель
Пользователь разрешил изменения PocketHUD и тестовый APK. Требует Antigravity как исполнителя. Реализуй настоящий русский конспект САМОГО аудио после остановки перевода. Сейчас MainActivity.showAudioRecordings умеет воспроизведение/экспорт, requestAiSummary отправляет ТОЛЬКО текст через живой PocketService. Новая функция не должна зависеть от PocketService.currentInstance или текстовых turns. Исходные записи и существующий захват PCM сохраняем.

Репозиторий задачи — android_pocket_hud (отдельный git). Прочитай ../CODEX_WORKFLOW.md и ../.agents/rules/pockethud.md. Прямое разрешение пользователя отменяет прежний запрет касаться этого проекта. Не меняй глобальную среду/IDE. Никаких секретов в логах. Не читай внутренние токены Antigravity. Модель воркера/стоимость не выводятся из имени CLI.

Решение мастера: минимальное расширение текущего окна «Записи»; отдельные WAV parser/slicer, API client и coordinator/store, доступные с applicationContext. Модель только gemini-3.8-flash, существующий пользовательский API-ключ из pocket_hud_prefs/api_key. Не выполнять реальные inference вызовы с платным/неизвестным billing, не искать ключи на компьютере. Бесплатный tier есть в официальной таблице, конкретная квота пользователя неизвестна. Никаких обещаний €0 в UI и fallback на другие модели.

Альтернативы исследованы мастером: desktop read_file Antigravity не является Android API; inline base64 годится для коротких клипов, но 10min WAV 19.2MB становится >25MB; Files API подходит для целых 10min частей и интервала. Используй Files API для всего, без base64 всего файла в памяти. Подробности REST сверяй по официальным документам:
- https://ai.google.dev/gemini-api/docs/audio
- https://ai.google.dev/gemini-api/docs/files (resumable upload start; X-Goog-Upload-URL; upload, finalize)
- https://ai.google.dev/api/files (state whitelist STATE_UNSPECIFIED, PROCESSING, ACTIVE, FAILED)
- https://ai.google.dev/api/generate-content (generateContent; contents/parts/fileData.mimeType,fileUri; text output; ignore thought parts)
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash (Audio input, Text output)
- https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash (Standard free tier)
- https://ai.google.dev/gemini-api/docs/rate-limits (per project quotas)
Все семь базовых URL мастер проверил Python urllib 14.11.2026: HTTP 200. Документы редиректят на aistudio.google.com. Документация audio сейчас продвигает interactions, но существующий generateContent поддерживает fileData. Проверяй официальный REST schema; не выдумывай поля.

## 2. Область изменений (Scope Whitelist)
- `app/src/main/java/com/aleksandr/pockethud/MainActivity.kt`
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryManager.kt`
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryApi.kt`
- `app/src/main/java/com/aleksandr/pockethud/WavAudioClip.kt`
- `app/src/test/java/com/aleksandr/pockethud/AudioSummaryTest.kt`
- `app/build.gradle.kts`
- `docs/audio-summary.md`
- `docs/tasks/TASK-005-REPORT.md`

## 3. Требования
- [R1] В меню конкретной записи: «Суть по аудио», просмотр сохранённых конспектов. На главных кнопках «Конспект» направить в выбор аудиозаписи; старую генерацию текста можно сохранить как явно обозначенный отдельный метод, но пользователь не должен путать её с аудио. Не затрагивать TtsMode, live socket/микрофон и каскад. В общем окне summary убрать фиктивные заголовки о модели, если они не отражают источник.
- [R2] Поддержать готовые PCM16 mono 16kHz WAV проекта: всю выбранную часть или интервал (от/до в MM:SS, допускай десятые секунды). Показать длительность, 0 <= start < end <= duration. Реально вырезать PCM по границам фреймов в временный WAV, корректный RIFF/data header; оригинал не менять. Полный файл и интервалы до конца должны работать для нецелой длительности 70.2 s. Корректно разобрать RIFF chunks/padding (не предполагать всегда header 44 bytes), отклонить truncated/неверный format/пустой/неполный. Для .m4a — ясное сообщение «Суть пока доступна для WAV Live», не выдавать за реализованное.
- [R3] Отправлять аудиобайты по Files API, затем только ACTIVE fileData в generateContent. HTTP таймауты и bounded polling PROCESSING. STATE_UNSPECIFIED/unknown/FAILED — ошибка; ни inference до ACTIVE, ни бесконечных повторов. 401/403/404/429/5xx, network, empty/blocked response и MAX_TOKENS — понятные локализованные ошибки, не успешный пустой/обрезанный конспект. API key только header x-goog-api-key. Upload URL валидировать https/доверенный Google host, не отдавать ключ произвольному redirect host (redirects disabled либо safe validation). DELETE uploaded file в finally после успешной/неуспешной генерации; ошибки cleanup не должны скрывать исходную ошибку, предупреждение пользователю если удаление не удалось. Временный локальный WAV удалить; оригиналы никогда не удалять.
- [R4] Генерация в background executor, не UI thread. Не привязывать сетевую работу к живому translation service. Один активный запрос, повторное нажатие не создаёт дубликат. Отмена/закрытие Activity и устаревший callback не рисуют чужой результат/не сохраняют под другим диапазоном. Задержанный ответ A после отмены и старта B не меняет состояние B; guard request identity. Используй appContext или pure Java APIs в core для JVM тестов. Чётко обработать onDestroy и отмену; ненужные Activity references не держать. При перезапуске Activity сохранённые результаты доступны с диска.
- [R5] Формат по-русски, компактно: «О чём разговор», «Главное», «Договорённости / действия», «Неясно». Сохранить отрицания, суммы, даты; не добавлять отсутствующее; «неразборчиво» для сомнений. Не подставлять subtitles ни на вход, ни как fallback. Сохранять UTF-8 Markdown рядом с оригиналом, имя зависит от recording + точный диапазон; метаданные источник, диапазон, модель, время, SHA256 исходника. Atomic write, предыдущий результат не теряется при сбое. Показать/скопировать уже сохранённый конспект без API и без сервиса. Лимит/ошибку сети показать честно.
- [R6] UI перед отправкой объясняет «Выбранное аудио будет отправлено в Gemini. Используется API-ключ из настроек; квота зависит от проекта». Не добавлять обещаний бесплатности, не включать billing, никаких скрытых auto-send. Нет нового сервиса/manifest permissions/микрофона.
- [R7] TDD: сначала реально падающие functional JVM tests, затем реализация. Проверять WAV payload bytes/диапазоны и повреждения; MockWebServer воспроизводит upload -> PROCESSING -> ACTIVE -> generate -> DELETE, проверяет POST JSON по whitelist схемы полей/enum из официального API, actual uploaded RIFF bytes (не строковый поиск кода). Тесты 429/auth/empty/blocked/failed polling/unknown enum/cleanup/cancel/stale response/sidecar persist exact selection. Test deps org.json (JVM android stubs иначе ломаются) и mockwebserver допустимы. Не ослаблять существующие LiveAudioPipelineTest. Kotlin ruff неприменим; явно указать N/A. Никаких surrogate APK/тестов assert in source.
- [R8] versionCode 57, versionName 1.0.53-audio-summary. Документировать ограничение отдельной выбранной WAV части (10min rotation), не утверждать конспект всех частей сессии. Указать телефонные проверки отдельно от JVM.

## 3.1. Контрактные проверки мастера
Исходный WAV record_20261114_091556_1_be8aa23e_0001.wav не менять и не включать в git. Его SHA256 91de6a9d393f18819770d0f546e0ad9fedd4dc6c6686bc41838d43ea45447763, duration 70.2s; диапазон 20–40s содержит 640000 исходных PCM байт. Правильный тест сравнивает реальные сэмплы, не markdown строки. При полном диапазоне последняя дробная секунда не должна теряться. Existing baseline 7 LiveAudioPipelineTest.

## 3.2. План этапа
1. Сверить текущий код и официальную REST схему, написать functional tests, получить Red.
2. Минимальная реализация pure JVM core + UI adapter; Green.
3. Собрать настоящим Gradle в существующем CI. Локальных JDK/SDK ранее не было; не устанавливать многогигабайтную среду. При необходимости доступен gh (проверь). Ранее build.yml на ветках asr-test/** выполняет testDebugUnitTest и assembleDebug. НЕ commit/push/dispatch в рамках этого TASK: мастер выполнит после review диффа. Если локально невозможно запустить — честно отчёт READY FOR CI, не SUCCESS; приложить тесты и обозначить их НЕ ПРОГНАНО, не выдумывай Red/Green логи.
4. Отчёт: Bridge-Run-ID, файлы, решения/источники, фактический сырой вывод выполненных команд, оставшиеся риски. Не заявлять GUI проверенным без устройства. Не надо переслушивать WAV или повторять серверный разбор.

## 4. Верификация
Целевая команда в CI: ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace
Перед отчетом git diff --check; scoped BOM check только новых/изменённых файлов, без массового autofix. Команды с ошибками не скрывать, сначала причина, затем конкретное исправление. Не менять файлы вне whitelist. Не коммитить, не пушить.
