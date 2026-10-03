param(
    [string]$ApplicationHome = ""
)

$ErrorActionPreference = "Stop"
$nativeSource = $PSScriptRoot
$projectRoot = (Resolve-Path (Join-Path $nativeSource "../../../..")).Path
$buildDirectory = Join-Path $projectRoot "target/native/windows-build"
$outputDirectory = Join-Path $projectRoot "target/native/windows"

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

cmake -S $nativeSource -B $buildDirectory -G "Visual Studio 17 2022" -A x64 `
    "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=$outputDirectory"
if ($LASTEXITCODE -ne 0) { throw "Windows desktop bridge configuration failed" }

cmake --build $buildDirectory --config Release
if ($LASTEXITCODE -ne 0) { throw "Windows desktop bridge build failed" }

$library = Join-Path $outputDirectory "javaclaw_desktop.dll"
if (-not (Test-Path -LiteralPath $library)) {
    throw "Expected DLL was not produced: $library"
}

if ($ApplicationHome -ne "") {
    New-Item -ItemType Directory -Path $ApplicationHome -Force | Out-Null
    $homePath = (Resolve-Path -LiteralPath $ApplicationHome).Path
    cmake --install $buildDirectory --config Release --prefix $homePath
    if ($LASTEXITCODE -ne 0) { throw "Windows desktop bridge installation failed" }
}

Write-Output $library
