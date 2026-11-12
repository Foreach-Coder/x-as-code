<#
.SYNOPSIS
    Create or reuse a GitCode release for a tag, then upload the release asset (jar).

.DESCRIPTION
    Called by the GitHub Actions workflow "publish"; can also be run locally:
      $env:GITCODE_TOKEN = '<token>'
      pwsh -NoProfile -File .github/scripts/sync-gitcode-release.ps1 `
        -Owner foreach1991 -Repo x-as-code -Tag v0.1.0 `
        -Asset dist/java-systemd-service-cli-0.1.0.jar

    Idempotent: an existing release is reused; the asset is skipped when its
    sha256 already matches the local file.
    Uses only built-in PowerShell 5.1+ commands.

.PARAMETER TokenEnv
    Environment variable that holds the GitCode personal access token.
    Defaults to GITCODE_TOKEN.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Asset,
    [string]$NotesFile = '',
    [string]$ApiBase = 'https://api.gitcode.com/api/v5',
    [string]$TokenEnv = 'GITCODE_TOKEN',
    [int]$PollAttempts = 30,
    [int]$PollSeconds = 2
)

$ErrorActionPreference = 'Stop'

function Get-StatusCode([System.Management.Automation.ErrorRecord]$err) {
    if ($null -eq $err.Exception.Response) { return 0 }
    try { return [int]$err.Exception.Response.StatusCode } catch { return 0 }
}

function Get-ReleaseOrNull([string]$uri, [hashtable]$headers) {
    try {
        return Invoke-RestMethod -Uri $uri -Headers $headers -Method Get
    }
    catch {
        if ((Get-StatusCode $_) -eq 404) { return $null }
        throw
    }
}

function Get-Digest([object]$asset) {
    if ($null -eq $asset -or [string]::IsNullOrWhiteSpace([string]$asset.digest)) { return '' }
    return ([string]$asset.digest -replace '^sha256:', '').ToLowerInvariant()
}

$token = [Environment]::GetEnvironmentVariable($TokenEnv)
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "FAILED: environment variable $TokenEnv is not set (needs a GitCode personal access token)"
}
if (-not (Test-Path -LiteralPath $Asset)) {
    throw "FAILED: asset does not exist: $Asset"
}

$assetItem = Get-Item -LiteralPath $Asset
$assetName = $assetItem.Name
$assetSha = (Get-FileHash -LiteralPath $assetItem.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$assetSize = $assetItem.Length

$headers = @{ Authorization = "Bearer $token" }
$releaseUri = "$ApiBase/repos/$Owner/$Repo/releases/$Tag"
$uploadUri = "$releaseUri/upload_url?file_name=$([uri]::EscapeDataString($assetName))"

Write-Host "target: $Owner/$Repo tag=$Tag"
Write-Host "asset: $assetName ($assetSize bytes) sha256=$assetSha"

$releaseName = "xac $Tag"
if ([string]::IsNullOrWhiteSpace($NotesFile) -or -not (Test-Path -LiteralPath $NotesFile)) {
    $body = "xac $Tag`n`nSee https://github.com/Foreach-Coder/x-as-code/releases/tag/$Tag"
}
else {
    $body = Get-Content -LiteralPath $NotesFile -Raw
}

$release = Get-ReleaseOrNull -uri $releaseUri -headers $headers

if ($null -eq $release) {
    $payload = @{
        tag_name       = $Tag
        name           = $releaseName
        body           = $body
        release_status = 'latest'
    } | ConvertTo-Json -Depth 4

    Write-Host 'creating release ...'
    $release = Invoke-RestMethod -Uri "$ApiBase/repos/$Owner/$Repo/releases" -Headers $headers `
        -Method Post -ContentType 'application/json; charset=utf-8' -Body $payload
    Write-Host "release created: $($release.html_url)"
}
else {
    $currentBody = [string]$release.body
    $sameName = ([string]$release.name) -eq $releaseName
    $sameBody = $currentBody.TrimEnd() -eq $body.TrimEnd()
    if ($sameName -and $sameBody) {
        Write-Host "release exists and matches: $($release.html_url)"
    }
    else {
        Write-Host 'release exists but title/notes differ, updating ...'
        $payload = @{
            name           = $releaseName
            body           = $body
            release_status = 'latest'
        } | ConvertTo-Json -Depth 4
        $release = Invoke-RestMethod -Uri $releaseUri -Headers $headers `
            -Method Patch -ContentType 'application/json; charset=utf-8' -Body $payload
        Write-Host "release updated: $($release.html_url)"
    }
}

$existing = @($release.assets | Where-Object { $_.name -eq $assetName })
if ($existing.Count -gt 0) {
    $knownDigest = Get-Digest $existing[0]
    if ($knownDigest -eq $assetSha) {
        Write-Host 'asset is up to date (sha256 matches), nothing to upload'
        exit 0
    }
    Write-Host "asset exists with a different digest (remote=$knownDigest), uploading again"
}

Write-Host 'requesting upload url ...'
$upload = Invoke-RestMethod -Uri $uploadUri -Headers $headers -Method Get
if ([string]::IsNullOrWhiteSpace([string]$upload.url)) {
    throw 'FAILED: upload_url response has no url'
}

$putHeaders = @{}
foreach ($prop in $upload.headers.PSObject.Properties) {
    $putHeaders[$prop.Name] = [string]$prop.Value
}

$contentType = 'application/octet-stream'
if ($putHeaders.ContainsKey('Content-Type')) {
    $contentType = $putHeaders['Content-Type']
    $putHeaders.Remove('Content-Type')
}

Write-Host "uploading to $($upload.url) ..."
$null = Invoke-WebRequest -Uri $upload.url -Method Put -Headers $putHeaders `
    -InFile $assetItem.FullName -ContentType $contentType

# The upload is registered asynchronously by the object storage callback,
# so poll the release until the asset shows up.
for ($i = 1; $i -le $PollAttempts; $i++) {
    Start-Sleep -Seconds $PollSeconds
    $current = Get-ReleaseOrNull -uri $releaseUri -headers $headers
    if ($null -eq $current) { continue }

    $hit = @($current.assets | Where-Object { $_.name -eq $assetName })
    if ($hit.Count -eq 0) { continue }

    $knownDigest = Get-Digest $hit[0]
    if ($knownDigest -ne '') {
        if ($knownDigest -eq $assetSha) {
            Write-Host "done: $assetName sha256=$assetSha"
            exit 0
        }
        continue
    }

    if ([int]$hit[0].size -eq $assetSize) {
        Write-Host "done: $assetName ($assetSize bytes, platform returned no digest)"
        exit 0
    }
}

throw "FAILED: asset $assetName not visible after $PollAttempts polls"
