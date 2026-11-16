@echo off
set "DIR=%~dp0\.."
java -Djava.awt.headless=true -jar "%DIR%\target\ffdec-mcp-server-1.0.0.jar" %*
