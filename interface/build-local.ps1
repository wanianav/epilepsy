param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$jdkRoot = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java') -Directory | Select-Object -First 1
if (-not $jdkRoot) { throw 'Local Java tools are missing. Open the project in Android Studio.' }
$env:JAVA_HOME = $jdkRoot.FullName
$env:ANDROID_SDK_ROOT = Join-Path $taskRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.tools\gradle-home'
$sdkPath = $env:ANDROID_SDK_ROOT.Replace('\', '/')
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Value "sdk.dir=$sdkPath"
$gradlePath = Join-Path $taskRoot '.tools\gradle\gradle-8.11.1\bin\gradle.bat'
Push-Location $PSScriptRoot
try {
    $tasks = @(':app:assembleDebug')
    if ($Verify) { $tasks += ':app:testDebugUnitTest', ':app:lintDebug' }
    & $gradlePath @tasks --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
