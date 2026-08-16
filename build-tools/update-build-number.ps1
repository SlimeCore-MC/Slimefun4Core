param(
    [Parameter(Mandatory = $true)]
    [string]$PropertiesPath,

    [Parameter(Mandatory = $true)]
    [string]$BuildSession
)

$ErrorActionPreference = 'Stop'

$propertiesDirectory = Split-Path -Parent $PropertiesPath
if (-not [string]::IsNullOrWhiteSpace($propertiesDirectory)) {
    New-Item -ItemType Directory -Force -Path $propertiesDirectory | Out-Null
}

$currentYear = (Get-Date).ToString('yy')
$currentBuildNumber = 0

if (Test-Path -LiteralPath $PropertiesPath) {
    $existing = @{}

    foreach ($line in Get-Content -LiteralPath $PropertiesPath) {
        if ($line -match '^\s*#' -or $line -notmatch '=') {
            continue
        }

        $key, $value = $line -split '=', 2
        $existing[$key.Trim()] = $value.Trim()
    }

    # Skip if this exact build session was already processed.
    if ($existing['build.session'] -eq $BuildSession) {
        return
    }

    # Read the current build number and increment it.
    $parsedNumber = 0
    if ([int]::TryParse($existing['short.build.number'], [ref]$parsedNumber)) {
        $currentBuildNumber = $parsedNumber
    }
}

$nextBuildNumber = $currentBuildNumber + 1

@(
    "# Auto-generated build number for SlimefunCore."
    "build.year=$currentYear"
    "build.count=$nextBuildNumber"
    "short.build.number=$nextBuildNumber"
    "generated.short.build.number=$nextBuildNumber"
    "build.session=$BuildSession"
) | Set-Content -LiteralPath $PropertiesPath -Encoding ASCII

