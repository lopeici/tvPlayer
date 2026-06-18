# Convenience build wrapper for tvPlayer.
#
# Why this exists: the Windows username contains a non-ASCII character, which makes the
# JVM's default temp dir (%TEMP%) break Gradle's NIO selector self-pipe (AF_UNIX) with
# "Unable to establish loopback connection". Forcing an ASCII java.io.tmpdir fixes it.
#
# Usage:  .\build.ps1 assembleRelease
#         .\build.ps1 assembleDebug
#         .\build.ps1 testDebugUnitTest
param([Parameter(ValueFromRemainingArguments = $true)] $GradleArgs)

$tmp = "D:\tmp"
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$tmp"
$env:TEMP = $tmp
$env:TMP = $tmp

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-21*" } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "D:\Android\Sdk" }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "D:\gradle" }

# -p binds the build to this project folder, so build.ps1 works from any current directory.
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" @GradleArgs
exit $LASTEXITCODE
