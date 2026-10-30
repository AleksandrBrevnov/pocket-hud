# ==============================================================================
# PocketHUD: Автоматическая инициализация и отправка в GitHub
# ==============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

Set-Location -Path $PSScriptRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   PocketHUD — Автоматический запуск сборки в GitHub      " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Проверка Git
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "[ОШИБКА] Git не найден в системе! Установите Git для Windows." -ForegroundColor Red
    exit 1
}

# 2. Инициализация Git (если еще не создан)
if (-not (Test-Path ".git")) {
    Write-Host "`n[1/4] Инициализация Git репозитория..." -ForegroundColor Green
    git init -q
} else {
    Write-Host "`n[1/4] Git репозиторий уже инициализирован." -ForegroundColor Green
}

git branch -M main

# Проверка идентификации автора Git
if (-not (git config user.name)) {
    git config user.name "Aleksandr"
}
if (-not (git config user.email)) {
    git config user.email "aleksandr@local"
}

# 3. Фиксация файлов (Commit)
Write-Host "[2/4] Проверка и сохранение файлов проекта..." -ForegroundColor Green
git add .
$status = git status --porcelain
if ($status) {
    git commit -m "PocketHUD: Real-time Italian-Russian Audio HUD Service"
    Write-Host "  -> Создан коммит со всеми файлами." -ForegroundColor Gray
} else {
    Write-Host "  -> Все файлы уже зафиксированы в Git." -ForegroundColor Gray
}

# 4. Проверка и настройка Remote Origin
Write-Host "`n[3/4] Проверка удалённого репозитория GitHub..." -ForegroundColor Green

$existingRemote = ""
try {
    $rawOrigin = git remote get-url origin 2>$null
    if ($rawOrigin) {
        $existingRemote = "$rawOrigin".Trim()
    }
} catch {}

if (-not [string]::IsNullOrWhiteSpace($existingRemote)) {
    Write-Host "  -> Обнаружен текущий адрес origin: $existingRemote" -ForegroundColor Gray
    $keepRemote = Read-Host "Использовать этот адрес? (Y/n)"
    if ($keepRemote -eq 'n' -or $keepRemote -eq 'N') {
        $existingRemote = ""
    }
}

$targetRemote = $existingRemote

if ([string]::IsNullOrWhiteSpace($existingRemote)) {
    # Проверяем, есть ли установленный и авторизованный gh CLI
    $ghAvailable = $false
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        $null = gh auth status 2>&1
        if ($LASTEXITCODE -eq 0) {
            $ghAvailable = $true
        }
    }

    if ($ghAvailable) {
        Write-Host "`n[GH CLI] Обнаружен авторизованный GitHub CLI!" -ForegroundColor Cyan
        $autoCreate = Read-Host "Создать репозиторий 'pocket-hud' на GitHub автоматически? (Y/n)"
        if ($autoCreate -ne 'n' -and $autoCreate -ne 'N') {
            Write-Host "Создание публичного репозитория pocket-hud и отправка кода..." -ForegroundColor Yellow
            gh repo create pocket-hud --public --source=. --remote=origin --push
            try {
                $rawGhOrigin = git remote get-url origin 2>$null
                if ($rawGhOrigin) {
                    $targetRemote = "$rawGhOrigin".Trim()
                }
            } catch {}
        }
    }

    # Если gh CLI нет или пользователь выбрал ручной ввод / gh завершился без remote
    if ([string]::IsNullOrWhiteSpace($targetRemote)) {
        Write-Host "`n----------------------------------------------------------" -ForegroundColor DarkCyan
        Write-Host "Создайте пустой репозиторий на GitHub в 3 клика:" -ForegroundColor White
        Write-Host "  1. Откройте страницу: https://github.com/new" -ForegroundColor Yellow
        Write-Host "  2. Введите имя (Repository name): pocket-hud" -ForegroundColor Yellow
        Write-Host "  3. Выберите 'Public' (для бесплатных GitHub Actions)" -ForegroundColor Yellow
        Write-Host "  4. НЕ добавляйте README, .gitignore или лицензию!" -ForegroundColor Yellow
        Write-Host "  5. Нажмите кнопку 'Create repository'" -ForegroundColor Yellow
        Write-Host "----------------------------------------------------------" -ForegroundColor DarkCyan

        $openBrowser = Read-Host "`nОткрыть https://github.com/new в браузере сейчас? (Y/n)"
        if ($openBrowser -ne 'n' -and $openBrowser -ne 'N') {
            Start-Process "https://github.com/new"
        }

        Write-Host "`nВведите ссылку на репозиторий (например: https://github.com/ИМЯ/pocket-hud.git)" -ForegroundColor Cyan
        Write-Host "Или просто введите ваш GitHub логин:" -ForegroundColor Gray
        $userInput = (Read-Host "URL или логин").Trim()

        if ([string]::IsNullOrWhiteSpace($userInput)) {
            Write-Host "[ОШИБКА] Ссылка не введена. Запустите скрипт повторно, когда создадите репозиторий." -ForegroundColor Red
            exit 1
        }

        if ($userInput -match "(?:https?:\/\/|git@)?github\.com[:\/]([^\/]+)\/([^\/\s]+)") {
            $user = $matches[1]
            $repo = $matches[2] -replace "\.git$", ""
            $targetRemote = "https://github.com/$user/$repo.git"
        } elseif ($userInput -match "^https?:\/\/") {
            $targetRemote = $userInput
        } elseif ($userInput -match "^git@") {
            $targetRemote = $userInput
        } else {
            # Введен только логин
            $cleanUser = $userInput.Trim('@').Trim('/')
            $targetRemote = "https://github.com/$cleanUser/pocket-hud.git"
        }

        $allRemotes = git remote
        if ($allRemotes -contains "origin") {
            git remote set-url origin $targetRemote
        } else {
            git remote add origin $targetRemote
        }
        Write-Host "  -> Remote origin установлен: $targetRemote" -ForegroundColor Gray
    }
} else {
    Write-Host "  -> Remote origin уже настроен: $existingRemote" -ForegroundColor Gray
}

# 5. Отправка кода в GitHub
Write-Host "`n[4/4] Отправка кода в GitHub (git push -u origin main)..." -ForegroundColor Green

$pushSuccess = $false
$maxAttempts = 2
$attempt = 0

while (-not $pushSuccess -and $attempt -lt $maxAttempts) {
    $attempt++
    
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "git.exe"
    $pinfo.Arguments = "push -u origin main"
    $pinfo.RedirectStandardError = $true
    $pinfo.RedirectStandardOutput = $true
    $pinfo.UseShellExecute = $false
    $pinfo.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($pinfo)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    $combined = "$stdout`n$stderr"

    if ($proc.ExitCode -eq 0) {
        $pushSuccess = $true
        if ($stdout) { Write-Host $stdout }
        break
    }

    if ($combined -match "denied to\s+([^\.\s]+)") {
        $deniedUser = $matches[1]
        Write-Host "`n==========================================================" -ForegroundColor Red
        Write-Host " [ВНИМАНИЕ] Обнаружен конфликт учётных записей в Windows!" -ForegroundColor Yellow
        Write-Host "==========================================================" -ForegroundColor Red
        Write-Host " В Windows сохранён старый вход: $deniedUser" -ForegroundColor White
        Write-Host " А репозиторий создан под аккаунтом: AleksandrBrevnov" -ForegroundColor White
        Write-Host " GitHub отклонил отправку (HTTP 403 Forbidden)." -ForegroundColor DarkGray
        Write-Host "`nВыберите удобное решение:" -ForegroundColor Cyan
        Write-Host " 1. Сбросить вход '$deniedUser' (откроется окно входа AleksandrBrevnov) [Рекомендуется]" -ForegroundColor Yellow
        Write-Host " 2. Добавить '$deniedUser' в соавторы AleksandrBrevnov/pocket-hud на GitHub" -ForegroundColor White
        Write-Host " 3. Ввести GitHub Personal Access Token (PAT)" -ForegroundColor White

        $sol = Read-Host "`nВаш выбор (1/2/3) [по умолчанию 1]"
        if ([string]::IsNullOrWhiteSpace($sol) -or $sol -eq '1') {
            Write-Host "`nОчищаю сохранённые данные входа Git из Windows..." -ForegroundColor Yellow
            cmdkey /delete:LegacyGeneric:target=git:https://github.com 2>$null
            cmdkey /delete:git:https://github.com 2>$null
            Write-Host "Запускаю повторную отправку с интерактивной авторизацией..." -ForegroundColor Green
            Write-Host "Сейчас появится окно GitHub — нажмите 'Sign in with your browser' и подтвердите вход." -ForegroundColor Cyan
            & git.exe push -u origin main
            if ($LASTEXITCODE -eq 0) {
                $pushSuccess = $true
                break
            }
        } elseif ($sol -eq '2') {
            $collabUrl = "https://github.com/AleksandrBrevnov/pocket-hud/settings/access"
            Write-Host "`nОткрываю страницу доступа: $collabUrl" -ForegroundColor Cyan
            Start-Process $collabUrl
            Write-Host "В открывшемся окне нажмите 'Add people', найдите '$deniedUser' и подтвердите." -ForegroundColor Yellow
            Read-Host "После добавления нажмите Enter для отправки кода"
            & git.exe push -u origin main
            if ($LASTEXITCODE -eq 0) {
                $pushSuccess = $true
                break
            }
        } elseif ($sol -eq '3') {
            $token = (Read-Host "Вставьте Personal Access Token").Trim()
            if (-not [string]::IsNullOrWhiteSpace($token)) {
                $tokenUrl = "https://${token}@github.com/AleksandrBrevnov/pocket-hud.git"
                & git.exe push -u $tokenUrl main
                if ($LASTEXITCODE -eq 0) {
                    $pushSuccess = $true
                    break
                }
            }
        }
    } elseif ($combined -match "fetch first|Updates were rejected|non-fast-forward") {
        Write-Host "`n[ПОДСКАЗКА] На GitHub уже были файлы (например README)." -ForegroundColor Yellow
        $forcePush = Read-Host "Перезаписать репозиторий вашим кодом (git push -u origin main --force)? (y/N)"
        if ($forcePush -eq 'y' -or $forcePush -eq 'Y') {
            & git.exe push -u origin main --force
            if ($LASTEXITCODE -eq 0) {
                $pushSuccess = $true
                break
            }
        }
    } else {
        Write-Host $stderr -ForegroundColor Red
        & git.exe push -u origin main
        if ($LASTEXITCODE -eq 0) {
            $pushSuccess = $true
            break
        }
    }
}

if (-not $pushSuccess) {
    Write-Host "`n[ОШИБКА] Не удалось отправить код в GitHub." -ForegroundColor Red
    Write-Host "Команда для ручной попытки: git push -u origin main" -ForegroundColor Gray
    exit 1
}

# 6. Извлечение ссылок Actions и Releases
$repoUrlClean = ($targetRemote -replace "\.git$", "" -replace "^git@github\.com:", "https://github.com/").TrimEnd('/')
$actionsUrl = "$repoUrlClean/actions"
$releasesUrl = "$repoUrlClean/releases"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  УСПЕХ! Проект отправлен на GitHub!                      " -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "`nЧто происходит прямо сейчас:" -ForegroundColor Cyan
Write-Host "1. GitHub Actions автоматически собирает APK файл." -ForegroundColor White
Write-Host "   Следить за сборкой: $actionsUrl" -ForegroundColor Yellow
Write-Host "   (Сборка занимает около 1.5 - 2 минут)" -ForegroundColor DarkGray
Write-Host "`n2. Когда сборка завершится, готовый APK появится здесь:" -ForegroundColor Cyan
Write-Host "   Скачать APK:        $releasesUrl" -ForegroundColor Yellow
Write-Host "   (Если Releases пуст из-за прав токена: зайдите в Actions -> сборка -> внизу секция 'Artifacts')" -ForegroundColor DarkGray
Write-Host "`n3. Также доступен Web HUD для браузера телефона:" -ForegroundColor Cyan
Write-Host "   В GitHub репозитории: Settings -> Pages -> Source: Deploy from a branch -> main /docs -> Save" -ForegroundColor DarkGray

$openActions = Read-Host "`nОткрыть страницу сборки ($actionsUrl) в браузере? (Y/n)"
if ($openActions -ne 'n' -and $openActions -ne 'N') {
    Start-Process $actionsUrl
}
