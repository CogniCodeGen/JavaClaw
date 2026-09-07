param(
    [Parameter(Mandatory = $true)][string]$JPackage,
    [Parameter(Mandatory = $true)][string]$GuardExecutable,
    [Parameter(Mandatory = $true)][string]$ResourceDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 从本次打包实际使用的 JDK 提取模板，避免复制并漂移维护官方 WiX 模板。
$jdkBin = Split-Path -Parent (Resolve-Path -LiteralPath $JPackage).Path
$jmod = Join-Path $jdkBin "jmod.exe"
$module = Join-Path (Split-Path -Parent $jdkBin) "jmods\jdk.jpackage.jmod"
if (-not (Test-Path -LiteralPath $jmod -PathType Leaf) -or -not (Test-Path -LiteralPath $module -PathType Leaf)) {
    throw "Windows MSI lifecycle generation requires the complete packaging JDK"
}
$guard = (Resolve-Path -LiteralPath $GuardExecutable).Path
if ((Get-AuthenticodeSignature -LiteralPath $guard).Status -ne "Valid") {
    throw "MSI may only embed the signed fixed network guard"
}
New-Item -ItemType Directory -Path $ResourceDirectory -Force | Out-Null
$resources = (Resolve-Path -LiteralPath $ResourceDirectory).Path
$extracted = Join-Path $resources "jdk-template"
if (Test-Path -LiteralPath $extracted) { throw "MSI template extraction directory must be new" }
& $jmod extract --dir $extracted $module
if ($LASTEXITCODE -ne 0) { throw "could not extract the packaging JDK WiX template" }
try {
    $template = Join-Path $extracted "classes\jdk\jpackage\internal\resources\main.wxs"
    [xml]$document = [IO.File]::ReadAllText($template)
    $ns = New-Object Xml.XmlNamespaceManager($document.NameTable)
    $ns.AddNamespace("w", "http://schemas.microsoft.com/wix/2006/wi")
    $product = $document.SelectSingleNode("/w:Wix/w:Product", $ns)
    $sequence = $document.SelectSingleNode("/w:Wix/w:Product/w:InstallExecuteSequence", $ns)
    if ($null -eq $product -or $null -eq $sequence) {
        throw "unsupported packaging JDK WiX template; lifecycle injection must fail closed"
    }
    $uri = "http://schemas.microsoft.com/wix/2006/wi"
    $binary = $document.CreateElement("Binary", $uri)
    $binary.SetAttribute("Id", "JavaClawNetworkGuardCleanupBinary")
    $binary.SetAttribute("SourceFile", $guard)
    [void]$product.AppendChild($binary)
    $action = $document.CreateElement("CustomAction", $uri)
    $action.SetAttribute("Id", "JavaClawNetworkGuardCleanup")
    $action.SetAttribute("BinaryKey", "JavaClawNetworkGuardCleanupBinary")
    $action.SetAttribute("ExeCommand", "--uninstall")
    $action.SetAttribute("Execute", "deferred")
    $action.SetAttribute("Impersonate", "no")
    $action.SetAttribute("Return", "check")
    [void]$product.AppendChild($action)
    $scheduled = $document.CreateElement("Custom", $uri)
    $scheduled.SetAttribute("Action", "JavaClawNetworkGuardCleanup")
    $scheduled.SetAttribute("Before", "RemoveFiles")
    $scheduled.InnerText = 'Installed AND (REMOVE~="ALL" OR REINSTALL)'
    [void]$sequence.AppendChild($scheduled)
    # 嵌入二进制只接受固定卸载动词，不通过可覆盖的 INSTALLDIR 拼出管理员命令。
    # Major upgrade 会先执行旧 MSI 的卸载序列；错误阻止删除仍拥有网络状态的服务文件。
    $document.Save((Join-Path $resources "main.wxs"))
} finally {
    Remove-Item -LiteralPath $extracted -Recurse -Force
}
