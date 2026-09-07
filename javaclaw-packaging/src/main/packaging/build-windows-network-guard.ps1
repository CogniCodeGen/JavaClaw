param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [switch]$Sign
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
        throw "MSVC C++20 build tools are required for the Windows network guard"
    }
    $visualStudio = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($visualStudio)) {
        throw "a supported MSVC installation could not be located"
    }
    & (Join-Path $visualStudio "Common7\Tools\Launch-VsDevShell.ps1") -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
}
if ($env:VSCMD_ARG_TGT_ARCH -and $env:VSCMD_ARG_TGT_ARCH -notin @("x64", "amd64")) {
    throw "the Windows network guard release target must be x64"
}

$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$sources = @("guard_service.cpp", "guard_wfp.cpp", "guard_identity.cpp", "guard_install.cpp") |
    ForEach-Object { Join-Path $source $_ }
foreach ($file in $sources) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "fixed network guard source is missing: $file"
    }
}

# 固定源码和链接库；不接受来自模型、工作区配置或安装参数的编译选项。
Push-Location -LiteralPath $output
try {
    & cl.exe /nologo /std:c++20 /EHsc /permissive- /W4 /WX /O2 /MT /utf-8 `
        /DUNICODE /D_UNICODE /DNOMINMAX /D_WIN32_WINNT=0x0A00 /DWINVER=0x0A00 `
        /guard:cf /FeJavaClawNetworkGuard.exe @sources /link /DYNAMICBASE /NXCOMPAT /HIGHENTROPYVA /CETCOMPAT `
        advapi32.lib fwpuclnt.lib FirewallAPI.lib userenv.lib wintrust.lib crypt32.lib ws2_32.lib `
        iphlpapi.lib shell32.lib ole32.lib uuid.lib rpcrt4.lib
    if ($LASTEXITCODE -ne 0) { throw "MSVC network guard compilation or linking failed" }
} finally {
    Pop-Location
}

$executable = Join-Path $output "JavaClawNetworkGuard.exe"
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "MSVC did not produce the network guard executable"
}
if ($Sign) {
    if ([string]::IsNullOrWhiteSpace($env:JAVACLAW_WINDOWS_CERTIFICATE) -or
        [string]::IsNullOrWhiteSpace($env:JAVACLAW_WINDOWS_TIMESTAMP_URL)) {
        throw "signed Windows packaging requires the release certificate and timestamp URL"
    }
    & signtool.exe sign /sha1 $env:JAVACLAW_WINDOWS_CERTIFICATE /fd SHA256 `
        /tr $env:JAVACLAW_WINDOWS_TIMESTAMP_URL /td SHA256 $executable
    if ($LASTEXITCODE -ne 0) { throw "network guard Authenticode signing failed" }
    & signtool.exe verify /pa /all $executable
    if ($LASTEXITCODE -ne 0) { throw "network guard Authenticode verification failed" }
    if ((Get-AuthenticodeSignature -LiteralPath $executable).Status -ne "Valid") {
        throw "network guard Authenticode signature is invalid"
    }
}
