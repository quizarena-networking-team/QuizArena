[CmdletBinding()]
param(
    [string]$MavenRepository,
    [switch]$SmokeTest
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $mavenArguments = @('-B', '-ntp')
    if ($MavenRepository) {
        $mavenArguments += "-Dmaven.repo.local=$MavenRepository"
    }
    $mavenArguments += @(
        '-DskipTests',
        'compile',
        'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath',
        '-Dmdep.outputFile=target/member3-preview-classpath.txt'
    )
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Preview build failed. Check the Maven output above.' }

    $dependencyClasspath = (Get-Content -LiteralPath 'target/member3-preview-classpath.txt' -Raw).Trim()
    $classesPath = Join-Path $projectRoot 'target/classes'
    $runtimeClasspath = $classesPath + [IO.Path]::PathSeparator + $dependencyClasspath
    $javaArguments = @(
        '--enable-native-access=ALL-UNNAMED',
        '-cp',
        $runtimeClasspath,
        'com.quizarena.client.quiz.preview.Member3PreviewLauncher'
    )
    if ($SmokeTest) { $javaArguments += '--smoke-test' }
    & java @javaArguments
    if ($LASTEXITCODE -ne 0) { throw 'Member 3 preview failed. Check the Java output above.' }
} finally {
    Pop-Location
}
