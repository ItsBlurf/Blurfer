$ErrorActionPreference = "Stop"

$RootDir = $PSScriptRoot
Set-Location $RootDir

$VenvDir = Join-Path $RootDir ".build-venv"
$PythonExe = Join-Path $VenvDir "Scripts\python.exe"

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
Write-Host "Built: $RootDir\dist\Blurfer.exe"
