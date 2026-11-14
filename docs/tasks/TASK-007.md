# TASK-007: Green после реального CI Red аудиоконспекта

## Метаданные
- Статус: Ready for Worker
- Файл отчета воркера: `docs/tasks/TASK-007-REPORT.md`

## 1. Контекст
Продолжение TASK-005/006. Пользователь разрешил PocketHUD и тестовый APK; Antigravity исполнитель, Codex мастер. Правила ../CODEX_WORKFLOW.md применяются. НЕ тратить время на повторное исследование окружения/API. Код приложения уже успешно компилируется в CI. Мастер поправил Kotlin test dispatcher setter. Теперь реальный Red: https://github.com/AleksandrBrevnov/pocket-hud/actions/runs/43583379457 commit6498fac94c3c9b6b43c30b461a240c711db8a8f1. 33 tests completed, 5 failed. XML сохранены в docs/ci/audio-summary-red/; читать можно, менять нельзя.

## 2. Область изменений (Scope Whitelist)
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryApi.kt`
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryManager.kt`
- `app/src/main/java/com/aleksandr/pockethud/WavAudioClip.kt`
- `app/src/test/java/com/aleksandr/pockethud/AudioSummaryTest.kt`
- `docs/audio-summary.md`
- `docs/tasks/TASK-007-REPORT.md`

## 3. Требования и точные причины
- [R1] Master AudioSummaryMasterTest.cancel... FAILED expected requestCount3 actual2; disconnect... expected4 actual3. uploadedFileName в generateSummary присваивается ТОЛЬКО после возврата Pair из executePipeline. При CancellationException / IOException после upload Pair не возвращён, имя потеряно, DELETE не вызывается. Решение: передать во внутренний executePipeline onUploadedFile:(String)->Unit, немедленно вызвать после успешной валидации name, установить outer local uploadedFileName. Либо другой столь же простой per-call holder. Не хранить имя как shared поле API. Cleanup всегда с фактически полученным именем и отдельным Call, даже при исключении на poll/gen. Не отменять cleanup текущим cancellation token. Покрыть также IOException после poll и разобранный/невалидный JSON без потери handle.
- [R2] Master intervalEndingOneFrame... expected31998 actual32000. Во всех трёх местах (Wav.extractClip, Manager.commitSummary, Manager.saveSummary) условие abs(end-duration)<1e-4 расширяет любой интервал в 1сэмпл от конца до полного файла. Убрать эвристику, брать Math.round(effectiveEnd*sampleRate) с корректными bounds. Общий helper расчёта start/end frames предпочтительнее дублирования; весь диапазон передаёт точную duration, Math.round сохранит все frames без epsilon. Метаданные должны отражать реальные отправленные frame bounds; seconds=frames/sampleRate, не исходные округления.
- [R3] Worker managerPrecommitRace... expected "SUMMARY B" but was "# AI-Конспект по аудио (...)\n\nSUMMARY B". API body должен round-trip через store. Убрать дополнительный heading из сохраняемого body (модель/источник уже metadata, UI рисует title), либо корректно отделить его при load. Не ослаблять assertEquals в этом тесте. Сохранённый текст и onSuccess должны совпадать.
- [R4] AtomicSave test НЕ проверяет отказ move на Linux: Java FileLock advisory и rename не блокирует. Это дефект теста + нарушение реализации: fallback Files.move без ATOMIC_MOVE не гарантирует старый target при ошибке. Добавь маленький injectable publisher/move function параметром manager constructor, production default ТОЛЬКО Files.move(...ATOMIC_MOVE,REPLACE_EXISTING), никаких non-atomic fallback, target.delete/writeBytes. Переделай test: normal manager создаёт initial target; failing manager с publisher, бросающим AtomicMoveNotSupportedException/IOException, пытается заменить тот же target; original bytes неизменны, tmp очищен. Проверяй именно вызванную логику, не наличие строк. Повторное сохранение при рабочем atomic move должно заменять body.
- [R5] Убрать реальную сеть из geminiApiProductionRejectsLoopbackUrls: сейчас вызов настоящего Google с fake key, который падает независимо от проверки URL; это суррогатный тест. Inject OkHttp application interceptor, возвращающий upload start response с X-Goog-Upload-URL=http://127.0.0.1/...; сам api baseUrl остаётся production, interceptor не вызывает proceed. Убедиться, что Error именно про небезопасный URL и всего один intercepted request. Любой попытанный второй запрос — провал. Не использовать реальные ключи, внешнюю сеть и мокинг только вывода текста.
- [R6] Сохранить все имеющиеся meaningful проверки, добавлять тесты для исправленных дефектов. Master test файл `app/src/test/java/com/aleksandr/pockethud/AudioSummaryMasterTest.kt` строго READ ONLY — не менять, не отключать, не ослаблять, в scope не входит. LiveAudioPipelineTest тоже не менять. Не менять Gradle/версию57/подпись/CI/UI. Commit/push запрещены воркеру, мастер запускает CI после review.

## 4. Проверка
Red уже получен реальным Gradle и JVM в GitHub Actions. Не повторять попытки Java локально: она отсутствует; ruff Kotlin N/A. git diff --check, scoped UTF8 без BOM. В отчёт новый Bridge-Run-ID, точные исправления R1-R6, статус READY FOR CI. Не писать «все тесты прошли» пока новый CI не прогнан. Никаких длинных пересказов общих правил: исправь указанные точки и закончи.
