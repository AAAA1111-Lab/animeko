#Requires -Version 5.1
<#
.SYNOPSIS
    更新仓库中的版本号.

.DESCRIPTION
    修改以下位置的版本号并保持一致:
      - gradle.properties: version.name / package.version
      - gradle.properties: android.version.code / ios.version.code (可选)
      - .github/workflows/manual-build.yml: CODE_VERSION 回退默认值

    package.version 自动取 version.name 去掉 "-元数据" 后缀的值
    (例如 6.2.0.2-alpha01 -> 6.2.0.2).

.PARAMETER VersionName
    完整版本名, 例如 "6.2.0.2-alpha01" 或正式版 "6.2.1".

.PARAMETER VersionCode
    可选. 显式指定 android.version.code 与 ios.version.code (发布到商店时应单调递增).

.PARAMETER BumpVersionCode
    可选开关. 未提供 -VersionCode 时, 将 android/ios 的 version.code 各 +1.

.EXAMPLE
    .\scripts\set-version.ps1 -VersionName 6.2.0.2-alpha01

.EXAMPLE
    .\scripts\set-version.ps1 -VersionName 6.2.1 -BumpVersionCode
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [ValidateRange(1, [int]::MaxValue)]
    [int]$VersionCode = 0,

    [switch]$BumpVersionCode
)

$ErrorActionPreference = 'Stop'

if ($VersionName -notmatch '^\d+(\.\d+){1,3}(-[0-9A-Za-z][0-9A-Za-z.-]*)?$') {
    throw "版本号格式不正确: '$VersionName'. 期望形如 6.2.1 或 6.2.0.2-alpha01"
}
if ($VersionCode -gt 0 -and $BumpVersionCode) {
    throw "-VersionCode 与 -BumpVersionCode 不能同时使用"
}
$packageVersion = ($VersionName -split '-')[0]

$repoRoot = Split-Path -Parent $PSScriptRoot
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# --- 1. gradle.properties ---
$propertiesPath = Join-Path $repoRoot 'gradle.properties'
$content = [System.IO.File]::ReadAllText($propertiesPath)

if (-not [regex]::IsMatch($content, '(?m)^version\.name=')) {
    throw "gradle.properties 中未找到 version.name"
}
$content = [regex]::Replace($content, '(?m)^version\.name=.*$', "version.name=$VersionName")
$content = [regex]::Replace($content, '(?m)^package\.version=.*$', "package.version=$packageVersion")

if ($VersionCode -gt 0) {
    $content = [regex]::Replace($content, '(?m)^android\.version\.code=\d+$', "android.version.code=$VersionCode")
    $content = [regex]::Replace($content, '(?m)^ios\.version\.code=\d+$', "ios.version.code=$VersionCode")
} elseif ($BumpVersionCode) {
    foreach ($platform in 'android', 'ios') {
        $match = [regex]::Match($content, "(?m)^$platform\.version\.code=(\d+)(\r?)$")
        if (-not $match.Success) {
            throw "gradle.properties 中未找到 $platform.version.code"
        }
        $next = [int]$match.Groups[1].Value + 1
        $content = $content.Remove($match.Index, $match.Length).
            Insert($match.Index, "$platform.version.code=$next$($match.Groups[2].Value)")
    }
}
[System.IO.File]::WriteAllText($propertiesPath, $content, $utf8NoBom)

# --- 2. manual-build.yml 的 CODE_VERSION 回退默认值 ---
$workflowPath = Join-Path $repoRoot '.github\workflows\manual-build.yml'
$workflowContent = [System.IO.File]::ReadAllText($workflowPath)
$codeVersionPattern = 'CODE_VERSION="[0-9][0-9A-Za-z.-]*"'
if ([regex]::IsMatch($workflowContent, $codeVersionPattern)) {
    $workflowContent = [regex]::Replace($workflowContent, $codeVersionPattern, "CODE_VERSION=`"$VersionName`"")
    [System.IO.File]::WriteAllText($workflowPath, $workflowContent, $utf8NoBom)
} else {
    Write-Warning "未在 manual-build.yml 中找到 CODE_VERSION 回退默认值, 已跳过; 请手动确认."
}

# --- 摘要 ---
$finalCodes = [regex]::Matches($content, '(?m)^(android|ios)\.version\.code=(\d+)$')
Write-Host "版本号已更新:" -ForegroundColor Green
Write-Host "  version.name     = $VersionName"
Write-Host "  package.version  = $packageVersion"
foreach ($code in $finalCodes) {
    Write-Host ("  {0} version.code = {1}" -f $code.Groups[1].Value, $code.Groups[2].Value)
}
Write-Host ""
$modifiedFiles = "gradle.properties"
if (Test-Path $workflowPath) { $modifiedFiles += ", .github/workflows/manual-build.yml" }
Write-Host "已修改: $modifiedFiles"
Write-Host "请用 'git diff' 确认后自行提交."
