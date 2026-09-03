$ErrorActionPreference = 'Stop'

# 切换到后端模板目录，使 Java Bootstrap 的默认输入路径保持与 Bash 入口一致。
$backendDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $backendDirectory

# 仅解析 KEY=VALUE 形式的本地 .env，避免把 PowerShell 脚本注入为配置内容执行。
$envPath = Join-Path $backendDirectory '.env'
if (Test-Path -LiteralPath $envPath) {
  Get-Content -LiteralPath $envPath | ForEach-Object {
    $line = $_.Trim()
    if (!$line -or $line.StartsWith('#')) { return }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) { throw "非法 .env 配置行：$line" }
    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim().Trim('"').Trim("'")
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
  }
}

# 优先遵循 MAVEN_CMD；未配置时使用 PATH 中的 Maven。
$mavenCommand = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { 'mvn' }
if (-not (Get-Command $mavenCommand -ErrorAction SilentlyContinue)) {
  Write-Error '未找到 Maven，请安装 Maven 或通过 MAVEN_CMD 指定路径'
  exit 127
}

& $mavenCommand -q -DskipTests compile exec:java "-Dexec.args=$args"
exit $LASTEXITCODE
