# Starts the React UI on http://localhost:5173 (backend must be running on 8080).
Set-Location (Join-Path $PSScriptRoot "frontend")
if (-not (Test-Path "node_modules")) { npm install }
npm run dev
