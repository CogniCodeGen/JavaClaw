param(
    [string]$OutputDirectory = "",
    [string]$HostJar = "",
    [string]$CertificateThumbprint = $env:JAVACLAW_CODESIGN_THUMBPRINT,
    [string]$SignToolPath = "signtool.exe",
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

function Assert-NoReparsePoints([string]$Directory) {
    $entries = @(Get-Item -LiteralPath $Directory) + @(Get-ChildItem -LiteralPath $Directory -Recurse -Force)
    foreach ($entry in $entries) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Portable home contains a reparse point: $($entry.FullName)"
        }
    }
}

function Assert-BundledJava([string]$PortableHome) {
    Assert-NoReparsePoints $PortableHome
    $java = Join-Path $PortableHome "runtime/JavaClaw/runtime/bin/java.exe"
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw "Missing regular bundled Java executable: $java"
    }
    # 使用服务插件的固定模块与访问参数检查内置 Java。
    Invoke-Checked $java @("--add-modules", "jdk.httpserver,jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-version")
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$target = Join-Path $projectRoot "target"
if ($OutputDirectory -eq "") { $OutputDirectory = Join-Path $target "portable/JavaClaw" }
if ($HostJar -eq "") {
    $candidateJars = @(Get-ChildItem -LiteralPath $target -Filter "javaclaw-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(service-plugin-api|sources|javadoc|tests)\.jar$' })
    if ($candidateJars.Count -ne 1) {
        throw "Expected one Maven host JAR in target; run 'mvn -DskipTests package' first or pass -HostJar"
    }
    $HostJar = $candidateJars[0].FullName
}
if (-not (Test-Path -LiteralPath $HostJar -PathType Leaf)) { throw "Missing regular host JAR: $HostJar" }
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output already exists: $OutputDirectory" }
if ($CertificateThumbprint -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Set a 40-character JAVACLAW_CODESIGN_THUMBPRINT for the release signing certificate"
}
$CertificateThumbprint = $CertificateThumbprint.ToUpperInvariant()

Invoke-Checked "jpackage.exe" @("--version")
if ($LASTEXITCODE -ne 0) { throw "JDK 25 jpackage is required" }
$jpackageVersion = (& jpackage.exe --version | Out-String).Trim()
if (-not $jpackageVersion.StartsWith("25")) { throw "JDK 25 jpackage is required: $jpackageVersion" }

New-Item -ItemType Directory -Path $target -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputDirectory) -Force | Out-Null
$stage = Join-Path $target (".portable-windows." + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stage | Out-Null

try {
    $inputDirectory = Join-Path $stage "input"
    $portableHome = Join-Path $stage "home"
    $runtime = Join-Path $portableHome "runtime"
    $plugins = Join-Path $portableHome "plugins"
    $data = Join-Path $portableHome "data"
    New-Item -ItemType Directory -Path $inputDirectory, $runtime, $plugins, $data -Force | Out-Null
    Copy-Item -LiteralPath $HostJar -Destination (Join-Path $inputDirectory "javaclaw.jar")
    $mavenArgs = @("--batch-mode", "--no-transfer-progress", "-q", "-f", (Join-Path $projectRoot "pom.xml"))
    if ($Offline) { $mavenArgs += "-o" }
    $mavenArgs += @("dependency:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=$inputDirectory")
    Invoke-Checked "mvn.cmd" $mavenArgs

    $imageDirectory = Join-Path $stage "image"
    Invoke-Checked "jpackage.exe" @("--type", "app-image", "--dest", $imageDirectory,
        "--name", "JavaClaw", "--input", $inputDirectory, "--main-jar", "javaclaw.jar",
        "--main-class", "com.javaclaw.app.Launcher",
        # 保留服务插件需要的 bin/java.exe，继续使用其他默认裁剪选项。
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--java-options", "--add-modules=jdk.incubator.vector,jdk.httpserver",
        "--java-options", "--enable-native-access=ALL-UNNAMED")
    $appImage = Join-Path $imageDirectory "JavaClaw"
    if (-not (Test-Path -LiteralPath $appImage -PathType Container)) {
        throw "jpackage did not produce a JavaClaw app image"
    }
    Move-Item -LiteralPath $appImage -Destination (Join-Path $runtime "JavaClaw")
    Assert-BundledJava $portableHome

    $launcher = Join-Path $runtime "JavaClaw/JavaClaw.exe"
    $bundledJava = Join-Path $runtime "JavaClaw/runtime/bin/java.exe"
    foreach ($file in @($launcher, $bundledJava)) {
        Invoke-Checked $SignToolPath @("sign", "/fd", "SHA256", "/sha1", $CertificateThumbprint,
            "/tr", $TimestampUrl, "/td", "SHA256", $file)
        Invoke-Checked $SignToolPath @("verify", "/pa", "/v", $file)
        $signature = Get-AuthenticodeSignature -LiteralPath $file
        if ($signature.Status -ne "Valid" -or
                $signature.SignerCertificate.Thumbprint.ToUpperInvariant() -ne $CertificateThumbprint) {
            throw "Unexpected or invalid Authenticode signature: $file"
        }
    }

    $stagedPlugins = Join-Path $target "distribution/plugins"
    if (Test-Path -LiteralPath $stagedPlugins -PathType Container) {
        Assert-NoReparsePoints $stagedPlugins
        Get-ChildItem -LiteralPath $stagedPlugins -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $plugins -Recurse -Force
        }
    }
    Assert-BundledJava $portableHome
    Get-ChildItem -LiteralPath $runtime -Recurse -File -Force | ForEach-Object { $_.IsReadOnly = $true }
    Move-Item -LiteralPath $portableHome -Destination $OutputDirectory
    Write-Output $OutputDirectory
}
finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
