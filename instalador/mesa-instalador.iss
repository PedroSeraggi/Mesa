; Instalador da Mesa 1.0 (Inno Setup 6)
; Gera: Instalar Mesa 1.0.exe

#define Versao "1.0"

[Setup]
AppId={{6E1C7A52-3B8E-4C7D-9F1A-4D2B7C6E0A10}
AppName=Mesa
AppVersion={#Versao}
AppVerName=Mesa {#Versao}
AppPublisher=Twelvez
VersionInfoVersion={#Versao}
DefaultDirName={localappdata}\Mesa
DefaultGroupName=Mesa
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=Instalar-Mesa-{#Versao}
SetupIconFile=Mesa\mesa-xmage\icone-mesa.ico
UninstallDisplayIcon={app}\mesa-xmage\icone-mesa.ico
UninstallDisplayName=Mesa
Compression=lzma2/max
SolidCompression=yes
LZMAUseSeparateProcess=yes
WizardStyle=modern
ShowLanguageDialog=no

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Messages]
pt.WelcomeLabel2=Isto vai instalar a Mesa {#Versao} no seu computador: Commander com as regras do XMage, para jogar com os amigos.%n%nJá vem tudo junto (XMage e Java). Não precisa instalar mais nada, só o Radmin VPN para jogar online.

[Tasks]
Name: "atalho"; Description: "Criar atalho na área de trabalho"; GroupDescription: "Atalhos:"

[Files]
; a Mesa não leva decks prontos: a pasta decks\ é só de quem usa e o instalador nunca mexe nela
; (se o pacote tiver uma por engano, ela fica de fora)
Source: "Mesa\*"; DestDir: "{app}"; Excludes: "\mesa-xmage\decks\*"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Mesa"; Filename: "{sys}\wscript.exe"; Parameters: """{app}\Abrir Mesa.vbs"""; WorkingDir: "{app}"; IconFilename: "{app}\mesa-xmage\icone-mesa.ico"; Comment: "Commander com as regras do XMage"
Name: "{group}\Desinstalar a Mesa"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Mesa"; Filename: "{sys}\wscript.exe"; Parameters: """{app}\Abrir Mesa.vbs"""; WorkingDir: "{app}"; IconFilename: "{app}\mesa-xmage\icone-mesa.ico"; Comment: "Commander com as regras do XMage"; Tasks: atalho

[Run]
Filename: "{sys}\wscript.exe"; Parameters: """{app}\Abrir Mesa.vbs"""; Description: "Abrir a Mesa agora"; Flags: postinstall nowait skipifsilent
Filename: "https://www.radmin-vpn.com/br/"; Description: "Abrir o site do Radmin VPN (para jogar online com os amigos)"; Flags: postinstall shellexec nowait skipifsilent unchecked

[UninstallRun]
; fecha a Mesa (e o servidor do XMage dela) antes de apagar os arquivos
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -Command ""Get-CimInstance Win32_Process | Where-Object {{ $_.CommandLine -like '*{app}*' -and $_.Name -like 'java*' } | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force }"""; Flags: runhidden; RunOnceId: "FecharMesa"

[UninstallDelete]
Type: filesandordirs; Name: "{app}\mesa-xmage\cache"
Type: files; Name: "{app}\mesa-xmage\*.log"
Type: files; Name: "{app}\XMage\xmage\mage-client\*.log"
Type: files; Name: "{app}\XMage\xmage\mage-server\*.log"
Type: filesandordirs; Name: "{app}\XMage\xmage\mage-client\gamelogsJson"
Type: filesandordirs; Name: "{app}\XMage\xmage\mage-server\db"
Type: filesandordirs; Name: "{app}\XMage\xmage\mage-client\db"
