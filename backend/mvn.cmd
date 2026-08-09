@echo off
setlocal

set "JDK17=C:\Program Files\Java\jdk-17.0.19"
if not exist "%JDK17%\bin\java.exe" (
    echo ERROR: JDK 17 not found at %JDK17%
    echo Please install JDK 17 and set JAVA_HOME, or use build.ps1 / build.bat.
    exit /b 1
)

set "JAVA_HOME=%JDK17%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MAVEN_CMD=C:\Program Files\Apache\Maven\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
    echo ERROR: Maven executable not found at %MAVEN_CMD%
    echo Please install Maven or use build.ps1 / build.bat.
    exit /b 1
)

call "%MAVEN_CMD%" %*
endlocal
