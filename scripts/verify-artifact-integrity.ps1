param(
    [string]$OfficialRuneLiteJar,
    [string]$InstalledRuneLiteJar,
    [string]$UpstreamRlawtJar,
    [string]$PatchedRlawtJar,
    [Parameter(Mandatory = $true)]
    [string]$OutputManifest
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ZipClassHashes {
    param([string]$Path)
    $result = [ordered]@{}
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path))
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.EndsWith(".class", [StringComparison]::OrdinalIgnoreCase)) {
                continue
            }
            $stream = $entry.Open()
            try {
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try {
                    $hash = $sha.ComputeHash($stream)
                    $result[$entry.FullName] = ([Convert]::ToHexString($hash)).ToLowerInvariant()
                } finally {
                    $sha.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    return $result
}

$checks = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

if ($OfficialRuneLiteJar -or $InstalledRuneLiteJar) {
    if (-not $OfficialRuneLiteJar -or -not $InstalledRuneLiteJar) {
        throw "Both -OfficialRuneLiteJar and -InstalledRuneLiteJar are required as a pair"
    }
    $officialHash = Get-Sha256 $OfficialRuneLiteJar
    $installedHash = Get-Sha256 $InstalledRuneLiteJar
    $matched = $officialHash -eq $installedHash
    $checks.Add([ordered]@{
        name = "runelite_jar_byte_identity"
        matched = $matched
        official_path = (Resolve-Path -LiteralPath $OfficialRuneLiteJar).Path
        installed_path = (Resolve-Path -LiteralPath $InstalledRuneLiteJar).Path
        official_sha256 = $officialHash
        installed_sha256 = $installedHash
    })
    if (-not $matched) { $failures.Add("Installed RuneLite JAR does not match the official artifact") }
}

if ($UpstreamRlawtJar -or $PatchedRlawtJar) {
    if (-not $UpstreamRlawtJar -or -not $PatchedRlawtJar) {
        throw "Both -UpstreamRlawtJar and -PatchedRlawtJar are required as a pair"
    }
    $upstreamClasses = Get-ZipClassHashes $UpstreamRlawtJar
    $patchedClasses = Get-ZipClassHashes $PatchedRlawtJar
    $allNames = @($upstreamClasses.Keys + $patchedClasses.Keys | Sort-Object -Unique)
    $mismatches = New-Object System.Collections.Generic.List[object]
    foreach ($name in $allNames) {
        $upstreamHash = $upstreamClasses[$name]
        $patchedHash = $patchedClasses[$name]
        if ($upstreamHash -ne $patchedHash) {
            $mismatches.Add([ordered]@{
                entry = $name
                upstream_sha256 = $upstreamHash
                patched_sha256 = $patchedHash
            })
        }
    }
    $matched = $mismatches.Count -eq 0
    $checks.Add([ordered]@{
        name = "rlawt_java_class_identity"
        matched = $matched
        upstream_jar_sha256 = Get-Sha256 $UpstreamRlawtJar
        patched_jar_sha256 = Get-Sha256 $PatchedRlawtJar
        compared_class_count = $allNames.Count
        mismatches = @($mismatches | ForEach-Object { $_ })
        permitted_difference = "non-class native compatibility payloads only"
    })
    if (-not $matched) { $failures.Add("Patched rlawt JAR changes Java class entries") }
}

if ($checks.Count -eq 0) {
    throw "Provide at least one official/installed artifact pair"
}

$manifest = [ordered]@{
    schema_version = 1
    generated_utc = (Get-Date).ToUniversalTime().ToString("o")
    passed = $failures.Count -eq 0
    checks = @($checks | ForEach-Object { $_ })
    failures = @($failures | ForEach-Object { $_ })
    policy = [ordered]@{
        runelite_client_gamepack_gpu_plugin_must_match_official = $true
        rlawt_java_classes_must_match_upstream = $true
        gameplay_automation_allowed = $false
        protocol_or_packet_changes_allowed = $false
    }
}

$parent = Split-Path -Parent $OutputManifest
if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputManifest -Encoding utf8

if ($failures.Count -gt 0) {
    Write-Error ($failures -join "; ")
    exit 1
}

Write-Host "Artifact integrity verified -> $OutputManifest"
