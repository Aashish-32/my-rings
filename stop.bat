@echo off
rem Stops the My Rings dashboard server (the background python process running app\server.py).
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter \"name='python.exe'\" | Where-Object { $_.CommandLine -match 'server\.py' }; if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; Write-Host 'Dashboard stopped.' } else { Write-Host 'Dashboard was not running.' }"
echo.
pause
