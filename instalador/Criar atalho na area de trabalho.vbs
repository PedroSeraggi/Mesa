' Cria o atalho "Mesa" (com o icone do M dourado) na area de trabalho.
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
Set atalho = sh.CreateShortcut(sh.SpecialFolders("Desktop") & "\Mesa.lnk")
atalho.TargetPath = sh.ExpandEnvironmentStrings("%WinDir%") & "\System32\wscript.exe"
atalho.Arguments = """" & base & "\Abrir Mesa.vbs"""
atalho.WorkingDirectory = base
atalho.IconLocation = base & "\mesa-xmage\icone-mesa.ico,0"
atalho.Description = "Mesa - Commander com as regras do XMage"
atalho.Save
MsgBox "Pronto! O atalho ""Mesa"" foi criado na sua area de trabalho.", 64, "Mesa"
