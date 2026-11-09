$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$javaHome = 'C:\Program Files\Java\jdk-17'
if (Test-Path $javaHome) {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
}

if (-not $env:DASHSCOPE_API_KEY) {
    Write-Host '使用 application.yml 中的百炼演示配置。建议生产环境将 DASHSCOPE_API_KEY 设为环境变量。' -ForegroundColor Yellow
}

Write-Host '启动云枢 Copilot：http://localhost:8080' -ForegroundColor Cyan
mvn spring-boot:run
