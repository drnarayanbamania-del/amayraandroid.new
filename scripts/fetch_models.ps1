# Fetch large TTS models excluded from git (GitHub 100 MB per-file limit).
# Run once after cloning; idempotent — skips if model.int8.onnx already exists.

$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "..\maya\app\src\main\assets\tts\kokoro"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
$tarball = Join-Path $env:TEMP "kokoro-int8-multi-lang-v1_1.tar.bz2"
$model = Join-Path $dest "model.int8.onnx"

if (Test-Path $model) {
    Write-Host "model.int8.onnx already present — nothing to do."
    exit 0
}

Write-Host "Downloading Kokoro bundle (~147 MB)..."
Invoke-WebRequest -Uri $url -OutFile $tarball

# The tarball contains the full bundle; we only need the big .onnx here
# (voices.bin/tokens.txt/espeak-ng-data are already committed).
Write-Host "Extracting model.int8.onnx..."
tar -xjf $tarball -C $env:TEMP kokoro-int8-multi-lang-v1_1/model.int8.onnx
Move-Item (Join-Path $env:TEMP "kokoro-int8-multi-lang-v1_1\model.int8.onnx") $model -Force
Remove-Item $tarball -ErrorAction SilentlyContinue

Write-Host "Done: $model ($([math]::Round((Get-Item $model).Length/1MB)) MB)"
