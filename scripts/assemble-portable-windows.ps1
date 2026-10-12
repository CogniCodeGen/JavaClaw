param(
    [string]$OutputDirectory = "",
    [string]$HostJar = "",
    [string]$DistributionRuntime = $env:JAVACLAW_DISTRIBUTION_RUNTIME,
    [string]$CertificateThumbprint = $env:JAVACLAW_CODESIGN_THUMBPRINT,
    [string]$SignToolPath = "signtool.exe",
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [switch]$DevelopmentUnsigned,
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
    # 进程运行参数由插件清单与批准权限控制。
    Invoke-Checked $java @("-version")
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$target = Join-Path $projectRoot "target"
if ($OutputDirectory -eq "") { $OutputDirectory = Join-Path $target "portable/JavaClaw" }
if ([string]::IsNullOrEmpty($DistributionRuntime)) {
    $DistributionRuntime = Join-Path $projectRoot "distribution/target/runtime"
}
if ($HostJar -eq "") { $HostJar = Join-Path $DistributionRuntime "desktop.jar" }
$runnerJar = Join-Path $DistributionRuntime "plugin-runner.jar"
$desktopLibraries = Join-Path $DistributionRuntime "lib"
# 链接完整 JDK 模块，避免遗漏反射或服务发现依赖；插件启用模块仍须单独声明并批准。
$runtimeModules = "ALL-MODULE-PATH"
if (-not (Test-Path -LiteralPath $runnerJar -PathType Leaf)) { throw "Run mvn package to assemble Runner" }
if (-not (Test-Path -LiteralPath $HostJar -PathType Leaf)) { throw "Missing regular host JAR: $HostJar" }
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output already exists: $OutputDirectory" }
if ($DevelopmentUnsigned) {
    if ($CertificateThumbprint -ne "") { throw "DevelopmentUnsigned cannot be combined with a signing certificate" }
} else {
    if ($CertificateThumbprint -notmatch '^[0-9a-fA-F]{40}$') {
        throw "Set a 40-character JAVACLAW_CODESIGN_THUMBPRINT for the release signing certificate"
    }
    $CertificateThumbprint = $CertificateThumbprint.ToUpperInvariant()
}

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
    # Runner 单独保存，绝不进入 jpackage 为 Desktop 生成的 classpath。
    Copy-Item -Path (Join-Path $desktopLibraries "*.jar") -Destination $inputDirectory
    Copy-Item -LiteralPath $runnerJar -Destination (Join-Path $runtime "plugin-runner.jar")

    $imageDirectory = Join-Path $stage "image"
    Invoke-Checked "jpackage.exe" @("--type", "app-image", "--dest", $imageDirectory,
        "--name", "JavaClaw", "--add-modules", $runtimeModules, "--input", $inputDirectory, "--main-jar", "javaclaw.jar",
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
    # 开发镜像只用于验证内置 Java；默认发行路径仍必须签名并核验指定证书。
    if (-not $DevelopmentUnsigned) {
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
    }

    $stagedPackages = Join-Path $DistributionRuntime "plugin-packages"
    if (Test-Path -LiteralPath $stagedPackages -PathType Container) {
        Assert-NoReparsePoints $stagedPackages
        Copy-Item -LiteralPath $stagedPackages -Destination $runtime -Recurse
    }
    $distributionManifest = Join-Path $DistributionRuntime "plugin-distribution.json"
    if (Test-Path -LiteralPath $distributionManifest -PathType Leaf) {
        Copy-Item -LiteralPath $distributionManifest -Destination $runtime
    }
    Assert-BundledJava $portableHome
    Get-ChildItem -LiteralPath $runtime -Recurse -File -Force | ForEach-Object { $_.IsReadOnly = $true }
    Move-Item -LiteralPath $portableHome -Destination $OutputDirectory
    Write-Output $OutputDirectory
}
finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
