$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
Set-Location $RootDir

$VenvDir = Join-Path $RootDir ".build-venv"
$PythonExe = Join-Path $VenvDir "Scripts\python.exe"
$OutputExe = Join-Path $RootDir "dist\Blurfer.exe"

$RunningBuild = Get-Process -Name "Blurfer" -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $OutputExe }
if ($RunningBuild) {
    throw "Close the Blurfer instance running from $OutputExe before rebuilding."
}

if (-not (Test-Path $PythonExe)) {
    python -m venv $VenvDir
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

& $PythonExe -m pip install -r requirements-build.txt
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $PythonExe -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name Blurfer `
    --icon "assets\blurfer.ico" `
    --add-data "assets;assets" `
    blurfer.py
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Built: $OutputExe"
