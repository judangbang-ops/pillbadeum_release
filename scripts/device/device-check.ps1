<#
.SYNOPSIS
갤럭시 실기기 확인(TASK-052·TASK-032·BUG-004)에서 휴대폰을 USB adb로 읽고 눌러 결과를 파일로 남깁니다. 루트가 필요 없습니다.

.DESCRIPTION
동희님 PC에서 Claude가 돌립니다. 순서와 합격 기준은 docs/07-qa/device-check.md에 있고, 이 스크립트는 그 순서의
한 단계씩을 합니다. 결과는 -OutDir(기본: 저장소의 docs\07-qa\<오늘>-galaxy\raw)에 파일로 남고 요약을 화면에 씁니다.
보고서는 그 파일을 보고 Claude가 씁니다.

사람 손이 필요한 일(위젯 놓기, 잠금 풀기)은 묻지 않습니다. 스크립트는 휴대폰 상태를 지켜보다가 그 일이 끝나면
이어 갑니다. 그래서 Claude가 돌리기 전에 채팅으로 무엇을 할지 먼저 알립니다.

읽기만 하는 단계
  info     기기·Android·One UI 버전, 앱 버전, 알림·정확 알람·배터리 상태, 잠금 화면 알림 설정 값
  snap     캡처 + 화면 덤프 + 알림 목록. -Names를 주면 화면에 보이는 항목 이름과 버튼을 셈 (-Name은 파일 이름)
  alarms   이 앱의 알람 (dumpsys alarm)
  logcat   이 앱 로그, 이벤트 로그의 이 앱 줄, 앱이 죽은 기록
  night    -Phase start: 밤 측정 시작 상태를 남김. -Phase end -Slots ...: 밤사이 알림이 계획 칸보다 얼마나 늦었는지

휴대폰을 바꾸는 단계 (이름으로 부를 때만 함)
  install  -Apk를 깜. -Fresh를 주면 먼저 앱을 지움(그 폰의 이 앱 기록이 지워짐)
  exact    정확 알람을 -Mode allow 또는 deny로 바꿈 (appops). deny 뒤에는 앱을 한 번 열어야 알람을 다시 걺
  content  잠금 화면 알림 내용을 -Mode show(내용 표시) 또는 hide(내용 숨김)로 바꿈 (설정 값 1·0)
  lock     화면을 껐다 켜서 잠금 화면을 띄움. 잠금을 푸는 것은 사람
  shade    알림창을 -Mode open 또는 close
  tap      화면에서 글자나 설명이 -Text인 곳을 누름. -When HH:mm:ss를 주면 휴대폰 시계가 그 시각이 될 때 누름
  clock    휴대폰 시각을 -At 'yyyy-MM-dd HH:mm:ss'(휴대폰 시간대)로 맞춤(자동 시간 끔). -Mode auto면 자동 시간으로 되돌림
  additem  앱 첫 화면에서 -Names의 항목을 하나씩 만듦 ([항목 추가] → 이름 → [저장], 이름은 영문·숫자).
           처음 여는 앱이면 안내를 [건너뛰기]로 넘김
  remind   -Names의 항목마다 [알림 받기]를 켜고 저장함 (첫 알림 시각은 앱 기본값, 아침·없음이면 08:00).
           Android 13 이상이면 먼저 알림 권한을 줌 (pm grant)
  appnames 앱 설정의 [잠금 화면에 약 이름 보이기]를 -Mode show(켬) 또는 hide(끔)로 바꿈
  bug004   처음 놓는 위젯을 지켜봄: 사람이 위젯을 놓으면 3초·10초 뒤 화면과 로그를 남김 (install -Fresh 뒤에 부름)
  latency  홈 화면 위젯에서 항목 칸을 누르고 위젯 머리글 자리가 바뀔 때까지 시간을 잼 (기록이 생김).
           -Item 하나 또는 -Names 여럿 (-Count번을 이름 차례로 돌아가며 누름). 위젯에 안 보이면 위젯 목록을 밀어 찾음.
           -Process dead면 누르기 전에 앱 프로세스를 내림 (am kill, 위젯 작업이 끝날 때까지 최대 90초 되풀이)
  reboot   앞의 로그를 남기고 휴대폰을 다시 시작함. 사람이 잠금을 풀면 화면·알람·로그를 남김

비밀값을 쓰지 않습니다. 화면 잠금 PIN은 사람이 휴대폰에서 직접 넣고, 스크립트는 묻거나 적지 않습니다.
필요한 것: Android SDK platform-tools(adb). USB 디버깅을 허용한 휴대폰 한 대(여럿이면 -Serial).
Windows PowerShell 5.1과 PowerShell 7에서 돕니다.

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device\device-check.ps1 -Step info

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device\device-check.ps1 -Step snap -Name lock-off-show-locked -Names PillA,PillB,PillC,PillD,PillE

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device\device-check.ps1 -Step night -Phase end -Slots 00:30,01:00,01:15,01:30,01:45,02:00,02:15,02:30,02:45,03:15

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device\device-check.ps1 -Step latency -Names L01,L02,L03,L04,L05,L06,L07,L08,L09,L10 -Count 20 -Process dead
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('info', 'snap', 'alarms', 'logcat', 'night', 'install', 'exact', 'content', 'lock', 'shade', 'tap', 'clock',
        'additem', 'remind', 'appnames', 'bug004', 'latency', 'reboot')]
    [string]$Step,
    # 남길 파일 이름의 앞부분 (snap은 꼭 줌, reboot·logcat은 고를 수 있음). 영문·숫자·-·_만
    [string]$Name = '',
    # snap: 화면에서 찾을 항목 이름, additem·remind: 만들거나 알림을 켤 항목, latency: 누를 항목. 예: -Names PillA,PillB
    [string[]]$Names = @(),
    # exact: allow·deny, content·appnames: show·hide, shade: open·close, clock: auto
    [ValidateSet('allow', 'deny', 'show', 'hide', 'open', 'close', 'auto')]
    [string]$Mode,
    # tap: 누를 곳의 글자나 설명 (같은 것이 먼저, 없으면 들어 있는 것)
    [string]$Text = '',
    # tap: 휴대폰 시계가 이 시각(HH:mm:ss)이 되면 누름 (경계 직후 탭, RR-13). 30분 안의 시각만
    [string]$When = '',
    # clock: 맞출 휴대폰 시각 'yyyy-MM-dd HH:mm:ss' (휴대폰 시간대)
    [string]$At = '',
    # install: 깔 APK 파일
    [string]$Apk = '',
    # install: 깔기 전에 앱을 지움 (기록이 지워짐)
    [switch]$Fresh,
    # latency: 위젯에서 누를 항목 이름 하나 (여럿이면 -Names)
    [string]$Item = '',
    # latency: alive(앱 프로세스가 살아 있을 때) 또는 dead(누르기 전에 내림)
    [ValidateSet('alive', 'dead')]
    [string]$Process = 'alive',
    # latency: 몇 번 잴지. 두 번째부터는 같은 항목을 다시 눌러 "이미 기록됨"이 뜨는 시간을 잼 (12초 간격)
    [ValidateRange(1, 30)]
    [int]$Count = 1,
    # night: start 또는 end
    [ValidateSet('start', 'end')]
    [string]$Phase,
    # night end: 계획한 알림 칸 (HH:mm, 휴대폰 시각). 예: -Slots 00:30,01:00,01:30
    [string[]]$Slots = @(),
    # 휴대폰이 여럿 꽂혀 있을 때 adb 일련번호
    [string]$Serial = '',
    # 결과를 둘 폴더
    [string]$OutDir = ''
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$PackageName = 'com.judangbang.pillbadeum'
$RemoteDir = '/data/local/tmp/pillbadeum-check'
# 앱 문구 (shared/.../status/ReminderLabels.kt). 위젯 글자는 낱말 안 글자 사이에 U+2060을 넣으므로 지우고 비교함
$LockTitle = '복용 시간이에요'
$ButtonWords = @('먹었어', '30분 뒤', '오늘 건너뜀', '이번 주 건너뜀')
# 위젯에서 이미 기록된 칸을 누르면 머리글 자리에 10초 뜨는 띠 (Labels.recordMessage)
$AlreadyWord = '이미 기록됨'
$AlreadySkipWord = '건너뜀으로 기록됨'
# 그려 둔 날이 지난 위젯 칸을 누르면 뜨는 띠 (REQ-004, RR-13): 기록하지 않고 다시 그림
$StaleWord = '날짜가 바뀌었어요'
# 앱 화면 (shared/.../ui). 첫 화면 위 막대의 [지난 기록]은 첫 화면에만 있고 목록이 길어도 늘 보임
# (scripts/emulator/backup_restore_check.py의 HOME_MARK). [건너뛰기]는 처음 여는 앱의 안내(SCR-005)에만 있음
$HomeMark = '지난 기록'
$IntroSkip = '건너뛰기'
$SettingsButton = '설정'
$AddButton = '항목 추가'
$SaveButton = '저장'
# 스위치 줄(SwitchRow)은 화면 읽기 설명이 '<이름>' 또는 '<이름>. <설명>'이고 켬·끔 상태가 있음 (Labels.REMINDER_SWITCH 등)
$ReminderSwitch = '알림 받기'
$ReminderStart = '첫 알림 시각'
$NamesOnLockSwitch = '잠금 화면에 약 이름 보이기'
# 저장할 때 방해 금지 시간 때문에 다시 알림이 빠지면 뜨는 안내 (NoticeDialog, [확인])
$SavedTitle = '저장했어요'
$DoseChannel = 'dose_reminders'
# BUG-004: 앱이 시작할 때 켜 두는 WorkManager 수신기 (RescheduleReceiverKeeper)
$RescheduleReceiver = 'androidx.work.impl.background.systemalarm.RescheduleReceiver'
$AppLogTags = @('Reminders', 'ReminderAlarm', 'ReminderReceiver', 'DayBoundaryAlarm', 'DayBoundaryReceiver', 'DidITakeWidget',
    'WidgetChoices', 'WidgetPickActivity', 'WidgetPinnedReceiver', 'RescheduleReceiverKeeper', 'AppContainer', 'AndroidPhone', 'MainActivity',
    'DidITakeApplication', 'WM-WorkerWrapper', 'WM-SystemJobService', 'WM-Schedulers', 'AndroidRuntime')
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$WordJoiner = [string][char]0x2060
$EpochZero = New-Object DateTime 1970, 1, 1, 0, 0, 0, ([DateTimeKind]::Unspecified)

# ---------------------------------------------------------------------------------------------
# adb

function Find-Adb {
    $command = @(Get-Command 'adb' -CommandType Application -ErrorAction SilentlyContinue)
    if ($command.Count -gt 0) { return $command[0].Path }
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    foreach ($root in $roots) {
        if (-not $root) { continue }
        foreach ($file in @('platform-tools\adb.exe', 'platform-tools/adb')) {
            $candidate = Join-Path $root $file
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    throw 'adb를 찾지 못했습니다. Android SDK platform-tools를 깔고 PATH나 ANDROID_HOME을 확인하세요.'
}

function Invoke-Adb {
    # adb를 돌려 표준 출력과 오류를 한 글로 돌려줍니다. -AllowFail이 없으면 실패할 때 멈춥니다
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFail)
    $all = @()
    if ($script:SerialArgs.Count -gt 0) { $Arguments = $script:SerialArgs + $Arguments }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $all = @(& $script:Adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $text = (@($all | ForEach-Object { "$_" }) -join "`n")
    if ($exitCode -ne 0 -and -not $AllowFail) {
        throw ("adb {0} 실패 (종료 코드 {1}){2}{3}" -f ($Arguments -join ' '), $exitCode, [Environment]::NewLine, $text)
    }
    return $text
}

function Invoke-Shell {
    # 휴대폰 셸에서 명령 한 줄. 명령 글에 큰따옴표를 쓰지 않음 (Windows PowerShell 5.1이 인수의 큰따옴표를 망가뜨림)
    param([Parameter(Mandatory = $true)][string]$Command, [switch]$AllowFail)
    if ($Command.Contains('"')) { throw "휴대폰 명령에 큰따옴표를 쓰지 않습니다: $Command" }
    return (Invoke-Adb -Arguments @('shell', $Command) -AllowFail:$AllowFail)
}

function Get-Prop([string]$Key) {
    return (Invoke-Shell "getprop $Key" -AllowFail).Trim()
}

function Save-Text([string]$FileName, [string]$Text) {
    $path = Join-Path $script:OutPath $FileName
    [System.IO.File]::WriteAllText($path, $Text, $script:Utf8)
    return $path
}

function Save-DeviceOutput {
    # 휴대폰에서 돌린 명령의 출력을 휴대폰 파일로 쓰고 PC로 가져와 UTF-8로 읽음 (한글이 PC 콘솔 인코딩을 거치지 않게)
    param([Parameter(Mandatory = $true)][string]$Command, [Parameter(Mandatory = $true)][string]$FileName)
    $remote = "$RemoteDir/$FileName"
    Invoke-Shell "$Command > $remote 2>&1" -AllowFail | Out-Null
    $local = Join-Path $script:OutPath $FileName
    Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
    Invoke-Shell "rm -f $remote" -AllowFail | Out-Null
    return [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
}

function Save-Screenshot([string]$Stem) {
    $remote = "$RemoteDir/$Stem.png"
    Invoke-Shell "screencap -p $remote" | Out-Null
    $local = Join-Path $script:OutPath "$Stem.png"
    Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
    Invoke-Shell "rm -f $remote" -AllowFail | Out-Null
    return $local
}

function Get-DeviceTime {
    # 휴대폰 시각: 에폭 초와 logcat -T에 쓰는 'MM-DD hh:mm:ss.000' 꼴
    $out = (Invoke-Shell 'date +%s@%m-%d@%H:%M:%S').Trim()
    $parts = $out.Split('@')
    if ($parts.Count -ne 3) { throw "휴대폰 시각을 읽지 못했습니다: $out" }
    [pscustomobject]@{ Epoch = [long]$parts[0]; Logcat = ('{0} {1}.000' -f $parts[1], $parts[2]) }
}

function Get-PhoneOffset {
    # 휴대폰 시간대의 UTC 차이 (date +%z, 예: +0900)
    $zoneOffset = (Invoke-Shell 'date +%z').Trim()
    if ($zoneOffset -notmatch '^[+-]\d{4}$') { throw "휴대폰 시간대를 읽지 못했습니다: $zoneOffset" }
    $sign = if ($zoneOffset.StartsWith('-')) { -1 } else { 1 }
    return (New-Object TimeSpan ($sign * [int]$zoneOffset.Substring(1, 2)), ($sign * [int]$zoneOffset.Substring(3, 2)), 0)
}

function Get-Stem([string]$Value, [string]$Fallback) {
    $stem = if ($Value) { $Value } else { $Fallback }
    if ($stem -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw "-Name은 영문·숫자·-·_로 씁니다: $stem" }
    return $stem
}

function Test-PackageInstalled {
    return ((Invoke-Shell "pm list packages $PackageName" -AllowFail) -match ("(?m)^package:{0}\s*$" -f [regex]::Escape($PackageName)))
}

# ---------------------------------------------------------------------------------------------
# 화면 덤프 (uiautomator)

function Get-UiDump([string]$Stem) {
    # 화면 덤프 XML. 화면이 바뀌는 중이면 다시 시도함. 끝내 못 얻으면 $null
    $remote = "$RemoteDir/$Stem.xml"
    for ($i = 0; $i -lt 6; $i++) {
        $out = Invoke-Shell "uiautomator dump $remote" -AllowFail
        if ($out -match 'dumped') {
            $local = Join-Path $script:OutPath "$Stem.xml"
            Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
            Invoke-Shell "rm -f $remote" -AllowFail | Out-Null
            $text = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
            # 화면 덤프에 XML에 쓸 수 없는 제어 문자가 섞일 때가 있음
            $text = [regex]::Replace($text, '[\x00-\x08\x0B\x0C\x0E-\x1F]', '')
            try { return [xml]$text } catch { Start-Sleep -Seconds 1; continue }
        }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Get-Bounds($Node) {
    $m = [regex]::Match($Node.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
    if (-not $m.Success) { return $null }
    [pscustomobject]@{
        Left = [int]$m.Groups[1].Value; Top = [int]$m.Groups[2].Value
        Right = [int]$m.Groups[3].Value; Bottom = [int]$m.Groups[4].Value
    }
}

function Get-NodeText($Node) {
    return $Node.GetAttribute('text').Replace($WordJoiner, '')
}

function Get-NodeDesc($Node) {
    return $Node.GetAttribute('content-desc').Replace($WordJoiner, '')
}

function Read-Screen($Xml, [string[]]$Wanted) {
    # 화면 글자에 보이는 이름·버튼·"복용 시간이에요", 그리고 글자에는 없고 설명(content-desc, 화면 읽기 프로그램이 읽는 글)에만
    # 있는 이름. 알림 아이콘 설명("<앱> 알림: <제목>")에 이름이 들어가는 Android가 있어(보충 N-13 한계) 따로 셈
    $texts = New-Object System.Collections.Generic.List[string]
    $descs = New-Object System.Collections.Generic.List[string]
    if ($null -ne $Xml) {
        foreach ($node in $Xml.SelectNodes('//node')) {
            $t = Get-NodeText $node
            $d = Get-NodeDesc $node
            if ($t) { $texts.Add($t) }
            if ($d) { $descs.Add($d) }
        }
    }
    $textBlob = $texts -join "`n"
    $descBlob = $descs -join "`n"
    $inText = @($Wanted | Where-Object { $_ -and $textBlob.Contains($_) })
    $inDescOnly = @($Wanted | Where-Object { $_ -and -not $textBlob.Contains($_) -and $descBlob.Contains($_) })
    $buttons = @($ButtonWords | Where-Object { $textBlob.Contains($_) -or $descBlob.Contains($_) })
    [pscustomobject]@{
        Dumped     = ($null -ne $Xml)
        Names      = $inText
        DescOnly   = $inDescOnly
        Buttons    = $buttons
        LockTitle  = ($textBlob.Contains($LockTitle) -or $descBlob.Contains($LockTitle))
        Stale      = ($textBlob.Contains($StaleWord) -or $descBlob.Contains($StaleWord))
    }
}

# ---------------------------------------------------------------------------------------------
# 알림 목록 (dumpsys notification --noredact), scripts/emulator/reminder_check.py의 posted()와 같은 읽기

function Read-Notifications([string]$Dump) {
    $items = @()
    $parts = $Dump -split 'NotificationRecord\('
    for ($i = 1; $i -lt $parts.Count; $i++) {
        $part = $parts[$i]
        $lines = $part -split "`r?`n"
        $head = $lines[0]
        $pkg = [regex]::Match($head, '\bpkg=(\S+)')
        if (-not $pkg.Success -or $pkg.Groups[1].Value -ne $PackageName) { continue }
        # 이 기록의 줄은 들여 쓰여 있고, 다음 부분은 더 왼쪽에서 시작함
        $end = $lines.Count
        for ($j = 1; $j -lt $lines.Count; $j++) {
            $line = $lines[$j]
            if ($line.Trim() -and ($line.Length - $line.TrimStart().Length) -le 4) { $end = $j; break }
        }
        $body = ($lines[0..($end - 1)]) -join "`n"
        $id = [regex]::Match($head, '\bid=(-?\d+)')
        $tag = [regex]::Match($head, '\btag=(\S+)')
        $channel = [regex]::Match($head, '\bchannel=([^\s:]+)')
        $vis = [regex]::Match($head, '\bvis=(\w+)')
        $title = [regex]::Match($body, '(?m)^\s*android\.title=\w+ \((.*)\)\s*$')
        $actions = @([regex]::Matches($body, '(?m)^\s*\[\d+\] "(.*)" ->') | ForEach-Object { $_.Groups[1].Value })
        $items += [pscustomobject]@{
            Id        = if ($id.Success) { [int]$id.Groups[1].Value } else { -1 }
            Tag       = if ($tag.Success) { $tag.Groups[1].Value } else { 'null' }
            Channel   = if ($channel.Success) { $channel.Groups[1].Value } else { '?' }
            Vis       = if ($vis.Success) { $vis.Groups[1].Value } else { '?' }
            Public    = $head.Contains('publicVersion=')
            Title     = if ($title.Success) { $title.Groups[1].Value } else { '' }
            Actions   = $actions
            AutoGroup = [regex]::IsMatch($body, '(?m)^\s*flags=\S*\bAUTOGROUP_SUMMARY\b')
        }
    }
    # 같은 (tag, id)는 마지막 것 하나
    $seen = @{}
    foreach ($item in $items) { $seen["$($item.Tag)|$($item.Id)"] = $item }
    return @($seen.Values)
}

function Format-Notification($Item) {
    $label = if ($Item.AutoGroup) { 'Android 자동 묶음 요약' } elseif ($Item.Title) { $Item.Title } else { '(제목 없음)' }
    $public = if ($Item.Public) { ', 공개용 판' } else { '' }
    return ('{0} [{1}, {2}{3}, 버튼 {4}]' -f $label, $Item.Channel, $Item.Vis, $public, @($Item.Actions).Count)
}

function Get-KeyguardShowing {
    # 잠금 화면이 떠 있는지. Android 버전마다 dumpsys 글이 달라 차례로 봄 (scripts/emulator/reminder_check.py의
    # KEYGUARD_SOURCES와 같은 순서, API 26·30·34·36에서 확인됨). 모르면 $null
    $sources = @(
        @('dumpsys window policy', 'KeyguardServiceDelegate\s+showing=(true|false)'),
        @('dumpsys activity activities', '\bmKeyguardShowing=(true|false)'),
        @('dumpsys window', '\bmShowingLockscreen=(true|false)'),
        @('dumpsys window', '\bisStatusBarKeyguard=(true|false)')
    )
    $cache = @{}
    foreach ($source in $sources) {
        if (-not $cache.ContainsKey($source[0])) { $cache[$source[0]] = Invoke-Shell $source[0] -AllowFail }
        $m = [regex]::Match($cache[$source[0]], $source[1])
        if ($m.Success) { return ($m.Groups[1].Value -eq 'true') }
    }
    return $null
}

function Format-Bool($Value, [string]$Yes, [string]$No) {
    if ($null -eq $Value) { return '모름' }
    if ($Value) { return $Yes }
    return $No
}

# ---------------------------------------------------------------------------------------------
# 알람 (dumpsys alarm)

function Get-AppAlarms([string]$Dump, [TimeSpan]$Offset) {
    # 이 앱이 걸어 둔 알람 (scripts/emulator/reminder_check.py의 alarms()·PlannedAlarm과 같은 읽기). 시각은 머리 줄의
    # 에폭(when/origWhen)을 휴대폰 시간대로 바꾼 것: 글로 찍힌 when=은 Android 8에서 system_server의 시간대라 믿지 않음.
    # 정확 알람은 window=0. 비정확 길의 둘째 알람(setWindow, 10분 창)은 allow-while-idle 플래그(0x4·0x8·0x20)가 없음
    $lines = $Dump -split "`r?`n"
    $found = @()
    $i = 0
    while ($i -lt $lines.Count) {
        if ($lines[$i] -notmatch '^\s*(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED|ELAPSED_REALTIME_WAKEUP|ELAPSED_REALTIME) #\d+: Alarm\{') { $i++; continue }
        $indent = $lines[$i].Length - $lines[$i].TrimStart().Length
        $j = $i + 1
        while ($j -lt $lines.Count -and $lines[$j].Trim() -and ($lines[$j].Length - $lines[$j].TrimStart().Length) -gt $indent) { $j++ }
        $block = ($lines[$i..($j - 1)]) -join "`n"
        $i = $j
        if (-not $block.Contains($PackageName)) { continue }
        $action = [regex]::Match($block, '\btag=\*w?alarm\*:(\S+)')
        $millis = [regex]::Match($block, '\b(?:origWhen|when) (\d{12,})')
        $window = [regex]::Match($block, '\bwindow=(\S+)')
        $flags = [regex]::Match($block, '\bflags=0x([0-9a-f]+)')
        $reason = [regex]::Match($block, '\bexactAllowReason=(\S+)')
        $flagValue = if ($flags.Success) { [Convert]::ToInt32($flags.Groups[1].Value, 16) } else { 0 }
        $found += [pscustomobject]@{
            Action   = if ($action.Success) { $action.Groups[1].Value.Replace("$PackageName.action.", '') } else { '?' }
            When     = if ($millis.Success) { '{0:yyyy-MM-dd HH:mm:ss}' -f $script:EpochZero.AddMilliseconds([double]$millis.Groups[1].Value).Add($Offset) } else { '?' }
            Window   = if ($window.Success) { $window.Groups[1].Value } else { '?' }
            Exact    = ($window.Success -and $window.Groups[1].Value -eq '0')
            Windowed = (($flagValue -band 0x2C) -eq 0)
            Reason   = if ($reason.Success) { $reason.Groups[1].Value } else { '' }
        }
    }
    return $found
}

function Format-Alarm($Alarm) {
    $kind = if ($Alarm.Exact) { '정확' } elseif ($Alarm.Windowed) { '창 알람(setWindow)' } else { '비정확' }
    $reason = if ($Alarm.Reason) { ", exactAllowReason=$($Alarm.Reason)" } else { '' }
    return ('{0} {1}, {2} (window={3}{4})' -f $Alarm.Action, $Alarm.When, $kind, $Alarm.Window, $reason)
}

# ---------------------------------------------------------------------------------------------
# 단계

function Step-Info {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("PC 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    $lines.Add("휴대폰 시각: $((Invoke-Shell 'date').Trim())")
    foreach ($key in @('ro.product.manufacturer', 'ro.product.model', 'ro.product.device', 'ro.build.version.release',
            'ro.build.version.sdk', 'ro.build.version.oneui', 'ro.build.display.id', 'ro.build.version.security_patch',
            'persist.sys.timezone', 'persist.sys.locale')) {
        $lines.Add("${key}: $(Get-Prop $key)")
    }
    foreach ($pair in @(@('secure', 'lock_screen_show_notifications'), @('secure', 'lock_screen_allow_private_notifications'),
            @('system', 'font_scale'), @('global', 'auto_time'), @('global', 'auto_time_zone'))) {
        $value = (Invoke-Shell ("settings get {0} {1}" -f $pair[0], $pair[1]) -AllowFail).Trim()
        $lines.Add(("settings {0} {1}: {2}" -f $pair[0], $pair[1], $value))
    }
    $lock = Invoke-Shell 'dumpsys lock_settings' -AllowFail
    $credential = @([regex]::Matches($lock, '(?m)^\s*(CredentialType|Quality)\s*[:=]\s*(\S+)') | ForEach-Object { "$($_.Groups[1].Value)=$($_.Groups[2].Value)" })
    $lines.Add("화면 잠금 (dumpsys lock_settings): $(if ($credential.Count) { $credential -join ', ' } else { '읽지 못함' })")
    $battery = Invoke-Shell 'dumpsys battery' -AllowFail
    $level = [regex]::Match($battery, '(?m)^\s*level:\s*(\d+)')
    $plugged = @([regex]::Matches($battery, '(?m)^\s*(AC|USB|Wireless|Dock) powered:\s*(true|false)') | Where-Object { $_.Groups[2].Value -eq 'true' } | ForEach-Object { $_.Groups[1].Value })
    $lines.Add("배터리: $(if ($level.Success) { $level.Groups[1].Value + '%' } else { '?' }), 전원 $(if ($plugged.Count) { $plugged -join '·' } else { '안 꽂힘' })")
    if (Test-PackageInstalled) {
        $package = Invoke-Shell "dumpsys package $PackageName"
        foreach ($pattern in @('versionCode=\d+[^\r\n]*', 'versionName=\S+', 'firstInstallTime=[^\r\n]+', 'lastUpdateTime=[^\r\n]+', '\bflags=\[[^\]]*\]')) {
            $m = [regex]::Match($package, $pattern)
            if ($m.Success) { $lines.Add("앱 $($m.Value.Trim())") }
        }
        $postNotifications = [regex]::Match($package, 'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)')
        $lines.Add("앱 알림 권한 (POST_NOTIFICATIONS): $(if ($postNotifications.Success) { $postNotifications.Groups[1].Value } else { '항목 없음 (Android 12 이하는 권한 없이 켜짐)' })")
        $lines.Add("앱 정확 알람 (appops SCHEDULE_EXACT_ALARM): $((Invoke-Shell "appops get $PackageName SCHEDULE_EXACT_ALARM" -AllowFail).Trim())")
        $lines.Add("앱 백그라운드 (appops RUN_ANY_IN_BACKGROUND): $((Invoke-Shell "appops get $PackageName RUN_ANY_IN_BACKGROUND" -AllowFail).Trim())")
        $whitelist = Invoke-Shell 'dumpsys deviceidle whitelist' -AllowFail
        $lines.Add("앱 배터리 최적화 예외 목록: $(if ($whitelist.Contains($PackageName)) { '있음' } else { '없음' })")
        $lines.Add("앱 대기 버킷: $((Invoke-Shell "am get-standby-bucket $PackageName" -AllowFail).Trim())")
        $receiverOn = Test-ComponentEnabled $package $RescheduleReceiver
        $lines.Add("WorkManager RescheduleReceiver 켜짐 (BUG-004): $receiverOn")
        $jobs = @((Invoke-Shell 'dumpsys jobscheduler' -AllowFail) -split "`r?`n" | Where-Object { $_.Contains($PackageName) })
        $lines.Add("JobScheduler에 이 앱 줄: $($jobs.Count)개")
    } else {
        $lines.Add("앱: 깔려 있지 않음 ($PackageName)")
    }
    $text = $lines -join [Environment]::NewLine
    $stem = Get-Stem $Name 'info'
    $path = Save-Text "$stem.txt" $text
    Write-Output $text
    Write-Output "남김: $path"
}

function Test-ComponentEnabled([string]$PackageDump, [string]$Component) {
    # dumpsys package의 enabledComponents 아래에 그 이름이 있는지 (scripts/emulator/release_check.py와 같은 읽기)
    $lines = $PackageDump -split "`r?`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -ne 'enabledComponents:') { continue }
        $indent = $lines[$i].Length - $lines[$i].TrimStart().Length
        for ($j = $i + 1; $j -lt $lines.Count; $j++) {
            $item = $lines[$j]
            if (-not $item.Trim() -or ($item.Length - $item.TrimStart().Length) -le $indent) { break }
            if ($item.Trim() -eq $Component) { return $true }
        }
    }
    return $false
}

function Step-Snap {
    if (-not $Name) { throw 'snap에는 -Name을 줍니다 (예: -Name lock-off-show-locked)' }
    $stem = Get-Stem $Name ''
    $keyguard = Get-KeyguardShowing
    $png = Save-Screenshot $stem
    $xml = Get-UiDump $stem
    $dump = Save-DeviceOutput 'dumpsys notification --noredact' "$stem-notifications.txt"
    $screen = Read-Screen $xml $Names
    $posted = @(Read-Notifications $dump)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("캡처: $stem ($(Get-Date -Format 'HH:mm:ss'))")
    $lines.Add("잠금 화면: $(Format-Bool $keyguard '떠 있음' '없음')")
    if (-not $screen.Dumped) {
        $lines.Add('화면 덤프: 얻지 못함 (캡처 그림으로 판정)')
    } else {
        $lines.Add("화면 글자의 항목 이름: $(if ($screen.Names.Count) { $screen.Names -join ', ' } else { '없음' })")
        $lines.Add("글자에는 없고 설명에만 있는 이름: $(if ($screen.DescOnly.Count) { $screen.DescOnly -join ', ' } else { '없음' })")
        $lines.Add("알림 버튼 글자: $(if ($screen.Buttons.Count) { $screen.Buttons -join ', ' } else { '없음' })")
        $lines.Add("'$LockTitle': $(if ($screen.LockTitle) { '보임' } else { '없음' })")
        if ($screen.Stale) { $lines.Add("'$StaleWord' 띠: 보임 (RR-13: 기록하지 않고 다시 그림)") }
    }
    $lines.Add("이 앱 알림 $($posted.Count)개$(if ($posted.Count) { ': ' + (($posted | ForEach-Object { Format-Notification $_ }) -join '; ') })")
    if (@($posted | Where-Object { $_.AutoGroup }).Count -gt 0) {
        $lines.Add('Android가 이 앱 알림을 자기 묶음(AUTOGROUP_SUMMARY)으로 바꿈 (보충 N-13 한계)')
    }
    $text = $lines -join [Environment]::NewLine
    $path = Save-Text "$stem-summary.txt" $text
    Write-Output $text
    Write-Output "남김: $png, $path"
}

function Step-Alarms {
    $stem = Get-Stem $Name 'alarms'
    $dump = Save-DeviceOutput 'dumpsys alarm' "$stem-dumpsys.txt"
    $mine = @(Get-AppAlarms $dump (Get-PhoneOffset))
    $exact = (Invoke-Shell "appops get $PackageName SCHEDULE_EXACT_ALARM" -AllowFail).Trim()
    $lines = @("이 앱 알람 $($mine.Count)개 (휴대폰 시각 $((Invoke-Shell 'date +%H:%M:%S').Trim()), 정확 알람 권한: $exact)")
    $lines += @($mine | ForEach-Object { '  ' + (Format-Alarm $_) })
    $text = $lines -join [Environment]::NewLine
    $path = Save-Text "$stem.txt" $text
    Write-Output $text
    Write-Output "남김: $path (dumpsys 원본 $stem-dumpsys.txt)"
}

function Step-Exact {
    if (-not $Mode) { throw 'exact에는 -Mode allow 또는 -Mode deny를 줍니다' }
    $sdk = [int](Get-Prop 'ro.build.version.sdk')
    if ($sdk -lt 31) {
        Write-Output "Android API $sdk 는 정확 알람 허용이 없어(늘 허용) 바꾸지 않습니다."
        return
    }
    Invoke-Shell "appops set $PackageName SCHEDULE_EXACT_ALARM $Mode" | Out-Null
    $now = (Invoke-Shell "appops get $PackageName SCHEDULE_EXACT_ALARM" -AllowFail).Trim()
    Write-Output "정확 알람: $now"
    if ($Mode -eq 'deny') {
        # 거부하면 Android가 이 앱의 정확 알람을 지우고, 앱은 거부 방송을 받지 않음. 앱은 프로세스가 시작할 때 다시 계산하므로
        # 앱을 멈췄다가(떠 있는 알림도 지워짐) 한 번 열고 홈으로 돌아옴
        Invoke-Shell "am force-stop $PackageName" -AllowFail | Out-Null
        Invoke-Shell "monkey -p $PackageName -c android.intent.category.LAUNCHER 1" -AllowFail | Out-Null
        Start-Sleep -Seconds 5
        Invoke-Shell 'input keyevent KEYCODE_HOME' -AllowFail | Out-Null
        Write-Output '앱을 멈췄다가 한 번 열어 알람을 다시 걸게 했습니다. -Step alarms로 비정확 알람과 10분 창 알람을 확인합니다.'
    } else {
        # 허용하면 앱이 권한 바뀜 방송을 받아 다시 계산함. 바로 다음 alarms가 계산 전 알람을 읽지 않게 잠깐 기다림
        Start-Sleep -Seconds 3
        Write-Output '허용하면 앱이 권한 바뀜 방송을 받아 다시 계산합니다. -Step alarms로 정확 알람 하나를 확인합니다.'
    }
}

function Step-Content {
    # 기기의 잠금 화면 알림 설정: 1 '내용 표시', 0 '내용 숨김' (scripts/emulator/reminder_check.py의 여덟 칸과 같은 값).
    # One UI 설정 화면이 같은 값을 보이는지는 처음 한 번 설정 화면 캡처로 봄 (docs/07-qa/device-check.md)
    if ($Mode -notin @('show', 'hide')) { throw 'content에는 -Mode show(내용 표시) 또는 -Mode hide(내용 숨김)를 줍니다' }
    $value = if ($Mode -eq 'show') { '1' } else { '0' }
    Invoke-Shell "settings put secure lock_screen_allow_private_notifications $value" | Out-Null
    $now = (Invoke-Shell 'settings get secure lock_screen_allow_private_notifications' -AllowFail).Trim()
    $shown = (Invoke-Shell 'settings get secure lock_screen_show_notifications' -AllowFail).Trim()
    Write-Output "잠금 화면 알림 내용: $now (1 내용 표시, 0 내용 숨김). 잠금 화면에 알림 보이기: $shown (1 보임)"
    if ($now -ne $value) { throw '설정 값이 바뀌지 않았습니다. 휴대폰 설정 화면에서 바꿉니다.' }
}

function Step-Lock {
    # 화면을 껐다 켬. 화면 잠금(PIN 등)이 있고 자동 잠금이 '즉시'면 잠금 화면이 뜸. 잠금을 푸는 것은 사람
    Invoke-Shell 'input keyevent KEYCODE_SLEEP' | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Start-Sleep -Seconds 2
    $showing = Get-KeyguardShowing
    Write-Output "잠금 화면: $(Format-Bool $showing '떠 있음' '없음')"
    if ($showing -ne $true) { throw '화면을 껐다 켰는데 잠금 화면이 없습니다. 휴대폰의 화면 잠금(PIN)과 자동 잠금(즉시)을 봅니다.' }
}

function Step-Shade {
    if ($Mode -notin @('open', 'close')) { throw 'shade에는 -Mode open 또는 -Mode close를 줍니다' }
    $command = if ($Mode -eq 'open') { 'cmd statusbar expand-notifications' } else { 'cmd statusbar collapse' }
    $out = (Invoke-Shell $command -AllowFail).Trim()
    Start-Sleep -Seconds 2
    Write-Output ("알림창 {0}{1}" -f $(if ($Mode -eq 'open') { '엶' } else { '닫음' }), $(if ($out) { " ($out)" } else { '' }))
}

function Step-Tap {
    # 화면 덤프에서 글자(없으면 설명)가 -Text와 같은 노드, 없으면 -Text가 든 노드를 찾아 그 노드나 누를 수 있는
    # 조상의 가운데를 누름. 잠금 화면에서도 됨
    if (-not $Text) { throw 'tap에는 -Text를 줍니다' }
    $stem = Get-Stem $Name ('tap-{0}' -f (Get-Date -Format 'HHmmss'))
    $xml = Get-UiDump $stem
    if ($null -eq $xml) { throw '화면 덤프를 얻지 못했습니다.' }
    $hit = Find-Node $xml $Text
    if ($null -eq $hit) { throw "화면에서 '$Text'를 찾지 못했습니다 (덤프: $stem.xml)." }
    $bounds = Get-Bounds (Find-Clickable $hit)
    $x = [int](($bounds.Left + $bounds.Right) / 2)
    $y = [int](($bounds.Top + $bounds.Bottom) / 2)
    if (-not $When) {
        Invoke-Shell "input tap $x $y" | Out-Null
        Write-Output "'$Text' 누름 ($x, $y)"
        return
    }
    if ($When -notmatch '^\d{2}:\d{2}:\d{2}$') { throw "-When은 HH:mm:ss로 씁니다: $When" }
    # 휴대폰 안에서 시계를 0.05초마다 보다가 그 시각이 되면 누름 (최대 30분). 누른 뒤의 휴대폰 시각을 돌려줌
    $wait = "n=0; until [ `$(date +%H:%M:%S) = $When ] || [ `$n -ge 36000 ]; do sleep 0.05; n=`$((n+1)); done; input tap $x $y; date +%H:%M:%S; echo n=`$n"
    $out = (Invoke-Shell $wait).Trim()
    if ($out -match 'n=36000') { throw "30분 안에 휴대폰 시계가 $When 이 되지 않았습니다: $out" }
    Write-Output "'$Text' 누름 ($x, $y), 누른 뒤 휴대폰 시각 $(($out -split "`r?`n")[0])"
}

function Find-Node($Xml, [string]$Wanted, [switch]$Exact) {
    # 글자가 같은 노드, 없으면 설명이 같은 노드, -Exact가 아니면 글자나 설명에 든 노드
    if ($null -eq $Xml) { return $null }
    $nodes = @($Xml.SelectNodes('//node'))
    $hit = $nodes | Where-Object { (Get-NodeText $_).Trim() -eq $Wanted } | Select-Object -First 1
    if ($null -eq $hit) { $hit = $nodes | Where-Object { (Get-NodeDesc $_).Trim() -eq $Wanted } | Select-Object -First 1 }
    if ($null -eq $hit -and -not $Exact) {
        $hit = $nodes | Where-Object { (Get-NodeText $_).Contains($Wanted) -or (Get-NodeDesc $_).Contains($Wanted) } | Select-Object -First 1
    }
    return $hit
}

function Wait-Node([string]$Wanted, [int]$Seconds, [switch]$Exact) {
    # 화면에 나올 때까지 덤프를 다시 봄. 끝내 없으면 $null
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $hit = Find-Node (Get-UiDump 'wait') $Wanted -Exact:$Exact
        if ($null -ne $hit) { return $hit }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Invoke-TapNode($Node) {
    $bounds = Get-Bounds (Find-Clickable $Node)
    Invoke-Shell ("input tap {0} {1}" -f [int](($bounds.Left + $bounds.Right) / 2), [int](($bounds.Top + $bounds.Bottom) / 2)) | Out-Null
}

# ---------------------------------------------------------------------------------------------
# 앱 화면 다루기. 여기 함수는 값을 돌려주므로 안내 글은 Write-Host로 씀 (Write-Output은 돌려주는 값에 섞임)

function Get-ScreenSignature($Xml) {
    # 화면이 바뀌었는지 볼 때 쓰는 덤프 요약 (노드의 자리·글자·설명)
    if ($null -eq $Xml) { return '' }
    return (@($Xml.SelectNodes('//node') | ForEach-Object { '{0}|{1}|{2}' -f $_.GetAttribute('bounds'), $_.GetAttribute('text'), $_.GetAttribute('content-desc') }) -join "`n")
}

function Get-ScrollArea($Xml) {
    # 밀 곳: 덤프에서 밀 수 있는(scrollable) 가장 큰 노드. 없으면(내용이 한 화면에 다 들어감) $null.
    # 화면 전체를 밀지 않음: 화면 키보드 위를 밀면 글자가 들어갈 수 있음 (adjustResize라 앱의 밀 곳은 키보드 위에 있음)
    if ($null -eq $Xml) { return $null }
    $areas = @($Xml.SelectNodes('//node') | Where-Object { $_.GetAttribute('scrollable') -eq 'true' } | ForEach-Object { Get-Bounds $_ } |
        Where-Object { $null -ne $_ -and ($_.Bottom - $_.Top) -gt 200 })
    if ($areas.Count -eq 0) { return $null }
    return ($areas | Sort-Object { ($_.Right - $_.Left) * ($_.Bottom - $_.Top) } -Descending | Select-Object -First 1)
}

function Invoke-Swipe($Area, [string]$Toward) {
    # $Area 안 가운데 세로줄을 천천히 밂. down: 아래쪽 내용을 봄(손가락을 위로), up: 위쪽 내용을 봄
    $x = [int](($Area.Left + $Area.Right) / 2)
    $low = [int]($Area.Top + ($Area.Bottom - $Area.Top) * 0.75)
    $high = [int]($Area.Top + ($Area.Bottom - $Area.Top) * 0.3)
    if ($Toward -eq 'down') { Invoke-Shell "input swipe $x $low $x $high 600" | Out-Null } else { Invoke-Shell "input swipe $x $high $x $low 600" | Out-Null }
    Start-Sleep -Milliseconds 800
}

function Find-WithScroll([scriptblock]$Finder, [int]$Swipes = 10) {
    # 화면 덤프를 & $Finder <덤프>로 찾음. 없으면 밀어서 아래쪽을, 끝(밀어도 같은 화면)에 닿으면 위쪽을 보며 다시 찾음.
    # uiautomator 덤프에는 화면에 보이는 노드만 있어서 목록이 길면 밀어야 함. 끝내 없으면 $null.
    # $Finder 안의 변수는 부른 쪽 변수를 읽음 (PowerShell의 동적 범위). 그래서 여기 지역 변수 이름은 scroll로 시작함
    foreach ($scrollToward in @('down', 'up')) {
        $scrollPrevious = $null
        for ($scrollStep = 0; $scrollStep -le $Swipes; $scrollStep++) {
            $scrollPage = Get-UiDump 'work'
            if ($null -eq $scrollPage) { return $null }
            $scrollHit = & $Finder $scrollPage
            if ($null -ne $scrollHit) { return $scrollHit }
            $scrollArea = Get-ScrollArea $scrollPage
            if ($null -eq $scrollArea) { return $null }
            $scrollSignature = Get-ScreenSignature $scrollPage
            if ($scrollSignature -eq $scrollPrevious) { break }
            $scrollPrevious = $scrollSignature
            Invoke-Swipe $scrollArea $scrollToward
        }
    }
    return $null
}

function Find-AppTile($Xml, [string]$ItemName) {
    # 첫 화면의 항목 칸 (HomeScreen.kt의 Tile, 누르면 항목 수정). 칸 오른쪽 위 [⋯]의 설명 '<이름> 판정 고치기'로 찾고
    # 그 왼쪽(이름 줄)을 누를 자리로 돌려줌. '이번 주 약통' 표에도 이름 글자가 있어 이름 글자로는 찾지 않음.
    # 화면 아래 끝에 걸린 칸은 몸짓 막대와 겹칠 수 있어 없는 것으로 봄 (밀어서 다시 찾음)
    if ($null -eq $Xml) { return $null }
    $wanted = "$ItemName 판정 고치기"
    $more = @($Xml.SelectNodes('//node') | Where-Object { (Get-NodeDesc $_).Trim() -eq $wanted }) | Select-Object -First 1
    if ($null -eq $more) { return $null }
    $b = Get-Bounds $more
    $screen = Get-Bounds $Xml.SelectSingleNode('//node')
    if ($null -eq $b -or ($null -ne $screen -and $b.Bottom -gt $screen.Bottom - 200)) { return $null }
    [pscustomobject]@{ X = [int]($b.Left - ($b.Right - $b.Left)); Y = [int](($b.Top + $b.Bottom) / 2) }
}

function Find-Switch($Xml, [string]$Label) {
    # 이름이 $Label인 스위치 줄(SwitchRow): 설명이 '<이름>' 또는 '<이름>. <설명>'이고 켬·끔이 있는(checkable) 노드.
    # 설정을 불러오는 중에는 설명이 '<이름>, 불러오는 중…'이고 checkable이 아니어서 찾지 않음
    if ($null -eq $Xml) { return $null }
    return (@($Xml.SelectNodes('//node') | Where-Object {
                $d = (Get-NodeDesc $_).Trim()
                ($d -eq $Label -or $d.StartsWith("$Label.")) -and $_.GetAttribute('checkable') -eq 'true'
            }) | Select-Object -First 1)
}

function Find-StartTime($Xml) {
    # 항목 화면의 첫 알림 시각 버튼: 설명 '첫 알림 시각, 8시' (Labels.reminderStartSpoken), 글자 '08:00'
    if ($null -eq $Xml) { return $null }
    return (@($Xml.SelectNodes('//node') | Where-Object { (Get-NodeDesc $_).Trim().StartsWith("$ReminderStart,") }) | Select-Object -First 1)
}

function Wait-Unlocked([int]$Seconds = 120) {
    # 잠금 화면이 떠 있으면 사람이 풀 때까지 기다림. 앱 화면을 누르는 단계 앞에서 부름
    if ((Get-KeyguardShowing) -ne $true) { return }
    Write-Host "잠금 화면이 떠 있습니다. 휴대폰 잠금을 풀어 주세요 ($([int]($Seconds / 60))분 기다림)."
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-KeyguardShowing) -eq $true) {
        if ((Get-Date) -gt $deadline) { throw "$([int]($Seconds / 60))분 안에 잠금이 풀리지 않았습니다." }
        Start-Sleep -Seconds 2
    }
    Start-Sleep -Seconds 1
}

function Open-AppHome {
    # 앱을 앞으로 띄우고 첫 화면을 기다림 (위 막대에 [지난 기록]이 있는 화면). 처음 여는 앱이면 안내(SCR-005)를
    # [건너뛰기]로 넘기고, 설정·지난 기록 같은 화면이면 위 막대의 [뒤로]를 누름. 항목·대상자 화면([닫기])에서는 멈춤:
    # 쓰던 내용이 있을 수 있어 사람이 정함. 앱이 앞에 없으면 다시 띄움. 첫 화면 덤프를 돌려줌
    Wait-Unlocked
    $launches = 0
    $backs = 0
    $launch = $true
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ($launch) {
            if ($launches -ge 3) { break }
            Invoke-Shell "monkey -p $PackageName -c android.intent.category.LAUNCHER 1" -AllowFail | Out-Null
            $launches++
            $launch = $false
        }
        Start-Sleep -Seconds 2
        $page = Get-UiDump 'work'
        if ($null -eq $page) { continue }
        if ($null -ne (Find-Node $page $HomeMark -Exact)) { return $page }
        $skip = Find-Node $page $IntroSkip -Exact
        if ($null -ne $skip) { Invoke-TapNode $skip; continue }
        $mine = @($page.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq $PackageName })
        if ($mine.Count -eq 0) { $launch = $true; continue }
        if ($null -ne (Find-Node $page $SavedTitle -Exact)) { Invoke-TapNode (Find-Node $page '확인' -Exact); continue }
        if (@($mine | Where-Object { (Get-NodeDesc $_).Trim() -eq '닫기' }).Count -gt 0) {
            throw "앱에 항목이나 대상자 화면이 열려 있습니다. 휴대폰에서 [닫기]로 첫 화면에 돌아온 뒤 다시 부릅니다."
        }
        $back = @($mine | Where-Object { (Get-NodeDesc $_).Trim() -eq '뒤로' }) | Select-Object -First 1
        if ($null -ne $back -and $backs -lt 4) { Invoke-TapNode $back; $backs++ }
    }
    throw "앱 첫 화면($HomeMark 버튼이 있는 화면)이 열리지 않았습니다 (덤프: work.xml). 휴대폰에서 앱 첫 화면을 띄운 뒤 다시 부릅니다."
}

function Wait-HomeAfterSave {
    # [저장] 뒤 첫 화면이 올 때까지 봄. 방해 금지 시간 때문에 다시 알림이 빠지면 '저장했어요' 안내가 떠서 [확인]을 누름.
    # 그 안내 글을 돌려줌 (없으면 빈 글). 20초 안에 첫 화면이 오지 않으면 멈춤
    $notice = ''
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Seconds 1
        $page = Get-UiDump 'work'
        if ($null -eq $page) { continue }
        if ($null -ne (Find-Node $page $HomeMark -Exact)) { return $notice }
        if ($null -ne (Find-Node $page $SavedTitle -Exact)) {
            $notice = (@($page.SelectNodes('//node') | ForEach-Object { (Get-NodeText $_).Trim() } | Where-Object { $_ -and $_ -ne $SavedTitle -and $_ -ne '확인' }) -join ' ')
            $ok = Find-Node $page '확인' -Exact
            if ($null -ne $ok) { Invoke-TapNode $ok }
        }
    } while ((Get-Date) -lt $deadline)
    throw '저장 뒤 첫 화면으로 돌아오지 않았습니다 (덤프: work.xml).'
}

function Hide-Keyboard {
    # 화면 키보드가 떠 있으면 내림 (뒤로 키는 키보드만 닫음). 떠 있는지 모르면 누르지 않음: 뒤로 키가 화면을 닫을 수 있음
    $ime = Invoke-Shell 'dumpsys input_method' -AllowFail
    if ($ime -match '\bmInputShown=true\b') {
        Invoke-Shell 'input keyevent KEYCODE_BACK' | Out-Null
        Start-Sleep -Seconds 1
    }
}

function Remove-WorkDumps {
    # 찾는 동안 쓴 덤프는 단계가 끝나면 지움 (멈춘 단계의 덤프는 원인을 보려고 남김)
    foreach ($file in @('work.xml', 'wait.xml')) {
        Remove-Item -LiteralPath (Join-Path $script:OutPath $file) -ErrorAction SilentlyContinue
    }
}

function Step-AddItem {
    # 앱 첫 화면에서 [항목 추가] → 이름 → [저장]을 -Names마다 (scripts/emulator/backup_restore_check.py의 add_item과 같은 길).
    # 이름은 영문·숫자만 (adb input text는 한글을 못 넣음). 알림·일정은 앱 기본값(매일, 알림 끔). 목록이 길면 밀어서 찾음
    if ($Names.Count -eq 0) { throw 'additem에는 -Names를 줍니다 (예: -Names A01,A02)' }
    foreach ($itemName in $Names) {
        if ($itemName -notmatch '^[A-Za-z0-9]+$') { throw "항목 이름은 영문·숫자만 씁니다: $itemName" }
    }
    $made = @()
    foreach ($itemName in $Names) {
        $done = "만든 것: $(if ($made.Count) { $made -join ', ' } else { '없음' })"
        Open-AppHome | Out-Null
        $add = Find-WithScroll { param($page) Find-Node $page $AddButton -Exact }
        if ($null -eq $add) { throw "첫 화면에서 '$AddButton' 버튼을 찾지 못했습니다. $done" }
        Invoke-TapNode $add
        Start-Sleep -Seconds 2
        $form = Get-UiDump 'work'
        $field = $null
        if ($null -ne $form) { $field = @($form.SelectNodes('//node') | Where-Object { $_.GetAttribute('class') -like '*EditText' }) | Select-Object -First 1 }
        if ($null -eq $field) { throw "이름 칸을 찾지 못했습니다. $done" }
        Invoke-TapNode $field
        Invoke-Shell "input text $itemName" | Out-Null
        # [저장]은 화면 아래 막대에 있어 키보드 위에 보임 (ItemScreen.kt)
        $save = Wait-Node $SaveButton 20 -Exact
        if ($null -eq $save) { throw "'$SaveButton' 버튼이 화면에 없습니다. $done" }
        Invoke-TapNode $save
        Wait-HomeAfterSave | Out-Null
        if ($null -eq (Find-WithScroll { param($page) Find-AppTile $page $itemName })) { throw "$itemName 칸이 첫 화면에 나오지 않았습니다. $done" }
        $made += $itemName
        Write-Output "항목 추가: $itemName"
    }
    Remove-WorkDumps
    Write-Output "만든 항목 $($made.Count)개: $($made -join ', ')"
}

function Step-Remind {
    # -Names의 항목마다 첫 화면의 칸 → 항목 수정 → [알림 받기] 켬 → [저장]. 첫 알림 시각은 앱 기본값(아침·없음 08:00,
    # 점심 12:00, 저녁 18:00, 자기 전 21:00, Reminders.kt의 defaultReminderStart). 다른 시각은 사람이 앱에서 고름:
    # 시각 고르기 창은 Android가 그려 휴대폰마다 달라 스크립트가 누르지 않음
    if ($Names.Count -eq 0) { throw 'remind에는 -Names를 줍니다 (알림을 켤 항목 이름)' }
    $sdk = [int](Get-Prop 'ro.build.version.sdk')
    if ($sdk -ge 33) {
        # 앱이 [알림 받기]를 켤 때 띄우는 알림 권한 창을 건너뜀 (사람이 '허용'을 누른 것과 같음)
        Invoke-Shell "pm grant $PackageName android.permission.POST_NOTIFICATIONS" | Out-Null
        Write-Output "알림 권한(POST_NOTIFICATIONS)을 줌 (Android API $sdk)"
    }
    $lines = @()
    foreach ($itemName in $Names) {
        $done = "끝낸 것: $(if ($lines.Count) { ($lines | ForEach-Object { $_.Split(':')[0] }) -join ', ' } else { '없음' })"
        Open-AppHome | Out-Null
        $tile = Find-WithScroll { param($page) Find-AppTile $page $itemName }
        if ($null -eq $tile) { throw "첫 화면에서 '$itemName' 칸을 찾지 못했습니다. $done" }
        Invoke-Shell "input tap $($tile.X) $($tile.Y)" | Out-Null
        Start-Sleep -Seconds 2
        Hide-Keyboard
        $toggle = Find-WithScroll { param($page) Find-Switch $page $ReminderSwitch }
        if ($null -eq $toggle) { throw "'$itemName' 항목 화면에서 '$ReminderSwitch' 스위치를 찾지 못했습니다 (덤프: work.xml). $done" }
        $was = ($toggle.GetAttribute('checked') -eq 'true')
        if (-not $was) {
            Invoke-TapNode $toggle
            Start-Sleep -Seconds 2
            $toggle = Find-WithScroll { param($page) Find-Switch $page $ReminderSwitch }
            if ($null -eq $toggle -or $toggle.GetAttribute('checked') -ne 'true') {
                throw "'$itemName'의 '$ReminderSwitch'가 켜지지 않았습니다. 알림 권한 창이 떴는지 휴대폰을 봅니다. $done"
            }
        }
        $startNode = Find-WithScroll { param($page) Find-StartTime $page }
        $start = '?'
        if ($null -ne $startNode) {
            $clock = (Get-NodeText $startNode).Trim()
            # 설명 '첫 알림 시각, 8시'에서 '8시' (Labels.clockSpoken: '8시', '13시 30분')
            $spoken = (Get-NodeDesc $startNode).Trim().Substring($ReminderStart.Length + 1).Trim()
            if (-not $clock -and $spoken -match '^(\d{1,2})시(?: (\d{1,2})분)?$') {
                # 버튼의 설명이 글자 '08:00'을 덮어 덤프에 글자가 없음 (API 34·36 에뮬레이터, 실행 43391459701): 설명을 시각으로 바꿈
                $minute = if ($Matches[2]) { [int]$Matches[2] } else { 0 }
                $clock = '{0:D2}:{1:D2}' -f [int]$Matches[1], $minute
            }
            $start = if ($clock) { "$clock ('$spoken')" } else { "'$spoken'" }
        }
        $save = Wait-Node $SaveButton 20 -Exact
        if ($null -eq $save) { throw "'$SaveButton' 버튼이 화면에 없습니다. $done" }
        Invoke-TapNode $save
        $notice = Wait-HomeAfterSave
        $line = "${itemName}: 알림 받기 $(if ($was) { '이미 켜져 있었음' } else { '켬' }), 첫 알림 시각 $start$(if ($notice) { ", 안내 '$notice'" })"
        $lines += $line
        Write-Output $line
    }
    Remove-WorkDumps
    $path = Save-Text 'remind.txt' ($lines -join [Environment]::NewLine)
    Write-Output "남김: $path. 알람은 -Step alarms로 봅니다."
}

function Step-AppNames {
    # 앱 설정의 [잠금 화면에 약 이름 보이기] (SPEC-034, 처음은 끔)를 -Mode show(켬)·hide(끔)로. 바꾸면 앱이 떠 있는 알림을
    # 새 공개 범위로 다시 올림 (TASK-025). 잠금 화면 여덟 칸(TASK-052)의 '이름 표시' 축
    if ($Mode -notin @('show', 'hide')) { throw 'appnames에는 -Mode show(약 이름 보이기 켬) 또는 -Mode hide(끔)를 줍니다' }
    $want = ($Mode -eq 'show')
    $page = Open-AppHome
    $settings = Find-Node $page $SettingsButton -Exact
    if ($null -eq $settings) { throw "첫 화면에 '$SettingsButton' 버튼이 없습니다." }
    Invoke-TapNode $settings
    $toggle = $null
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Seconds 1
        $toggle = Find-WithScroll { param($p) Find-Switch $p $NamesOnLockSwitch }
    } while ($null -eq $toggle -and (Get-Date) -lt $deadline)
    if ($null -eq $toggle) { throw "설정 화면에서 '$NamesOnLockSwitch' 스위치를 찾지 못했습니다 (덤프: work.xml)." }
    $was = ($toggle.GetAttribute('checked') -eq 'true')
    if ($was -ne $want) {
        Invoke-TapNode $toggle
        Start-Sleep -Seconds 2
        $toggle = Find-WithScroll { param($p) Find-Switch $p $NamesOnLockSwitch }
        if ($null -eq $toggle -or (($toggle.GetAttribute('checked') -eq 'true') -ne $want)) { throw "'$NamesOnLockSwitch'가 바뀌지 않았습니다 (덤프: work.xml)." }
    }
    Open-AppHome | Out-Null
    Remove-WorkDumps
    $state = if ($want) { '켬 (잠금 화면 알림에 약 이름이 보임)' } else { "끔 (잠금 화면에는 '$LockTitle'만)" }
    Write-Output "앱 설정 '$NamesOnLockSwitch': $state, $(if ($was -eq $want) { '이미 그 상태였음' } else { '바꿈' })"
}

function Step-Clock {
    # 자동 시간을 끄고 휴대폰 시계를 맞춤 (cmd alarm set-time, 루트 없이). 거부되면 사람이 설정 화면에서 맞춤.
    # 설정 값(auto_time)만 끄면 Android 14 에뮬레이터에서 1분 뒤 시계가 실제 시각으로 돌아왔음(scripts/emulator/explore.py
    # 7회차): 시각 감지(time_detector)도 끔. 그 명령이 없는 Android(12 이하 일부)에서는 설정 값만으로 됨
    if ($Mode -eq 'auto') {
        Invoke-Shell 'settings put global auto_time 1' | Out-Null
        Invoke-Shell 'cmd time_detector set_auto_detection_enabled true' -AllowFail | Out-Null
        Start-Sleep -Seconds 3
        Write-Output "자동 시간 켬. 휴대폰 시각: $((Invoke-Shell 'date').Trim()) (네트워크 시각을 받기까지 몇 초~몇 분 걸릴 수 있음)"
        return
    }
    if ($At -notmatch '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$') { throw "clock에는 -At 'yyyy-MM-dd HH:mm:ss' 또는 -Mode auto를 줍니다" }
    $local = [datetime]::ParseExact($At, 'yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    $offset = Get-PhoneOffset
    $millis = [long](($local - $offset - $script:EpochZero).TotalMilliseconds)
    Invoke-Shell 'settings put global auto_time 0' | Out-Null
    Invoke-Shell 'cmd time_detector set_auto_detection_enabled false' -AllowFail | Out-Null
    $out = (Invoke-Shell "cmd alarm set-time $millis" -AllowFail).Trim()
    Start-Sleep -Seconds 1
    $now = (Invoke-Shell 'date +%Y-%m-%d@%H:%M:%S').Trim().Replace('@', ' ')
    $drift = [Math]::Abs(([datetime]::ParseExact($now, 'yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture) - $local).TotalSeconds)
    if ($drift -gt 10) {
        throw "휴대폰 시계를 바꾸지 못했습니다 (cmd alarm set-time: '$out', 지금 $now). 자동 시간은 꺼 두었으니 설정 > 일반 > 날짜 및 시간에서 맞춥니다."
    }
    Write-Output "휴대폰 시각: $now (UTC$('{0}{1:hh\:mm}' -f $(if ($offset -lt [TimeSpan]::Zero) { '-' } else { '+' }), $offset)), 자동 시간 끔. 끝나면 -Step clock -Mode auto로 되돌립니다."
}

function Step-Install {
    if (-not $Apk) { throw 'install에는 -Apk를 줍니다' }
    if (-not (Test-Path -LiteralPath $Apk)) { throw "APK가 없습니다: $Apk" }
    if ($Fresh -and (Test-PackageInstalled)) {
        Write-Output "앱을 지웁니다 ($PackageName, 이 폰의 앱 기록이 지워짐)"
        Write-Output (Invoke-Adb -Arguments @('uninstall', $PackageName) -AllowFail)
    }
    $out = Invoke-Adb -Arguments @('install', '-r', (Resolve-Path -LiteralPath $Apk).Path) -AllowFail
    Write-Output $out
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        throw '깔린 앱과 서명이 달라 덮어 깔 수 없습니다. 기록을 지워도 되면 -Fresh를 붙여 다시 돌립니다.'
    }
    if ($out -notmatch '(?m)^Success') { throw 'APK를 깔지 못했습니다' }
    $package = Invoke-Shell "dumpsys package $PackageName"
    $version = [regex]::Match($package, 'versionCode=(\d+)')
    $name = [regex]::Match($package, 'versionName=(\S+)')
    $flags = [regex]::Match($package, '\bflags=\[([^\]]*)\]')
    $text = "깔림: versionCode $($version.Groups[1].Value), versionName $($name.Groups[1].Value), flags [$($flags.Groups[1].Value.Trim())]"
    Save-Text 'install.txt' $text | Out-Null
    Write-Output $text
}

function Get-WidgetIds {
    # dumpsys appwidget에서 이 앱 위젯의 번호들
    $dump = Invoke-Shell 'dumpsys appwidget' -AllowFail
    $ids = New-Object System.Collections.Generic.List[int]
    $blocks = [regex]::Split($dump, '(?m)^\s*\[\d+\]\s+')
    foreach ($block in $blocks) {
        $id = [regex]::Match($block, '^id=(\d+)')
        if ($id.Success -and $block.Contains($PackageName)) { $ids.Add([int]$id.Groups[1].Value) }
    }
    return $ids
}

function Step-Bug004 {
    if (-not (Test-PackageInstalled)) { throw '앱이 깔려 있지 않습니다. -Step install -Fresh 뒤에 앱을 한 번 열고 항목 하나를 더한 다음 부릅니다.' }
    $start = Get-DeviceTime
    $package = Invoke-Shell "dumpsys package $PackageName"
    $receiverOn = Test-ComponentEnabled $package $RescheduleReceiver
    $before = @(Get-WidgetIds)
    Write-Output "RescheduleReceiver 켜짐: $receiverOn (앱을 연 뒤라면 켜져 있어야 함, TASK-054)"
    Write-Output "이 앱 위젯 $($before.Count)개. 지금 홈 화면에 이 앱 위젯(4×2)을 놓아 주세요. 3분 기다립니다."
    $placedAt = $null
    $newIds = @()
    for ($i = 0; $i -lt 180; $i++) {
        Start-Sleep -Seconds 1
        $now = @(Get-WidgetIds)
        $newIds = @($now | Where-Object { $before -notcontains $_ })
        if ($newIds.Count -gt 0) { $placedAt = Get-Date; break }
    }
    if ($null -eq $placedAt) { throw '3분 안에 새 위젯이 생기지 않았습니다. 다시 부르면 처음부터 기다립니다.' }
    Write-Output "새 위젯 번호 $($newIds -join ', ') ($(Get-Date -Format 'HH:mm:ss')). 3초·10초 뒤 화면을 남깁니다."
    $results = @()
    foreach ($wait in @(3, 10)) {
        $target = $placedAt.AddSeconds($wait)
        $left = ($target - (Get-Date)).TotalMilliseconds
        if ($left -gt 0) { Start-Sleep -Milliseconds ([int]$left) }
        $stem = "bug004-${wait}s"
        Save-Screenshot $stem | Out-Null
        $xml = Get-UiDump $stem
        $note = ''
        $root = if ($null -ne $xml) { $xml.SelectSingleNode('//node') } else { $null }
        if ($null -ne $root -and $root.GetAttribute('package') -ne $PackageName -and @(Get-WidgetNodes $xml).Count -eq 0) {
            # 런처가 막 놓은 위젯에 크기 조절 테두리를 띄우는 동안은 덤프에 그 테두리만 있고 홈 화면 노드가 없음 (Pixel 런처,
            # API 34 에뮬레이터 실행 43391459701: widget_resize_frame뿐). [뒤로]로 테두리만 닫고 다시 덤프함. 위젯을 놓을 때 뜨는
            # 대상자 고르기(SCR-002)는 이 앱 화면이라 그때는 누르지 않음. 캡처는 위에서 이미 그 시각에 남김
            Invoke-Shell 'input keyevent KEYCODE_BACK' -AllowFail | Out-Null
            Start-Sleep -Milliseconds 800
            $xml = Get-UiDump $stem
            $note = ', 런처의 위젯 테두리를 [뒤로]로 닫고 다시 덤프함'
        }
        $progress = $false
        $texts = @()
        if ($null -ne $xml) {
            $progress = @($xml.SelectNodes('//node') | Where-Object { $_.GetAttribute('class') -match 'ProgressBar' }).Count -gt 0
            $texts = @($xml.SelectNodes('//node') | ForEach-Object { Get-NodeText $_ } | Where-Object { $_ })
        }
        $results += "${wait}초: 도는 원(ProgressBar) $(if ($progress) { '있음' } else { '없음' }), 화면 글자 $($texts.Count)개 (캡처 $stem.png$note)"
    }
    $log = Save-DeviceOutput ("logcat -d -v year -T '{0}'" -f $start.Logcat) 'bug004-logcat.txt'
    $changed = @($log -split "`r?`n" | Where-Object { $_.Contains($PackageName) -and $_ -match 'PACKAGE_CHANGED|onPackageModified|package changed|pkg changed' })
    $text = @(
        "BUG-004 첫 위젯 ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))",
        "RescheduleReceiver 켜짐(놓기 전): $receiverOn",
        "새 위젯: $($newIds -join ', ')"
    ) + $results + @("logcat에서 이 앱의 패키지 바뀜 줄: $($changed.Count)개") + @($changed | Select-Object -First 10)
    $joined = $text -join [Environment]::NewLine
    Save-Text 'bug004.txt' $joined | Out-Null
    Write-Output $joined
    Write-Output '합격: 10초 캡처에 위젯 항목이 보이고 도는 원이 아님, 패키지 바뀜 줄 없음. 사람이 캡처를 보고 정함.'
}

function Get-WidgetNodes($Xml) {
    # 홈 화면 덤프에서 이 앱 위젯 안의 노드: 위젯이 그린 노드는 package가 이 앱임 (런처 노드는 런처 패키지)
    if ($null -eq $Xml) { return @() }
    return @($Xml.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq $PackageName })
}

function Find-Clickable($Node) {
    # 그 노드나 가장 가까운 누를 수 있는 조상
    $current = $Node
    while ($null -ne $current -and $current.LocalName -eq 'node') {
        if ($current.GetAttribute('clickable') -eq 'true') { return $current }
        $current = $current.ParentNode
    }
    return $Node
}

function Find-WidgetTarget($Xml, [string]$ItemName) {
    # 위젯 안의 항목 칸(누를 곳)과 머리글의 '1 / 4' 글자(바뀌는지 볼 곳, DidITakeWidget의 Count).
    # 항목 칸은 이름 글자로 찾고, 없으면 칸의 설명(content-desc)에 이름이 든 것으로 찾음
    $nodes = @(Get-WidgetNodes $Xml)
    $label = $nodes | Where-Object { (Get-NodeText $_).Trim() -eq $ItemName } | Select-Object -First 1
    if ($null -eq $label) { $label = $nodes | Where-Object { (Get-NodeText $_).Contains($ItemName) } | Select-Object -First 1 }
    if ($null -eq $label) { $label = $nodes | Where-Object { (Get-NodeDesc $_).Contains($ItemName) } | Select-Object -First 1 }
    $count = $nodes | Where-Object { (Get-NodeText $_) -match '^\s*\d+\s*/\s*\d+\s*$' } | Select-Object -First 1
    [pscustomobject]@{
        Found      = ($null -ne $label)
        Button     = if ($null -ne $label) { Get-Bounds (Find-Clickable $label) } else { $null }
        Header     = if ($null -ne $count) { Get-Bounds $count } else { $null }
        HeaderText = if ($null -ne $count) { (Get-NodeText $count).Trim() } else { '' }
        Band       = (@($nodes | ForEach-Object { Get-NodeText $_ }) -join "`n")
    }
}

function Show-WidgetItem([string]$ItemName, [string]$Stem) {
    # 홈 화면 위젯에서 그 항목 칸이 보이게 함. 안 보이면 위젯 안의 목록을 밀어 찾음: 아래로 끝까지, 다음에 위로.
    # 밀 수 있는(scrollable) 위젯 목록만 밀고 런처 화면은 밀지 않음. 목록 끝에서 한 번 더 밀면 런처가 그 몸짓을 받아
    # 앱 서랍이나 알림창을 열 수 있어, 위젯이 사라지면 홈 버튼으로 돌아옴 (두 번까지).
    # 찾으면 $true. 위젯에 밀 목록이 없거나 끝까지 없으면 $false. 위젯을 항목이 다 보이는 크기로 두면 밀지 않음
    $homePresses = 0
    foreach ($toward in @('down', 'up')) {
        $previous = $null
        for ($swipe = 0; $swipe -le 8; $swipe++) {
            $page = Get-UiDump $Stem
            if ($null -eq $page) { return $false }
            $widget = @(Get-WidgetNodes $page)
            if ($widget.Count -eq 0) {
                if ($homePresses -ge 2) { return $false }
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Start-Sleep -Seconds 2
                $homePresses++
                continue
            }
            if ((Find-WidgetTarget $page $ItemName).Found) { return $true }
            $lists = @($widget | Where-Object { $_.GetAttribute('scrollable') -eq 'true' } |
                ForEach-Object { Get-Bounds $_ } | Where-Object { $null -ne $_ -and ($_.Bottom - $_.Top) -gt 100 })
            if ($lists.Count -eq 0) { break }
            $signature = (@($widget | ForEach-Object { '{0}|{1}|{2}' -f $_.GetAttribute('bounds'), $_.GetAttribute('text'), $_.GetAttribute('content-desc') }) -join "`n")
            if ($signature -eq $previous) { break }
            $previous = $signature
            Invoke-Swipe $lists[0] $toward
            Start-Sleep -Seconds 1
        }
    }
    return $false
}

function Get-ScreenSize {
    # 원시 캡처의 너비·높이: wm size가 아니라 캡처 머리(너비, 높이, 형식)에서 읽음
    $remote = "$RemoteDir/size.raw"
    Invoke-Shell "screencap $remote" | Out-Null
    $out = (Invoke-Shell "head -c 12 $remote | od -An -tu4" -AllowFail).Trim()
    $size = (Invoke-Shell "wc -c < $remote" -AllowFail).Trim()
    Invoke-Shell "rm -f $remote" -AllowFail | Out-Null
    $numbers = @($out -split '\s+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [long]$_ })
    if ($numbers.Count -lt 3) { throw "원시 캡처 머리를 읽지 못했습니다: $out" }
    $width = $numbers[0]; $height = $numbers[1]
    $header = [long]$size - $width * $height * 4
    if ($header -lt 12 -or $header -gt 64) { throw "원시 캡처 크기가 예상과 다릅니다: $width x $height, $size 바이트" }
    [pscustomobject]@{ Width = $width; Height = $height; Header = $header }
}

function Measure-WidgetTap($Target, $Size, [int]$Frames) {
    # 휴대폰 안에서: 머리글 줄만 잘라 해시 → 누름 → 원시 캡처를 이어 찍으며 해시. 시각은 /proc/uptime(0.01초)이고 캡처를
    # 시작하기 직전에 읽음. 머리글 자리에는 기록하면 '2 / 4'처럼 바뀐 수가, "이미 기록됨"이면 그 글 띠가 옴 (WidgetContent).
    # 위젯 목록 칸을 누르면 Glance가 보이지 않는 활동(InvisibleActionTrampolineActivity)을 거치고, 그 활동이 뜨는 동안
    # 머리글 줄이 잠깐 다르게 찍힘: 앱 프로세스가 내려가 있으면 활동이 뜨기까지 0.8초쯤 걸려 첫 캡처가 이것을 잡았고, 앱은
    # 그 뒤에 띠를 그렸음(API 36, 실행 43414847467). 그래서 바뀐 모습이 1.5초 그대로 머물면 멈추고(SETTLE, 1/100초 단위),
    # 그 마지막 모습이 처음 찍힌 캡처의 시각을 위 끝, 그 앞에서 누르기 전과 똑같이 찍힌 마지막 캡처의 시각을 아래 끝으로 함
    $rowBytes = $Size.Width * 4
    # 캡처 머리 바이트 때문에 잘린 곳이 몇 픽셀 밀리지만, 같은 곳을 매번 비교하므로 바뀜을 보는 데 문제없음
    $top = [Math]::Max(0, $Target.Header.Top - 4)
    $rows = [Math]::Max(8, ($Target.Header.Bottom - $Target.Header.Top) + 8)
    $x = [int](($Target.Button.Left + $Target.Button.Right) / 2)
    $y = [int](($Target.Button.Top + $Target.Button.Bottom) / 2)
    $deviceScript = @'
d=@DIR@/lat; mkdir -p $d; rm -f $d/t.txt;
sc() { screencap $d/f.raw; dd if=$d/f.raw bs=@RB@ skip=@TOP@ count=@ROWS@ 2>/dev/null | md5sum | cut -d' ' -f1; };
read u x < /proc/uptime; h0=$(sc); echo f0 $u $h0 >> $d/t.txt;
input tap @X@ @Y@; read u x < /proc/uptime; echo tap $u >> $d/t.txt;
i=1; run=x; rs=0;
while [ $i -le @N@ ]; do read u x < /proc/uptime; h=$(sc); echo f$i $u $h >> $d/t.txt; c=${u%.*}${u#*.};
if [ x$h = x$h0 ]; then run=x; elif [ x$h != x$run ]; then run=$h; rs=$c; elif [ $((c-rs)) -ge @SETTLE@ ]; then break; fi;
i=$((i+1)); done;
cat $d/t.txt; rm -f $d/f.raw
'@
    $deviceScript = $deviceScript.Replace("`r", '').Replace("`n", ' ').Replace('@DIR@', $RemoteDir).Replace('@RB@', "$rowBytes").Replace('@TOP@', "$top").Replace('@ROWS@', "$rows").Replace('@X@', "$x").Replace('@Y@', "$y").Replace('@N@', "$Frames").Replace('@SETTLE@', '150')
    $out = Invoke-Shell $deviceScript
    $shots = @()
    $tap = $null
    foreach ($line in ($out -split "`r?`n")) {
        $m = [regex]::Match($line.Trim(), '^f(\d+) (\d+(?:\.\d+)?) ([0-9a-f]{32})$')
        if ($m.Success) {
            $shots += [pscustomobject]@{ Index = [int]$m.Groups[1].Value; At = [double]::Parse($m.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture); Hash = $m.Groups[3].Value }
            continue
        }
        $t = [regex]::Match($line.Trim(), '^tap (\d+(?:\.\d+)?)$')
        if ($t.Success) { $tap = [double]::Parse($t.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture) }
    }
    if ($shots.Count -lt 2 -or $null -eq $tap) { throw "재기 출력을 읽지 못했습니다:$([Environment]::NewLine)$out" }
    $intervals = @()
    for ($k = 2; $k -lt $shots.Count; $k++) { $intervals += ($shots[$k].At - $shots[$k - 1].At) }
    $step = if ($intervals.Count) { ($intervals | Measure-Object -Average).Average } else { 0 }
    $covered = $shots[-1].At - $tap
    $final = $shots[-1].Hash
    if ($final -eq $shots[0].Hash) {
        return [pscustomobject]@{ Changed = $false; Settled = $false; Upper = $null; Lower = $null; Step = $step; Covered = $covered }
    }
    $first = $shots | Where-Object { $_.Index -ge 1 -and $_.Hash -eq $final } | Select-Object -First 1
    $unchanged = @($shots | Where-Object { $_.Index -ge 1 -and $_.Index -lt $first.Index -and $_.Hash -eq $shots[0].Hash })
    $lower = if ($unchanged.Count) { [Math]::Max(0.0, $unchanged[-1].At - $tap) } else { 0.0 }
    # 지켜볼 장수를 다 써서 멈췄으면 마지막 모습이 1.5초 머물렀는지 따로 봄
    $runFrom = $shots.Count - 1
    while ($runFrom -gt 1 -and $shots[$runFrom - 1].Hash -eq $final) { $runFrom-- }
    $settled = ($shots[-1].At - $shots[$runFrom].At) -ge 1.5
    [pscustomobject]@{ Changed = $true; Settled = $settled; Upper = [Math]::Round($first.At - $tap, 2); Lower = [Math]::Round($lower, 2); Step = $step; Covered = $covered }
}

function Get-NearestRank([double[]]$Sorted, [double]$Fraction) {
    # 가장 가까운 순위 백분위: 20개의 P95는 19번째 값
    if ($Sorted.Count -eq 0) { return $null }
    $rank = [int][Math]::Ceiling($Fraction * $Sorted.Count)
    return $Sorted[[Math]::Max(1, $rank) - 1]
}

function Write-LatencySummary([string]$Csv) {
    # latency.csv 전체를 프로세스 상태·결과별로 묶어 위 끝(처음 바뀐 캡처까지) 기준 P50·P95·최댓값
    $rows = @(Import-Csv -LiteralPath $Csv -Encoding UTF8)
    foreach ($group in ($rows | Group-Object process, kind | Sort-Object Name)) {
        $upper = @($group.Group | Where-Object { $_.upper_s } | ForEach-Object { [double]::Parse($_.upper_s, [Globalization.CultureInfo]::InvariantCulture) } | Sort-Object)
        $missed = @($group.Group | Where-Object { -not $_.upper_s }).Count
        if ($upper.Count -eq 0) { Write-Output ("{0}: {1}번, 바뀜을 못 봄 {2}번" -f $group.Name, $group.Count, $missed); continue }
        Write-Output ("{0}: {1}번, P50 {2:N2}초, P95 {3:N2}초, 최댓값 {4:N2}초{5}" -f $group.Name, $group.Count,
            (Get-NearestRank $upper 0.5), (Get-NearestRank $upper 0.95), $upper[-1], $(if ($missed) { ", 바뀜을 못 봄 $($missed)번" } else { '' }))
    }
}

function Step-Latency {
    # REQ-070: 항목 10개, 상태마다 20번. -Names를 주면 -Count번을 이름 차례로 돌아가며 누름 (10개 20번이면 처음 10번은 기록,
    # 다음 10번은 "이미 기록됨" 띠). 결과는 latency.csv에 쌓이고 부를 때마다 전체를 다시 묶어 보임
    $targets = @(if ($Names.Count -gt 0) { $Names } elseif ($Item) { $Item })
    if ($targets.Count -eq 0) { throw 'latency에는 -Item(위젯에서 누를 항목 이름) 또는 -Names(여럿)를 줍니다' }
    $size = Get-ScreenSize
    # 캡처 한 장에 0.2~0.5초: 살아 있으면 약 10초, 내려가 있으면 약 20초까지 지켜봄
    $frames = if ($Process -eq 'dead') { 60 } else { 30 }
    $csv = Join-Path $script:OutPath 'latency.csv'
    if (-not (Test-Path -LiteralPath $csv)) {
        [System.IO.File]::WriteAllText($csv, "pc_time,item,process,pid_before,before,after,kind,lower_s,upper_s,frame_step_s,covered_s$([Environment]::NewLine)", $script:Utf8)
    }
    for ($n = 1; $n -le $Count; $n++) {
        $target = $targets[($n - 1) % $targets.Count]
        # 띠("이미 기록됨 …")는 10초 뒤 머리글로 돌아감(MESSAGE_TTL_MS). 띠가 남아 있으면 같은 글이라 바뀜을 못 봄
        if ($n -gt 1) { Start-Sleep -Seconds 12 }
        if ($Process -eq 'dead') {
            # am kill은 뒤에서 쉬는(cached) 프로세스만 내림. 위젯을 그리는 작업(Glance, WorkManager)이 도는 동안은 프로세스가
            # 남음 (API 34·36 에뮬레이터, 실행 43391459701): 작업이 끝날 때까지 2초마다 다시 내려 봄 (최대 90초)
            $killFrom = Get-Date
            do {
                Invoke-Shell "am kill $PackageName" -AllowFail | Out-Null
                Start-Sleep -Seconds 2
                $left = (Invoke-Shell "pidof $PackageName" -AllowFail).Trim()
            } while ($left -and ((Get-Date) - $killFrom).TotalSeconds -lt 90)
            if (-not $left -and ((Get-Date) - $killFrom).TotalSeconds -gt 5) {
                Write-Output ("{0}/{1} 앱 프로세스가 {2:N0}초 만에 내려감 (그동안 위젯 작업이 돌았음)" -f $n, $Count, ((Get-Date) - $killFrom).TotalSeconds)
            }
        } elseif (-not (Invoke-Shell "pidof $PackageName" -AllowFail).Trim()) {
            # 프로세스를 띄우고 홈으로 돌아감
            Invoke-Shell "monkey -p $PackageName -c android.intent.category.LAUNCHER 1" -AllowFail | Out-Null
            Start-Sleep -Seconds 3
            Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
            Start-Sleep -Seconds 2
        }
        $appPid = (Invoke-Shell "pidof $PackageName" -AllowFail).Trim()
        if ($Process -eq 'dead' -and $appPid) { throw "90초 동안 am kill을 되풀이해도 앱 프로세스가 남았습니다(pid $appPid). 앱 화면이 앞에 떠 있지 않은지 봅니다." }
        $stem = Get-Stem ("latency-{0}-{1}" -f (Get-Date -Format 'HHmmss'), $Process) ''
        if (-not (Show-WidgetItem $target "$stem-before")) {
            throw "홈 화면의 이 앱 위젯에서 '$target' 칸을 찾지 못했습니다 (덤프: $stem-before.xml). 위젯이 홈 화면 첫 페이지에 보이는지, 위젯 크기를 봅니다."
        }
        # 앞 탭의 띠가 아직 머리글 자리에 있으면 사라질 때까지 (최대 15초) 다시 봄
        $deadline = (Get-Date).AddSeconds(15)
        do {
            $xml = Get-UiDump "$stem-before"
            if ($null -eq $xml) { throw '화면 덤프를 얻지 못했습니다. 홈 화면에 위젯이 보이는지 확인합니다.' }
            $spot = Find-WidgetTarget $xml $target
            if (-not $spot.Found) { throw "홈 화면의 이 앱 위젯에서 '$target' 칸이 사라졌습니다 (덤프: $stem-before.xml)." }
            if ($null -ne $spot.Header) { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if ($null -eq $spot.Header) { throw "위젯 머리글의 'n / m' 글자를 찾지 못했습니다 (덤프: $stem-before.xml)." }
        $before = $spot.HeaderText
        $result = Measure-WidgetTap $spot $size $frames
        # 띠는 10초만 있음: 덤프를 먼저, 캡처는 그 뒤
        $afterXml = Get-UiDump "$stem-after"
        Save-Screenshot "$stem-after" | Out-Null
        $afterText = ''
        $kind = '?'
        if ($null -ne $afterXml) {
            $after = Find-WidgetTarget $afterXml $target
            $afterText = $after.HeaderText
            if ($after.Band.Contains($AlreadyWord)) { $kind = 'already' }
            elseif ($after.Band.Contains($AlreadySkipWord)) { $kind = 'already-skipped' }
            elseif ($after.Band.Contains($StaleWord)) { $kind = 'stale' }
            elseif ($afterText -and $afterText -ne $before) { $kind = 'recorded' }
        }
        $line = '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $target, $Process, $appPid, $before, $afterText, $kind,
            $(if ($result.Changed) { $result.Lower } else { '' }), $(if ($result.Changed) { $result.Upper } else { '' }),
            [Math]::Round($result.Step, 3), [Math]::Round($result.Covered, 2)
        [System.IO.File]::AppendAllText($csv, $line + [Environment]::NewLine, $script:Utf8)
        $state = if ($Process -eq 'dead') { 'dead' } else { "alive (pid $appPid)" }
        if ($result.Changed) {
            Write-Output ("{0}/{1} {2} {3}: 머리글 '{4}' → '{5}' ({6}), {7:N2}~{8:N2}초 사이 (캡처 간격 평균 {9:N2}초){10}" -f $n, $Count, $target, $state, $before, $afterText, $kind, $result.Lower, $result.Upper, $result.Step,
                $(if ($result.Settled) { '' } else { ', 바뀐 모습이 1.5초 머물기 전에 지켜볼 장수가 끝남' }))
        } else {
            Write-Output ("{0}/{1} {2} {3}: {4:N1}초 안에 머리글 자리가 바뀌지 않음 (캡처 간격 평균 {5:N2}초)" -f $n, $Count, $target, $state, $result.Covered, $result.Step)
        }
    }
    Write-Output '지금까지 모은 값 (latency.csv 전체, 위 끝 기준):'
    Write-LatencySummary $csv
    Write-Output "남김: $csv"
}

function Get-UsageEvents([string]$Dump) {
    # dumpsys usagestats의 이 앱 알림 울림(NOTIFICATION_INTERRUPTION) 시각, 알림 채널 하나만. usagestats는 하루치를
    # 메모리에 두고 24시간마다 파일로 넘기므로, 밤사이 넘어간 앞부분은 여기 없을 수 있음 (그래서 events 버퍼도 봄)
    $events = @()
    foreach ($line in ($Dump -split "`r?`n")) {
        if (-not $line.Contains('NOTIFICATION_INTERRUPTION') -or -not $line.Contains("package=$PackageName")) { continue }
        $time = [regex]::Match($line, 'time="?(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})"?')
        $channel = [regex]::Match($line, 'channelId=(\S+)')
        if (-not $time.Success -or -not $channel.Success -or $channel.Groups[1].Value -ne $DoseChannel) { continue }
        $events += [datetime]::ParseExact($time.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    }
    return @($events | Sort-Object -Unique)
}

function Get-EventLog([string]$Dump) {
    # logcat events 버퍼(-v year): 이 앱 알림 채널에 올린 시각(notification_enqueue)과 Doze 상태 바뀜(device_idle).
    # device_idle 상태: 0 ACTIVE, 1 INACTIVE, 2 IDLE_PENDING, 3 SENSING, 4 LOCATING, 5 IDLE, 6 IDLE_MAINTENANCE
    $posts = @()
    $idle = @()
    foreach ($line in ($Dump -split "`r?`n")) {
        $m = [regex]::Match($line, '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s')
        if (-not $m.Success) { continue }
        $at = [datetime]::ParseExact($m.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        if ($line.Contains('notification_enqueue:') -and $line.Contains(",$PackageName,") -and $line.Contains("channel=$DoseChannel")) {
            $posts += $at
            continue
        }
        $state = [regex]::Match($line, '\bdevice_idle: \[(\d+),')
        if ($state.Success) { $idle += [pscustomobject]@{ At = $at; State = [int]$state.Groups[1].Value } }
    }
    [pscustomobject]@{ Posts = @($posts | Sort-Object -Unique); Idle = @($idle | Sort-Object At) }
}

function Format-Seconds([double]$Seconds) {
    if ($Seconds -lt 60) { return ('{0:N0}초' -f $Seconds) }
    return ('{0}분 {1:N0}초' -f [int][Math]::Floor($Seconds / 60), ($Seconds % 60))
}

function Step-Night {
    if (-not $Phase) { throw 'night에는 -Phase start 또는 -Phase end를 줍니다' }
    $stateFile = Join-Path $script:OutPath 'night-start.txt'
    if ($Phase -eq 'start') {
        $time = Get-DeviceTime
        $exact = (Invoke-Shell "appops get $PackageName SCHEDULE_EXACT_ALARM" -AllowFail).Trim()
        $dump = Save-DeviceOutput 'dumpsys alarm' 'night-start-alarm.txt'
        $alarms = @(Get-AppAlarms $dump (Get-PhoneOffset) | ForEach-Object { 'alarm=' + (Format-Alarm $_) })
        $battery = Invoke-Shell 'dumpsys battery' -AllowFail
        $level = [regex]::Match($battery, '(?m)^\s*level:\s*(\d+)')
        $text = @("epoch=$($time.Epoch)", "phone_time=$((Invoke-Shell 'date').Trim())", "exact=$exact",
            "battery=$(if ($level.Success) { $level.Groups[1].Value } else { '?' })") + $alarms
        [System.IO.File]::WriteAllText($stateFile, ($text -join [Environment]::NewLine), $script:Utf8)
        Write-Output ($text -join [Environment]::NewLine)
        if (@($alarms | Where-Object { $_.Contains('REMINDER_ALARM') }).Count -eq 0) { Write-Output '주의: 이 앱의 알림 알람이 없습니다. 항목 알림 설정을 봅니다.' }
        Write-Output '이제 USB를 뽑고 화면을 끈 채, 충전하지 않고 아침까지 둡니다(Doze). 아침에 다시 꽂고 -Phase end를 부릅니다.'
        return
    }
    if ($Slots.Count -eq 0) { throw 'night end에는 -Slots(계획한 알림 칸, HH:mm)를 줍니다' }
    if (-not (Test-Path -LiteralPath $stateFile)) { throw "밤 시작 기록이 없습니다: $stateFile (-Phase start를 먼저)" }
    $startEpoch = [long](([regex]::Match([System.IO.File]::ReadAllText($stateFile), 'epoch=(\d+)')).Groups[1].Value)
    $nowEpoch = (Get-DeviceTime).Epoch
    # 휴대폰 시각과 같은 시간대로 계산: 휴대폰의 에폭을 휴대폰 시간대의 시각으로 바꿈 (usagestats·logcat이 그 시각으로 씀)
    $offset = Get-PhoneOffset
    $start = $script:EpochZero.AddSeconds($startEpoch).Add($offset)
    $end = $script:EpochZero.AddSeconds($nowEpoch).Add($offset)
    $usage = Save-DeviceOutput 'dumpsys usagestats' 'night-usagestats.txt'
    $eventDump = Save-DeviceOutput 'logcat -d -v year -b events' 'night-logcat-events.txt'
    Save-DeviceOutput ("logcat -d -v year -s {0}" -f (($AppLogTags | ForEach-Object { "${_}:V" }) -join ' ')) 'night-logcat-app.txt' | Out-Null
    Save-DeviceOutput 'dumpsys deviceidle' 'night-deviceidle.txt' | Out-Null
    Save-DeviceOutput 'dumpsys alarm' 'night-end-alarm.txt' | Out-Null
    $rung = @(Get-UsageEvents $usage | Where-Object { $_ -ge $start -and $_ -le $end })
    $log = Get-EventLog $eventDump
    $posts = @($log.Posts | Where-Object { $_ -ge $start -and $_ -le $end })
    # 계획 칸: 밤 시작 뒤 처음 오는 그 시각. 칸마다 그 시각 이후 처음 울린(또는 올린) 때까지가 늦음.
    # 거부한 밤에는 Doze 정비 창에서 밀린 칸이 한 번에 뜰 수 있어(N-12), 두 칸이 같은 알림을 가리키면 '묶임'
    $planned = @()
    foreach ($slot in $Slots) {
        if ($slot -notmatch '^\d{2}:\d{2}$') { throw "-Slots는 HH:mm로 씁니다: $slot" }
        $candidate = $start.Date.Add([TimeSpan]::Parse($slot))
        if ($candidate -lt $start) { $candidate = $candidate.AddDays(1) }
        $planned += $candidate
    }
    $planned = @($planned | Sort-Object)
    $rows = @('| 계획 칸 | 울림 (usagestats) | 올림 (events) | 늦음 | 비고 |', '| --- | --- | --- | --- | --- |')
    $delays = @()
    $used = @{}
    foreach ($from in $planned) {
        if ($from -gt $end) { $rows += ('| {0:MM-dd HH:mm} | - | - | - | 끝낸 뒤의 칸 |' -f $from); continue }
        $hitRung = $rung | Where-Object { $_ -ge $from } | Select-Object -First 1
        # events 시각은 밀리초까지: 계획 칸 정각 직전(1초 안)에 올린 것도 그 칸 (정확 알람은 몇 ms 일찍 울릴 수 있음)
        $hitPost = $posts | Where-Object { $_ -ge $from.AddSeconds(-1) } | Select-Object -First 1
        $times = @(@($hitRung, $hitPost) | Where-Object { $null -ne $_ })
        if ($times.Count -eq 0) { $rows += ('| {0:MM-dd HH:mm} | 없음 | 없음 | - | 끝낼 때까지 안 옴 |' -f $from); continue }
        $first = ($times | Sort-Object)[0]
        $late = [Math]::Max(0.0, ($first - $from).TotalSeconds)
        $delays += $late
        $key = '{0:HHmmss}' -f $first
        $note = if ($used.ContainsKey($key)) { "$($used[$key]) 칸과 묶임" } else { '' }
        if (-not $used.ContainsKey($key)) { $used[$key] = '{0:HH:mm}' -f $from }
        $rows += ('| {0:MM-dd HH:mm} | {1} | {2} | {3} | {4} |' -f $from,
            $(if ($null -ne $hitRung) { '{0:HH:mm:ss}' -f $hitRung } else { '없음' }),
            $(if ($null -ne $hitPost) { '{0:HH:mm:ss.fff}' -f $hitPost } else { '없음' }), (Format-Seconds $late), $note)
    }
    $sorted = @($delays | Sort-Object)
    $summary = if ($sorted.Count) {
        $median = if ($sorted.Count % 2) { $sorted[[int][Math]::Floor($sorted.Count / 2)] } else { ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2 }
        "온 칸 $($sorted.Count)/$($planned.Count), 늦음 P50 $(Format-Seconds $median), 최댓값 $(Format-Seconds $sorted[-1])"
    } else { "온 칸 0/$($planned.Count)" }
    $idle = @($log.Idle | Where-Object { $_.At -ge $start -and $_.At -le $end })
    $deep = $idle | Where-Object { $_.State -eq 5 } | Select-Object -First 1
    $windows = @($idle | Where-Object { $_.State -eq 6 }).Count
    $idleLine = if ($idle.Count -eq 0) { 'Doze: events 버퍼에 device_idle 줄 없음 (버퍼가 넘쳤거나 Doze에 들지 않음)' }
        elseif ($null -eq $deep) { "Doze: 깊은 Doze(IDLE)에 들지 않음 (상태 바뀜 $($idle.Count)번)" }
        else { "Doze: $('{0:HH:mm}' -f $deep.At)에 깊은 Doze(IDLE), 정비 창 $($windows)번" }
    $text = @("밤 측정 ($('{0:yyyy-MM-dd HH:mm}' -f $start) ~ $('{0:yyyy-MM-dd HH:mm}' -f $end), 휴대폰 시각)",
        "정확 알람(시작 때): $(([regex]::Match([System.IO.File]::ReadAllText($stateFile), 'exact=([^\r\n]*)')).Groups[1].Value)",
        $idleLine, $summary, '') + $rows
    $joined = $text -join [Environment]::NewLine
    $path = Save-Text 'night.md' $joined
    Write-Output $joined
    Write-Output "남김: $path (usagestats·logcat·deviceidle·alarm 원본도 같은 폴더)"
}

function Step-Reboot {
    $stem = Get-Stem $Name 'reboot'
    # adb reboot는 앱 권한(appops) 바뀜을 디스크에 쓰는 프레임워크 종료를 건너뜀: 그대로면 정확 알람 허용이 재부팅 뒤
    # 사라짐(API 34, scripts/emulator/reminder_check.py 실행 43360309166). 그래서 먼저 쓰게 함. 로그 버퍼도 재부팅에
    # 남지 않아 앞의 로그를 먼저 남김
    Invoke-Shell 'cmd appops write-settings' -AllowFail | Out-Null
    $script:Name = "$stem-before"
    Step-Logcat
    Write-Output "다시 시작합니다 ($(Get-Date -Format 'HH:mm:ss'))."
    Invoke-Adb -Arguments @('reboot') | Out-Null
    Start-Sleep -Seconds 5
    Invoke-Adb -Arguments @('wait-for-device') | Out-Null
    # 갤럭시는 1~2분이면 부팅이 끝남. 에뮬레이터 확인(device_kit_check.py)은 5분 안에 부팅이 끝나지 않으면 에뮬레이터를
    # 껐다 켜므로 그만큼 더 기다림
    $booted = $false
    for ($i = 0; $i -lt 300; $i++) {
        if ((Invoke-Shell 'getprop sys.boot_completed' -AllowFail).Trim() -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) { throw '10분 안에 부팅이 끝나지 않았습니다.' }
    Write-Output "부팅 끝 ($(Get-Date -Format 'HH:mm:ss')). 휴대폰 잠금을 풀어 주세요. 5분 기다립니다."
    # 부팅이 막 끝난 때는 잠금 화면이 아직 안 떠 '잠금 없음'으로 읽힐 수 있어 잠깐 둔 뒤 봄
    Start-Sleep -Seconds 10
    $unlocked = $false
    for ($i = 0; $i -lt 150; $i++) {
        $keyguard = Get-KeyguardShowing
        if ($keyguard -eq $false) { $unlocked = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $unlocked) { throw '5분 안에 잠금이 풀리지 않았습니다(또는 잠금 상태를 읽지 못함).' }
    Write-Output "잠금 풀림 ($(Get-Date -Format 'HH:mm:ss')). 20초 뒤 남깁니다."
    Start-Sleep -Seconds 20
    Invoke-Shell 'input keyevent KEYCODE_HOME' -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    $script:Name = "$stem-home"
    Step-Snap
    $script:Name = "$stem-alarms"
    Step-Alarms
    $script:Name = "$stem-logcat"
    Step-Logcat
}

function Step-Logcat {
    $stem = Get-Stem $Name 'logcat'
    $tags = ($AppLogTags | ForEach-Object { "${_}:V" }) -join ' '
    $app = Save-DeviceOutput "logcat -d -v year -s $tags" "$stem-app.txt"
    $events = Save-DeviceOutput 'logcat -d -v year -b events' "$stem-events-all.txt"
    $mine = @($events -split "`r?`n" | Where-Object { $_.Contains($PackageName) })
    Save-Text "$stem-events.txt" ($mine -join [Environment]::NewLine) | Out-Null
    Remove-Item -LiteralPath (Join-Path $script:OutPath "$stem-events-all.txt")
    $crash = Save-DeviceOutput 'logcat -d -v year -b crash' "$stem-crash.txt"
    $crashMine = @($crash -split "`r?`n" | Where-Object { $_.Contains($PackageName) })
    $appLines = @($app -split "`r?`n" | Where-Object { $_.Trim() -and $_ -notmatch '^-+ beginning of' })
    Write-Output "앱 로그 $($appLines.Count)줄, 이벤트 로그의 이 앱 줄 $($mine.Count)개, 앱이 죽은 기록(crash)의 이 앱 줄 $($crashMine.Count)개"
    Write-Output ($appLines | Select-Object -Last 15)
    Write-Output "남김: $(Join-Path $script:OutPath $stem)-app.txt, -events.txt, -crash.txt"
}

# ---------------------------------------------------------------------------------------------
# 시작

try { [Console]::OutputEncoding = $Utf8 } catch { Write-Verbose '콘솔 인코딩을 바꾸지 못함 (출력에 한글이 깨질 수 있음, 파일은 UTF-8)' }
# powershell -File로 부르면 -Names A,B가 'A,B' 한 덩어리로 옴 (배열 문법은 명령 안에서만): 쉼표로 나눔
$Names = @($Names | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$Slots = @($Slots | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$script:Adb = Find-Adb
$script:SerialArgs = @()
if ($Serial) { $script:SerialArgs = @('-s', $Serial) }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not $OutDir) { $OutDir = Join-Path $repoRoot ("docs\07-qa\{0}-galaxy\raw" -f (Get-Date -Format 'yyyy-MM-dd')) }
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$script:OutPath = (Resolve-Path -LiteralPath $OutDir).Path

$devices = @((Invoke-Adb -Arguments @('devices') -AllowFail) -split "`r?`n" | Where-Object { $_ -match '\tdevice$' })
if (-not $Serial -and $devices.Count -ne 1) {
    throw "USB 디버깅으로 연결된 휴대폰이 한 대여야 합니다(지금 $($devices.Count)대). 여럿이면 -Serial을 줍니다. 'unauthorized'면 휴대폰에서 USB 디버깅을 허용합니다."
}
Invoke-Shell "mkdir -p $RemoteDir" | Out-Null

switch ($Step) {
    'info' { Step-Info }
    'snap' { Step-Snap }
    'alarms' { Step-Alarms }
    'logcat' { Step-Logcat }
    'night' { Step-Night }
    'install' { Step-Install }
    'exact' { Step-Exact }
    'content' { Step-Content }
    'lock' { Step-Lock }
    'shade' { Step-Shade }
    'tap' { Step-Tap }
    'clock' { Step-Clock }
    'additem' { Step-AddItem }
    'remind' { Step-Remind }
    'appnames' { Step-AppNames }
    'bug004' { Step-Bug004 }
    'latency' { Step-Latency }
    'reboot' { Step-Reboot }
}
