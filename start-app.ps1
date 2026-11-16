# Starts the whole app: Spring Boot backend + React UI, each in its own window,
# then opens the browser.     Usage:  cd C:\FCI\TableApp ;  .\start-app.ps1
# Database details (host, user, password) are entered in the UI.
# Close the two PowerShell windows (or press Ctrl+C in them) to stop the app.

$backendDir  = Join-Path $PSScriptRoot "backend"
$frontendDir = Join-Path $PSScriptRoot "frontend"

Write-Host "Starting backend (Spring Boot) ..." -ForegroundColor Cyan
Start-Process powershell -WorkingDirectory $backendDir -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='Backend :8080'; mvn spring-boot:run"

Write-Host "Waiting for backend on http://localhost:8080 ..." -NoNewline
$ready = $false
for ($i = 0; $i -lt 120; $i++) {
    try {
        Invoke-WebRequest "http://localhost:8080/api/connections/ping" -UseBasicParsing -TimeoutSec 2 | Out-Null
        $ready = $true; break
    } catch {
        # any HTTP answer (e.g. 404 for the unknown session) means the backend is up
        if ($_.Exception.Response) { $ready = $true; break }
        Start-Sleep -Seconds 1; Write-Host "." -NoNewline
    }
}
Write-Host ""
if (-not $ready) {
    Write-Host "Backend did not start within 2 minutes - check the 'Backend :8080' window for errors." -ForegroundColor Red
    exit 1
}
Write-Host "Backend is up." -ForegroundColor Green

Write-Host "Starting UI (React/Vite) ..." -ForegroundColor Cyan
$npmCmd = "`$host.UI.RawUI.WindowTitle='UI :5173'; if (-not (Test-Path node_modules)) { npm install }; npm run dev"
Start-Process powershell -WorkingDirectory $frontendDir -ArgumentList "-NoExit", "-Command", $npmCmd

Start-Sleep -Seconds 4
Start-Process "http://localhost:5173"
Write-Host "Opened http://localhost:5173" -ForegroundColor Green
