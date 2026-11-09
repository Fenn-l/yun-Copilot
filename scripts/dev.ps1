$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (Test-Path 'C:\Program Files\Java\jdk-17') {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

Write-Host '[1/3] Building Vue frontend...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'frontend')
npm install --no-audit --no-fund
npm run build
Pop-Location

Write-Host '[2/3] Compiling and testing backend...' -ForegroundColor Cyan
mvn -B test

Write-Host '[3/3] Starting Spring Boot on http://localhost:8080 ...' -ForegroundColor Green
mvn spring-boot:run
