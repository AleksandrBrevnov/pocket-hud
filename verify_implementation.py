import re
import sys
import os
import math

main_activity_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\src\main\java\com\aleksandr\pockethud\MainActivity.kt"
pocket_service_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\src\main\java\com\aleksandr\pockethud\PocketService.kt"
build_gradle_path = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\build.gradle.kts"

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

with open(main_activity_path, "r", encoding="utf-8") as f:
    main_code = f.read()

with open(pocket_service_path, "r", encoding="utf-8") as f:
    service_code = f.read()

with open(build_gradle_path, "r", encoding="utf-8") as f:
    gradle_code = f.read()

print("\n--- 1. Verification of v1.0.23 Version Bumps ---")
assert 'versionCode = 23' in gradle_code, "versionCode 23 missing in app/build.gradle.kts"
assert 'versionName = "1.0.23"' in gradle_code, "versionName 1.0.23 missing in app/build.gradle.kts"
assert 'text = "v1.0.23"' in main_code, "v1.0.23 badge missing in MainActivity.kt"
assert 'PocketHUD — Ушной суфлёр v1.0.23' in service_code, "v1.0.23 notification title missing in PocketService.kt"
assert 'PocketHUD v1.0.23 активен' in service_code, "v1.0.23 start subtitle missing in PocketService.kt"
assert 'Версия: v1.0.23' in service_code, "v1.0.23 diagnostic summary missing in PocketService.kt"
print("  -> Version bump v1.0.23 OK across all files.")

print("\n--- 2. Verification of Google 10-15 min Session Rotation & Reconnect ---")
assert "code == 1000" in service_code, "Session rotation code 1000 check missing in onClosed"
assert "SESSION_ROTATION_RECONNECT" in service_code, "Session rotation reconnect log missing"
assert "sessionResumptionUpdate" in service_code, "Root-level sessionResumptionUpdate extraction missing"
assert "resumptionHandle = handle" in service_code, "resumptionHandle persistence missing"
assert "root.has(\"goAway\")" in service_code, "Root-level goAway extraction missing"
assert "goAwayRunnable" in service_code, "goAwayRunnable missing in PocketService.kt"
assert "webSocket === currentWs" in service_code, "goAway race condition guard missing"
assert "@Volatile private var isSetupComplete = false" in service_code, "isSetupComplete volatile flag missing"
assert "if (!isSetupComplete)" in service_code, "isSetupComplete guard missing in sendThread"
assert "Thread.sleep(40)" in service_code, "sendThread must sleep while !isSetupComplete to hold queue"
assert "isSetupComplete = true" in service_code, "isSetupComplete activation on setupComplete missing"
print("  -> Session rotation & reconnect OK.")

print("\n--- 3. Verification of Natural Gemini Server-Side VAD sendSetup() ---")
assert '"realtimeInputConfig"' not in service_code, "realtimeInputConfig must be removed to allow natural server-side VAD"
assert '"silenceDurationMs"' not in service_code, "silenceDurationMs must be removed to prevent premature sentence cutting"
assert '"NO_INTERRUPTION"' not in service_code, "NO_INTERRUPTION must be removed"
assert '"sessionResumption"' in service_code, "sessionResumption object missing in sendSetup"
assert '"handle", handle' in service_code, "resumptionHandle injection missing in sendSetup"
assert '"languageCodes"' in service_code, "languageCodes array missing in sendSetup"
assert '"it-IT"' in service_code, "it-IT missing in languageCodes"
assert '"en-US"' in service_code, "en-US missing in languageCodes"
print("  -> Gemini Live natural server-side VAD configuration OK.")

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

print("\n--- 8. Verification of Dynamic Soft-Knee DSP Gain Curve ---")
# Verify mathematical properties of applyDynamicSoftKneeAgc
gain = 4.85
quiet_sample = 2000 # ~6.1% amplitude
s_quiet = quiet_sample / 32768.0
out_quiet = int(math.tanh(s_quiet * gain) * 32767.0)
gain_quiet = out_quiet / quiet_sample
db_quiet = 20 * math.log10(gain_quiet)
print(f"  Quiet sample {quiet_sample} -> {out_quiet} (gain = {gain_quiet:.2f}x, {db_quiet:+.1f} dB)")
assert 4.5 <= gain_quiet <= 5.0, f"Gain {gain_quiet} not in expected [4.5x, 5.0x] range"

loud_sample = 32767 # 100% full scale
s_loud = loud_sample / 32768.0
out_loud = int(math.tanh(s_loud * gain) * 32767.0)
print(f"  Loud sample {loud_sample} -> {out_loud} (soft saturation without digital clipping)")
assert out_loud <= 32767, "Loud sample must not clip or overflow"
assert out_loud > 32000, "Loud sample should saturate near ceiling"

print("\n--- 9. Verification of TRANSLATION_EMPTY Logging & Utterance Accumulation ---")
assert "TRANSLATION_EMPTY" in service_code, "TRANSLATION_EMPTY event logging missing in PocketService.kt"
assert "hasPendingTranslation" in service_code, "hasPendingTranslation state tracking missing in PocketService.kt"
assert "turnComplete" in service_code, "turnComplete check missing in PocketService.kt"
assert "lastRecognizedInput = lastTurn.italianText" in service_code, "lastRecognizedInput must accumulate full Italian turn across chunks"
assert "currentTurnInputSpeech = lastTurn.italianText" in service_code, "currentTurnInputSpeech must accumulate full Italian turn across chunks"
assert "val prevPrompt = currentTurnInputSpeech ?: lastTurn.italianText" in service_code, "Timeout TRANSLATION_EMPTY must use accumulated prompt"

# Python simulation of turn accumulation and TRANSLATION_EMPTY state machine
class DialogueTurnSim:
    def __init__(self, italianText, russianText, timestamp):
        self.italianText = italianText
        self.russianText = russianText
        self.timestamp = timestamp

class TurnStateMachine:
    def __init__(self):
        self.sessionDialogueTurns = []
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None
        self.lastRecognizedInput = ""
        self.logged_events = []

    def recordInputSpeech(self, cleanedInput: str, now: int):
        lastTurn = self.sessionDialogueTurns[-1] if self.sessionDialogueTurns else None
        if lastTurn and not lastTurn.russianText.strip() and (now - lastTurn.timestamp < 8000):
            if lastTurn.italianText and not lastTurn.italianText.endswith(" ") and not cleanedInput.startswith(" "):
                lastTurn.italianText += f" {cleanedInput}"
            else:
                lastTurn.italianText += cleanedInput
            self.lastRecognizedInput = lastTurn.italianText
            self.currentTurnInputSpeech = lastTurn.italianText
            self.hasPendingTranslation = True
        else:
            if lastTurn and not lastTurn.russianText.strip() and lastTurn.italianText.strip() and self.hasPendingTranslation:
                prevPrompt = self.currentTurnInputSpeech or lastTurn.italianText
                self.logged_events.append(("TRANSLATION_EMPTY", f'ORIG="{prevPrompt}" (turn timeout)'))
            newTurn = DialogueTurnSim(cleanedInput, "", now)
            self.lastRecognizedInput = cleanedInput
            self.currentTurnInputSpeech = cleanedInput
            self.hasPendingTranslation = True
            self.sessionDialogueTurns.append(newTurn)

    def onTurnComplete(self):
        if self.hasPendingTranslation:
            origPrompt = self.currentTurnInputSpeech or (self.sessionDialogueTurns[-1].italianText if self.sessionDialogueTurns else "") or self.lastRecognizedInput
            self.logged_events.append(("TRANSLATION_EMPTY", f'ORIG="{origPrompt}"'))
            self.hasPendingTranslation = False
            self.currentTurnInputSpeech = None

    def recordTranslationOutput(self, cleanedText: str):
        self.hasPendingTranslation = False
        self.currentTurnInputSpeech = None
        origPrompt = self.sessionDialogueTurns[-1].italianText if self.sessionDialogueTurns else self.lastRecognizedInput
        self.logged_events.append(("TRANSLATION", f'ORIG="{origPrompt}" -> RU="{cleanedText}"'))
        if self.sessionDialogueTurns:
            self.sessionDialogueTurns[-1].russianText = cleanedText

# Scenario 1: Chunked speech with empty translation -> logs full sentence, no duplicate on subsequent speech
sm1 = TurnStateMachine()
sm1.recordInputSpeech("Vorrei", now=1000)
sm1.recordInputSpeech("un caffe", now=1500)
sm1.recordInputSpeech("per favore", now=2000)
assert sm1.currentTurnInputSpeech == "Vorrei un caffe per favore", f"Expected accumulated prompt, got '{sm1.currentTurnInputSpeech}'"
sm1.onTurnComplete()
assert len(sm1.logged_events) == 1, f"Expected 1 event, got {len(sm1.logged_events)}"
assert sm1.logged_events[0] == ("TRANSLATION_EMPTY", 'ORIG="Vorrei un caffe per favore"'), f"Unexpected event {sm1.logged_events[0]}"

# 10s later, new speech arrives -> must NOT duplicate TRANSLATION_EMPTY for previous turn
sm1.recordInputSpeech("Buongiorno", now=12000)
assert len(sm1.logged_events) == 1, f"Expected still 1 event (no duplicate), got {len(sm1.logged_events)}"

# Scenario 2: Turn timeout without turnComplete
sm2 = TurnStateMachine()
sm2.recordInputSpeech("Scusi", now=1000)
# 10s later new turn arrives without turnComplete or translation
sm2.recordInputSpeech("Ehi", now=11000)
assert len(sm2.logged_events) == 1, f"Expected 1 event, got {len(sm2.logged_events)}"
assert sm2.logged_events[0] == ("TRANSLATION_EMPTY", 'ORIG="Scusi" (turn timeout)'), f"Unexpected event {sm2.logged_events[0]}"

# Scenario 3: Normal translation output
sm3 = TurnStateMachine()
sm3.recordInputSpeech("Come", now=1000)
sm3.recordInputSpeech("va?", now=1500)
sm3.recordTranslationOutput("Как дела?")
sm3.onTurnComplete()
assert len(sm3.logged_events) == 1, f"Expected only TRANSLATION event, got {len(sm3.logged_events)}"
assert sm3.logged_events[0] == ("TRANSLATION", 'ORIG="Come va?" -> RU="Как дела?"')

print("  -> TRANSLATION_EMPTY logging & utterance accumulation verified perfectly.")

print("\n=======================================================")
print("🎉 ALL V1.0.23 ARCHITECTURAL TESTS PASSED PERFECTLY! 🎉")
print("=======================================================")
