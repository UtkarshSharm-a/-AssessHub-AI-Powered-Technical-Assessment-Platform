$possibleJdks = @(
    "C:\Program Files\Java\jdk-17.0.19",
    "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot",
    "C:\Program Files\Java\jdk-17",
    "C:\Program Files\Microsoft\jdk-17"
)

$javaHome = $possibleJdks | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
if (-not $javaHome) {
    Write-Error "No JDK 17 installation found. Install JDK 17 and set JAVA_HOME, or update build.ps1 with the correct path."
    exit 1
}

Write-Host "Using JAVA_HOME=$javaHome"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;" + $env:Path
mvn clean install
