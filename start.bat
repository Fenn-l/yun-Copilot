@echo off
setlocal
cd /d "%~dp0"
if exist "C:\Program Files\Java\jdk-17\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Java\jdk-17"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)
echo Starting 云枢 Copilot at http://localhost:8080
call mvn spring-boot:run
