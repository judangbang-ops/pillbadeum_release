<#
.SYNOPSIS
CI가 만든 서명하지 않은 릴리즈 APK를 앱 서명 키로 서명해 개인 설치용 APK를 만듭니다 (TASK-002, REQ-062).

.DESCRIPTION
동희님 PC에서 돌립니다. 앱 서명 키는 이 PC와 Google Drive 사본에만 있고 GitHub에는 없습니다.

1. GitHub Actions '릴리즈 빌드'(release.yml)의 main 최근 성공 실행에서 아티팩트 release-apk-unsigned를 받습니다.
   -RunId로 실행을 고르거나(ci.yml의 PR 실행도 같은 이름으로 하루 동안 남김) -Apk로 파일을 직접 줄 수 있습니다.
2. 패키지 이름과 debuggable이 아님을 확인하고, zipalign으로 정렬(4바이트, 네이티브 라이브러리 16KB 페이지)을 확인합니다.
3. apksigner로 앱 서명 키(별칭 app-signing)로 서명합니다.
4. apksigner verify --print-certs로 서명자가 하나이고 인증서 SHA-256이 앱 서명 키 지문과 같은지, 정렬이 그대로인지 확인합니다.

비밀번호는 키 폴더의 DPAPI 파일(이 PC의 이 Windows 계정에서만 풀림)에서 읽어 apksigner에 환경 변수로만 넘깁니다.
화면, 명령줄 인수, 파일 어디에도 비밀번호를 쓰지 않습니다.

필요한 것: Android SDK build-tools 35 이상(apksigner, zipalign, aapt2), JDK 17 이상, 로그인한 GitHub CLI(gh).

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sign-personal-apk.ps1

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sign-personal-apk.ps1 -RunId 1234567890 -OutDir C:\temp\apk
#>
[CmdletBinding()]
param(
    # 서명할 APK 파일. 주면 GitHub에서 받지 않습니다
    [string]$Apk,
    # 아티팩트를 받을 GitHub Actions 실행 ID. 안 주면 release.yml의 main 최근 성공 실행
    [long]$RunId = 0,
    # 서명한 APK를 둘 폴더
    [string]$OutDir = (Join-Path $env:USERPROFILE 'Downloads'),
    # 앱 서명 키와 비밀번호 파일이 있는 폴더
    [string]$KeyDir = (Join-Path $env:USERPROFILE '.pillbadeum-signing'),
    [string]$Repo = 'judangbang-ops/pillbadeum_release'
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

# 앱 서명 키 인증서 SHA-256 (공개값, 2026-11-12 만듦). docs/08-wbs/wbs.md의 TASK-002 메모와 같음
$ExpectedCertSha256 = '35:64:B6:82:4B:1E:3D:A0:74:7A:2E:97:96:6E:F5:5D:B9:CC:93:C5:E8:8A:7D:45:24:64:2F:E6:2A:32:95:36'
$PackageName = 'com.judangbang.pillbadeum'
$KeyAlias = 'app-signing'
$KeyStoreFile = Join-Path $KeyDir 'pillbadeum-app-signing.p12'
$PasswordFile = Join-Path $KeyDir 'app-signing-password.dpapi.xml'
$ArtifactName = 'release-apk-unsigned'
$PasswordVariable = 'PILLBADEUM_APP_SIGNING_PASSWORD'

function Invoke-Tool {
    # 네이티브 도구를 돌려 종료 코드와 표준 출력, 표준 오류를 나눠 돌려줍니다. 실패해도 여기서는 멈추지 않습니다
    param([Parameter(Mandatory = $true)][string]$FilePath, [string[]]$Arguments = @())
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $all = @(& $FilePath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Stdout   = @($all | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
        Stderr   = @($all | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    }
}

function Assert-Tool {
    # 도구가 실패했으면 마지막 몇 줄과 함께 멈춥니다
    param([Parameter(Mandatory = $true)]$Result, [Parameter(Mandatory = $true)][string]$What)
    if ($Result.ExitCode -ne 0) {
        $tail = @(@($Result.Stdout) + @($Result.Stderr) | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 5)
        throw ("{0} 실패 (종료 코드 {1}){2}{3}" -f $What, $Result.ExitCode, [Environment]::NewLine, ($tail -join [Environment]::NewLine))
    }
}

function Find-BuildTools {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'build-tools')) }
    foreach ($root in $roots) {
        $best = Get-ChildItem -LiteralPath (Join-Path $root 'build-tools') -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and [version]$_.Name -ge [version]'35.0.0' } |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') } |
            Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1
        if ($best) { return $best.FullName }
    }
    throw 'Android SDK build-tools 35 이상(apksigner, zipalign, aapt2)을 찾지 못했습니다. ANDROID_HOME이나 %LOCALAPPDATA%\Android\Sdk를 확인하세요.'
}

function Get-ApkInfo {
    param([string]$Aapt2, [string]$Path)
    $result = Invoke-Tool $Aapt2 @('dump', 'badging', $Path)
    Assert-Tool $result 'aapt2 dump badging'
    $text = $result.Stdout -join "`n"
    if ($text -notmatch "(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'") {
        throw "aapt2 출력에서 package 줄을 찾지 못했습니다: $Path"
    }
    $package = $Matches[1]
    $versionCode = [int]$Matches[2]
    $versionName = $Matches[3]
    $debuggable = $text -match '(?m)^application-debuggable\s*$'
    [pscustomobject]@{ Package = $package; VersionCode = $versionCode; VersionName = $versionName; Debuggable = $debuggable }
}

function Format-Fingerprint {
    param([string]$Value)
    $digits = ($Value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    (0..($digits.Length / 2 - 1) | ForEach-Object { $digits.Substring($_ * 2, 2) }) -join ':'
}

# 한글 메시지와 gh의 UTF-8 출력이 깨지지 않게 이 스크립트가 도는 동안만 콘솔 인코딩을 UTF-8로 둡니다
$previousEncoding = $null
try { $previousEncoding = [Console]::OutputEncoding; [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { $previousEncoding = $null }

$work = Join-Path ([IO.Path]::GetTempPath()) ('pillbadeum-sign-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $buildTools = Find-BuildTools
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    $zipalign = Join-Path $buildTools 'zipalign.exe'
    $aapt2 = Join-Path $buildTools 'aapt2.exe'
    foreach ($path in @($KeyStoreFile, $PasswordFile)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "앱 서명 키 파일이 없습니다: $path" }
    }

    # 1. 서명할 APK
    if ($Apk) {
        $inputApk = (Resolve-Path -LiteralPath $Apk).ProviderPath
        $source = $inputApk
    } else {
        if ($RunId -eq 0) {
            $list = Invoke-Tool 'gh' @('run', 'list', '--repo', $Repo, '--workflow', 'release.yml', '--branch', 'main',
                '--status', 'success', '--limit', '1', '--json', 'databaseId')
            Assert-Tool $list 'gh run list'
            $parsed = ConvertFrom-Json -InputObject ($list.Stdout -join "`n")
            $runs = @(foreach ($item in $parsed) { $item })
            if ($runs.Count -eq 0) {
                throw "release.yml의 main 성공 실행이 없습니다. 'gh workflow run release.yml --ref main --repo $Repo'로 새로 빌드한 뒤 다시 돌리세요."
            }
            $RunId = [long]$runs[0].databaseId
        }
        $view = Invoke-Tool 'gh' @('run', 'view', "$RunId", '--repo', $Repo, '--json', 'headSha,headBranch,event,conclusion')
        Assert-Tool $view "gh run view $RunId"
        $run = ConvertFrom-Json -InputObject ($view.Stdout -join "`n")
        $download = Join-Path $work 'artifact'
        Assert-Tool (Invoke-Tool 'gh' @('run', 'download', "$RunId", '--repo', $Repo, '--name', $ArtifactName, '--dir', $download)) `
            "gh run download $RunId (아티팩트 $ArtifactName, 보관 기간이 지났으면 새로 빌드하세요)"
        $found = @(Get-ChildItem -LiteralPath $download -Filter '*.apk' -File -Recurse)
        if ($found.Count -ne 1) { throw "아티팩트 $ArtifactName 안의 APK가 $($found.Count)개입니다 (1개여야 함)." }
        $inputApk = $found[0].FullName
        $source = 'GitHub Actions 실행 {0} ({1}, {2}, 커밋 {3}, 결과 {4})' -f $RunId, $run.event, $run.headBranch, $run.headSha.Substring(0, 7), $run.conclusion
    }

    # 2. 서명 전 확인
    $info = Get-ApkInfo $aapt2 $inputApk
    if ($info.Package -ne $PackageName) { throw "패키지 이름이 $PackageName 이 아닙니다: $($info.Package)" }
    if ($info.Debuggable) { throw 'debuggable APK는 앱 서명 키로 서명하지 않습니다. 릴리즈 APK를 쓰세요.' }
    $toSign = $inputApk
    if ((Invoke-Tool $zipalign @('-c', '-P', '16', '4', $inputApk)).ExitCode -ne 0) {
        Write-Host '정렬이 맞지 않아 zipalign으로 맞춘 사본에 서명합니다.'
        $toSign = Join-Path $work 'aligned.apk'
        Assert-Tool (Invoke-Tool $zipalign @('-f', '-P', '16', '4', $inputApk, $toSign)) 'zipalign'
    }

    # 3. 앱 서명 키로 서명. 비밀번호는 이 프로세스의 환경 변수로만 apksigner에 넘기고 바로 지웁니다
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $outApk = Join-Path $OutDir ('pillbadeum-{0}-{1}-personal.apk' -f $info.VersionName, $info.VersionCode)
    if (Test-Path -LiteralPath $outApk) { Remove-Item -LiteralPath $outApk -Force }
    $secure = Import-Clixml -LiteralPath $PasswordFile
    Set-Item -Path "Env:$PasswordVariable" -Value ([System.Net.NetworkCredential]::new('', $secure).Password)
    try {
        $sign = Invoke-Tool $apksigner @('sign', '--ks', $KeyStoreFile, '--ks-type', 'PKCS12', '--ks-key-alias', $KeyAlias,
            '--ks-pass', "env:$PasswordVariable", '--v4-signing-enabled', 'false', '--out', $outApk, $toSign)
    } finally {
        Remove-Item -Path "Env:$PasswordVariable" -ErrorAction SilentlyContinue
    }
    Assert-Tool $sign 'apksigner sign'

    # 4. 서명 뒤 확인: 서명자 하나, 인증서 SHA-256 = 앱 서명 키 지문, 정렬 그대로, 버전 번호 그대로
    $verify = Invoke-Tool $apksigner @('verify', '--print-certs', '--verbose', $outApk)
    Assert-Tool $verify 'apksigner verify'
    $digests = @($verify.Stdout | ForEach-Object {
            if ($_ -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$') { $Matches[1] }
        })
    if ($digests.Count -ne 1) { throw "서명자가 $($digests.Count)명입니다 (1명이어야 함)." }
    $actual = Format-Fingerprint $digests[0]
    if ($actual -ne $ExpectedCertSha256) {
        Remove-Item -LiteralPath $outApk -Force
        throw "서명 인증서 SHA-256이 앱 서명 키 지문과 다릅니다: $actual"
    }
    Assert-Tool (Invoke-Tool $zipalign @('-c', '-P', '16', '4', $outApk)) '서명한 APK 정렬 확인 (zipalign -c -P 16 4)'
    $signed = Get-ApkInfo $aapt2 $outApk
    if ($signed.VersionCode -ne $info.VersionCode -or $signed.Package -ne $info.Package) { throw '서명 전후의 패키지 정보가 다릅니다.' }
    $schemes = @($verify.Stdout | ForEach-Object { if ($_ -match '^Verified using (v[0-9.]+) scheme .*: true\s*$') { $Matches[1] } })

    Write-Host "개인 APK: $outApk"
    Write-Host "  원본: $source"
    Write-Host ('  패키지 {0}, versionCode {1}, versionName {2}' -f $signed.Package, $signed.VersionCode, $signed.VersionName)
    Write-Host "  서명 인증서 SHA-256: $actual (앱 서명 키와 같음), 서명 방식: $($schemes -join ', ')"
    Write-Host "  파일 SHA-256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $outApk).Hash)"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    if ($previousEncoding) { try { [Console]::OutputEncoding = $previousEncoding } catch { } }
}
