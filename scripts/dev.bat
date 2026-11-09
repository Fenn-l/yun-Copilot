@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [1/2] Building Vue frontend...
pushd frontend
call npm run build
if errorlevel 1 (popd & exit /b 1)
popd
echo [2/2] Starting Spring Boot on http://localhost:8080 ...
call mvn spring-boot:run
