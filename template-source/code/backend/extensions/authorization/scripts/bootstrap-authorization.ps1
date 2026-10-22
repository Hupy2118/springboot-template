# Windows PowerShell 5.1 compatible. Arguments are transported without Maven re-parsing.
$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$previous = @{}
$exitCode = 127
Push-Location -LiteralPath $backend
try {
    $values = @{ AUTHORIZATION_BOOTSTRAP_ARGUMENT_COUNT = [string]$args.Count }
    for ($i = 0; $i -lt $args.Count; $i++) {
        $values["AUTHORIZATION_BOOTSTRAP_ARGUMENT_$i"] = [string]$args[$i]
    }
    foreach ($key in $values.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    $maven = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { 'mvn' }
    & $maven '-q' '-DskipTests' 'compile' 'org.codehaus.mojo:exec-maven-plugin:3.1.0:java' '-Dexec.mainClass=com.cmbchina.backend.auth.bootstrap.AuthorizationBootstrapCommand'
    $exitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('Bootstrap Maven invocation failed.')
} finally {
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
    Pop-Location
}
exit $exitCode
