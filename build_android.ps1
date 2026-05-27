$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
$AndroidDir = Join-Path $RootDir "android"
$DistDir = Join-Path $RootDir "dist"

if (-not $env:JAVA_HOME) {
    $ScoopJava = Join-Path $env:USERPROFILE "scoop\apps\openjdk17\current"
    if (Test-Path (Join-Path $ScoopJava "bin\javac.exe")) {
        $env:JAVA_HOME = $ScoopJava
    }
}

if ($env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $ScoopAndroid = Join-Path $env:USERPROFILE "scoop\apps\android-clt\current"
    if (Test-Path (Join-Path $ScoopAndroid "cmdline-tools\latest\bin\sdkmanager.bat")) {
        $env:ANDROID_HOME = $ScoopAndroid
        $env:ANDROID_SDK_ROOT = $ScoopAndroid
    }
}

if ($env:ANDROID_HOME) {
    $env:Path = "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
}

Set-Location $AndroidDir

$GradleCommand = $null
$GradleWrapper = Join-Path $AndroidDir "gradlew.bat"
if (Test-Path $GradleWrapper) {
    $GradleCommand = $GradleWrapper
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    $GradleCommand = "gradle"
} else {
    Write-Error "Gradle was not found. Install Android Studio or Gradle, then rerun this script."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Error "Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or open the android folder in Android Studio."
}

& $GradleCommand :app:assembleDebug

$ApkPath = Join-Path $AndroidDir "app\build\outputs\apk\debug\app-debug.apk"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
Copy-Item -LiteralPath $ApkPath -Destination (Join-Path $DistDir "Blurfer.apk") -Force

Write-Host ""
Write-Host "Built: $AndroidDir\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Copied: $DistDir\Blurfer.apk"
