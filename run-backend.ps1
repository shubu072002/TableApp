# Starts the Spring Boot backend through Maven on http://localhost:8080.
# No database settings needed here: users connect from the UI.
# Usage (from C:\FCI\TableApp):   .\run-backend.ps1
Set-Location (Join-Path $PSScriptRoot "backend")
mvn spring-boot:run
