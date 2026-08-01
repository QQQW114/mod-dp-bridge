<#
.SYNOPSIS
Starts the local mod-dp-bridge Web UI.

.DESCRIPTION
Starts the Web UI from either a source checkout or an unpacked Web distribution.
In a source checkout, the script builds bridge-web:installDist automatically when
the launcher is missing. Runtime extraction is enabled by default when the verified
official v159.7 Server JAR is found at work/mindustry-v159.7-server-release.jar
(relative to the repository or distribution root); a missing default JAR falls back
to static conversion. Pass -ServerJar to pin a specific JAR, or -NoRuntime to
disable runtime extraction explicitly. The service is always bound to 127.0.0.1.

.PARAMETER ServerJar
Path to the trusted, unmodified Mindustry v159.7 server-release.jar. When omitted,
the default work/mindustry-v159.7-server-release.jar is probed and enabled if it
exists and matches the pinned SHA-256. Runtime extraction is always enabled only
after the file and SHA-256 are checked.

.PARAMETER NoRuntime
Disable runtime extraction even when a default or explicitly supplied Server JAR
is available, leaving static conversion only.

.PARAMETER Port
Loopback TCP port for the Web UI. The default is 8080.

.PARAMETER WorkDir
Directory used for Web conversion jobs. Relative paths are resolved from the
repository or unpacked distribution root. The default is work/web-jobs.

.PARAMETER NoBrowser
Do not open the Web UI automatically after its health endpoint becomes ready.

.PARAMETER Help
Print command usage and exit without building or starting the application.

.EXAMPLE
.\scripts\start-web.ps1

.EXAMPLE
.\scripts\start-web.ps1 -ServerJar "C:\Mindustry\server-release.jar"

.EXAMPLE
.\scripts\start-web.ps1 -NoRuntime

.EXAMPLE
.\scripts\start-web.ps1 -Port 8081 -WorkDir "D:\mod-dp-bridge-work" -NoBrowser
#>
[CmdletBinding()]
param(
    [string] $ServerJar,

    [switch] $NoRuntime,

    [ValidateRange(1, 65535)]
    [int] $Port = 8080,

    [string] $WorkDir = 'work/web-jobs',

    [switch] $NoBrowser,

    [Alias('h')]
    [switch] $Help
)

$ErrorActionPreference = 'Stop'
$expectedServerSha256 = 'E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB'
$defaultServerJar = 'work/mindustry-v159.7-server-release.jar'

function Get-LiteralFileSha256([string] $Path) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = $null
    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $hash = $algorithm.ComputeHash($stream)
        return ($hash | ForEach-Object { $_.ToString('X2') }) -join ''
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $algorithm.Dispose()
    }
}

function Assert-ServerJar([string] $Path) {
    $serverItem = Get-Item -LiteralPath $Path -Force
    if (-not ($serverItem -is [System.IO.FileInfo])) {
        throw "Server JAR must point to a regular file: $Path"
    }
    if (($serverItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Server JAR must not be a symbolic link or another reparse point: $Path"
    }

    $validated = $serverItem.FullName
    $actualHash = Get-LiteralFileSha256 $validated
    if ($actualHash -ne $expectedServerSha256) {
        throw "Mindustry Server SHA-256 mismatch. Expected $expectedServerSha256 but found $actualHash."
    }
    return $validated
}

if ($Help) {
    Get-Help $PSCommandPath -Detailed
    exit 0
}

if ([string]::IsNullOrWhiteSpace($WorkDir)) {
    throw '-WorkDir cannot be empty.'
}

$scriptDirectory = (Get-Item -LiteralPath $PSScriptRoot).FullName
$applicationRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory '..'))

$isRepository =
    (Test-Path -LiteralPath (Join-Path $applicationRoot 'settings.gradle.kts') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $applicationRoot 'gradlew.bat') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $applicationRoot 'bridge-web/build.gradle.kts') -PathType Leaf)
$isDistribution =
    (Test-Path -LiteralPath (Join-Path $applicationRoot 'bin/mod-dp-bridge-web.bat') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $applicationRoot 'lib') -PathType Container)

if ($isRepository) {
    $launcher = Join-Path $applicationRoot 'bridge-web/build/install/mod-dp-bridge-web/bin/mod-dp-bridge-web.bat'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        $gradleScript = Join-Path $applicationRoot 'scripts/gradle.ps1'
        if (-not (Test-Path -LiteralPath $gradleScript -PathType Leaf)) {
            throw "Repository helper was not found: $gradleScript"
        }

        Write-Host '[mod-dp-bridge] Web distribution is missing; building it now...' -ForegroundColor Cyan
        & $gradleScript ':bridge-web:installDist' '--no-daemon'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed while building the Web distribution (exit code $LASTEXITCODE)."
        }
    }
} elseif ($isDistribution) {
    $launcher = Join-Path $applicationRoot 'bin/mod-dp-bridge-web.bat'
} else {
    throw "Unsupported layout at '$applicationRoot'. Run this script from a source checkout or an unpacked mod-dp-bridge Web distribution."
}

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Web launcher was not found after preparation: $launcher"
}

if ([System.IO.Path]::IsPathRooted($WorkDir)) {
    $resolvedWorkDir = [System.IO.Path]::GetFullPath($WorkDir)
} else {
    $resolvedWorkDir = [System.IO.Path]::GetFullPath((Join-Path $applicationRoot $WorkDir))
}
New-Item -ItemType Directory -Path $resolvedWorkDir -Force | Out-Null

$validatedServerPath = $null
if ($NoRuntime) {
    # Explicit opt-out wins over any -ServerJar and inherited values.
    if (-not [string]::IsNullOrWhiteSpace($ServerJar)) {
        Write-Host '[mod-dp-bridge] -NoRuntime overrides -ServerJar; runtime extraction remains disabled.' -ForegroundColor Yellow
    }
} elseif (-not [string]::IsNullOrWhiteSpace($ServerJar)) {
    $validatedServerPath = Assert-ServerJar $ServerJar
} else {
    $defaultPath = Join-Path $applicationRoot $defaultServerJar
    if (Test-Path -LiteralPath $defaultPath -PathType Leaf) {
        Write-Host "[mod-dp-bridge] Probing default runtime Server JAR: $defaultPath" -ForegroundColor DarkGray
        $validatedServerPath = Assert-ServerJar $defaultPath
    } else {
        Write-Host "[mod-dp-bridge] Default runtime Server JAR not found: $defaultPath" -ForegroundColor Yellow
        Write-Host '[mod-dp-bridge] Falling back to static conversion. Pass -ServerJar to enable runtime extraction, or use -NoRuntime to keep it disabled.' -ForegroundColor Yellow
    }
}

$webUrl = "http://127.0.0.1:$Port/"
$healthUrl = "${webUrl}api/health"
$browserJob = $null
$exitCode = 0
$managedEnvironment = @(
    'MOD_DP_BRIDGE_HOST',
    'MOD_DP_BRIDGE_PORT',
    'MOD_DP_BRIDGE_WORK_DIR',
    'MOD_DP_BRIDGE_ENABLE_RUNTIME',
    'MOD_DP_BRIDGE_SERVER_JAR'
)
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) {
    $item = Get-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
    $previousEnvironment[$name] = if ($null -eq $item) {
        @{ Present = $false; Value = $null }
    } else {
        @{ Present = $true; Value = $item.Value }
    }
}

try {
    # Fail closed: inherited environment variables must never enable runtime mode.
    $env:MOD_DP_BRIDGE_HOST = '127.0.0.1'
    $env:MOD_DP_BRIDGE_PORT = [string]$Port
    $env:MOD_DP_BRIDGE_WORK_DIR = $resolvedWorkDir
    if ($null -eq $validatedServerPath) {
        $env:MOD_DP_BRIDGE_ENABLE_RUNTIME = 'false'
        Remove-Item Env:\MOD_DP_BRIDGE_SERVER_JAR -ErrorAction SilentlyContinue
        Write-Host '[mod-dp-bridge] Runtime extraction: disabled (static conversion only).' -ForegroundColor Yellow
    } else {
        $env:MOD_DP_BRIDGE_ENABLE_RUNTIME = 'true'
        $env:MOD_DP_BRIDGE_SERVER_JAR = $validatedServerPath
        Write-Host "[mod-dp-bridge] Runtime extraction: enabled with $validatedServerPath" -ForegroundColor Green
    }

    if (-not $NoBrowser) {
        $browserJob = Start-Job -ScriptBlock {
            param($HealthUrl, $WebUrl)

            $deadline = [DateTime]::UtcNow.AddSeconds(60)
            while ([DateTime]::UtcNow -lt $deadline) {
                try {
                    $response = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 2
                    if ($response.StatusCode -eq 200) {
                        Start-Process $WebUrl
                        return
                    }
                } catch {
                    # The child JVM may still be starting; retry quietly.
                }
                Start-Sleep -Milliseconds 500
            }
        } -ArgumentList $healthUrl, $webUrl
    }

    Write-Host "[mod-dp-bridge] Web UI: $webUrl" -ForegroundColor Cyan
    Write-Host "[mod-dp-bridge] Work directory: $resolvedWorkDir" -ForegroundColor DarkGray
    Write-Host '[mod-dp-bridge] Press Ctrl+C to stop.' -ForegroundColor DarkGray

    Push-Location $applicationRoot
    try {
        & $launcher
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    if ($null -ne $browserJob) {
        Stop-Job -Job $browserJob -ErrorAction SilentlyContinue
        Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue
    }
    foreach ($name in $managedEnvironment) {
        $previous = $previousEnvironment[$name]
        if ($previous.Present) {
            Set-Item -LiteralPath "Env:\$name" -Value $previous.Value
        } else {
            Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
        }
    }
}

if ($null -eq $exitCode) {
    $exitCode = 0
}
exit $exitCode
