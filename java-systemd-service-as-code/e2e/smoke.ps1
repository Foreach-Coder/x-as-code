#!/usr/bin/env pwsh
# e2e smoke: build the fat jar, ship it into a real systemd container with
# "docker cp" (the CLI never uploads artifacts), then exercise
# apply / status / logs / stop / destroy and the path-links state machine.
#
# Works on Windows PowerShell 5.1 and PowerShell 7+ (pwsh).
# Prints "SMOKE OK" only when every assertion passed; any failure exits non-zero.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File e2e/smoke.ps1
#   pwsh -NoProfile -File e2e/smoke.ps1
#
# Environment:
#   IMAGE  container image (default xac-e2e:24.04)

[CmdletBinding()]
param(
    [string]$Image = $(if ($env:IMAGE) { $env:IMAGE } else { 'xac-e2e:24.04' }),
    [string]$Container = 'xac-smoke'
)

$ErrorActionPreference = 'Continue'

$OldConsoleEncoding = $null
try {
    $OldConsoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
    $OldConsoleEncoding = $null
}

$E2eDir = $PSScriptRoot
$ProjectDir = Split-Path -Parent $E2eDir
$JarPath = Join-Path $ProjectDir (Join-Path 'target' 'java-systemd-service-cli-0.1.0-SNAPSHOT.jar')
$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) ('xac-smoke-' + [guid]::NewGuid().ToString('N'))
$ContainerStarted = $false
$ExitCode = 0

function Write-Step {
    param([string]$Label)
    Write-Host ''
    Write-Host "== $Label =="
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Lines = & $FilePath @Arguments 2>&1
        $Code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $Previous
    }
    $Text = (($Lines | ForEach-Object { [string]$_ }) -join "`n")
    return [pscustomobject]@{ ExitCode = $Code; Output = $Text }
}

function Invoke-Container {
    param([string[]]$Command)
    $Full = @('exec', $Container) + $Command
    return Invoke-Native -FilePath 'docker' -Arguments $Full
}

function Assert-True {
    param([bool]$Condition, [string]$What)
    if (-not $Condition) {
        throw "assertion failed: $What"
    }
    Write-Host "  ok  $What"
}

function Assert-ExitCode {
    param($Result, [int]$Expected, [string]$What)
    if ($Result.ExitCode -ne $Expected) {
        throw ("assertion failed: $What -- expected exit $Expected, got $($Result.ExitCode). Output:`n" + $Result.Output)
    }
    Write-Host "  ok  $What"
}

function Assert-Equal {
    param([string]$Actual, [string]$Expected, [string]$What)
    if ($Actual -ne $Expected) {
        throw "assertion failed: $What -- expected [$Expected], got [$Actual]"
    }
    Write-Host "  ok  $What"
}

function Assert-Contain {
    param([string]$Text, [string]$Needle, [string]$What)
    if ($Text.IndexOf($Needle, [System.StringComparison]::Ordinal) -lt 0) {
        throw ("assertion failed: $What -- [$Needle] not found in output:`n" + $Text)
    }
    Write-Host "  ok  $What"
}

function Assert-NotContain {
    param([string]$Text, [string]$Needle, [string]$What)
    if ($Text.IndexOf($Needle, [System.StringComparison]::Ordinal) -ge 0) {
        throw ("assertion failed: $What -- unexpected [$Needle] in output:`n" + $Text)
    }
    Write-Host "  ok  $What"
}

function Test-ContainerPath {
    param([string]$Path, [string]$Type)
    $Result = Invoke-Container @('test', $Type, $Path)
    return ($Result.ExitCode -eq 0)
}

function Assert-ContainerPath {
    param([string]$Path, [string]$Type, [bool]$Expected, [string]$What)
    $Actual = Test-ContainerPath -Path $Path -Type $Type
    if ($Actual -ne $Expected) {
        throw "assertion failed: $What -- expected [test $Type] = $Expected, got $Actual for $Path"
    }
    Write-Host "  ok  $What"
}

function Get-ContainerListing {
    param([string]$Path)
    $Result = Invoke-Container @('ls', '-1', $Path)
    if ($Result.ExitCode -ne 0) {
        throw "ls $Path failed: $($Result.Output)"
    }
    return @($Result.Output -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } | Sort-Object)
}

function Get-ContainerLinkTarget {
    param([string]$Path)
    $Result = Invoke-Container @('readlink', $Path)
    if ($Result.ExitCode -ne 0) {
        throw "readlink $Path failed: $($Result.Output)"
    }
    return $Result.Output.Trim()
}

function Get-ContainerFile {
    param([string]$Path)
    $Result = Invoke-Container @('cat', $Path)
    if ($Result.ExitCode -ne 0) {
        throw "cat $Path failed: $($Result.Output)"
    }
    return $Result.Output.Trim()
}

function Wait-ForActiveState {
    param(
        [string]$Expected,
        [int]$TimeoutSeconds,
        [string]$Unit = 'xac.demo.service'
    )
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $State = ''
    while ((Get-Date) -lt $Deadline) {
        $Result = Invoke-Container @('systemctl', 'is-active', $Unit)
        $State = $Result.Output.Trim()
        if ($State -eq $Expected) {
            Write-Host "  ok  $Unit reached $Expected"
            return
        }
        Start-Sleep -Milliseconds 400
    }
    throw "timed out waiting for $Unit to become $Expected (last state: [$State])"
}

function Wait-ForJournalText {
    param([string]$Needle, [int]$TimeoutSeconds)
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $Text = ''
    while ((Get-Date) -lt $Deadline) {
        $Result = Invoke-Container @('journalctl', '-u', 'xac.demo.service', '--no-pager', '-n', '200')
        $Text = $Result.Output
        if ($Result.ExitCode -eq 0 -and $Text.IndexOf($Needle, [System.StringComparison]::Ordinal) -ge 0) {
            return $Text
        }
        Start-Sleep -Milliseconds 500
    }
    throw "timed out waiting for [$Needle] in journal for xac.demo.service. Output:`n$Text"
}

try {
    New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null

    # ------------------------------------------------------------------
    Write-Step 'preflight: docker'
    $Docker = Invoke-Native 'docker' @('version', '--format', '{{.Server.Version}}')
    Assert-ExitCode $Docker 0 'docker server is reachable'

    $ImageInspect = Invoke-Native 'docker' @('image', 'inspect', $Image)
    if ($ImageInspect.ExitCode -ne 0) {
        Write-Host "  image $Image is missing; building it from e2e/Dockerfile (slow, one-off)"
        $Build = Invoke-Native 'docker' @('build', '-t', $Image, $E2eDir)
        Assert-ExitCode $Build 0 "docker build -t $Image"
    } else {
        Write-Host "  ok  image $Image exists"
    }

    # ------------------------------------------------------------------
    Write-Step 'build: fat jar and sample jar'
    $Pom = Join-Path $ProjectDir 'pom.xml'
    $Maven = Invoke-Native 'mvn' @('-q', '-f', $Pom, 'package')
    Assert-ExitCode $Maven 0 'mvn package (fat jar + unit tests)'
    Assert-True (Test-Path -LiteralPath $JarPath) "fat jar exists: $JarPath"

    $Help = Invoke-Native 'java' @('-jar', $JarPath, '--help')
    Assert-ExitCode $Help 0 'java -jar xac.jar --help'
    foreach ($Sub in @('apply', 'status', 'logs', 'destroy')) {
        Assert-Contain $Help.Output $Sub "usage mentions $Sub"
    }

    $ClassesDir = Join-Path $WorkDir 'classes'
    New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null
    $DemoJar = Join-Path $WorkDir 'demo.jar'
    $AppJar = Join-Path $WorkDir 'app.jar'
    $Javac = Invoke-Native 'javac' @('-d', $ClassesDir, '--release', '8', (Join-Path $E2eDir (Join-Path 'sample' 'Demo.java')))
    Assert-ExitCode $Javac 0 'javac --release 8 e2e/sample/Demo.java'
    $JarTool = Invoke-Native 'jar' @('cfe', $DemoJar, 'Demo', '-C', $ClassesDir, '.')
    Assert-ExitCode $JarTool 0 'jar cfe demo.jar Demo'
    Copy-Item -LiteralPath $DemoJar -Destination $AppJar -Force

    $Yaml = @'
name: demo
description: xac e2e smoke
artifact:
  jar: app.jar
runtime:
  java: /usr/bin/java
  opts:
    - -Xmx256m
env:
  GREETING: hello world
path-links:
  - app.jar:/opt/links/a/app.jar
  - app.jar:/opt/links/b/app.jar
  - app.jar:/opt/links/c/app.jar
  - app.jar:/opt/links/d/app.jar
  - app.jar:/opt/links/e/app.jar
lifecycle:
  auto-start: true
  restart: on-failure
  restart-sec: 2
  timeout-stop-sec: 10
'@
    $YamlPath = Join-Path $WorkDir 'xac.yaml'
    [System.IO.File]::WriteAllText($YamlPath, $Yaml + "`n", [System.Text.UTF8Encoding]::new($false))

    # ------------------------------------------------------------------
    Write-Step 'container: start systemd'
    $null = Invoke-Native 'docker' @('rm', '-f', $Container)
    $Run = Invoke-Native 'docker' @(
        'run', '-d', '--name', $Container,
        '--privileged', '--cgroupns=host',
        '--tmpfs', '/run', '--tmpfs', '/run/lock', '--tmpfs', '/tmp',
        '--no-healthcheck',
        '--entrypoint', '/sbin/init', $Image)
    Assert-ExitCode $Run 0 "docker run $Image"
    $ContainerStarted = $true

    $SystemdState = ''
    $Deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $Deadline) {
        $StateResult = Invoke-Container @('systemctl', 'is-system-running')
        $SystemdState = $StateResult.Output.Trim()
        if (($SystemdState -eq 'running') -or ($SystemdState -eq 'degraded')) {
            break
        }
        Start-Sleep -Milliseconds 500
    }
    Assert-True (($SystemdState -eq 'running') -or ($SystemdState -eq 'degraded')) "systemd is-system-running = [$SystemdState]"

    $Mkdir = Invoke-Container @('mkdir', '-p', '/opt/demo',
        '/opt/links/a', '/opt/links/b', '/opt/links/c', '/opt/links/d', '/opt/links/e', '/opt/elsewhere')
    Assert-ExitCode $Mkdir 0 'mkdir -p /opt/demo and link parents'

    $Copies = @(
        @{ Source = $JarPath; Destination = '/opt/xac.jar' },
        @{ Source = $YamlPath; Destination = '/opt/demo/xac.yaml' },
        @{ Source = $AppJar; Destination = '/opt/demo/app.jar' }
    )
    foreach ($Copy in $Copies) {
        $Destination = $Container + ':' + $Copy.Destination
        $Result = Invoke-Native 'docker' @('cp', $Copy.Source, $Destination)
        Assert-ExitCode $Result 0 ("docker cp -> " + $Copy.Destination)
    }
    Assert-ContainerPath '/opt/xac.jar' '-f' $true 'xac.jar is in the container'
    Assert-ContainerPath '/opt/demo/xac.yaml' '-f' $true 'xac.yaml is in the package dir'
    Assert-ContainerPath '/opt/demo/app.jar' '-f' $true 'app.jar is in the package dir'

    # ------------------------------------------------------------------
    Write-Step 'fixtures: a real directory and a foreign symlink block the targets'
    Assert-ExitCode (Invoke-Container @('mkdir', '/opt/links/b/app.jar')) 0 'b: real directory pre-created at target'
    Assert-ExitCode (Invoke-Container @('sh', '-c', 'printf keep > /opt/links/b/app.jar/keep.txt')) 0 'b: content pre-created inside the real directory'
    Assert-ExitCode (Invoke-Container @('ln', '-s', '/opt/elsewhere', '/opt/links/c/app.jar')) 0 'c: foreign symlink pre-created at target'
    Assert-Equal ((Get-ContainerListing '/opt/links/b') -join '|') 'app.jar' 'b: only the real directory exists'
    Assert-Equal (Get-ContainerFile '/opt/links/b/app.jar/keep.txt') 'keep' 'b: directory content is untouched'
    Assert-Equal ((Get-ContainerListing '/opt/links/c') -join '|') 'app.jar' 'c: only the foreign symlink exists'
    Assert-Equal (Get-ContainerLinkTarget '/opt/links/c/app.jar') '/opt/elsewhere' 'c: foreign symlink points elsewhere'

    # ------------------------------------------------------------------
    Write-Step 'status before apply: drift must be reported'
    $StatusBefore = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'status', '/opt/demo')
    Assert-ExitCode $StatusBefore 1 'status exits 1 when drift exists'
    Assert-Contain $StatusBefore.Output 'unit: /etc/systemd/system/xac.demo.service (missing)' 'status: unit is missing'
    Assert-Contain $StatusBefore.Output 'env: /etc/xac/xac.demo.env (out of sync)' 'status: env is not installed'
    Assert-Contain $StatusBefore.Output 'auto-start: disabled' 'status: auto-start is off'
    Assert-Contain $StatusBefore.Output 'drift: unit ' 'status: drift reports the unit'
    Assert-Contain $StatusBefore.Output 'drift: env ' 'status: drift reports the env file'
    for ($Index = 0; $Index -lt 5; $Index++) {
        Assert-Contain $StatusBefore.Output ('path-links[' + $Index + '] ') ("status: drift reports path-links[$Index]")
    }

    # ------------------------------------------------------------------
    Write-Step 'apply refuses blocked path-links: exit 2, nothing changed, service not started'
    $Blocked = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'apply', '/opt/demo')
    Assert-ExitCode $Blocked 2 'apply exits 2 when a path-links target is blocked'
    Assert-Contain $Blocked.Output 'path-links[1]' 'apply: b is reported as the blocked entry'
    Assert-Contain $Blocked.Output 'path-links[2]' 'apply: c is reported as the blocked entry'
    Assert-Contain $Blocked.Output '/opt/links/b/app.jar' 'apply: the b target is named in the error'
    Assert-Equal ((Get-ContainerListing '/opt/links/b') -join '|') 'app.jar' 'b: no .bak created for the real directory'
    Assert-Equal (Get-ContainerFile '/opt/links/b/app.jar/keep.txt') 'keep' 'b: directory content still untouched'
    Assert-Equal ((Get-ContainerListing '/opt/links/c') -join '|') 'app.jar' 'c: no .bak created for the foreign symlink'
    Assert-Equal (Get-ContainerLinkTarget '/opt/links/c/app.jar') '/opt/elsewhere' 'c: foreign symlink untouched'
    Assert-ContainerPath '/etc/systemd/system/xac.demo.service' '-e' $false 'blocked apply: unit file not written'
    Assert-ContainerPath '/etc/xac/xac.demo.env' '-e' $false 'blocked apply: env file not written'
    $BlockedPgrep = Invoke-Container @('pgrep', '-f', '/opt/demo/app\.jar')
    Assert-ExitCode $BlockedPgrep 1 'blocked apply: service process not started'

    # ------------------------------------------------------------------
    Write-Step 'remove the obstacles and apply'
    Assert-ExitCode (Invoke-Container @('rm', '-rf', '/opt/links/b/app.jar')) 0 'b: remove the blocking directory by hand'
    Assert-ExitCode (Invoke-Container @('rm', '-f', '/opt/links/c/app.jar')) 0 'c: remove the blocking symlink by hand'
    $Apply = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'apply', '/opt/demo')
    Assert-ExitCode $Apply 0 'apply exits 0 after the obstacles are gone'
    Assert-Contain $Apply.Output 'path-links' 'apply planned: path-links action'
    Assert-Contain $Apply.Output '/etc/xac/xac.demo.env' 'apply planned: env file path'
    Assert-Contain $Apply.Output '/etc/systemd/system/xac.demo.service' 'apply planned: unit file path'
    Assert-Contain $Apply.Output 'systemctl enable xac.demo.service' 'apply planned: systemctl enable'
    Assert-Contain $Apply.Output 'systemctl restart xac.demo.service' 'apply planned: systemctl restart'

    $UnitMode = Invoke-Container @('stat', '-c', '%a', '/etc/systemd/system/xac.demo.service')
    Assert-ExitCode $UnitMode 0 'stat -c %a unit file'
    Assert-Equal $UnitMode.Output.Trim() '644' 'apply: unit file mode is 0644'
    $EnvMode = Invoke-Container @('stat', '-c', '%a', '/etc/xac/xac.demo.env')
    Assert-ExitCode $EnvMode 0 'stat -c %a env file'
    Assert-Equal $EnvMode.Output.Trim() '600' 'apply: env file mode is 0600'

    Wait-ForActiveState -Expected 'active' -TimeoutSeconds 30

    # Wait-ForJournalText returns only after the needle appears (it throws on
    # timeout), so the wait itself is the assertion; re-checking the same text
    # with Assert-Contain would be redundant.  XMX is the JVM-reported value,
    # not the -Xmx spelling, so it stays the waited needle.
    Wait-ForJournalText -Needle 'READY jvm=' -TimeoutSeconds 30 | Out-Null
    Wait-ForJournalText -Needle 'GREETING=hello world' -TimeoutSeconds 20 | Out-Null
    Wait-ForJournalText -Needle 'CWD=/opt/demo' -TimeoutSeconds 20 | Out-Null
    Wait-ForJournalText -Needle 'XMX=268435456' -TimeoutSeconds 20 | Out-Null

    $UnitFileState = Invoke-Container @('systemctl', 'show', 'xac.demo.service', '--property=UnitFileState')
    Assert-ExitCode $UnitFileState 0 'systemctl show UnitFileState'
    Assert-Contain $UnitFileState.Output 'enabled' 'systemctl: UnitFileState=enabled'
    Assert-ContainerPath '/etc/systemd/system/multi-user.target.wants/xac.demo.service' '-L' $true 'apply: enable created the wants symlink'
    Assert-Equal (Get-ContainerLinkTarget '/etc/systemd/system/multi-user.target.wants/xac.demo.service') '/etc/systemd/system/xac.demo.service' 'apply: wants symlink points at the unit'

    Write-Step 'status after apply: in sync'
    $StatusAfter = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'status', '/opt/demo')
    Assert-ExitCode $StatusAfter 0 'status exits 0 when in sync'
    Assert-Contain $StatusAfter.Output 'unit: /etc/systemd/system/xac.demo.service (in sync)' 'status: unit in sync'
    Assert-Contain $StatusAfter.Output 'env: /etc/xac/xac.demo.env (in sync)' 'status: env in sync'
    Assert-Contain $StatusAfter.Output 'auto-start: enabled' 'status: auto-start enabled'
    Assert-Contain $StatusAfter.Output 'active: active (running)' 'status: service is active'
    Assert-NotContain $StatusAfter.Output 'drift:' 'status: no drift lines'

    Write-Step 'logs'
    $Logs = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'logs', '/opt/demo', '-n', '200')
    Assert-ExitCode $Logs 0 'logs exits 0'
    Assert-Contain $Logs.Output 'GREETING=hello world' 'logs: shows the service output'

    # ------------------------------------------------------------------
    Write-Step 'path-links: every target became a symlink pointing at the source'
    foreach ($Name in @('a', 'b', 'c', 'd', 'e')) {
        Assert-ContainerPath ('/opt/links/' + $Name + '/app.jar') '-L' $true ($Name + ': symlink created')
        Assert-Equal (Get-ContainerLinkTarget ('/opt/links/' + $Name + '/app.jar')) '/opt/demo/app.jar' ($Name + ': points at the configured source')
        Assert-Equal ((Get-ContainerListing ('/opt/links/' + $Name)) -join '|') 'app.jar' ($Name + ': no .bak appeared')
    }

    # ------------------------------------------------------------------
    Write-Step 'path-links: re-apply is idempotent and never creates a .bak'
    $InodeBeforeResult = Invoke-Container @('stat', '-c', '%i', '/opt/links/a/app.jar')
    Assert-ExitCode $InodeBeforeResult 0 'stat -c %i a before re-apply'
    $InodeBefore = $InodeBeforeResult.Output.Trim()
    $ListingBBefore = (Get-ContainerListing '/opt/links/b') -join '|'
    $ListingCBefore = (Get-ContainerListing '/opt/links/c') -join '|'
    $ApplyAgain = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'apply', '/opt/demo')
    Assert-ExitCode $ApplyAgain 0 're-apply exits 0'
    $InodeAfterResult = Invoke-Container @('stat', '-c', '%i', '/opt/links/a/app.jar')
    Assert-ExitCode $InodeAfterResult 0 'stat -c %i a after re-apply'
    $InodeAfter = $InodeAfterResult.Output.Trim()
    Assert-Equal $InodeAfter $InodeBefore 'a: link inode unchanged (not recreated)'
    Assert-Equal (Get-ContainerLinkTarget '/opt/links/a/app.jar') '/opt/demo/app.jar' 'a: link target unchanged'
    Assert-Equal ((Get-ContainerListing '/opt/links/a') -join '|') 'app.jar' 'a: still no .bak'
    Assert-Equal $ListingBBefore 'app.jar' 'b: only the link exists before re-apply'
    Assert-Equal $ListingCBefore 'app.jar' 'c: only the link exists before re-apply'
    Assert-Equal ((Get-ContainerListing '/opt/links/b') -join '|') $ListingBBefore 'b: no extra .bak after re-apply'
    Assert-Equal ((Get-ContainerListing '/opt/links/c') -join '|') $ListingCBefore 'c: no extra .bak after re-apply'

    # ------------------------------------------------------------------
    Write-Step 'stop: SuccessExitStatus=143 and the shutdown hook'
    Assert-ExitCode (Invoke-Container @('systemctl', 'start', 'xac.demo.service')) 0 'systemctl start'
    Wait-ForActiveState -Expected 'active' -TimeoutSeconds 30
    Assert-ExitCode (Invoke-Container @('systemctl', 'stop', 'xac.demo.service')) 0 'systemctl stop'
    Wait-ForActiveState -Expected 'inactive' -TimeoutSeconds 30
    $JournalAfterStop = Wait-ForJournalText -Needle 'STOPPING' -TimeoutSeconds 20
    Assert-Contain $JournalAfterStop 'STOPPING' 'shutdown hook printed STOPPING'

    # ------------------------------------------------------------------
    Write-Step 'destroy setup: replace d with a real file, e with a foreign symlink'
    Assert-ExitCode (Invoke-Container @('systemctl', 'start', 'xac.demo.service')) 0 'systemctl start before destroy'
    Wait-ForActiveState -Expected 'active' -TimeoutSeconds 30
    Assert-ExitCode (Invoke-Container @('rm', '-f', '/opt/links/d/app.jar')) 0 'd: remove the managed link'
    Assert-ExitCode (Invoke-Container @('sh', '-c', 'echo keep > /opt/links/d/app.jar')) 0 'd: put a real file at the target'
    Assert-ContainerPath '/opt/links/d/app.jar' '-f' $true 'd: target is a regular file'
    Assert-ContainerPath '/opt/links/d/app.jar' '-L' $false 'd: target is not a symlink anymore'
    Assert-ExitCode (Invoke-Container @('rm', '-f', '/opt/links/e/app.jar')) 0 'e: remove the managed link'
    Assert-ExitCode (Invoke-Container @('ln', '-s', '/opt/elsewhere', '/opt/links/e/app.jar')) 0 'e: put a foreign symlink at the target'
    Assert-ContainerPath '/opt/links/e/app.jar' '-L' $true 'e: target is a symlink'
    Assert-Equal (Get-ContainerLinkTarget '/opt/links/e/app.jar') '/opt/elsewhere' 'e: target points elsewhere'

    # ------------------------------------------------------------------
    Write-Step 'destroy'
    $Destroy = Invoke-Container @('java', '-jar', '/opt/xac.jar', 'destroy', '/opt/demo', '--yes')
    Assert-ExitCode $Destroy 0 'destroy exits 0'
    Assert-Contain $Destroy.Output 'systemctl stop xac.demo.service' 'destroy planned: systemctl stop'
    Assert-Contain $Destroy.Output 'systemctl disable xac.demo.service' 'destroy planned: systemctl disable'
    Assert-Contain $Destroy.Output '/etc/systemd/system/xac.demo.service' 'destroy planned: unit file path'
    Assert-Contain $Destroy.Output '/etc/xac/xac.demo.env' 'destroy planned: env file path'
    Assert-Contain $Destroy.Output 'path-links' 'destroy planned: path-links action'

    # ------------------------------------------------------------------
    Write-Step 'destroy outcome'
    Assert-ContainerPath '/etc/systemd/system/xac.demo.service' '-e' $false 'no unit file left'
    Assert-ContainerPath '/etc/xac/xac.demo.env' '-e' $false 'no env file left'
    # The unit file is already gone, so the wants link would be dangling;
    # `test -e` follows the link and is true only if the target exists, which
    # makes it vacuous here.  `test -L` is the load-bearing check: the link
    # itself must be gone whether or not it dangles.
    Assert-ContainerPath '/etc/systemd/system/multi-user.target.wants/xac.demo.service' '-e' $false 'wants symlink target no longer resolves'
    Assert-ContainerPath '/etc/systemd/system/multi-user.target.wants/xac.demo.service' '-L' $false 'enable symlink itself removed from multi-user.target.wants'

    $LoadState = Invoke-Container @('systemctl', 'show', 'xac.demo.service', '--property=LoadState')
    Assert-ExitCode $LoadState 0 'systemctl show LoadState'
    Assert-Contain $LoadState.Output 'not-found' 'systemd no longer knows xac.demo.service'

    # Escape the dot so the pattern cannot match paths such as appXjar.
    $Pgrep = Invoke-Container @('pgrep', '-f', '/opt/demo/app\.jar')
    Assert-ExitCode $Pgrep 1 'pgrep -f /opt/demo/app\.jar exits 1 (no match)'
    Assert-Equal $Pgrep.Output.Trim() '' 'no process is running /opt/demo/app.jar'

    Assert-ContainerPath '/opt/links/a/app.jar' '-L' $false 'a: managed link removed'
    Assert-ContainerPath '/opt/links/b/app.jar' '-L' $false 'b: managed link removed'
    Assert-ContainerPath '/opt/links/c/app.jar' '-L' $false 'c: managed link removed'

    Assert-ContainerPath '/opt/links/d/app.jar' '-f' $true 'd: real file survived destroy'
    Assert-Equal (Get-ContainerFile '/opt/links/d/app.jar') 'keep' 'd: real file content unchanged'
    Assert-ContainerPath '/opt/links/e/app.jar' '-L' $true 'e: foreign symlink survived destroy'
    Assert-Equal (Get-ContainerLinkTarget '/opt/links/e/app.jar') '/opt/elsewhere' 'e: foreign symlink target unchanged'

    Assert-Equal ((Get-ContainerListing '/opt/links/b') -join '|') '' 'b: only the removed link was there, no .bak'
    Assert-Equal ((Get-ContainerListing '/opt/links/c') -join '|') '' 'c: only the removed link was there, no .bak'

    Write-Host ''
    Write-Host 'SMOKE OK'
} catch {
    $ExitCode = 1
    Write-Host ''
    Write-Host ("SMOKE FAILED: " + $_.Exception.Message) -ForegroundColor Red
} finally {
    if ($ContainerStarted) {
        if ($ExitCode -eq 0) {
            $null = Invoke-Native 'docker' @('rm', '-f', $Container)
        } else {
            Write-Host "container kept for debugging: docker exec -it $Container bash"
        }
    }
    if (Test-Path -LiteralPath $WorkDir) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($OldConsoleEncoding -ne $null) {
        [Console]::OutputEncoding = $OldConsoleEncoding
    }
}

exit $ExitCode
