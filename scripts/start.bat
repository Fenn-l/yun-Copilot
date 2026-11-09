@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if "%DASHSCOPE_API_KEY%"=="" (
  set /p DASHSCOPE_API_KEY=请输入阿里云百炼 API Key（可留空使用 application.yml 演示值）: 
)
echo 正在启动企业智能客服 Copilot ...
call mvn spring-boot:run
