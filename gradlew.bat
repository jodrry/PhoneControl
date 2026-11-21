@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if exist "%WRAPPER_JAR%" goto :wrapper

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    gradle %*
    exit /b %ERRORLEVEL%
)

echo Gradle wrapper JAR was not generated in this checkout.
echo Open the project in Android Studio, or install Gradle 8.7 and run:
echo     gradle wrapper --gradle-version 8.7
exit /b 1

:wrapper
if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_CMD=java"
)
"%JAVA_CMD%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
