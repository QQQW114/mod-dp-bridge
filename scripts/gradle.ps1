param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @('build')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Gradle's Windows test-worker classpath argfile can corrupt non-ASCII project paths.
# Run through a stable ASCII junction while keeping all files in the real workspace.
$junction = Join-Path $env:USERPROFILE 'mod-dp-bridge-work'
if (Test-Path -LiteralPath $junction) {
    $item = Get-Item -LiteralPath $junction
    $target = @($item.Target)[0]
    if ($item.LinkType -ne 'Junction' -or
        -not [string]::Equals((Resolve-Path $target).Path, $projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to reuse $junction because it is not a junction to $projectRoot"
    }
} else {
    New-Item -ItemType Junction -Path $junction -Target $projectRoot | Out-Null
}

Push-Location $junction
try {
    & (Join-Path $junction 'gradlew.bat') @GradleArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
