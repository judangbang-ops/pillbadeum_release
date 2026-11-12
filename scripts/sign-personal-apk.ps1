<#
.SYNOPSIS
CI가 만든 서명하지 않은 릴리즈 APK를 앱 서명 키로 서명해 개인 설치용 APK를 만듭니다 (TASK-002, REQ-062).

.DESCRIPTION
동희님 PC에서 돌립니다. 앱 서명 키는 이 PC와 Google Drive 사본에만 있고 GitHub에는 없습니다.

1. GitHub Actions '릴리즈 빌드'(release.yml)의 main 최근 성공 실행에서 아티팩트 release-apk-unsigned를 받습니다.
   -RunId로 실행을 고를 수 있습니다. 개인 APK로 서명하는 것은 main에 들어간 커밋의 release.yml 성공 실행뿐입니다.
2. 패키지 이름과 debuggable이 아님을 확인하고, zipalign으로 정렬(4바이트, 네이티브 라이브러리 16KB 페이지)을 확인합니다.
3. apksigner로 앱 서명 키(별칭 app-signing)로 작업 폴더 안에서 서명합니다.
4. apksigner verify --print-certs로 서명자가 하나이고 인증서 SHA-256이 앱 서명 키 지문과 같은지, 정렬과 패키지 정보가
   그대로인지 확인합니다. 모두 통과해야 -OutDir로 옮깁니다.

main 릴리즈 빌드가 아닌 APK(ci.yml의 PR 실행, 다른 브랜치, -Apk로 준 파일)는 -Test를 붙여야 서명하고, 파일 이름이
-test.apk가 됩니다. 그 버전 번호는 뒤의 main 빌드보다 클 수 있어서 쓰는 폰에 깔면 main 개인 APK가 버전 번호 때문에
깔리지 않을 수 있습니다(INSTALL_FAILED_VERSION_DOWNGRADE). 시험 기기에만 깝니다.

비밀번호는 키 폴더의 DPAPI 파일(이 PC의 이 Windows 계정에서만 풀림)에서 읽어 apksigner에 환경 변수로만 넘깁니다.
화면, 명령줄 인수, 파일 어디에도 비밀번호를 쓰지 않습니다. 비밀번호 파일을 다시 만들 때는 이 PC, 이 계정에서
  Read-Host -AsSecureString | Export-Clixml -LiteralPath <키 폴더>\app-signing-password.dpapi.xml
로 만듭니다. Get-Credential | Export-Clixml로 만든 파일도 읽습니다.

필요한 것: Android SDK build-tools 35 이상(lib\apksigner.jar, zipalign, aapt2), JDK 17 이상(JAVA_HOME이나 PATH의 java),
로그인한 GitHub CLI(gh). -Apk만 쓸 때는 gh가 없어도 됩니다.

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sign-personal-apk.ps1

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sign-personal-apk.ps1 -RunId 1234567890 -Test -OutDir C:\temp\apk
#>
[CmdletBinding()]
param(
    # 서명할 APK 파일. 주면 GitHub에서 받지 않습니다. 어느 빌드인지 확인할 수 없어서 -Test가 필요합니다
    [string]$Apk,
    # 아티팩트를 받을 GitHub Actions 실행 ID. 안 주면 release.yml의 main 최근 성공 실행
    [long]$RunId = 0,
    # main 릴리즈 빌드가 아닌 APK를 시험용으로 서명합니다 (파일 이름 -test.apk)
    [switch]$Test,
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
# GitHub API가 주는 워크플로 경로. 개인 APK는 이 워크플로의 성공 실행에서만 만듦
$ReleaseWorkflowPattern = '^\.github/workflows/release\.yml(@.*)?$'

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

function Test-BuildTools {
    param([string]$Dir)
    foreach ($name in @('lib\apksigner.jar', 'zipalign.exe', 'aapt2.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Dir $name))) { return $false }
    }
    return $true
}

function Find-BuildTools {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'build-tools')) }
    foreach ($root in $roots) {
        $best = Get-ChildItem -LiteralPath (Join-Path $root 'build-tools') -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and [version]$_.Name -ge [version]'35.0.0' -and (Test-BuildTools $_.FullName) } |
            Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1
        if ($best) { return $best.FullName }
    }
    throw 'Android SDK build-tools 35 이상(lib\apksigner.jar, zipalign, aapt2)을 찾지 못했습니다. ANDROID_HOME이나 %LOCALAPPDATA%\Android\Sdk를 확인하세요.'
}

function Find-Java {
    # apksigner.jar를 돌릴 java.exe. apksigner.bat를 거치지 않아 cmd가 인수를 다시 나누는 일이 없음.
    # JAVA_HOME을 먼저 보고, 없으면 PATH에서 찾습니다
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = @(Get-Command 'java' -CommandType Application -ErrorAction SilentlyContinue)
    if ($command.Count -gt 0) { return $command[0].Path }
    throw 'JDK(java.exe)를 찾지 못했습니다. JDK 17 이상을 깔고 JAVA_HOME이나 PATH를 확인하세요.'
}

function Get-StoredPassword {
    # DPAPI로 저장한 비밀번호 파일(Export-Clixml)을 풉니다. SecureString과 PSCredential 둘 다 받습니다
    param([string]$Path)
    $stored = Import-Clixml -LiteralPath $Path
    if ($stored -is [System.Management.Automation.PSCredential]) { return $stored.GetNetworkCredential().Password }
    if ($stored -is [System.Security.SecureString]) { return [System.Net.NetworkCredential]::new('', $stored).Password }
    throw "비밀번호 파일이 SecureString이나 PSCredential이 아닙니다: $Path"
}

function Get-RunCheck {
    # 실행 정보와, 그 실행이 main에 들어간 커밋의 release.yml 성공 실행인지 돌려줍니다
    param([long]$Id)
    $runResult = Invoke-Tool 'gh' @('api', "repos/$Repo/actions/runs/$Id")
    Assert-Tool $runResult "gh api (실행 $Id 정보)"
    $run = ConvertFrom-Json -InputObject ($runResult.Stdout -join "`n")
    # main...커밋: 그 커밋이 main 이력 안에 있으면 identical(같음)이나 behind(main보다 뒤)
    $compare = Invoke-Tool 'gh' @('api', "repos/$Repo/compare/main...$($run.head_sha)", '--jq', '.status')
    Assert-Tool $compare 'gh api (main과 커밋 비교)'
    $relation = ($compare.Stdout -join '').Trim()
    [pscustomobject]@{
        Run       = $run
        Relation  = $relation
        IsRelease = ([string]$run.path -match $ReleaseWorkflowPattern)
        Success   = ($run.conclusion -eq 'success')
        OnMain    = (@('identical', 'behind') -contains $relation)
    }
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
    foreach ($path in @($KeyStoreFile, $PasswordFile)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "앱 서명 키 파일이 없습니다: $path" }
    }
    $buildTools = Find-BuildTools
    $java = Find-Java
    $apksignerJar = Join-Path $buildTools 'lib\apksigner.jar'
    $zipalign = Join-Path $buildTools 'zipalign.exe'
    $aapt2 = Join-Path $buildTools 'aapt2.exe'

    # 1. 서명할 APK의 출처. 개인 APK는 main에 들어간 커밋의 release.yml 성공 실행에서만 만듦
    if ($Apk) {
        $inputApk = (Resolve-Path -LiteralPath $Apk).ProviderPath
        $isMainRelease = $false
        $source = "파일 $inputApk (어느 빌드인지 확인할 수 없음)"
    } else {
        if (-not (Get-Command 'gh' -CommandType Application -ErrorAction SilentlyContinue)) {
            throw 'GitHub CLI(gh)를 찾지 못했습니다. gh를 깔고 gh auth login으로 로그인하거나, -Apk와 -Test로 파일을 직접 주세요.'
        }
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
        $check = Get-RunCheck $RunId
        $run = $check.Run
        $isMainRelease = $check.IsRelease -and $check.Success -and $check.OnMain
        $source = 'GitHub Actions 실행 {0} ({1}, {2} {3}, 커밋 {4}, 결과 {5}, main과의 관계 {6})' -f $RunId, $run.path, $run.event, $run.head_branch, $run.head_sha.Substring(0, 7), $run.conclusion, $check.Relation
    }
    if (-not $isMainRelease -and -not $Test) {
        throw ('개인 APK는 main에 들어간 커밋의 release.yml 성공 실행에서만 만듭니다. 이번 APK: {0}{1}' +
            'PR·다른 브랜치·-Apk 파일의 버전 번호는 뒤의 main 빌드보다 클 수 있어서, 쓰는 폰에 깔면 main 개인 APK가 깔리지 않을 수 있습니다. ' +
            '시험용으로만 서명하려면 -Test를 붙이세요 (파일 이름이 -test.apk가 됨).') -f $source, [Environment]::NewLine
    }
    $kind = if ($isMainRelease -and -not $Test) { 'personal' } else { 'test' }
    if (-not $Apk) {
        $download = Join-Path $work 'artifact'
        Assert-Tool (Invoke-Tool 'gh' @('run', 'download', "$RunId", '--repo', $Repo, '--name', $ArtifactName, '--dir', $download)) `
            "gh run download $RunId (아티팩트 $ArtifactName, 보관 기간이 지났으면 새로 빌드하세요)"
        $found = @(Get-ChildItem -LiteralPath $download -Filter '*.apk' -File -Recurse)
        if ($found.Count -ne 1) { throw "아티팩트 $ArtifactName 안의 APK가 $($found.Count)개입니다 (1개여야 함)." }
        $inputApk = $found[0].FullName
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

    # 3. 앱 서명 키로 작업 폴더 안에서 서명. 비밀번호는 이 프로세스의 환경 변수로만 apksigner에 넘기고 바로 지웁니다
    #    (PKCS12라 키 비밀번호도 같은 값)
    $signedApk = Join-Path $work 'signed.apk'
    Set-Item -Path "Env:$PasswordVariable" -Value (Get-StoredPassword $PasswordFile)
    try {
        $sign = Invoke-Tool $java @('-jar', $apksignerJar, 'sign', '--ks', $KeyStoreFile, '--ks-type', 'PKCS12', '--ks-key-alias', $KeyAlias,
            '--ks-pass', "env:$PasswordVariable", '--key-pass', "env:$PasswordVariable", '--v4-signing-enabled', 'false',
            '--out', $signedApk, $toSign)
    } finally {
        Remove-Item -Path "Env:$PasswordVariable" -ErrorAction SilentlyContinue
    }
    Assert-Tool $sign 'apksigner sign'

    # 4. 서명 뒤 확인: 서명자 하나, 인증서 SHA-256 = 앱 서명 키 지문, 정렬 그대로, 패키지 정보 그대로.
    #    하나라도 어긋나면 작업 폴더째 지우므로 -OutDir에는 확인을 모두 통과한 파일만 남음
    $verify = Invoke-Tool $java @('-jar', $apksignerJar, 'verify', '--print-certs', '--verbose', $signedApk)
    Assert-Tool $verify 'apksigner verify'
    # apksigner 0.9(build-tools 37)는 'V3.0 Signer: certificate SHA-256 digest: …', 그 전 판은
    # 'Signer #1 certificate SHA-256 digest: …'로 씀. 어느 꼴이든 서명자는 하나, 서명 인증서도 하나여야 함
    $signerCounts = @($verify.Stdout | ForEach-Object { if ($_ -match '^Number of signers: (\d+)\s*$') { [int]$Matches[1] } })
    if ($signerCounts.Count -ne 1 -or $signerCounts[0] -ne 1) { throw "서명자가 1명이 아닙니다 (apksigner의 Number of signers: $($signerCounts -join ', '))." }
    $digests = @($verify.Stdout | ForEach-Object {
            if ($_ -match 'Signer\b.*\bcertificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$') { Format-Fingerprint $Matches[1] }
        } | Sort-Object -Unique)
    if ($digests.Count -ne 1) { throw "서명 인증서가 $($digests.Count)개입니다 (1개여야 함)." }
    $actual = $digests[0]
    if ($actual -ne $ExpectedCertSha256) { throw "서명 인증서 SHA-256이 앱 서명 키 지문과 다릅니다: $actual" }
    Assert-Tool (Invoke-Tool $zipalign @('-c', '-P', '16', '4', $signedApk)) '서명한 APK 정렬 확인 (zipalign -c -P 16 4)'
    $signed = Get-ApkInfo $aapt2 $signedApk
    if ($signed.VersionCode -ne $info.VersionCode -or $signed.Package -ne $info.Package -or $signed.Debuggable) {
        throw '서명 전후의 패키지 정보가 다릅니다.'
    }
    $schemes = @($verify.Stdout | ForEach-Object { if ($_ -match '^Verified using (v[0-9.]+) scheme .*: true\s*$') { $Matches[1] } })

    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $outApk = Join-Path $OutDir ('pillbadeum-{0}-{1}-{2}.apk' -f $signed.VersionName, $signed.VersionCode, $kind)
    Move-Item -LiteralPath $signedApk -Destination $outApk -Force

    if ($kind -eq 'personal') { Write-Host "개인 APK: $outApk" } else { Write-Host "시험용 APK: $outApk" }
    Write-Host "  원본: $source"
    Write-Host ('  패키지 {0}, versionCode {1}, versionName {2}' -f $signed.Package, $signed.VersionCode, $signed.VersionName)
    Write-Host "  서명 인증서 SHA-256: $actual (앱 서명 키와 같음), 서명 방식: $($schemes -join ', ')"
    Write-Host "  파일 SHA-256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $outApk).Hash)"
    if ($kind -eq 'test') {
        Write-Host '  시험용입니다. 쓰는 폰에 깔면 버전 번호 때문에 뒤의 main 개인 APK가 깔리지 않을 수 있으니 시험 기기에만 까세요.'
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    if ($previousEncoding) { try { [Console]::OutputEncoding = $previousEncoding } catch { } }
}
