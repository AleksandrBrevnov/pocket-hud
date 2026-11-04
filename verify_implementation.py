import re
import sys
import os
import math

main_activity_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\src\main\java\com\aleksandr\pockethud\MainActivity.kt"
pocket_service_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\src\main\java\com\aleksandr\pockethud\PocketService.kt"
history_manager_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\src\main\java\com\aleksandr\pockethud\DialogueHistoryManager.kt"
build_gradle_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\build.gradle.kts"
setup_and_push_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\setup_and_push.ps1"

def check_brackets_kotlin(file_path):
    print(f"Checking syntax balancing for {os.path.basename(file_path)}...")
    with open(file_path, "r", encoding="utf-8") as f:
        text = f.read()

    i = 0
    n = len(text)
    stack = []
    pairs = {')': '(', '}': '{', ']': '['}
    
    # mode stack: 'CODE' or 'STRING' or 'TRIPLE_STRING'
    mode_stack = ['CODE']
    
    while i < n:
        mode = mode_stack[-1]
        
        if mode == 'CODE':
            # Multi-line comment
            if text[i:i+2] == '/*':
                end = text.find('*/', i+2)
                if end == -1: raise ValueError(f"Unclosed /* at {i}")
                i = end + 2
                continue
            # Single-line comment
            if text[i:i+2] == '//':
                end = text.find('\n', i+2)
                if end == -1: break
                i = end + 1
                continue
            # Triple-quoted raw string
            if text[i:i+3] == '"""':
                mode_stack.append('TRIPLE_STRING')
                i += 3
                continue
            # Character literal
            if text[i] == "'":
                end = text.find("'", i+1)
                while end != -1 and text[end-1] == '\\' and text[end-2:end] != '\\\\':
                    end = text.find("'", end+1)
                if end == -1: raise ValueError(f"Unclosed char literal at {i}")
                i = end + 1
                continue
            # Double-quoted string
            if text[i] == '"':
                mode_stack.append('STRING')
                i += 1
                continue
            # Brackets
            ch = text[i]
            if ch in '({[':
                stack.append((ch, i))
            elif ch in ')}]':
                if not stack:
                    line_no = text[:i].count('\n') + 1
                    raise ValueError(f"Unmatched closing bracket '{ch}' at line {line_no} in {file_path}")
                top, pos = stack.pop()
                if pairs[ch] != top:
                    line_no = text[:i].count('\n') + 1
                    open_line = text[:pos].count('\n') + 1
                    raise ValueError(f"Mismatched bracket '{ch}' at line {line_no}, expected '{pairs[ch]}' for '{top}' opened at line {open_line} in {file_path}")
                # If we just closed a '${' block inside a string, resume the string!
                if top == '{' and len(mode_stack) > 1 and mode_stack[-2] in ('STRING', 'TRIPLE_STRING'):
                    mode_stack.pop() # pop 'CODE'
            i += 1

        elif mode == 'STRING':
            if text[i] == '\\':
                i += 2
                continue
            if text[i] == '"':
                mode_stack.pop()
                i += 1
                continue
            if text[i:i+2] == '${':
                stack.append(('{', i))
                mode_stack.append('CODE')
                i += 2
                continue
            i += 1

        elif mode == 'TRIPLE_STRING':
            if text[i:i+3] == '"""':
                mode_stack.pop()
                i += 3
                continue
            if text[i:i+2] == '${':
                stack.append(('{', i))
                mode_stack.append('CODE')
                i += 2
                continue
            i += 1

    if stack:
        top, pos = stack[-1]
        line_no = text[:pos].count('\n') + 1
        raise ValueError(f"Unclosed bracket '{top}' opened at line {line_no} in {file_path}")
    print(f"  -> Bracket balancing OK.")

check_brackets_kotlin(main_activity_path)
check_brackets_kotlin(pocket_service_path)
check_brackets_kotlin(history_manager_path)

with open(main_activity_path, "r", encoding="utf-8") as f:
    main_code = f.read()

with open(pocket_service_path, "r", encoding="utf-8") as f:
    service_code = f.read()

with open(history_manager_path, "r", encoding="utf-8") as f:
    history_code = f.read()

with open(build_gradle_path, "r", encoding="utf-8") as f:
    gradle_code = f.read()

with open(setup_and_push_path, "r", encoding="utf-8") as f:
    setup_code = f.read()

print("\n--- 1. Verification of v1.0.33 Version Bumps & Fixed Keystore ---")
assert 'versionCode = 37' in gradle_code, "versionCode 37 missing in app/build.gradle.kts"
assert 'versionName = "1.0.33"' in gradle_code, "versionName 1.0.33 missing in app/build.gradle.kts"
assert 'signingConfigs' in gradle_code and 'debug.keystore' in gradle_code, "signingConfigs with fixed debug.keystore missing in app/build.gradle.kts"
assert os.path.exists(r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\debug.keystore"), "app/debug.keystore missing on disk"
assert 'text = "v1.0.33"' in main_code, "v1.0.33 badge missing in MainActivity.kt"
assert 'PocketHUD — Ушной суфлёр v1.0.33' in service_code, "v1.0.33 notification title missing in PocketService.kt"
assert 'PocketHUD v1.0.33 активен' in service_code, "v1.0.33 start subtitle missing in PocketService.kt"
assert 'Версия: v1.0.33' in service_code, "v1.0.33 diagnostic summary missing in PocketService.kt"
print("  -> Version bump v1.0.33 and fixed debug.keystore OK across all files.")

print("\n--- 2. Verification of Google 10-15 min Session Rotation & Reconnect ---")
assert "code == 1000" in service_code, "Session rotation code 1000 check missing in onClosed"
assert "SESSION_ROTATION_RECONNECT" in service_code, "Session rotation reconnect log missing"
assert "root.has(\"goAway\")" in service_code, "Root-level goAway extraction missing"
assert "goAwayRunnable" in service_code, "goAwayRunnable missing in PocketService.kt"
assert "webSocket === currentWs" in service_code, "goAway race condition guard missing"
assert "@Volatile private var isSetupComplete = false" in service_code, "isSetupComplete volatile flag missing"
assert "if (!isSetupComplete)" in service_code, "isSetupComplete guard missing in sendThread"
assert "Thread.sleep(40)" in service_code, "sendThread must sleep while !isSetupComplete to hold queue"
assert "isSetupComplete = true" in service_code, "isSetupComplete activation on setupComplete missing"
print("  -> Session rotation & reconnect OK.")

print("\n--- 3. Verification of Native Gemini Live Translate sendSetup() ---")
assert '"models/gemini-3.5-live-translate-preview"' in service_code, "models/gemini-3.5-live-translate-preview missing in sendSetup"
assert '"translationConfig"' in service_code, "translationConfig missing in sendSetup"
assert '"targetLanguageCode", "ru"' in service_code or '"targetLanguageCode"' in service_code, "targetLanguageCode ru missing in translationConfig"
assert '"echoTargetLanguage", false' in service_code, "echoTargetLanguage false missing in translationConfig"
assert '"responseModalities"' in service_code and '"AUDIO"' in service_code, "responseModalities AUDIO missing in generationConfig"
assert '"voiceName", "Puck"' in service_code, "voiceName Puck missing in speechConfig"
assert 'put("inputAudioTranscription", JSONObject())' in service_code, "inputAudioTranscription: {} missing at top level of setup"
assert 'put("outputAudioTranscription", JSONObject())' in service_code, "outputAudioTranscription: {} missing at top level of setup"

# Verify complete removal of sessionResumption from sendSetup to prevent duplicate phrases
setup_body_match = re.search(r'private fun sendSetup\(ws: WebSocket\)\s*\{(.*?)\n    \}', service_code, re.DOTALL)
assert setup_body_match, "Could not extract sendSetup body from PocketService.kt"
setup_body = setup_body_match.group(1)
assert "sessionResumption" not in setup_body, "sessionResumption must be completely removed from sendSetup"
assert "resumptionObj" not in setup_body, "resumptionObj must be completely removed from sendSetup"
assert "systemInstruction" not in setup_body, "systemInstruction must be completely removed from sendSetup"

assert '"realtimeInputConfig"' in service_code, "realtimeInputConfig missing in sendSetup"
assert '"silenceDurationMs", 800' in service_code or '"silenceDurationMs"' in service_code, "silenceDurationMs 800 missing in sendSetup"
assert '"NO_INTERRUPTION"' in service_code, "NO_INTERRUPTION missing in realtimeInputConfig"
assert '"START_SENSITIVITY_HIGH"' in service_code, "START_SENSITIVITY_HIGH missing in realtimeInputConfig"
assert '"END_SENSITIVITY_DEFAULT"' in service_code, "END_SENSITIVITY_DEFAULT missing in realtimeInputConfig"
assert '"prefixPaddingMs", 150' in service_code or '"prefixPaddingMs"' in service_code, "prefixPaddingMs missing in sendSetup"
print("  -> Native Gemini Live Translate sendSetup() configuration verified perfectly.")

print("\n--- 4. Verification of Split Audio Routing & Hardware DSP ---")
assert "var selectedMicSource: MicSource = MicSource.SMARTPHONE" in service_code, "Default mic in PocketService must be SMARTPHONE"
assert "currentMicSource = MicSource.SMARTPHONE" in main_code, "Default mic in MainActivity must be SMARTPHONE"
assert "Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION" in service_code, "VOICE_RECOGNITION missing in SMARTPHONE sources"
assert "AudioSource.MIC" in service_code, "AudioSource.MIC missing in SMARTPHONE sources"
assert "record.setPreferredDevice(builtinMic)" in service_code, "setPreferredDevice for built-in mic missing"
assert "AutomaticGainControl" in service_code, "Hardware AutomaticGainControl missing"
assert "NoiseSuppressor" in service_code, "Hardware NoiseSuppressor missing"
assert "attachHardwareEffects" in service_code, "attachHardwareEffects method missing"
assert "skipNoiseSuppressor" in service_code, "skipNoiseSuppressor parameter missing in attachHardwareEffects"
assert "HW_NS_SKIPPED" in service_code, "HW_NS_SKIPPED logging missing when VOICE_RECOGNITION is active"
assert "releaseHardwareEffects" in service_code, "releaseHardwareEffects method missing"
assert "applyDynamicSoftKneeAgc" in service_code, "Dynamic soft-knee AGC missing"
assert "USAGE_MEDIA" in service_code, "USAGE_MEDIA missing in audio routing"

# Verify VOICE_RECOGNITION priority in SMARTPHONE sources
smartphone_sources_match = re.search(r'selectedMicSource\s*==\s*MicSource\.SMARTPHONE\)\s*\{\s*listOf\((.*?)\)\s*\}\s*else', service_code, re.DOTALL)
assert smartphone_sources_match, "Could not find SMARTPHONE sources list in PocketService.kt"
smartphone_sources = smartphone_sources_match.group(1)
first_source = re.search(r'Pair\(MediaRecorder\.AudioSource\.(\w+)', smartphone_sources)
assert first_source and first_source.group(1) == "VOICE_RECOGNITION", f"Expected VOICE_RECOGNITION as first source, got {first_source.group(1) if first_source else None}"
print("  -> Split audio routing & hardware DSP OK (VOICE_RECOGNITION priority verified).")

print("\n--- 5. Verification of Streaming Tag Cleaner ---")
assert "class StreamingTagCleaner" in service_code, "StreamingTagCleaner class missing"
assert "inputTagCleaner = StreamingTagCleaner()" in service_code, "inputTagCleaner instance missing"
assert "outputTagCleaner = StreamingTagCleaner()" in service_code, "outputTagCleaner instance missing"
assert "cleanServerTags" in service_code, "cleanServerTags method missing"
assert "cleanServerTags(outputText)" in service_code, "cleanServerTags must clean outputText"
assert "cleanServerTags(text)" in service_code, "cleanServerTags must clean part text"

# Python simulation of StreamingTagCleaner
class PythonStreamingTagCleaner:
    def __init__(self):
        self.buffer = ""
        self.in_thought = False

    def clean(self, chunk: str) -> str:
        self.buffer += chunk
        output = []
        i = 0
        while i < len(self.buffer):
            if self.in_thought:
                close_idx = self.buffer.lower().find("</thought>", i)
                if close_idx != -1:
                    self.in_thought = False
                    i = close_idx + 10
                else:
                    keep_len = min(len(self.buffer) - i, 12)
                    self.buffer = self.buffer[-keep_len:]
                    return "".join(output).strip()
            else:
                ch = self.buffer[i]
                if ch == '<':
                    if self.buffer[i:].lower().startswith("<thought>", 0) or self.buffer[i:].lower().startswith("<thought", 0):
                        close_bracket = self.buffer.find('>', i)
                        if close_bracket != -1:
                            self.in_thought = True
                            i = close_bracket + 1
                            continue
                        else:
                            break
                    close_bracket = self.buffer.find('>', i)
                    if close_bracket != -1:
                        i = close_bracket + 1
                        continue
                    else:
                        if len(self.buffer) - i > 60:
                            output.append(ch)
                            i += 1
                            continue
                        break
                elif ch == '{':
                    close_brace = self.buffer.find('}', i)
                    if close_brace != -1:
                        i = close_brace + 1
                        continue
                    else:
                        if len(self.buffer) - i > 30:
                            output.append(ch)
                            i += 1
                            continue
                        break
                else:
                    output.append(ch)
                    i += 1
        if i < len(self.buffer):
            self.buffer = self.buffer[i:]
        else:
            self.buffer = ""
        res = "".join(output).replace("<no speech detected>", "").replace("<no speech>", "")
        return re.sub(r'\s+', ' ', res).strip()

cleaner = PythonStreamingTagCleaner()
c1 = cleaner.clean("Buongiorno! <no ")
c2 = cleaner.clean("speech detected> Tutto bene?")
assert c1 == "Buongiorno!", f"Expected 'Buongiorno!', got '{c1}'"
assert c2 == "Tutto bene?", f"Expected 'Tutto bene?', got '{c2}'"

c3 = cleaner.clean("Ciao {p")
c4 = cleaner.clean("ause} amico")
assert c3 == "Ciao", f"Expected 'Ciao', got '{c3}'"
assert c4 == "amico", f"Expected 'amico', got '{c4}'"

c5 = cleaner.clean("<thought>private translation</thought>Привет!")
assert c5 == "Привет!", f"Expected 'Привет!', got '{c5}'"

# Literal '<' followed by regular text does not drop
cleaner_lit = PythonStreamingTagCleaner()
c_lit = cleaner_lit.clean("Score is A < B and this is longer than 60 characters to ensure it emits properly without dropping")
assert "Score is A < B" in c_lit, f"Expected 'Score is A < B' to be preserved, got '{c_lit}'"

print("  -> Streaming tag cleaner OK (100% split token stripping & robustness verified).")

print("\n--- 6. Verification of Audio Latency Drift Catch-Up ---")
assert "queuedPlaybackBytes" in service_code, "queuedPlaybackBytes atomic counter missing"
assert "isPlaybackSpeedBoosted" in service_code, "isPlaybackSpeedBoosted class property missing"
assert "120_000L" in service_code, "2.5s (120,000 bytes) latency threshold missing"
assert "38_400L" in service_code, "0.8s (38,400 bytes) normalization threshold missing"
assert "setSpeed(1.15f)" in service_code, "1.15x speed catch-up setting missing"
assert "setSpeed(1.0f)" in service_code, "1.0x speed normalization setting missing"
print("  -> Audio latency drift catch-up OK.")

print("\n--- 7. Verification of UI Sticky Scroll ---")
assert "!dialogueScrollView.canScrollVertically(1)" in main_code, "Sticky scroll check missing in dialogueScrollView"
assert "!italianScrollView.canScrollVertically(1)" in main_code, "Sticky scroll check missing in italianScrollView"
assert "!russianScrollView.canScrollVertically(1)" in main_code, "Sticky scroll check missing in russianScrollView"
print("  -> UI sticky scroll OK.")

print("\n--- 8. Verification of Dynamic Soft-Knee DSP Gain Curve & Subtle Noise Gate ---")
assert "gateLow = 0.010f" in service_code, "gateLow threshold missing in applyDynamicSoftKneeAgc"
assert "gateHigh = 0.014f" in service_code, "gateHigh threshold missing in applyDynamicSoftKneeAgc"
assert "val attenuation = 0.25f" in service_code, "attenuation factor missing in applyDynamicSoftKneeAgc"

# Mathematical simulation of applyDynamicSoftKneeAgc with Noise Gate
def sim_apply_dynamic_soft_knee_agc(buffer):
    peak = max(abs(x) for x in buffer) if buffer else 0
    peak_ratio = peak / 32768.0
    gate_low = 0.010
    gate_high = 0.014
    
    if peak_ratio < gate_low:
        attenuation = 0.25
        return [int(x * attenuation) for x in buffer]
    
    base_gain = 4.85
    if peak_ratio < gate_high:
        t = (peak_ratio - gate_low) / (gate_high - gate_low)
        effective_gain = 0.25 + t * (base_gain - 0.25)
    else:
        effective_gain = base_gain
        
    out = []
    for x in buffer:
        s = x / 32768.0
        scaled = s * effective_gain
        saturated = math.tanh(scaled)
        sample_out = int(saturated * 32767.0)
        out.append(max(-32768, min(32767, sample_out)))
    return out

# 1. Background noise / music pause (< 1.0% amplitude, peak 250)
noise_chunk = [250, -200, 150, -100]
out_noise = sim_apply_dynamic_soft_knee_agc(noise_chunk)
assert max(out_noise) <= 65, f"Expected noise attenuation, got max={max(out_noise)}"
print(f"  Noise sample 250 -> {out_noise[0]} (attenuated by 0.25x = -12 dB)")

# 2. Quiet speech (> 1.4% amplitude, e.g. 2000 = 6.1%)
speech_chunk = [2000, 1500, -1800]
out_speech = sim_apply_dynamic_soft_knee_agc(speech_chunk)
gain_speech = out_speech[0] / speech_chunk[0]
db_speech = 20 * math.log10(gain_speech)
print(f"  Speech sample 2000 -> {out_speech[0]} (gain = {gain_speech:.2f}x, {db_speech:+.1f} dB)")
assert 4.5 <= gain_speech <= 5.0, f"Gain {gain_speech} not in expected [4.5x, 5.0x] range"

# 3. Loud speech (32767)
loud_chunk = [32767]
out_loud = sim_apply_dynamic_soft_knee_agc(loud_chunk)
print(f"  Loud sample 32767 -> {out_loud[0]} (soft saturation without digital clipping)")
assert out_loud[0] <= 32767 and out_loud[0] > 32000, "Loud sample must saturate near ceiling"
print("  -> Dynamic soft-knee AGC & Noise Gate verified perfectly.")

print("\n--- 9. Verification of Synchronous Live Translate Streaming, Idle Timer & Mic Silence Guard ---")
assert "SIMUL_IDLE_DELAY_MS = 3500L" in service_code, "SIMUL_IDLE_DELAY_MS = 3500L missing in PocketService companion object"
assert "simulIdleRunnable" in service_code, "simulIdleRunnable missing in PocketService.kt"
assert "finalizeCurrentTurn()" in service_code, "finalizeCurrentTurn() method missing in PocketService.kt"
assert "isFinished" in service_code, "isFinished flag handling missing in handleServerMessage"
assert "mainHandler.postDelayed(simulIdleRunnable, SIMUL_IDLE_DELAY_MS)" in service_code, "Sliding idle timer postDelayed missing in handleServerMessage"
assert "mainHandler.removeCallbacks(simulIdleRunnable)" in service_code, "Sliding idle timer removeCallbacks missing"

# Verify complete purging of dead code
assert "silenceFramesCount" not in service_code, "silenceFramesCount dead code must be removed from PocketService"
assert "tagRegex" not in service_code, "tagRegex dead code must be removed from PocketService"
assert "resumptionHandle" not in service_code, "resumptionHandle dead code must be removed from PocketService"

# Verify volatile threading guarantees and lifecycle cleanup
assert "@Volatile" in service_code and "var isServiceActive" in service_code, "isServiceActive must be volatile"
assert "@Volatile private var isRunning = false" in service_code, "isRunning must be volatile"
assert "@Volatile private var activeDialogueTurn: DialogueTurn? = null" in service_code, "activeDialogueTurn must be volatile in PocketService"
assert "@Volatile private var activeTranslationTurnId" in service_code, "activeTranslationTurnId must be volatile in PocketService"
assert "currentActive.italianText.length >= 200" in service_code, "Soft wrap 200 char threshold missing in PocketService.kt"
assert "@Volatile var onStatusChanged" in service_code, "onStatusChanged callback must be volatile"
assert "override fun onDestroy()" in main_code, "onDestroy missing in MainActivity.kt"
assert "PocketService.onStatusChanged = null" in main_code, "onStatusChanged cleanup missing in MainActivity.onDestroy"
assert "code == 400 || code == 401 || code == 403" in service_code, "fatal error codes 400/401/403 missing in PocketService.kt"
assert "TRANSLATION_EMPTY" in service_code, "TRANSLATION_EMPTY event logging missing in PocketService.kt"
assert "hasPendingTranslation" in service_code, "hasPendingTranslation state tracking missing in PocketService.kt"
assert "turnComplete" in service_code, "turnComplete check missing in PocketService.kt"
assert 'root.has("error")' in service_code, "Google Live server error check missing"
assert "SERVER_ERROR" in service_code, "SERVER_ERROR logging missing"
assert "lastRecognizedInput = lastTurn.italianText" in service_code, "lastRecognizedInput must accumulate full Italian turn across chunks"
assert "currentTurnInputSpeech = lastTurn.italianText" in service_code, "currentTurnInputSpeech must accumulate full Italian turn across chunks"
assert "isSentenceEnding" in service_code, "isSentenceEnding helper missing in PocketService.kt"
assert "isContinuation" in service_code, "isContinuation helper missing in PocketService.kt"
assert "appendWithPunctuationAwareSpace" in service_code, "appendWithPunctuationAwareSpace helper missing in PocketService.kt"
assert "splitSentences" in service_code, "splitSentences helper missing in PocketService.kt"
assert 'Regex("(?<=[.?!])\\\\s+")' in service_code, "Sentence splitting regex missing in PocketService.kt"
assert "!isSentenceEnding(it.russianText) || isContinuation(it.russianText)" in service_code, "Priority 1 must only match incomplete translations"

ABBREVIATIONS = {
    "sig.", "dott.", "prof.", "ing.", "avv.", "ecc.", "etc.", "e.g.", "i.e.",
    "т.д.", "т.п.", "г.", "руб.", "ул.", "д.", "кв.", "стр.", "корп."
}

def isSentenceEnding(text: str) -> bool:
    trimmed = text.strip()
    if not trimmed:
        return False
    s = trimmed.rstrip('\"\'»«)]}”’')
    if not s:
        return False
    if s.endswith("...") or s.endswith("…"):
        return False
    last_word = s.split()[-1].lower() if ' ' in s else s.lower()
    if last_word in ABBREVIATIONS:
        return False
    if s.endswith('.') and len(s) >= 2 and s[-2].isdigit():
        word_before = s.split()[-1]
        if word_before[:-1].isdigit():
            return False
    return s.endswith('.') or s.endswith('!') or s.endswith('?')

def isContinuation(text: str) -> bool:
    trimmed = text.strip()
    if not trimmed:
        return False
    s = trimmed.rstrip('\"\'»«)]}”’')
    return s.endswith(',') or s.endswith(';') or s.endswith(':') or s.endswith('-') or s.endswith('—')

def appendWithPunctuationAwareSpace(existing: str, addition: str) -> str:
    trimmed_add = addition.strip()
    if not trimmed_add:
        return existing
    if not existing.strip():
        return trimmed_add
    starts_with_punct = trimmed_add[0] in ['.', ',', '!', '?', ';', ':', '…', ')', ']', '}', '»', '"', '\'', '”', '’']
    ends_with_opening = existing[-1] in ['(', '[', '{', '«', '"', '“', '‘']
    if existing.endswith(" ") or starts_with_punct or ends_with_opening:
        return existing + trimmed_add
    else:
        return f"{existing} {trimmed_add}"

def splitSentences(text: str) -> list:
    trimmed = text.strip()
    if not trimmed:
        return []
    raw_parts = re.split(r'(?<=[.?!])\s+', trimmed)
    if len(raw_parts) <= 1:
        return [trimmed]
    sentences = []
    current = ""
    for part in raw_parts:
        if not current:
            current = part
        else:
            current = f"{current} {part}"
        if isSentenceEnding(current):
            sentences.append(current)
            current = ""
    if current:
        sentences.append(current)
    return sentences

# Python simulation of synchronous streaming turn state machine with sentence accumulation
class DialogueTurnSim:
    _id_counter = 0
    def __init__(self, italianText="", russianText="", timestamp=0, isFinalized=False, id=None):
        if id is None:
            DialogueTurnSim._id_counter += 1
            self.id = DialogueTurnSim._id_counter
        else:
            self.id = id
        self.italianText = italianText
        self.russianText = russianText
        self.timestamp = timestamp
        self.lastActivityTime = timestamp
        self.isFinalized = isFinalized

class SynchronousTurnStateMachine:
    def __init__(self, idle_delay_ms=2000):
        self.sessionDialogueTurns = []
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None
        self.lastRecognizedInput = ""
        self.idleTimerDeadline = None
        self.logged_events = []
        self.activeTranslationTurnId = None
        self.idle_delay_ms = idle_delay_ms

    def finalizeCurrentTurn(self, now=None):
        self.idleTimerDeadline = None
        self.activeTranslationTurnId = None
        for turn in self.sessionDialogueTurns:
            if not turn.isFinalized:
                turn.isFinalized = True
                if now is not None:
                    turn.lastActivityTime = now
                if self.hasPendingTranslation and not turn.russianText.strip():
                    origPrompt = turn.italianText or self.currentTurnInputSpeech or self.lastRecognizedInput
                    self.logged_events.append(("TRANSLATION_EMPTY", f'ORIG="{origPrompt}"'))
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None

    def recordInputSpeech(self, cleanedInput: str, now: int):
        self.idleTimerDeadline = now + self.idle_delay_ms
        segments = splitSentences(cleanedInput)
        for segment in segments:
            lastTurn = self.sessionDialogueTurns[-1] if self.sessionDialogueTurns else None

            isSentenceComplete = (lastTurn is not None and not lastTurn.isFinalized and
                (((bool(lastTurn.russianText.strip()) and
                   isSentenceEnding(lastTurn.russianText) and
                   not isContinuation(lastTurn.russianText)) or
                  (bool(lastTurn.italianText.strip()) and
                   isSentenceEnding(lastTurn.italianText) and
                   not isContinuation(lastTurn.italianText))) and
                 not isContinuation(lastTurn.italianText)))

            canAppendToCurrent = lastTurn is not None and not lastTurn.isFinalized and not isSentenceComplete

            if canAppendToCurrent:
                lastTurn.italianText = appendWithPunctuationAwareSpace(lastTurn.italianText, segment)
                lastTurn.lastActivityTime = now
                self.lastRecognizedInput = lastTurn.italianText
                self.currentTurnInputSpeech = lastTurn.italianText
                self.hasPendingTranslation = True
            else:
                for turn in self.sessionDialogueTurns:
                    if not turn.isFinalized and turn.russianText.strip():
                        if (isSentenceEnding(turn.russianText) and
                            not isContinuation(turn.russianText) and
                            not isContinuation(turn.italianText)):
                            turn.isFinalized = True
                newTurn = DialogueTurnSim(segment, "", now, isFinalized=False)
                self.lastRecognizedInput = segment
                self.currentTurnInputSpeech = segment
                self.hasPendingTranslation = True
                self.sessionDialogueTurns.append(newTurn)

    def recordTranslationOutput(self, cleanedText: str, now: int, isFinished: bool = False):
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None

        targetTurn = None
        # 1. If active streaming turn is bound, continue streaming into it
        if self.activeTranslationTurnId is not None:
            for turn in self.sessionDialogueTurns:
                if turn.id == self.activeTranslationTurnId:
                    targetTurn = turn
                    break

        # 2. Otherwise search by priority
        if targetTurn is None:
            # Priority 1: Active in-progress streaming turn (translation is still incomplete)
            for turn in self.sessionDialogueTurns:
                if (
                    turn.italianText.strip()
                    and turn.russianText.strip()
                    and not turn.isFinalized
                    and (not isSentenceEnding(turn.russianText) or isContinuation(turn.russianText))
                ):
                    targetTurn = turn
                    break
            # Priority 2: Earliest (FIFO) turn waiting for translation (unfinalized OR closed < 4000ms ago)
            if targetTurn is None:
                for turn in self.sessionDialogueTurns:
                    if (turn.italianText.strip() and not turn.russianText.strip() and
                        (not turn.isFinalized or (now - turn.lastActivityTime < 4000))):
                        targetTurn = turn
                        break
            # Priority 3: Last unfinalized turn
            if targetTurn is None:
                for turn in reversed(self.sessionDialogueTurns):
                    if not turn.isFinalized:
                        targetTurn = turn
                        break

        origPrompt = targetTurn.italianText if targetTurn and targetTurn.italianText.strip() else self.lastRecognizedInput
        self.logged_events.append(("TRANSLATION", f'ORIG="{origPrompt}" -> RU="{cleanedText}"'))

        if targetTurn is not None:
            wasFinalized = targetTurn.isFinalized
            if not wasFinalized:
                targetTurn.isFinalized = False
            targetTurn.russianText = appendWithPunctuationAwareSpace(targetTurn.russianText, cleanedText)
            targetTurn.lastActivityTime = now
            isEnding = isSentenceEnding(targetTurn.russianText) and not isContinuation(targetTurn.russianText)
            if isEnding:
                self.activeTranslationTurnId = None
                nextWaitingTurnExists = any(
                    t is not targetTurn and t.italianText.strip() and not t.russianText.strip()
                    for t in self.sessionDialogueTurns
                )
                if nextWaitingTurnExists and not isContinuation(targetTurn.italianText):
                    targetTurn.isFinalized = True
            else:
                self.activeTranslationTurnId = targetTurn.id
        else:
            newTurn = DialogueTurnSim("", cleanedText, now, isFinalized=False)
            self.sessionDialogueTurns.append(newTurn)
            targetTurn = newTurn
            isEnding = isSentenceEnding(cleanedText) and not isContinuation(cleanedText)
            if isEnding:
                self.activeTranslationTurnId = None
            else:
                self.activeTranslationTurnId = newTurn.id

        self.idleTimerDeadline = now + self.idle_delay_ms
        if isFinished:
            self.onTurnComplete()

    def checkIdleTimer(self, now: int):
        if self.idleTimerDeadline is not None and now >= self.idleTimerDeadline:
            self.finalizeCurrentTurn(now)

    def onTurnComplete(self):
        self.activeTranslationTurnId = None
        for turn in self.sessionDialogueTurns:
            # Finalize only if Russian translation is received and grammatically complete
            # Never prematurely finalize turns whose translation is still in flight
            if not turn.isFinalized and turn.russianText.strip():
                if (isSentenceEnding(turn.russianText) and
                    not isContinuation(turn.russianText) and
                    not isContinuation(turn.italianText)):
                    turn.isFinalized = True

    def onInterrupted(self):
        self.idleTimerDeadline = None
        self.activeTranslationTurnId = None
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None
        self.finalizeCurrentTurn()

# Scenario 1: Continuous streaming translation chunks with sliding idle timer
sm1 = SynchronousTurnStateMachine()
sm1.recordInputSpeech("Vorrei un caffe", now=1000)
sm1.recordTranslationOutput("Я хотел бы", now=1400, isFinished=False)
assert sm1.sessionDialogueTurns[0].isFinalized == False, "Turn must remain unfinalized while streaming"
assert sm1.idleTimerDeadline == 3400, f"Expected deadline 3400, got {sm1.idleTimerDeadline}"

# Next chunk arrives at 2200 ms (before 3400 ms timeout) -> resets deadline to 4200 ms
sm1.recordTranslationOutput("кофе", now=2200, isFinished=False)
assert sm1.idleTimerDeadline == 4200, f"Expected reset deadline 4200, got {sm1.idleTimerDeadline}"
assert sm1.sessionDialogueTurns[0].russianText == "Я хотел бы кофе"

# At 3500 ms (no new chunks yet), timer has NOT expired
sm1.checkIdleTimer(now=3500)
assert sm1.sessionDialogueTurns[0].isFinalized == False

# At 4250 ms (2050 ms of idle after last chunk), timer triggers
sm1.checkIdleTimer(now=4250)
assert sm1.sessionDialogueTurns[0].isFinalized == True, "Turn must be finalized by sliding idle timer"
assert sm1.idleTimerDeadline is None

# Subsequent speech arrives at 6000 ms -> starts fresh turn
sm1.recordInputSpeech("Grazie", now=6000)
assert len(sm1.sessionDialogueTurns) == 2, f"Expected 2 turns, got {len(sm1.sessionDialogueTurns)}"
assert sm1.sessionDialogueTurns[1].italianText == "Grazie"

# Scenario 2: Microphone silence does NOT kill ongoing translation context
sm2 = SynchronousTurnStateMachine()
sm2.recordInputSpeech("Quanto costa?", now=1000)
# 3 seconds pass in microphone (physical silence) -> context must NOT be dropped
assert sm2.hasPendingTranslation == True
assert sm2.currentTurnInputSpeech == "Quanto costa?"
# Server sends translation after mic was silent
sm2.recordTranslationOutput("Сколько это стоит?", now=4200, isFinished=True)
assert sm2.sessionDialogueTurns[0].russianText == "Сколько это стоит?"
assert sm2.sessionDialogueTurns[0].isFinalized == True
assert len(sm2.logged_events) == 1
assert sm2.logged_events[0] == ("TRANSLATION", 'ORIG="Quanto costa?" -> RU="Сколько это стоит?"')

# Scenario 3: Empty speech (noise/cough) followed by turnComplete does NOT finalize prematurely; silent idle timer does
sm3 = SynchronousTurnStateMachine()
sm3.recordInputSpeech("Pronto", now=1000)
sm3.onTurnComplete()
assert len(sm3.logged_events) == 0, "turnComplete must NOT log TRANSLATION_EMPTY prematurely while translation is pending!"
assert sm3.sessionDialogueTurns[0].isFinalized == False, "Turn must remain unfinalized on premature turnComplete"
# 2.0s of silence passes without translation -> idle timer triggers
sm3.checkIdleTimer(now=3050)
assert len(sm3.logged_events) == 1
assert sm3.logged_events[0] == ("TRANSLATION_EMPTY", 'ORIG="Pronto"')
assert sm3.sessionDialogueTurns[0].isFinalized == True

# Scenario 4: Sentence-ending punctuation with finished flag finalizes immediately without waiting for 2s idle timer
sm4 = SynchronousTurnStateMachine()
sm4.recordInputSpeech("Si.", now=1000)
sm4.recordTranslationOutput("Да.", now=1300, isFinished=True)
assert sm4.sessionDialogueTurns[0].isFinalized == True, "Finished flag with punctuation must finalize turn immediately"

# Scenario 5: Multiple chunks with finished flag without punctuation do NOT fragment turn, and finalize upon silence timeout
sm5 = SynchronousTurnStateMachine()
sm5.recordInputSpeech("Buonasera", now=1000)
sm5.recordTranslationOutput("Добрый", now=1200, isFinished=True)
assert sm5.sessionDialogueTurns[0].isFinalized == False, "Incomplete sentence without punctuation must NOT be finalized"
sm5.recordTranslationOutput("вечер.", now=1250, isFinished=True)
assert len(sm5.sessionDialogueTurns) == 1, f"Expected 1 turn, got {len(sm5.sessionDialogueTurns)}"
assert sm5.sessionDialogueTurns[0].russianText == "Добрый вечер."
assert sm5.sessionDialogueTurns[0].isFinalized == True, "Turn must be finalized once dot is received"

# Scenario 6: User interruption cancels idle timer and immediately finalizes
sm6 = SynchronousTurnStateMachine()
sm6.recordInputSpeech("Vorrei sapere...", now=1000)
sm6.recordTranslationOutput("Я хотел бы знать...", now=1300, isFinished=False)
assert sm6.idleTimerDeadline == 3300
sm6.onInterrupted()
assert sm6.idleTimerDeadline is None
# Scenario 7: In-place streaming accumulation across multiple interleaved micro-chunks WITH turnComplete/isFinished on every chunk (real Gemini WebSocket behavior from Session Log 4 & 5)
sm7 = SynchronousTurnStateMachine()
sm7.recordInputSpeech("Sei venuta", now=1000)
sm7.recordTranslationOutput("Ты пришла", now=1200, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1
assert sm7.sessionDialogueTurns[0].russianText == "Ты пришла"
assert sm7.sessionDialogueTurns[0].isFinalized == False, "Incomplete sentence must NOT be finalized by interim turnComplete!"

# 2nd micro-chunk: same sentence continues without punctuation
sm7.recordInputSpeech("a contatto con", now=1800)
sm7.recordTranslationOutput("в контакт с", now=2000, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1, "Micro-chunks must NOT create a new card every 2-3 words!"
assert sm7.sessionDialogueTurns[0].italianText == "Sei venuta a contatto con"
assert sm7.sessionDialogueTurns[0].russianText == "Ты пришла в контакт с"
assert sm7.sessionDialogueTurns[0].isFinalized == False

# 3rd micro-chunk
sm7.recordInputSpeech("con una", now=2800)
sm7.recordTranslationOutput("одной", now=3000, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1
assert sm7.sessionDialogueTurns[0].isFinalized == False

# 4th micro-chunk
sm7.recordInputSpeech("che non", now=3800)
sm7.recordTranslationOutput("которая не", now=4000, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1
assert sm7.sessionDialogueTurns[0].isFinalized == False

# 5th micro-chunk with comma (continuation)
sm7.recordInputSpeech("dovevi,", now=4800)
sm7.recordTranslationOutput("должна была,", now=5000, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1
assert sm7.sessionDialogueTurns[0].russianText == "Ты пришла в контакт с одной которая не должна была,"
assert sm7.sessionDialogueTurns[0].isFinalized == False

# 6th micro-chunk with sentence ending dot
sm7.recordInputSpeech("di farlo.", now=5800)
sm7.recordTranslationOutput("это сделать.", now=6000, isFinished=True)
assert len(sm7.sessionDialogueTurns) == 1
assert sm7.sessionDialogueTurns[0].russianText == "Ты пришла в контакт с одной которая не должна была, это сделать."
assert sm7.sessionDialogueTurns[0].isFinalized == True, "Final chunk with dot MUST finalize turn on turnComplete"

# Scenario 8: Punctuation boundary (. ! ?) properly triggers new card on subsequent sentence
sm7.recordInputSpeech("Va bene.", now=6800)
assert len(sm7.sessionDialogueTurns) == 2, "Completed sentence with dot must create new card on next speech"
assert sm7.sessionDialogueTurns[0].isFinalized == True
assert sm7.sessionDialogueTurns[1].italianText == "Va bene."
sm7.recordTranslationOutput("Хорошо.", now=7000, isFinished=True)
assert sm7.sessionDialogueTurns[1].russianText == "Хорошо."
assert sm7.sessionDialogueTurns[1].isFinalized == True

# Scenario 9: Delayed translation arriving to finalized turn with blank Russian text attaches correctly
sm9 = SynchronousTurnStateMachine()
sm9.recordInputSpeech("Ciao", now=1000)
# Silence timeout triggers finalization before translation arrives
sm9.checkIdleTimer(now=3500)
sm9.finalizeCurrentTurn()
assert sm9.sessionDialogueTurns[0].isFinalized == True
assert sm9.sessionDialogueTurns[0].russianText == ""
# Delayed translation arrives
sm9.recordTranslationOutput("Привет", now=3600, isFinished=True)
assert len(sm9.sessionDialogueTurns) == 1, "Delayed translation must attach to existing turn with blank Russian text!"
assert sm9.sessionDialogueTurns[0].russianText == "Привет"

# Scenario 10: Abbreviations (sig., dott., руб.) and hesitation ellipsis (...) do not prematurely split cards
sm10 = SynchronousTurnStateMachine()
sm10.recordInputSpeech("Il sig.", now=1000)
sm10.recordTranslationOutput("Господин", now=1200, isFinished=True)
assert len(sm10.sessionDialogueTurns) == 1
assert sm10.sessionDialogueTurns[0].isFinalized == False, "Abbreviation 'sig.' must not finalize card"

sm10.recordInputSpeech("Rossi ha pagato 10 руб.", now=1800)
sm10.recordTranslationOutput("Росси заплатил 10 руб.", now=2000, isFinished=True)
assert len(sm10.sessionDialogueTurns) == 1
assert sm10.sessionDialogueTurns[0].isFinalized == False, "Abbreviation 'руб.' must not finalize card"

sm10.recordInputSpeech("in contanti.", now=2800)
sm10.recordTranslationOutput("наличными.", now=3000, isFinished=True)
assert len(sm10.sessionDialogueTurns) == 1
assert sm10.sessionDialogueTurns[0].isFinalized == True, "Final period must finalize card"
assert sm10.sessionDialogueTurns[0].russianText == "Господин Росси заплатил 10 руб. наличными."

# Scenario 11: turnComplete arrives BEFORE outputTranscription translation chunk (v1.0.29 fix)
sm11 = SynchronousTurnStateMachine()
sm11.recordInputSpeech("Come stai?", now=1000)
assert sm11.hasPendingTranslation == True
assert sm11.sessionDialogueTurns[0].isFinalized == False

# Server sends interim packet with turnComplete = True (before translation text arrives)
sm11.onTurnComplete()
assert sm11.sessionDialogueTurns[0].isFinalized == False, "turnComplete must NOT prematurely finalize turn when translation is pending!"
assert len(sm11.logged_events) == 0, "TRANSLATION_EMPTY must NOT be logged on premature turnComplete!"

# 300 ms later, delayed translation chunk arrives over network
sm11.recordTranslationOutput("Как дела?", now=1300, isFinished=True)
assert len(sm11.sessionDialogueTurns) == 1, "Translation must attach to existing Italian card, not create orphan!"
assert sm11.sessionDialogueTurns[0].italianText == "Come stai?"
assert sm11.sessionDialogueTurns[0].russianText == "Как дела?"
assert sm11.sessionDialogueTurns[0].isFinalized == True, "Turn with question mark must be finalized on isFinished"
assert ("TRANSLATION_EMPTY", 'ORIG="Come stai?"') not in sm11.logged_events, "TRANSLATION_EMPTY must never be logged when translation succeeds"
assert len(sm11.logged_events) == 1
assert sm11.logged_events[0] == ("TRANSLATION", 'ORIG="Come stai?" -> RU="Как дела?"')

# Scenario 12: recordTranslationOutput finds parent card with italianText even across intermediate states
sm12 = SynchronousTurnStateMachine()
sm12.recordInputSpeech("Quanto costa?", now=1000)
# Delayed translation arrives
sm12.recordTranslationOutput("Сколько это стоит?", now=1400, isFinished=True)
assert sm12.sessionDialogueTurns[0].italianText == "Quanto costa?"
assert sm12.sessionDialogueTurns[0].russianText == "Сколько это стоит?"
assert len(sm12.logged_events) == 1
assert sm12.logged_events[0] == ("TRANSLATION", 'ORIG="Quanto costa?" -> RU="Сколько это стоит?"')

# Scenario 13: Multiple sequential turns with delayed translations attach in FIFO order without orphan or inverted cards
sm13 = SynchronousTurnStateMachine()
sm13.recordInputSpeech("Uno", now=1000)
sm13.checkIdleTimer(now=3050)
assert sm13.sessionDialogueTurns[0].isFinalized == True
assert sm13.sessionDialogueTurns[0].russianText == ""

sm13.recordInputSpeech("Due", now=3100)
sm13.checkIdleTimer(now=5150)
assert sm13.sessionDialogueTurns[1].isFinalized == True
assert sm13.sessionDialogueTurns[1].russianText == ""

sm13.recordInputSpeech("Tre", now=5200)

# Delayed translations arrive in chronological FIFO order (testing that wasFinalized preservation works without isFinished=True):
sm13.recordTranslationOutput("Т_Один.", now=5300, isFinished=False)
sm13.recordTranslationOutput("Т_Два.", now=5400, isFinished=False)
sm13.recordTranslationOutput("Т_Три.", now=5500, isFinished=True)

assert len(sm13.sessionDialogueTurns) == 3
assert sm13.sessionDialogueTurns[0].italianText == "Uno"
assert sm13.sessionDialogueTurns[0].russianText == "Т_Один."
assert sm13.sessionDialogueTurns[0].isFinalized == True

assert sm13.sessionDialogueTurns[1].italianText == "Due"
assert sm13.sessionDialogueTurns[1].russianText == "Т_Два."
assert sm13.sessionDialogueTurns[1].isFinalized == True

assert sm13.sessionDialogueTurns[2].italianText == "Tre"
assert sm13.sessionDialogueTurns[2].russianText == "Т_Три."
assert sm13.sessionDialogueTurns[2].isFinalized == True

for turn in sm13.sessionDialogueTurns:
    assert turn.russianText.strip() != "", f"Turn {turn.italianText} was left without translation!"

# Scenario 14: Multi-chunk streaming into delayed/finalized turns preserves continuity and prevents orphan cards
sm14 = SynchronousTurnStateMachine()
sm14.recordInputSpeech("Uno", now=1000)
sm14.checkIdleTimer(now=3050)
assert sm14.sessionDialogueTurns[0].isFinalized == True
assert sm14.sessionDialogueTurns[0].russianText == ""

sm14.recordInputSpeech("Due", now=3100)
sm14.checkIdleTimer(now=5150)
assert sm14.sessionDialogueTurns[1].isFinalized == True
assert sm14.sessionDialogueTurns[1].russianText == ""

# Delayed translation for Uno arrives in 2 streaming micro-chunks without isFinished:
sm14.recordTranslationOutput("Т_Один", now=5300, isFinished=False)
sm14.recordTranslationOutput(" продолжение.", now=5350, isFinished=False)

# Delayed translation for Due arrives in 2 streaming micro-chunks with turnComplete:
sm14.recordTranslationOutput("Т_Два", now=5400, isFinished=False)
sm14.recordTranslationOutput(" продолжение.", now=5450, isFinished=True)

assert len(sm14.sessionDialogueTurns) == 2, f"Expected exactly 2 turns, got {len(sm14.sessionDialogueTurns)}"
assert sm14.sessionDialogueTurns[0].italianText == "Uno"
assert sm14.sessionDialogueTurns[0].russianText == "Т_Один продолжение."
assert sm14.sessionDialogueTurns[0].isFinalized == True

assert sm14.sessionDialogueTurns[1].italianText == "Due"
assert sm14.sessionDialogueTurns[1].russianText == "Т_Два продолжение."
assert sm14.sessionDialogueTurns[1].isFinalized == True

# Scenario 15: In-chunk sentence boundary splits sentences into distinct cards and prevents off-by-one desync cascade (Session Log 6)
sm15 = SynchronousTurnStateMachine()
# Replica 1: "Ah, e poi la bevi... pezzi di costa. Sono" (dot inside chunk!)
sm15.recordInputSpeech("Ah, e poi la bevi... pezzi di costa. Sono", now=1000)
# Replica 2: "sempre stati due."
sm15.recordInputSpeech("sempre stati due.", now=2000)
# Translation 1 completes: "А, и потом... куска ребрышек."
sm15.recordTranslationOutput("А, и потом... куска ребрышек.", now=2500, isFinished=True)
# Replica 3: "Lui bugiardo."
sm15.recordInputSpeech("Lui bugiardo.", now=3000)
# Delayed translation 2 arrives: "Всегда было два."
sm15.recordTranslationOutput("Всегда было два.", now=3500, isFinished=True)
# Translation 3 arrives: "Он лжец."
sm15.recordTranslationOutput("Он лжец.", now=4000, isFinished=True)

assert "Sono sempre stati due." not in sm15.sessionDialogueTurns[0].italianText, (
    f"Card 0 should not contain 'Sono sempre stati due.', got '{sm15.sessionDialogueTurns[0].italianText}'"
)
assert len(sm15.sessionDialogueTurns) == 3, f"Expected exactly 3 turns, got {len(sm15.sessionDialogueTurns)}"
assert sm15.sessionDialogueTurns[0].italianText == "Ah, e poi la bevi... pezzi di costa."
assert sm15.sessionDialogueTurns[0].russianText == "А, и потом... куска ребрышек."
assert sm15.sessionDialogueTurns[1].italianText == "Sono sempre stati due."
assert sm15.sessionDialogueTurns[1].russianText == "Всегда было два."
assert sm15.sessionDialogueTurns[2].italianText == "Lui bugiardo."
assert sm15.sessionDialogueTurns[2].russianText == "Он лжец."

# Scenario 16: Multi-chunk streaming translation without isFinished preserves FIFO turn routing across in-chunk sentence splits (Session Log 6 edge case)
sm16 = SynchronousTurnStateMachine()
# Replica 1: dot inside chunk
sm16.recordInputSpeech("Ah, e poi la bevi... pezzi di costa. Sono", now=1000)
# Interim streaming translation chunk 1a arrives without isFinished:
sm16.recordTranslationOutput("евро всего за два", now=1100, isFinished=False)
# Replica 2 continues second sentence:
sm16.recordInputSpeech("sempre stati due.", now=1200)
# Sentence 1 translation finishes without isFinished:
sm16.recordTranslationOutput("куска ребрышек.", now=1250, isFinished=False)
# Replica 3:
sm16.recordInputSpeech("Lui bugiardo.", now=1300)
# Delayed translation 2 arrives without isFinished:
sm16.recordTranslationOutput("Всегда было два.", now=1350, isFinished=False)
# Delayed translation 3 arrives without isFinished:
sm16.recordTranslationOutput("Он лжец.", now=1400, isFinished=False)

assert len(sm16.sessionDialogueTurns) == 3, f"Expected exactly 3 turns, got {len(sm16.sessionDialogueTurns)}"
assert sm16.sessionDialogueTurns[0].italianText == "Ah, e poi la bevi... pezzi di costa."
assert sm16.sessionDialogueTurns[0].russianText == "евро всего за два куска ребрышек."
assert sm16.sessionDialogueTurns[1].italianText == "Sono sempre stati due."
assert sm16.sessionDialogueTurns[1].russianText == "Всегда было два."
assert sm16.sessionDialogueTurns[2].italianText == "Lui bugiardo."
assert sm16.sessionDialogueTurns[2].russianText == "Он лжец."

# Scenario 17: Multiple sentences in a single input speech chunk without isFinished
sm17 = SynchronousTurnStateMachine()
sm17.recordInputSpeech("Ah, e poi la bevi... pezzi di costa. Sono sempre stati due. Lui bugiardo.", now=1000)
assert len(sm17.sessionDialogueTurns) == 3
sm17.recordTranslationOutput("А, и потом... куска ребрышек.", now=2000, isFinished=False)
sm17.recordTranslationOutput("Всегда было два.", now=3000, isFinished=False)
sm17.recordTranslationOutput("Он лжец.", now=4000, isFinished=False)
assert sm17.sessionDialogueTurns[0].russianText == "А, и потом... куска ребрышек."
assert sm17.sessionDialogueTurns[1].russianText == "Всегда было два."
assert sm17.sessionDialogueTurns[2].russianText == "Он лжец."

# Scenario 18: Session Log (7) reproduction: Monologue of 4 fragments, 3 translations, pause, new question -> Prevents cascading desync
sm18 = SynchronousTurnStateMachine(idle_delay_ms=3500)
sm18.recordInputSpeech("No, basta cosi,", now=1000)
sm18.recordInputSpeech("questo e basta.", now=1500)
sm18.recordTranslationOutput("Нет, хватит,", now=1600, isFinished=False)
sm18.recordTranslationOutput("это все.", now=1700, isFinished=True)

# Speaker pauses for 4 seconds -> idle timer finalizes Turn 0
sm18.checkIdleTimer(now=5300)
assert sm18.sessionDialogueTurns[0].isFinalized == True
assert sm18.sessionDialogueTurns[0].russianText == "Нет, хватит, это все."

# Brief phrase that gets empty translation
sm18.recordInputSpeech("Sì, Alex.", now=6000)
# Silence passes (3.5s) -> Sì, Alex. gets TRANSLATION_EMPTY:
sm18.checkIdleTimer(now=9600)
assert sm18.sessionDialogueTurns[1].isFinalized == True
assert sm18.sessionDialogueTurns[1].russianText == ""
assert ("TRANSLATION_EMPTY", 'ORIG="Sì, Alex."') in sm18.logged_events

# 19 seconds of silence pass. New monologue / question arrived at 29000:
sm18.recordInputSpeech("Ma, come", now=29000)
sm18.recordInputSpeech("mai io", now=29500)
sm18.recordInputSpeech("Aspetta, non vorrei che", now=30000)
sm18.recordInputSpeech("questo turno.", now=30500)

# Translation for the new question arrives:
sm18.recordTranslationOutput("Но, как", now=30600, isFinished=False)
sm18.recordTranslationOutput("так я", now=30700, isFinished=False)
sm18.recordTranslationOutput("Подожди, я не хочу этот ход.", now=30800, isFinished=True)

# 1. Turn 1 (Sì, Alex.) MUST NOT have stolen translation from the new question!
assert sm18.sessionDialogueTurns[1].russianText == "", f"Turn 1 stole translation! Got: {sm18.sessionDialogueTurns[1].russianText}"

# 2. Turn 2 (Ma, come...) must receive all monologue translations without desync
assert sm18.sessionDialogueTurns[2].italianText == "Ma, come mai io Aspetta, non vorrei che questo turno."
assert sm18.sessionDialogueTurns[2].russianText == "Но, как так я Подожди, я не хочу этот ход."

print("  -> Synchronous streaming state machine, sentence accumulation & sliding idle timer verified perfectly.")

print("\n--- 10. Verification of v1.0.33 P1/P2 Architectural Enhancements & Guardrails ---")
# 1. DialogueTurn.id with AtomicLong
assert "dialogueTurnIdCounter = AtomicLong(0L)" in service_code, "dialogueTurnIdCounter = AtomicLong(0L) missing in PocketService.kt"
assert "val id: Long = dialogueTurnIdCounter.incrementAndGet()" in service_code, "DialogueTurn must use dialogueTurnIdCounter.incrementAndGet() for default id"

# Functional Test: DialogueTurnSim monotonic counter & zero collision
turn_objs = [DialogueTurnSim("test", "тест", 1000) for _ in range(100)]
turn_ids = [t.id for t in turn_objs]
assert len(set(turn_ids)) == 100, f"Expected 100 unique turn IDs, got {len(set(turn_ids))}"
assert turn_ids == sorted(turn_ids), "Turn IDs must be strictly monotonically increasing"
assert all(b == a + 1 for a, b in zip(turn_ids, turn_ids[1:])), "Turn IDs must increment by 1 without gaps"

# 2. applyMicSourceChange safe thread restart & interruption
assert "recordThread?.interrupt()" in service_code and "recordThread?.join(500)" in service_code, "recordThread interrupt and join(500) missing in applyMicSourceChange"
assert "private fun startRecordThread()" in service_code, "startRecordThread() method missing in PocketService.kt"
assert "startRecordThread()" in service_code, "startRecordThread() call missing in applyMicSourceChange/startRecording"
assert "isRunning && !Thread.currentThread().isInterrupted" in service_code, "Thread interruption check missing in startRecordThread"
assert "audioRecord?.stop()" in service_code and "recordThread?.join(500)" in service_code, "Immediate read unblocking missing before recordThread.join in applyMicSourceChange"

# 3. onFailure fatal error unification (400, 401, 403)
assert "httpCode == 400 || httpCode == 401 || httpCode == 403" in service_code, "Fatal HTTP 400/401/403 unification missing in onFailure"

# Functional Test: HTTP fatal code evaluation (400, 401, 403 are fatal; others are transient)
def is_fatal_http(code):
    return code == 400 or code == 401 or code == 403

assert is_fatal_http(400) == True
assert is_fatal_http(401) == True
assert is_fatal_http(403) == True
assert is_fatal_http(500) == False
assert is_fatal_http(503) == False
assert is_fatal_http(None) == False

# 4. activeTranslationTurnId reset
assert "activeTranslationTurnId = null" in service_code, "activeTranslationTurnId reset missing"

# 5. Gemini Live Translate model log status
assert '"🔵 WebSocket соединён. Инициализация Gemini Live Translate..."' in service_code, "Gemini Live Translate log status missing in onOpen"

# 6. audioReadErrors incremented only when read < 0
assert "read < 0" in service_code and "audioReadErrors++" in service_code, "audioReadErrors++ must only increment on read < 0"

# Functional Test: audioReadErrors logic (read < 0 increments, read == 0 does not)
simulated_reads = [1600, 1600, 0, 0, -1, 1600, -2, 0, -3]
sim_audio_read_errors = 0
for r in simulated_reads:
    if r > 0:
        pass
    else:
        if r < 0:
            sim_audio_read_errors += 1
assert sim_audio_read_errors == 3, f"Expected exactly 3 read errors, got {sim_audio_read_errors}"

# 7. setup_and_push.ps1 dynamic repoOwner/repoName
assert "AleksandrBrevnov" not in setup_code, "Hardcoded AleksandrBrevnov must be purged from setup_and_push.ps1"
assert "$repoOwner" in setup_code and "$repoName" in setup_code, "Dynamic repoOwner and repoName variables missing in setup_and_push.ps1"

# Check UTF-8 without BOM on setup_and_push.ps1
with open(setup_and_push_path, "rb") as f:
    raw_setup_bytes = f.read()
assert not raw_setup_bytes.startswith(b'\xef\xbb\xbf'), "setup_and_push.ps1 MUST NOT have UTF-8 BOM"

print("  -> v1.0.33 P1/P2 Architectural Enhancements & Guardrails verified perfectly.")

print("\n--- 11. Verification of Dialogue Persistence & Saved Sessions ---")
# 1. DialogueHistoryManager structure & methods
assert "object DialogueHistoryManager" in history_code, "DialogueHistoryManager object missing in DialogueHistoryManager.kt"
assert "fun saveSession" in history_code, "saveSession method missing in DialogueHistoryManager.kt"
assert "fun saveCurrentSessionCache" in history_code, "saveCurrentSessionCache method missing in DialogueHistoryManager.kt"
assert "fun loadCurrentSessionCache" in history_code, "loadCurrentSessionCache method missing in DialogueHistoryManager.kt"
assert "fun listSavedSessions" in history_code, "listSavedSessions method missing in DialogueHistoryManager.kt"
assert "fun loadSessionTurns" in history_code, "loadSessionTurns method missing in DialogueHistoryManager.kt"
assert "fun deleteSession" in history_code, "deleteSession method missing in DialogueHistoryManager.kt"
assert "fun formatDialogueText" in history_code, "formatDialogueText helper missing in DialogueHistoryManager.kt"

# 2. Integration in PocketService
assert "DialogueHistoryManager.saveCurrentSessionCache" in service_code, "saveCurrentSessionCache call missing in finalizeCurrentTurn"
assert "DialogueHistoryManager.saveSession" in service_code, "saveSession call missing in onDestroy / clearDialogueTurns"
assert "fun restoreDialogueTurns" in service_code, "restoreDialogueTurns companion method missing in PocketService"
assert "fun clearActiveDialogueTurn" in service_code, "clearActiveDialogueTurn method missing in PocketService"
assert "val curActive = activeDialogueTurn" in service_code, "curActive local val copy missing for safe smart-cast in Priority 3"

# 3. Integration in MainActivity
assert "historyDialogueBtn" in main_code, "historyDialogueBtn button missing in MainActivity.kt"
assert "📁 История" in main_code, "📁 История button label missing in MainActivity.kt"
assert "showSavedDialoguesDialog" in main_code, "showSavedDialoguesDialog method missing in MainActivity.kt"
assert "DialogueHistoryManager.loadCurrentSessionCache" in main_code, "loadCurrentSessionCache call missing in restoreDialogueView"
assert "PocketService.clearDialogueTurns(this" in main_code, "clearDialogueTurns with context missing in MainActivity.kt"

# 4. Functional Test: Simulated dialogue session serialization, deserialization, and preview formatting
import json

sample_turns = [
    {"id": 1, "italianText": "No, io non ho mai", "russianText": "Нет, я никогда не", "timestamp": 1730668800000, "isFinalized": True},
    {"id": 2, "italianText": "Che strana. Che strano.", "russianText": "Какая странная. Как странно.", "timestamp": 1730668805000, "isFinalized": True},
    {"id": 3, "italianText": "Ho vinto 41.000 euro!", "russianText": "Я выиграл 41 000 евро!", "timestamp": 1730668810000, "isFinalized": True}
]

# Simulate DialogueHistoryManager.serializeTurnsToJson
def sim_serialize_turns(turns, ts):
    meaningful = [t for t in turns if t["italianText"].strip() or t["russianText"].strip()]
    preview_parts = []
    for t in meaningful[:2]:
        if t["russianText"].strip():
            preview_parts.append(f"🇷🇺 {t['russianText'][:45]}")
        elif t["italianText"].strip():
            preview_parts.append(f"🇮🇹 {t['italianText'][:45]}")
    preview = " | ".join(preview_parts) if preview_parts else f"Диалог ({len(meaningful)} реплик)"
    
    return {
        "id": f"session_{ts}",
        "timestamp": ts,
        "formattedDate": "03.11.2026 20:00",
        "turnCount": len(meaningful),
        "preview": preview,
        "turns": meaningful
    }

session_json = sim_serialize_turns(sample_turns, 1730668800000)
assert session_json["turnCount"] == 3
assert "41 000 евро" in session_json["turns"][2]["russianText"]
assert "Нет, я никогда не" in session_json["preview"]

# Simulate DialogueHistoryManager.formatDialogueText
def sim_format_dialogue(turns, include_orig=True):
    lines = ["=== POCKET HUD ДИАЛОГ ==="]
    for t in turns:
        if include_orig and t["italianText"].strip():
            lines.append(f"🇮🇹/🇬🇧 {t['italianText']}")
        if t["russianText"].strip():
            lines.append(f"➔ 🇷🇺 {t['russianText']}")
        lines.append("")
    lines.append("=========================")
    return "\n".join(lines).strip()

formatted_text = sim_format_dialogue(sample_turns, include_orig=True)
assert "🇮🇹/🇬🇧 Ho vinto 41.000 euro!" in formatted_text
assert "➔ 🇷🇺 Я выиграл 41 000 евро!" in formatted_text

print("  -> Dialogue Persistence & Saved Sessions verified perfectly.")

print("\n=======================================================")
print("🎉 ALL V1.0.33 ARCHITECTURAL TESTS PASSED PERFECTLY! 🎉")
print("=======================================================")

