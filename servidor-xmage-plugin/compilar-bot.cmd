@echo off
rem Compila o bot da Mesa (src\mage\player\ai\MesaComputerPlayer.java) e gera mage-player-ai-mesa-1.4.63.jar.
rem Precisa de um JDK (javac e jar) no PATH e do XMage 1.4.63 na pasta ..\..\XMage (ao lado do repositorio).
rem Depois copie o .jar para XMage\xmage\mage-server\plugins\ (do pacote do instalador e, se quiser, do Zera).
rem O config.xml do servidor local a ponte ajusta sozinha ao ligar o XMage.
cd /d "%~dp0"
set SRV=%~dp0..\..\XMage\xmage\mage-server
if exist bot-classes rmdir /s /q bot-classes
mkdir bot-classes
javac --release 8 -proc:none -nowarn -encoding UTF-8 -cp "%SRV%\lib\*;%SRV%\plugins\*" -d bot-classes src\mage\player\ai\MesaComputerPlayer.java || exit /b 1
rem leva junto as classes da IA original: no config.xml a linha "Computer - mad" passa a apontar so para este jar
pushd bot-classes && jar xf "%SRV%\plugins\mage-player-ai-mad-1.4.63.jar" && rmdir /s /q META-INF & popd
jar cf mage-player-ai-mesa-1.4.63.jar -C bot-classes . || exit /b 1
rmdir /s /q bot-classes
echo Pronto: mage-player-ai-mesa-1.4.63.jar
