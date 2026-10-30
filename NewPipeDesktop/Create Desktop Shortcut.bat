@echo off
rem Creates a NewPipe shortcut on the desktop pointing at the NewPipe.exe next to this file.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\NewPipe.lnk'); $s.TargetPath='%~dp0NewPipe.exe'; $s.WorkingDirectory='%~dp0'; $s.Save()"
echo Shortcut created on your desktop.
pause
