<#
.SYNOPSIS
Starts the local mod-dp-bridge Web UI.

.DESCRIPTION
Starts the Web UI from either a source checkout or an unpacked Web distribution.
In a source checkout, the script builds bridge-web:installDist automatically when
the launcher is missing. Runtime extraction is disabled unless -ServerJar is
provided explicitly. The service is always bound to 127.0.0.1.

.PARAMETER ServerJar
Path to the trusted, unmodified Mindustry v159.7 server-release.jar. Supplying
this parameter enables runtime extraction after the file and SHA-256 are checked.

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
.\scripts\start-web.ps1 -Port 8081 -WorkDir "D:\mod-dp-bridge-work" -NoBrowser
#>
[CmdletBinding()]
param(
    [string] $ServerJar,

    [ValidateRange(1, 65535)]
    [int] $Port = 8080,

    [string] $WorkDir = 'work/web-jobs',

    [switch] $NoBrowser,

    [Alias('h')]
    [switch] $Help
)

$ErrorActionPreference = 'Stop'
$expectedServerSha256 = 'E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB'

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
if ([string]::IsNullOrWhiteSpace($ServerJar)) {
    # Runtime remains disabled below; inherited values are deliberately ignored.
} else {
    $serverItem = Get-Item -LiteralPath $ServerJar -Force
    if (-not ($serverItem -is [System.IO.FileInfo])) {
        throw "-ServerJar must point to a regular file: $ServerJar"
    }
    if (($serverItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "-ServerJar must not be a symbolic link or another reparse point: $ServerJar"
    }

    $validatedServerPath = $serverItem.FullName
    $actualHash = Get-LiteralFileSha256 $validatedServerPath
    if ($actualHash -ne $expectedServerSha256) {
        throw "Mindustry Server SHA-256 mismatch. Expected $expectedServerSha256 but found $actualHash."
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
