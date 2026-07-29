[CmdletBinding()]
param(
    [ValidateSet("capabilities", "health", "state", "logs", "watch-state", "capture")]
    [string]$Action = "state",
    [string]$Serial,
    [ValidateRange(1, 65535)][int]$HostPort = 8099,
    [ValidateRange(0, [long]::MaxValue)][long]$After = 0,
    [ValidateRange(1, 500)][int]$Limit = 100,
    [ValidateRange(0, [int]::MaxValue)][int]$MaxEvents = 0,
    [string]$LastEventId,
    [string]$Output
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$arguments = @(
    (Join-Path $projectRoot "tools/agent_diagnostics.py"),
    $Action,
    "--project-root", $projectRoot,
    "--host-port", "$HostPort",
    "--after", "$After",
    "--limit", "$Limit",
    "--max-events", "$MaxEvents"
)
if ($Serial) {
    $arguments += @("--serial", $Serial)
}
if ($LastEventId) {
    $arguments += @("--last-event-id", $LastEventId)
}
if ($Output) {
    $arguments += @("--output", $Output)
}

& python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Agent diagnostics command failed with exit code $LASTEXITCODE."
}
