param(
    [Parameter(Mandatory = $true)][string]$JPackage,
    [Parameter(Mandatory = $true)][ValidateSet("msi", "exe")][string]$PackageType,
    [Parameter(Mandatory = $true)][string]$Destination,
    [Parameter(Mandatory = $true)][string]$AppImageRoot,
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$RuntimeImage,
    [Parameter(Mandatory = $true)][string]$WorkersDirectory,
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

$requiredWorkerMarkers = @(
    "knowledge\worker-image-v1.capability",
    "skill\worker-image-v1.capability",
    "browser\worker-image-v1.capability",
    "browser\browser-login-v1.capability",
    "browser\browser-oauth-v1.capability")
foreach ($relative in $requiredWorkerMarkers) {
    $marker = Join-Path $WorkersDirectory $relative
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
        throw "required Worker image marker is missing: $relative"
    }
}

& $JPackage --type app-image --name JavaClaw --app-version $Version `
    --vendor JavaClaw --input $InputDirectory --runtime-image $RuntimeImage `
    --main-jar $MainJar --main-class $MainClass `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options '-Djavaclaw.program.dir=$APPDIR' --dest $AppImageRoot
if ($LASTEXITCODE -ne 0) { throw "jpackage failed while creating the Windows app image" }

$appImage = Join-Path $AppImageRoot "JavaClaw"
$launcher = Join-Path $appImage "JavaClaw.exe"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "jpackage did not create the Windows application launcher"
}
$installedWorkers = Join-Path $appImage "app\workers"
Copy-Item -LiteralPath $WorkersDirectory -Destination $installedWorkers -Recurse
if (-not (Test-Path -LiteralPath (Join-Path $installedWorkers "browser\browser-login-v1.capability"))) {
    throw "the Windows app image does not contain verified Browser workers"
}
if (-not (Test-Path -LiteralPath (Join-Path $installedWorkers "browser\browser-oauth-v1.capability"))) {
    throw "the Windows app image does not contain verified OAuth Browser workers"
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

& $JPackage --type $PackageType --name JavaClaw --app-image $appImage --dest $Destination
if ($LASTEXITCODE -ne 0) { throw "jpackage failed while creating the Windows installer" }

$installers = Get-ChildItem -LiteralPath $Destination -File |
    Where-Object { $_.Extension -eq ".$PackageType" }
if ($installers.Count -eq 0) {
    throw "jpackage did not create a Windows $PackageType installer"
}

# 正式发行必须同时签署嵌入的启动器和最终安装器，避免安装后才暴露未签名入口。
if (-not [string]::IsNullOrWhiteSpace($env:JAVACLAW_WINDOWS_CERTIFICATE)) {
    foreach ($installer in $installers) {
        & signtool.exe sign /sha1 $env:JAVACLAW_WINDOWS_CERTIFICATE /fd SHA256 `
            /tr $env:JAVACLAW_WINDOWS_TIMESTAMP_URL /td SHA256 $installer.FullName
        if ($LASTEXITCODE -ne 0) { throw "signtool failed for $($installer.Name)" }
        $signature = Get-AuthenticodeSignature -LiteralPath $installer.FullName
        if ($signature.Status -ne "Valid") {
            throw "the Windows installer has an invalid Authenticode signature: $($installer.Name)"
        }
    }
}
