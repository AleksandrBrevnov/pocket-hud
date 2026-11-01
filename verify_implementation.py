import re
import sys
import os

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

print("Checking TurnViewHolder definition and usages...")
with open(main_activity_path, "r", encoding="utf-8") as f:
    main_code = f.read()

# TurnViewHolder definition check
assert "class TurnViewHolder(" in main_code, "TurnViewHolder not found"
assert "val ruBadge: TextView" in main_code, "ruBadge missing in TurnViewHolder"
assert "val ruTimeView: TextView" in main_code, "ruTimeView missing in TurnViewHolder"

# TurnViewHolder return check
assert "return TurnViewHolder(container, itBubble, itTv, ruTv, ruBadge, ruTimeView)" in main_code, "createTurnViewHolder return mismatched"

# show_original_text key check
assert 'val keyShowOriginalText = "show_original_text"' in main_code, "Preference key missing"
assert 'showOriginalText = prefs.getBoolean(keyShowOriginalText, true)' in main_code, "Default preference missing"

# Dynamic visibility check
assert "holder.ruBadge.text = if (showOriginalText) \"➔ 🇷🇺 ПЕРЕВОД\" else \"🇷🇺 ПЕРЕВОД\"" in main_code, "ruBadge text dynamic update missing"
assert "holder.ruTimeView.visibility = if (showOriginalText) View.GONE else View.VISIBLE" in main_code, "ruTimeView dynamic visibility missing"

# 200 items pruning check
assert "while (turnViewHolders.size > 200)" in main_code, "200 turn limit synchronization missing"

# Version check across files
assert 'versionCode = 21' in open(build_gradle_path, 'r', encoding='utf-8').read(), "versionCode 21 missing"
assert 'versionName = "1.0.21"' in open(build_gradle_path, 'r', encoding='utf-8').read(), "versionName 1.0.21 missing"
assert 'text = "v1.0.21"' in main_code, "v1.0.21 badge missing in MainActivity"
assert 'Версия: v1.0.21' in open(pocket_service_path, 'r', encoding='utf-8').read(), "v1.0.21 missing in PocketService"

print("✅ ALL TESTS PASSED SUCCESSFULLY!")
