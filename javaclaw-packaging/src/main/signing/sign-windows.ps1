param(
    [Parameter(Mandatory = $true)][string]$ArtifactDirectory,
    [Parameter(Mandatory = $true)][string]$CertificateThumbprint,
    [Parameter(Mandatory = $true)][string]$TimestampUrl
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ArtifactDirectory -PathType Container)) {
    throw "native artifact directory is missing"
}
$artifacts = Get-ChildItem -LiteralPath $ArtifactDirectory -File |
    Where-Object { $_.Extension -in ".msi", ".exe" }
if ($artifacts.Count -eq 0) { throw "no Windows installer artifacts found" }
foreach ($artifact in $artifacts) {
    & signtool.exe sign /sha1 $CertificateThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $artifact.FullName
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for $($artifact.Name)" }
    $signature = Get-AuthenticodeSignature -LiteralPath $artifact.FullName
    if ($signature.Status -ne "Valid") {
        throw "invalid Authenticode signature for $($artifact.Name)"
    }
}
