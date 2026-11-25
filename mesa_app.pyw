"""Atalho da Mesa: liga a ponte (que liga o servidor do XMage se precisar) e abre a tela no navegador."""
import os
import socket
import subprocess
import time
import webbrowser

MESA = os.path.dirname(os.path.abspath(__file__))
XMAGE = os.path.join(os.path.dirname(MESA), "XMage")
CLIENTE = os.path.join(XMAGE, "xmage", "mage-client")
JAVA = os.path.join(XMAGE, "java", "jre1.8.0_201", "bin", "javaw.exe")
PORTA = 8792
URL = f"http://localhost:{PORTA}"


def no_ar(porta: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", porta), timeout=1):
            return True
    except OSError:
        return False


if not no_ar(PORTA):
    cp = os.path.join(MESA, "classes") + ";" + os.path.join(CLIENTE, "lib", "*")
    with open(os.path.join(MESA, "ponte.log"), "w", encoding="utf-8") as log:
        subprocess.Popen(
            [JAVA if os.path.exists(JAVA) else "javaw", "-Xmx1500m", "-Dfile.encoding=UTF-8",
             f"-Dmesa.dir={MESA}", f"-Dxmage.dir={XMAGE}", "-cp", cp, "Ponte"],
            cwd=CLIENTE, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS,
        )
    for _ in range(60):
        if no_ar(PORTA):
            break
        time.sleep(0.5)



def abrir_como_app():
    """Abre a Mesa numa janela própria (sem abas nem barra de endereço), como um aplicativo."""
    pf = [os.environ.get(k, "") for k in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA")]
    candidatos = [os.path.join(b, r) for b in pf if b for r in (
        r"Google\Chrome\Application\chrome.exe", r"Microsoft\Edge\Application\msedge.exe")]
    for exe in candidatos:
        if os.path.isfile(exe):
            subprocess.Popen([exe, f"--app={URL}", "--start-maximized"])
            return
    webbrowser.open(URL)  # sem Chrome/Edge: abre no navegador padrão


if "--sem-tela" not in __import__("sys").argv:
    abrir_como_app()
