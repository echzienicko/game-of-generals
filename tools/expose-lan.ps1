<#
.SYNOPSIS
    Expose the Game of the Generals server to other devices on your LAN.

.DESCRIPTION
    WSL2 runs the VM behind a NAT, so the Linux address (e.g. 172.24.x.x) is unreachable
    from anywhere but Windows itself. Mirrored networking would fix that natively, but it
    requires Windows 11 22H2 or newer and is by design unavailable on Windows 10 (WSL just
    prints "not supported, falling back to NAT"). So we forward instead: Windows listens on
    its own LAN address and relays the bytes to WSL.

    Two rules are installed per port, both of which need administrator rights:
      * netsh interface portproxy - a raw TCP relay, so WebSocket/SockJS passes through
        untouched (it is not HTTP-aware).
      * a Windows firewall inbound rule scoped to LocalSubnet, because this machine's
        Ethernet adapter is on the Public network profile, which blocks unsolicited
        inbound traffic by default.

.PARAMETER Ports
    Windows-side ports to forward. Defaults to the packaged game (8080), the dev server
    (5173), and 80 as an alias for 8080 so a bare http://<ip> also works.

.PARAMETER OpenToAny
    Accept the ports from any source address instead of just the local subnet. Only use
    this to diagnose a client that cannot reach you at all - a phone on a different
    network is the usual reason - since it exposes the game beyond the local subnet.

.PARAMETER Remove
    Delete the rules instead of adding them.

.EXAMPLE
    # from an elevated PowerShell (right-click PowerShell > Run as administrator)
    powershell -ExecutionPolicy Bypass -File .\tools\expose-lan.ps1

.EXAMPLE
    # after `wsl --shutdown`, since the WSL address changes on every restart
    powershell -ExecutionPolicy Bypass -File .\tools\expose-lan.ps1

.NOTES
    Run this again after every WSL restart. The relay points at a literal address, and WSL
    hands out a new one each time the VM boots.
#>
[CmdletBinding()]
param(
    [int[]]$Ports = @(80, 8080, 5173),
    [switch]$OpenToAny,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Administrator)) {
    throw "Run this from an elevated PowerShell: Start-Process powershell -Verb RunAs"
}

if ($Remove) {
    foreach ($port in $Ports) {
        & netsh interface portproxy delete v4tov4 listenport=$port | Out-Null
        & netsh advfirewall firewall delete rule name="Generals LAN $port" | Out-Null
        Write-Host "removed rules for port $port"
    }
    return
}

# WSL hands the VM a fresh address on every boot, so ask it rather than caching one.
$wslIp = (& wsl.exe hostname -I).Trim().Split(' ') | Where-Object { $_ } | Select-Object -First 1
if (-not $wslIp) {
    throw "could not read the WSL address - is the distribution running? (wsl.exe hostname -I)"
}
if ($wslIp -notmatch '^\d+\.\d+\.\d+\.\d+$') {
    throw "unexpected WSL address '$wslIp'"
}

$lanIps = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.InterfaceAlias -notlike '*Loopback*' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -ExpandProperty IPAddress

# Port 80 is forwarded to 8080 as well, so http://<ip> works with no port typed at
# all. That removes the single most common way this goes wrong: someone types the bare
# address, hits nothing listening on 80, and reports "can't be reached".
$targets = @{ 80 = 8080; 8080 = 8080; 5173 = 5173 }
$scope = if ($OpenToAny) { 'any' } else { 'localsubnet' }

Write-Host "WSL address : $wslIp"
Write-Host "Windows LAN : $($lanIps -join ', ')"
Write-Host "Firewall    : inbound from $scope"
Write-Host ''

foreach ($port in $Ports) {
    if (-not $targets.ContainsKey($port)) { throw "no target port known for listen port $port" }
    $target = $targets[$port]

    & netsh interface portproxy delete v4tov4 listenport=$port | Out-Null
    & netsh interface portproxy add v4tov4 listenport=$port connectport=$target connectaddress=$wslIp | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "netsh portproxy failed for port $port" }

    & netsh advfirewall firewall delete rule name="Generals LAN $port" | Out-Null
    & netsh advfirewall firewall add rule `
        name="Generals LAN $port" `
        dir=in action=allow protocol=TCP localport=$port `
        profile=any remoteip=$scope | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "firewall rule failed for port $port" }

    if ($target -ne $port) {
        Write-Host "forwarded 0.0.0.0:$port -> $wslIp`:$target"
    } else {
        Write-Host "forwarded 0.0.0.0:$port -> $wslIp`:$target"
    }
}

Write-Host ''
foreach ($port in $Ports) {
    foreach ($lanIp in $lanIps) {
        if ($port -eq 5173) {
            Write-Host "  dev server    : http://$lanIp`:$port/       (hot reload)"
        } else {
            Write-Host "  packaged game : http://$lanIp`:$port/       <- share this one"
        }
    }
}
Write-Host ''
Write-Host 'Create a game on one device, then type the 6-character code into the Lobby on the other.'
Write-Host 'Note the game code is the only secret: joining needs no token, so keep this to a trusted LAN.'
if (-not $OpenToAny) {
    Write-Host ''
    Write-Host 'Still blocked? The client may be outside this subnet (a phone on Wi-Fi often is).'
    Write-Host 'Re-run with -OpenToAny to accept any source address while you diagnose.'
}