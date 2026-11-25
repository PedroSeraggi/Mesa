' Abre a Mesa (Commander com as regras do XMage).
' Liga a ponte escondida (ela liga o servidor do XMage quando precisa) e abre a tela como um aplicativo.
Option Explicit
Dim fso, sh, base, java, cliente, mesa, cmd, url, i, pronto, http, navegador, caminhos, c
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
base = fso.GetParentFolderName(WScript.ScriptFullName)
java = base & "\XMage\java\jre1.8.0_201\bin\javaw.exe"
cliente = base & "\XMage\xmage\mage-client"
mesa = base & "\mesa-xmage"
url = "http://localhost:8792"

If Not fso.FileExists(java) Then
  MsgBox "Nao achei o Java que vem com a Mesa em:" & vbCrLf & java & vbCrLf & vbCrLf & _
         "Extraia o ZIP inteiro (botao direito > Extrair tudo) antes de abrir.", 16, "Mesa"
  WScript.Quit
End If

' a ponte ja esta rodando? entao so abre a tela
If Not NoAr() Then
  cmd = """" & java & """ -Xmx1500m -Dfile.encoding=UTF-8" & _
        " -Dmesa.dir=""" & mesa & """" & _
        " -Dxmage.dir=""" & base & "\XMage""" & _
        " -cp """ & mesa & "\classes;" & cliente & "\lib\*"" Ponte"
  sh.CurrentDirectory = cliente
  sh.Run cmd, 0, False
  For i = 1 To 60
    WScript.Sleep 500
    If NoAr() Then Exit For
  Next
End If

' abre como aplicativo (janela propria, sem abas): Chrome, senao Edge, senao o navegador padrao
caminhos = Array( _
  sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\Google\Chrome\Application\chrome.exe", _
  sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%") & "\Google\Chrome\Application\chrome.exe", _
  sh.ExpandEnvironmentStrings("%LocalAppData%") & "\Google\Chrome\Application\chrome.exe", _
  sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%") & "\Microsoft\Edge\Application\msedge.exe", _
  sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\Microsoft\Edge\Application\msedge.exe")
navegador = ""
For Each c In caminhos
  If navegador = "" And fso.FileExists(c) Then navegador = c
Next
If navegador <> "" Then
  sh.Run """" & navegador & """ --app=" & url & " --start-maximized", 1, False
Else
  sh.Run url
End If

Function NoAr()
  On Error Resume Next
  Set http = CreateObject("MSXML2.XMLHTTP")
  http.Open "GET", url & "/api/estado", False
  http.Send
  NoAr = (Err.Number = 0 And http.Status = 200)
  Err.Clear
End Function
