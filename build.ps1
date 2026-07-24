<#
.SYNOPSIS
    Finds JDK 25, sets JAVA_HOME, and builds the project with Maven (verbose logs).
#>

$ErrorActionPreference = "Stop"

function Find-Java25 {
    Write-Host "Searching for JDK 25..." -ForegroundColor Cyan

    $candidates = @()

    # 1. Check common install locations
    $searchRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files (x86)\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:USERPROFILE\.jdks",
        "$env:USERPROFILE\.sdkman\candidates\java"
    )

    foreach ($root in $searchRoots) {
        if (Test-Path $root) {
            Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "25" } |
                ForEach-Object { $candidates += $_.FullName }
        }
    }

    # 2. Check existing JAVA_HOME
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $candidates += $env:JAVA_HOME
    }

    # 3. Check registry (Windows JDK installers often register here)
    $regPaths = @(
        "HKLM:\SOFTWARE\JavaSoft\JDK",
        "HKLM:\SOFTWARE\Eclipse Adoptium\JDK"
    )
    foreach ($regPath in $regPaths) {
        if (Test-Path $regPath) {
            Get-ChildItem $regPath -ErrorAction SilentlyContinue |
                Where-Object { $_.PSChildName -match "25" } |
                ForEach-Object {
                    $jdkHome = (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).JavaHome
                    if ($jdkHome) { $candidates += $jdkHome }
                }
        }
    }

    # Dedup and validate by actually running java -version
    $candidates = $candidates | Select-Object -Unique
    foreach ($path in $candidates) {
        $javaExe = Join-Path $path "bin\java.exe"
        if (Test-Path $javaExe) {
            $verOutput = & $javaExe -version 2>&1 | Out-String
            if ($verOutput -match '"?25[.\d]*"?') {
                return $path
            }
        }
    }

    return $null
}

# --- Locate JDK 25 ---
$jdk25Path = Find-Java25

if (-not $jdk25Path) {
    Write-Host "ERROR: JDK 25 not found on this system." -ForegroundColor Red
    Write-Host "Install it (e.g. https://adoptium.net) or set JAVA_HOME manually, then re-run." -ForegroundColor Yellow
    exit 1
}

Write-Host "Found JDK 25 at: $jdk25Path" -ForegroundColor Green

# --- Set environment for this session ---
$env:JAVA_HOME = $jdk25Path
$env:Path = "$jdk25Path\bin;$env:Path"

Write-Host "`n=== Java version ===" -ForegroundColor Cyan
java -version

Write-Host "`n=== Maven version ===" -ForegroundColor Cyan
mvn -v

# --- Run build with full verbose logs ---
$logFile = "build.log"
Write-Host "`n=== Building JAR (verbose, logging to $logFile) ===" -ForegroundColor Cyan

mvn clean package -X -e "-Dmaven.compiler.verbose=true" 2>&1 | Tee-Object -FilePath $logFile

if ($LASTEXITCODE -ne 0) {
    Write-Host "`nBUILD FAILED. See $logFile for details." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "`n=== Build succeeded ===" -ForegroundColor Green
Write-Host "=== JAR output ===" -ForegroundColor Cyan
Get-ChildItem -Path "target" -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host $_.FullName -ForegroundColor Green
}