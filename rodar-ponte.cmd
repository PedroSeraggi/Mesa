@echo off
rem Roda a ponte da Mesa com o Java que vem com o XMage (..\XMage). Depois abra http://localhost:8792
set MESA=%~dp0.
set XMAGE=%~dp0..\XMage
cd /d "%XMAGE%\xmage\mage-client"
"%XMAGE%\java\jre1.8.0_201\bin\java.exe" -Xmx1500m -Dfile.encoding=UTF-8 -Dmesa.dir="%MESA%" -Dxmage.dir="%XMAGE%" -cp "%MESA%\classes;%XMAGE%\xmage\mage-client\lib\*" Ponte
