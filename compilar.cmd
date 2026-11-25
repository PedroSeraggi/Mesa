@echo off
rem Compila a ponte (src\Ponte.java -> classes\). Precisa de um JDK (javac) no PATH.
rem O XMage fica na pasta ao lado: ..\XMage
cd /d "%~dp0"
if not exist classes mkdir classes
javac --release 8 -proc:none -nowarn -encoding UTF-8 -cp "%~dp0..\XMage\xmage\mage-client\lib\*" -d classes src\Ponte.java
