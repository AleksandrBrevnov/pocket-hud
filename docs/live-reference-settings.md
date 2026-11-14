# Адаптация эталонных настроек Gemini LiveTranslate из открытых репозиториев GitHub

Документ фиксирует результаты инженерного анализа открытых репозиториев, реализующих Gemini Live Translate, и точные параметры, перенесённые в PocketHUD (v1.0.54-live-reference, versionCode 58).

---

## 1. Изученные публичные источники (Pinned Links)

1. **Google Gemini Cookbook (Официальный референс):**
   - **URL:** [google-gemini/cookbook/.../Get_started_LiveTranslate.py](https://github.com/google-gemini/cookbook/blob/6c416d766328d125774e21f282e59dfdf12049c9/quickstarts/Get_started_LiveTranslate.py)
   - **Архитектура:** Использование выделенной модели `gemini-3.5-live-translate-preview`, модальность `AUDIO`, явная конфигурация перевода `translation_config` (сериализуется в `setup.generationConfig.translationConfig`), пустые схемы для `inputAudioTranscription` и `outputAudioTranscription`.
   - **Аудио:** Сырой PCM16 LE, моно, 16 кГц, размер блока 1600 сэмплов (100 мс). Полное отсутствие программного AGC, гейтов и лимитеров. Отсутствуют `speechConfig`, `realtimeInputConfig`, `systemInstruction`.

2. **ALAD-Mobile (Android Kotlin реализация):**
   - **URL:** [navidseyedain/ALAD-Mobile/.../ALADWebSocketManager.kt](https://github.com/navidseyedain/ALAD-Mobile/blob/e6c22492dd302e394d89ce105a617ae93b362d41/app/src/main/kotlin/com/alad/app/core/network/ALADWebSocketManager.kt)
   - **Особенности:** Та же модель, вложенная структура `translationConfig` внутри `generationConfig`, отсутствие кастомного VAD. Принудительно задан голос Puck. Захват аудио выполнен через системный лупбэк (`AudioPlaybackCaptureConfiguration`), а не через микрофон.

3. **Gemini Live Translator (Web / Agent):**
   - **URL:** [Dani-aw/gemini-live-translator/.../agent.py](https://github.com/Dani-aw/gemini-live-translator/blob/c04f75fa5dffbe767deb9fad4fc5486672f5eead/app/translator_agent/agent.py)
   - **Особенности:** Выделенный режим опускает voice/VAD/SI. В клиенте `app/static/js/app.js` финализация выполняется по таймеру бездействия, так как модель в режиме синхронного перевода не генерирует надёжных маркеров `turnComplete`.

4. **Официальная документация Gemini Live API:**
   - **URL:** [ai.google.dev/gemini-api/docs/live-api/live-translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate)
   - **Спецификация:** Минимальная схема сообщения `setup`.

---

## 2. Принятые настройки и архитектурные решения

### 2.1. Минимальная схема `setup` (LiveTranslateReference.kt)
В соответствии с официальным cookbook и схемой API сформирован строгий JVM-хелпер:
```json
{
  "setup": {
    "model": "models/gemini-3.5-live-translate-preview",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "translationConfig": {
        "targetLanguageCode": "ru",
        "echoTargetLanguage": false
      }
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {}
  }
}
```
- Исключены: `speechConfig`, `voiceConfig`, `realtimeInputConfig`, `systemInstruction`, `sessionConfig`.
- Параметр `echoTargetLanguage` выставлен в `false` для одностороннего перевода (иностранная речь -> русский без обратного эха русской речи).

### 2.2. Захват аудио микрофона и отказ от программного Soft-Knee AGC
- Отправка в WebSocket выполняется в исходном PCM16 Little-Endian формате с частотой 16 кГц и размером блока 1600 сэмплов (100 мс).
- Полностью удалена программная функция `applyDynamicSoftKneeAgc` (+14 дБ boost, noise gate и `tanh` сатурация).
- Массив сэмплов не подвергается программным модификациям.
- Идентичный буфер байтов (`byteBuffer`) передаётся как в локальный аудиоархив (`liveArchive?.offer(byteBuffer)`), так и в сетевую очередь отправки (`audioSendQueue.offer(byteBuffer)`).

### 2.3. Единственный источник переведённого текста (`outputTranscription.text`)
- В качестве единственного официального источника текста перевода используется поле `outputTranscription.text`.
- Исключено считывание текстовых частей из `modelTurn.parts[].text`, чтобы два источника не добавляли один перевод дважды. В реальном replay такие двойные текстовые ответы не наблюдались; это защита от возможного дублирования, а не доказанная причина прежних повторов.
- Декодирование и воспроизведение синтезированного аудио из `modelTurn.parts[].inlineData` сохранено в полном объёме.
- Никакой эвристической дедупликации последовательных фрагментов или повторов слов не производится (сохраняются легитимные повторы речи).

### 2.4. Удаление эвристики Attention Sink Watchdog в тишине
- Удалён механизм сброса сокета по совпадению фраз ASR при амплитуде $\le 1\%$ (`lastAsrPhrase`, `consecutiveAsrRepeatCount`).
- Повторы тихой речи могут быть обычной частью диалога. Порог громкости и совпадение фраз сами по себе не доказывают сбой модели, поэтому они больше не вызывают перезапуск сессии и отбрасывание фразы.

---

## 3. Отвергнутые настройки (Что НЕ копировалось)

1. **`echoTargetLanguage = true`**: PocketHUD работает в режиме персонального синхронного перевода для пользователя, повторение русских реплик создаёт акустический шум.
2. **Браузерные чанки 8 мс / 128 сэмплов**: сохранён существующий размер блока 100 мс (1600 сэмплов), совпадающий с официальным cookbook. Его оптимальность на телефоне не установлена.
3. **Захват системного звука ALAD (`AudioPlaybackCaptureConfiguration`)**: PocketHUD слушает внешний мир через микрофон, а не внутренний медиапоток Android.
4. **Укороченный таймер бездействия (2000 мс)**: сохранён существующий таймер PocketHUD `SIMUL_IDLE_DELAY_MS = 3500L`. Подбор задержки и качество границ карточек на устройстве в этой задаче не проверялись.
5. **Зависимость от `turnComplete`**: модель Gemini Live Translate при непрерывном синхронном переводе не гарантирует своевременной отправки `turnComplete`, поэтому логика сохранения карточек опирается на скользящий таймер бездействия и пунктуацию.

---

## 4. Ограничения и эксплуатационные особенности

> [!WARNING] Неизвестное качество на физических устройствах (Unknown Device Quality)
> Устранение программной обработки меняет громкость передаваемого PCM: прежнее усиление повышало одни блоки, а гейт ослаблял другие. В зависимости от микрофона и акустических условий качество распознавания и перевода может отличаться. Сравнение на телефоне ещё не выполнено; улучшение точности не доказано.

> [!NOTE] Аппаратные эффекты Android (Hardware DSP)
> Аппаратное шумоподавление (`NoiseSuppressor`) и аппаратный AGC (`AutomaticGainControl`) Android API остаются включёнными при поддержке драйвером устройства. Маршрутизация микрофонов (CAMCORDER, VOICE_RECOGNITION, Bluetooth SCO) не изменялась.
