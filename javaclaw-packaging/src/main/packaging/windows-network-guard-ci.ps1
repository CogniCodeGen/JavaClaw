param(
    [Parameter(Mandatory = $true)][ValidateSet("Prepare", "Cleanup")][string]$Mode
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 仅临时 GitHub Windows Runner 可创建本机测试信任；不是生产安装或签名替代路径。
if ($env:GITHUB_ACTIONS -ne "true" -or $env:RUNNER_OS -ne "Windows" -or
    $env:RUNNER_ENVIRONMENT -ne "github-hosted" -or
    [string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
    throw "the network guard test fixture is restricted to ephemeral GitHub Windows runners"
}
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "the real Windows guard fixture requires the runner administrator token"
}

$fixtureRoot = Join-Path $env:ProgramFiles "JavaClawGuardTest"
$fixtureRuntime = Join-Path $fixtureRoot "runtime"
$guard = Join-Path $fixtureRoot "app\native\JavaClawNetworkGuard.exe"
$statePath = Join-Path $env:RUNNER_TEMP "javaclaw-network-guard-ci.json"
$buildRoot = Join-Path $env:RUNNER_TEMP "javaclaw-network-guard-build"

function Invoke-FixedGuard([string]$Argument) {
    if ($Argument -notin @("--install", "--uninstall")) { throw "unsupported guard fixture action" }
    & $guard $Argument
    if ($LASTEXITCODE -ne 0) { throw "fixed guard $Argument failed: $LASTEXITCODE" }
}

function Remove-TestFixture {
    if (-not (Test-Path -LiteralPath $statePath)) { return }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.thumbprint -and $state.thumbprint -notmatch "^[A-Fa-f0-9]{40}$") {
        throw "invalid test certificate identity"
    }
    $failure = $null
    try {
        $service = Get-Service -Name JavaClawNetworkGuard -ErrorAction SilentlyContinue
        if ($service) {
            if (-not (Test-Path -LiteralPath $guard -PathType Leaf)) {
                throw "guard service exists without its signed cleanup executable"
            }
            Invoke-FixedGuard "--uninstall"
        }
        if (Get-Service -Name JavaClawNetworkGuard -ErrorAction SilentlyContinue) {
            throw "guard service was not removed"
        }
        # 防止测试签名的 Java 被误带入发行目录；jlink 正常从未修改的 jmods 生成运行库。
        $distributionJava = Join-Path $env:GITHUB_WORKSPACE "javaclaw-packaging\target\distribution\runtime\bin\java.exe"
        if (Test-Path -LiteralPath $distributionJava) {
            $signature = Get-AuthenticodeSignature -LiteralPath $distributionJava
            if ($signature.SignerCertificate -and $signature.SignerCertificate.Thumbprint -eq $state.thumbprint) {
                throw "a test-signed Java executable entered the distribution"
            }
        }
    } catch {
        $failure = $_
    } finally {
        if ($state.originalJavaHome) {
            "JAVA_HOME=$($state.originalJavaHome)" | Out-File $env:GITHUB_ENV -Append -Encoding utf8
        }
    }
    if (Get-Service -Name JavaClawNetworkGuard -ErrorAction SilentlyContinue) {
        # 保留签名信任与清理程序供 always 步骤重试，不能先撤信任导致固定卸载再也无法验签。
        if ($failure) { throw $failure }
        throw "guard service remains active after cleanup"
    }
    foreach ($store in @("My", "Root", "TrustedPublisher")) {
        if ($state.thumbprint) {
            $certificatePath = "Cert:\LocalMachine\$store\$($state.thumbprint)"
            if (Test-Path -LiteralPath $certificatePath) {
                if ($store -eq "My") {
                    Remove-Item -LiteralPath $certificatePath -DeleteKey -Force
                } else {
                    Remove-Item -LiteralPath $certificatePath -Force
                }
            }
        }
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
    Remove-Item -LiteralPath $statePath -Force
    if ($failure) { throw $failure }
}

if ($Mode -eq "Cleanup") {
    Remove-TestFixture
    exit 0
}

if ((Test-Path -LiteralPath $fixtureRoot) -or (Test-Path -LiteralPath $statePath) -or
    (Get-Service -Name JavaClawNetworkGuard -ErrorAction SilentlyContinue)) {
    throw "the ephemeral runner already contains guard resources; refusing to replace them"
}
$originalJavaHome = (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
$state = @{ originalJavaHome = $originalJavaHome; thumbprint = "" }
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
try {
    & (Join-Path $PSScriptRoot "build-windows-network-guard.ps1") `
        -SourceDirectory (Join-Path $env:GITHUB_WORKSPACE "javaclaw-native-hosts\src\main\native\windows-network-guard") `
        -OutputDirectory $buildRoot
    New-Item -ItemType Directory -Path (Split-Path -Parent $guard) -Force | Out-Null
    Copy-Item -LiteralPath $originalJavaHome -Destination $fixtureRuntime -Recurse
    Copy-Item -LiteralPath (Join-Path $buildRoot "JavaClawNetworkGuard.exe") -Destination $guard

    $certificate = New-SelfSignedCertificate -Type CodeSigningCert `
        -Subject "CN=JavaClaw ephemeral Windows guard verification" `
        -CertStoreLocation Cert:\LocalMachine\My -KeyAlgorithm RSA -KeyLength 3072 `
        -HashAlgorithm SHA256 -KeyExportPolicy NonExportable -NotAfter (Get-Date).AddDays(2)
    $state.thumbprint = $certificate.Thumbprint
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    $publicCertificate = Join-Path $env:RUNNER_TEMP "javaclaw-network-guard-test.cer"
    Export-Certificate -Cert $certificate -FilePath $publicCertificate | Out-Null
    foreach ($store in @("Root", "TrustedPublisher")) {
        Import-Certificate -FilePath $publicCertificate -CertStoreLocation "Cert:\LocalMachine\$store" | Out-Null
    }
    Remove-Item -LiteralPath $publicCertificate -Force
    foreach ($executable in @($guard, (Join-Path $fixtureRuntime "bin\java.exe"))) {
        $signed = Set-AuthenticodeSignature -LiteralPath $executable -Certificate $certificate -HashAlgorithm SHA256
        if ($signed.Status -ne "Valid") { throw "test Authenticode signing failed: $executable" }
    }

    # 与正式安装相同：所有可替换层级归 Administrators，普通 Users 只有读取/执行。
    & icacls.exe $fixtureRoot /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" `
        "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-32-545:(OI)(CI)RX"
    if ($LASTEXITCODE -ne 0) { throw "cannot protect fixture root ACL" }
    & icacls.exe "$fixtureRoot\*" /reset /T /C
    if ($LASTEXITCODE -ne 0) { throw "cannot reset fixture descendants to protected inheritance" }
    & icacls.exe $fixtureRoot /setowner "*S-1-5-32-544" /T /C
    if ($LASTEXITCODE -ne 0) { throw "cannot establish administrator ownership" }
    Invoke-FixedGuard "--install"
    $service = Get-Service -Name JavaClawNetworkGuard
    if ($service.Status -ne "Running") { Start-Service -Name JavaClawNetworkGuard }
    $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(15))

    # Surefire 的 java.home 与服务验证的固定 runtime/bin/java.exe 保持一致；最终 cleanup 恢复原 JDK。
    "JAVA_HOME=$fixtureRuntime" | Out-File $env:GITHUB_ENV -Append -Encoding utf8
    (Join-Path $fixtureRuntime "bin") | Out-File $env:GITHUB_PATH -Append -Encoding utf8
} catch {
    $failure = $_
    Remove-TestFixture
    throw $failure
}
