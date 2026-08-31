param(
    [Parameter(Mandatory = $true)][string]$JPackage,
    [Parameter(Mandatory = $true)][string]$Destination,
    [Parameter(Mandatory = $true)][string]$AppImageRoot,
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$RuntimeImage,
    [Parameter(Mandatory = $true)][string]$MainJar,
    [Parameter(Mandatory = $true)][string]$MainClass,
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = "Stop"

if (Test-Path -LiteralPath $AppImageRoot) {
    Remove-Item -LiteralPath $AppImageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $AppImageRoot -Force | Out-Null
New-Item -ItemType Directory -Path $Destination -Force | Out-Null

& $JPackage --type app-image --name JavaClaw --app-version $Version `
    --vendor JavaClaw --input $InputDirectory --runtime-image $RuntimeImage `
    --main-jar $MainJar --main-class $MainClass `
    --java-options '-Djavaclaw.program.dir=$APPDIR' --dest $AppImageRoot
if ($LASTEXITCODE -ne 0) { throw "jpackage failed while creating the Windows app image" }

$appImage = Join-Path $AppImageRoot "JavaClaw"
$launcher = Join-Path $appImage "JavaClaw.exe"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "jpackage did not create the Windows application launcher"
}

# Formal releases sign the executable inside the app image before WiX embeds it in
# MSI/EXE installers. Runtime DLLs retain their upstream signatures.
if (-not [string]::IsNullOrWhiteSpace($env:JAVACLAW_WINDOWS_CERTIFICATE)) {
    if ([string]::IsNullOrWhiteSpace($env:JAVACLAW_WINDOWS_TIMESTAMP_URL)) {
        throw "JAVACLAW_WINDOWS_TIMESTAMP_URL is required for Authenticode signing"
    }
    & signtool.exe sign /sha1 $env:JAVACLAW_WINDOWS_CERTIFICATE /fd SHA256 `
        /tr $env:JAVACLAW_WINDOWS_TIMESTAMP_URL /td SHA256 $launcher
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for the packaged application launcher" }
    $signature = Get-AuthenticodeSignature -LiteralPath $launcher
    if ($signature.Status -ne "Valid") {
        throw "the packaged application launcher has an invalid Authenticode signature"
    }
}
