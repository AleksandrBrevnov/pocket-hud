# Отчет о выполнении TASK-007: Green после реального CI Red аудиоконспекта

Bridge-Run-ID: e390149f-fb8b-4502-8115-ab093361e2b1
Статус: READY FOR CI

## 1. Контекст и анализ Red в CI
- Исходный коммит Red: `6498fac94c3c9b6b43c30b461a240c711db8a8f1`
- CI Run: https://github.com/AleksandrBrevnov/pocket-hud/actions/runs/43583379457
- Результат CI: 33 tests completed, 5 failed.
- Исходные логи 5 упавших тестов из `docs/ci/audio-summary-red/`:

```
1) AudioSummaryMasterTest.intervalEndingOneFrameBeforeEndDoesNotIncludeLastFrame
java.lang.AssertionError: expected:<31998> but was:<32000>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:647)
	at org.junit.Assert.assertEquals(Assert.java:633)
	at com.aleksandr.pockethud.AudioSummaryMasterTest.intervalEndingOneFrameBeforeEndDoesNotIncludeLastFrame(AudioSummaryMasterTest.kt:94)

2) AudioSummaryMasterTest.cancellationDuringProcessingStillDeletesRemoteFile
java.lang.AssertionError: Uploaded file must be cleaned up expected:<3> but was:<2>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:120)
	at com.aleksandr.pockethud.AudioSummaryMasterTest.assertFinalDelete(AudioSummaryMasterTest.kt:59)
	at com.aleksandr.pockethud.AudioSummaryMasterTest.cancellationDuringProcessingStillDeletesRemoteFile(AudioSummaryMasterTest.kt:87)

3) AudioSummaryMasterTest.disconnectAfterUploadStillDeletesRemoteFile
java.lang.AssertionError: Uploaded file must be cleaned up expected:<4> but was:<3>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:120)
	at com.aleksandr.pockethud.AudioSummaryMasterTest.assertFinalDelete(AudioSummaryMasterTest.kt:59)
	at com.aleksandr.pockethud.AudioSummaryMasterTest.disconnectAfterUploadStillDeletesRemoteFile(AudioSummaryMasterTest.kt:73)

4) AudioSummaryTest.managerPrecommitRaceSerializedWithCancel
org.junit.ComparisonFailure: expected:<[]SUMMARY B> but was:<[# AI-Конспект по аудио (gemini-3.8-flash)

]SUMMARY B>
	at org.junit.Assert.assertEquals(Assert.java:117)
	at org.junit.Assert.assertEquals(Assert.java:146)
	at com.aleksandr.pockethud.AudioSummaryTest.managerPrecommitRaceSerializedWithCancel(AudioSummaryTest.kt:683)

5) AudioSummaryTest.managerAtomicSaveFailClosedPreservesOldTargetByteForByte
java.lang.AssertionError: Expected exception of type IOException was not thrown
	at org.junit.Assert.fail(Assert.java:89)
	at com.aleksandr.pockethud.AudioSummaryTest.managerAtomicSaveFailClosedPreservesOldTargetByteForByte(AudioSummaryTest.kt:1057)
```

---

## 2. Реализованные исправления (R1-R6)

### [R1] Немедленный захват дескриптора загруженного файла и гарантированный DELETE-cleanup
- **Проблема:** В `AudioSummaryApi.generateSummary` локальная переменная `uploadedFileName` присваивалась только из возвращаемого значения `Pair` функции `executePipeline`. При возникновении `CancellationException` или `IOException` (например, при прерывании соединения MockWebServer `SocketPolicy.DISCONNECT_AFTER_REQUEST` или отмене пользователем во время polling/generateContent) блок `try` прерывался до возврата `Pair`, имя файла терялось, и `performDeleteCleanup` не вызывался.
- **Решение:**
  - В `executePipeline` добавлен параметр обратного вызова `onUploadedFile: (String) -> Unit`.
  - В блоке обработки ответа `uploadCall` после строгой валидации формата имени файла (`FILE_NAME_REGEX`) немедленно вызывается `onUploadedFile(name)`, который устанавливает внешнюю локальную переменную `uploadedFileName` вызова `generateSummary`.
  - Дополнительно гарантирована обработка невалидного JSON на этапе `uploadCall` и `pollCall` с сохранением полученного дескриптора.
  - Очистка `DELETE` в блоке `finally` всегда выполняется отдельным неотменённым сетевым вызовом `client.newCall(deleteReq)`.
  - В `AudioSummaryTest.kt` добавлены новые тесты:
    - `geminiApiDisconnectDuringPollingCleansUpRemoteFile` (IOException на polling -> DELETE).
    - `geminiApiInvalidJsonInGenerateContentCleansUpRemoteFile` (502 HTML / невалидный JSON на генерации -> DELETE).
    - `geminiApiInvalidJsonInPollCleansUpRemoteFile` (невалидный JSON на polling -> DELETE).

### [R2] Сэмпл-аккуратный расчет фреймовых границ без эвристики `1e-4`
- **Проблема:** В `WavAudioClip.extractClip`, `AudioSummaryManager.commitSummary` и `saveSummary` условие `abs(effectiveEnd - info.durationSeconds) < 1e-4` ошибочно трактовало любой интервал, оканчивающийся за 1 сэмпл до конца (например, `15999.0 / 16000.0` при `duration = 1.0`), как конец файла и расширяло выборку до полного файла (32000 байт вместо 31998 байт).
- **Решение:**
  - В `WavAudioClip` создан общий helper `calculateFrameBounds(info: WavInfo, startSeconds: Double, endSeconds: Double): FrameBounds`.
  - Эвристика `1e-4` удалена во всех трёх местах. Расчет выполняется математически строго:
    `startFrame = Math.round(startSeconds * info.sampleRate).toLong().coerceIn(0L, totalFrames)`
    `endFrame = Math.round(effectiveEnd * info.sampleRate).toLong().coerceIn(startFrame + 1L, totalFrames)`
    `actualStartSeconds = startFrame.toDouble() / info.sampleRate`
    `actualEndSeconds = endFrame.toDouble() / info.sampleRate`
  - Метаданные sidecar и возвращаемый объект `SavedAudioSummary` отражают реальные границы отправленных сэмплов (`seconds = frames / sampleRate`).
  - В `AudioSummaryTest.kt` добавлен тест `wavCalculateFrameBoundsExactAndSampleAccurate`.

### [R3] Сохранение тела конспекта без искусственного заголовка (Round-trip)
- **Проблема:** `commitSummary` и `saveSummary` дописывали перед текстом конспекта заголовок `# AI-Конспект по аудио ($model)\n\n`. При последующей загрузке `loadSummary` тело конспекта содержало этот заголовок, в то время как `onSuccess` возвращал чистый текст от модели, что приводило к расхождению и падению `managerPrecommitRaceSerializedWithCancel`.
- **Решение:**
  - Удалена строка `append("# AI-Конспект по аудио ($model)\n\n")` из сохраняемого содержимого sidecar-файла в `commitSummary` и `saveSummary`. Модель и источник сохраняются в frontmatter-комментарии, а UI PocketHUD самостоятельно отображает необходимый заголовок.
  - В `loadSummary` добавлена обратная совместимость: если ранее сохранённый файл содержал `# AI-Конспект по аудио (...)`, он корректно отделяется от тела конспекта.
  - Сохранённый конспект и результат `onSuccess` теперь совпадают байт-в-байт (`round-trip`).

### [R4] Устранение дефекта `managerAtomicSaveFailClosedPreservesOldTargetByteForByte` и внедрение строгого Atomic Move
- **Проблема:** Тест блокировал файл через `FileLock` в расчете на отказ `Files.move`. На Linux `FileLock` носит advisory-характер и не препятствует переименованию файлов, из-за чего исключение `IOException` в CI не выбрасывалось. Кроме того, реализация содержала non-atomic fallback при `AtomicMoveNotSupportedException`, что нарушало гарантию неизменности исходного файла при ошибке.
- **Решение:**
  - В конструктор `AudioSummaryManager` добавлен внедряемый параметр `atomicMover: (Path, Path) -> Unit` со значением по умолчанию `Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`.
  - Удален неатомарный fallback в `commitSummary` и `saveSummary`.
  - Тест `managerAtomicSaveFailClosedPreservesOldTargetByteForByte` переписан:
    1. Обычный менеджер создает исходный целевой файл.
    2. Менеджер с инъекцией `atomicMover`, выбрасывающего `AtomicMoveNotSupportedException` / `IOException`, пытается заменить целевой файл.
    3. Проверяется перехват `IOException`, вызов инъектированной функции, абсолютная неизменность байтов целевого файла (`assertArrayEquals`) и отсутствие временных `.tmp.` файлов.
    4. Повторное сохранение через рабочий атомарный менеджер успешно обновляет тело целевого файла.

### [R5] Устранение реальной сети из `geminiApiProductionRejectsLoopbackUrls`
- **Проблема:** Тест обращался к настоящему `generativelanguage.googleapis.com` с фиктивным ключом без мока, падая из-за внешней сети/авторизации, а не из-за проверки loopback URL.
- **Решение:**
  - В `AudioSummaryApi` через конструктор передан `OkHttpClient` с application interceptor.
  - Interceptor перехватывает стартовый запрос, не вызывает `chain.proceed(request)` (0 сетевых запросов наружу) и возвращает ответ с заголовком `X-Goog-Upload-URL: http://127.0.0.1/upload-session`.
  - `AudioSummaryApi` валидирует URL, отклоняет его с понятным текстом ошибки `Небезопасный URL для загрузки аудио (host: 127.0.0.1)`.
  - Тест проверяет, что был перехвачен ровно один запрос (`requestCount == 1`), а любая попытка второго запроса вызывает `AssertionError`.

### [R6] Сохранение инвариантов и отсутствие регрессий
- Мастер-контракты в `AudioSummaryMasterTest.kt` не модифицировались и не ослаблялись (файл не входит в scope изменений).
- Файл `LiveAudioPipelineTest.kt` не модифицировался.
- Конфигурация сборки, versionCode (57), подписи, CI workflows и UI не изменялись.
- Документация в `docs/audio-summary.md` синхронизирована с новыми гарантиями.

---

## 3. Scope Whitelist Проверка

Измененные файлы (`git status`):
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryApi.kt`
- `app/src/main/java/com/aleksandr/pockethud/AudioSummaryManager.kt`
- `app/src/main/java/com/aleksandr/pockethud/WavAudioClip.kt`
- `app/src/test/java/com/aleksandr/pockethud/AudioSummaryTest.kt`
- `docs/audio-summary.md`
- `docs/tasks/TASK-007-REPORT.md`

Все затронутые файлы строго соответствуют Разделу 2 спецификации TASK-007.

---

## 4. Результаты проверок

### `git diff --check`
```
warning: in the working copy of 'app/src/main/java/com/aleksandr/pockethud/AudioSummaryApi.kt', LF will be replaced by CRLF the next time Git touches it
warning: in the working copy of 'app/src/main/java/com/aleksandr/pockethud/AudioSummaryManager.kt', LF will be replaced by CRLF the next time Git touches it
warning: in the working copy of 'app/src/main/java/com/aleksandr/pockethud/WavAudioClip.kt', LF will be replaced by CRLF the next time Git touches it
warning: in the working copy of 'app/src/test/java/com/aleksandr/pockethud/AudioSummaryTest.kt', LF will be replaced by CRLF the next time Git touches it
warning: in the working copy of 'docs/audio-summary.md', LF will be replaced by CRLF the next time Git touches it
[Exit Code: 0]
```

### Кодировка UTF-8 No-BOM
```
OK: UTF-8 No-BOM in app/src/main/java/com/aleksandr/pockethud/AudioSummaryApi.kt (19847 bytes)
OK: UTF-8 No-BOM in app/src/main/java/com/aleksandr/pockethud/AudioSummaryManager.kt (16601 bytes)
OK: UTF-8 No-BOM in app/src/main/java/com/aleksandr/pockethud/WavAudioClip.kt (14464 bytes)
OK: UTF-8 No-BOM in app/src/test/java/com/aleksandr/pockethud/AudioSummaryTest.kt (49971 bytes)
OK: UTF-8 No-BOM in docs/audio-summary.md (15652 bytes)
OK: UTF-8 No-BOM in docs/tasks/TASK-007-REPORT.md
```

### Линтинг (`ruff`)
Затронутые файлы проекта написаны на Kotlin (`.kt`) и Markdown (`.md`), Python-файлы в рамках TASK-007 не создавались и не изменялись.

---

## 5. Заключение
Все требования спецификации TASK-007 (R1-R6) полностью реализованы.
Статус: READY FOR CI.
Коммит и пуш не выполнялись, код готов для ревью Мастером и запуска в CI.
