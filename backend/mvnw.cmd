@REM ----------------------------------------------------------------------------
@REM Maven Wrapper (Windows): downloads Maven on first use into
@REM %USERPROFILE%\.m2\wrapper\dists, then runs it. No Maven install needed.
@REM   mvnw.cmd spring-boot:run
@REM ----------------------------------------------------------------------------
@echo off
setlocal
set "MVNW_VERSION=3.9.9"
set "MVNW_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVNW_VERSION%"
set "MVNW_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVNW_VERSION%/apache-maven-%MVNW_VERSION%-bin.zip"

if exist "%MVNW_HOME%\bin\mvn.cmd" goto run

echo Downloading Maven %MVNW_VERSION% (first run only)...
if not exist "%MVNW_HOME%" mkdir "%MVNW_HOME%"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$zip=Join-Path $env:TEMP 'apache-maven-%MVNW_VERSION%-bin.zip';" ^
  "Invoke-WebRequest -UseBasicParsing -Uri '%MVNW_URL%' -OutFile $zip;" ^
  "Expand-Archive -Force -Path $zip -DestinationPath (Join-Path $env:TEMP 'mvnw-unpack');" ^
  "Copy-Item -Recurse -Force (Join-Path $env:TEMP 'mvnw-unpack\apache-maven-%MVNW_VERSION%\*') '%MVNW_HOME%';" ^
  "Remove-Item -Recurse -Force (Join-Path $env:TEMP 'mvnw-unpack'), $zip"
if errorlevel 1 (
  echo Failed to download Maven. Check your internet connection.
  exit /b 1
)

:run
set "MAVEN_PROJECTBASEDIR=%~dp0"
"%MVNW_HOME%\bin\mvn.cmd" %*
