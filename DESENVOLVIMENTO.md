# Desenvolvendo a Mesa

Guia para quem vai mexer no código. (Para **jogar**, veja o [README](README.md).)

## Como a Mesa funciona

```
navegador (web/index.html)  <-- HTTP/SSE :8792 -->  Ponte (src/Ponte.java)  <-- XMage -->  servidor XMage (:17171)
```

- **`src/Ponte.java`** — um único arquivo Java que usa as bibliotecas do cliente do XMage. Conecta no servidor, joga a partida e serve a tela em `http://localhost:8792` (rotas `/api/...` + eventos SSE em `/api/eventos`). Também liga o servidor do XMage quando você é o anfitrião.
- **`web/`** — a tela inteira (HTML/CSS/JS puro, sem build):
  - `index.html` — menu, decks, perfil, configurações e a partida.
  - `efeitos.js` (animações de magia), `sons.js` (sons), `mundo.js` (cursores/desenho/ping compartilhados).
  - `cursores/`, `fundos/`, `musica/`, `sons/` — arquivos estáticos.
- **`instalador/`** — roteiro do Inno Setup (`mesa-instalador.iss`), o `Abrir Mesa.vbs` e o passo a passo para jogadores.
- **`mesa_app.pyw`** — atalho de desenvolvimento (Python) que liga a ponte e abre a tela.

## Preparar o PC

1. **XMage 1.4.63** numa pasta **ao lado** desta, chamada `XMage` (com `java\jre1.8.0_201` e `xmage\mage-client`, `xmage\mage-server`). Peça a pasta pronta para o Pedro (é a mesma que vai no instalador) — o `config.xml` do servidor tem a porta secundária fixa em 17172.
   ```
   Desktop\
     XMage\        <- servidor/cliente XMage + Java 8
     mesa-xmage\   <- este repositório
   ```
2. Um **JDK** (qualquer versão 11+) com `javac` no PATH, para compilar (o código é compilado com `--release 8`).
3. (Opcional) **Python 3** para o `mesa_app.pyw`.

## Rodar

```
compilar.cmd        (gera classes\Ponte.class)
rodar-ponte.cmd     (liga a ponte; depois abra http://localhost:8792)
```

Mudou só arquivos de `web/`? Basta dar **F5** no navegador. Mudou `Ponte.java`? Compile e reinicie a ponte (isso derruba a partida em andamento).

A chave do Oráculo (Gemini) **não** fica no repositório: crie um arquivo `.env` com `GEMINI_API_KEY=...` ou cole a chave em Configurações → Oráculo. O `.gitignore` já impede subir `.env`, perfil, configurações e cache.

## Lançar uma versão nova (ex.: 1.1)

1. Monte a pasta do pacote (`Mesa-1.x\Mesa\`) com:
   - `XMage\` (sem `mage-client\sample-decks`, sem logs; copie com a Mesa **fechada**, senão o banco do XMage está travado);
   - `mesa-xmage\` com `classes\`, `web\` (sem `index-antes-*`), ícones (sem `decks\`: a Mesa não leva decks prontos) e um `versao.txt` contendo `1.1`;
   - `Abrir Mesa.vbs` e `Criar atalho na area de trabalho.vbs`.
   - em `XMage\xmage\mage-server\plugins\`, o **bot da Mesa** (`mage-player-ai-mesa-1.4.63.jar`, veja abaixo).
2. Em `instalador\mesa-instalador.iss`, troque `#define Versao "1.1"` e gere com o **Inno Setup 6** (`ISCC.exe mesa-instalador.iss`).
3. Publique: `gh release create v1.1 Instalar-Mesa-1.1.exe --title "Mesa 1.1" --notes "o que mudou"`.

A Mesa de todo mundo consulta `api.github.com/repos/PedroSeraggi/Mesa/releases/latest` ao abrir e oferece **Atualizar agora** (rota `/api/atualizar`, que só aceita downloads deste repositório).

## Partidas contra bots e o bot da Mesa

Partidas contra bots e o **Chefe do dia** rodam no **XMage do próprio PC**: a ponte liga o servidor local em segundo plano quando a Mesa abre. Assim cada pessoa roda o próprio bot e o Zera não fica sobrecarregado quando várias pessoas jogam ao mesmo tempo. Se o PC não tiver o XMage da Mesa, a partida vai para o Zera. Para forçar o Zera, coloque `"botsNoServidor": true` no `config.json`. O XP e a vitória contra o chefe continuam indo para o servidor de amigos, pela ponte.

O **bot da Mesa** (`servidor-xmage-plugin/src/mage/player/ai/MesaComputerPlayer.java`) é a mesma IA "mad" do XMage, com duas mudanças:

- quando tem algo na pilha e ele não tem resposta possível (só habilidades de mana), **passa na hora** em vez de simular todas as jogadas;
- pensa **no máximo 10 s por decisão** (o XMage usa nível × 3, ou seja, 30 s para o chefe de nível 10). A profundidade continua sendo o nível.

Para gerar o jar: `servidor-xmage-plugin\compilar-bot.cmd` (precisa de JDK e do XMage na pasta ao lado). Copie `mage-player-ai-mesa-1.4.63.jar` para `XMage\xmage\mage-server\plugins\` do pacote do instalador. Não precisa mexer no `config.xml`: ao ligar o XMage local, a ponte aponta a linha do "Computer - mad" para o jar da Mesa (o XMage só aceita os nomes de bot que já conhece, então não dá para criar outra linha; por isso o `compilar-bot.cmd` põe as classes da IA original dentro do mesmo jar). Se o jar sumir, ela volta para a IA original. No Zera, a instalação é opcional e manual: copie o jar para `plugins/` e faça a mesma troca no `config.xml`.

## Combinados

- Textos da tela em **português**.
- Visual "Grimório": madeira, dourado, fontes Cinzel e EB Garamond.
- Nada de chave, senha ou dado pessoal no repositório.

## Servidor do grupo (Zera)

O Zera (ZimaOS, sempre ligado, no Tailscale) roda dois containers, em `/DATA/AppData/mesa`:

- **`mesa-xmage`**: o servidor do XMage 1.4.63 (portas 17171/17172), imagem `eclipse-temurin:8-jre`, `--network host`, limite de 5 GB (`-Xmx4g`). Na Mesa: *Jogar com amigos → Servidor do grupo (Zera)*. A ponte tenta o IP do Tailscale e depois o da rede de casa (`ENDERECOS_ZERA` em `Ponte.java`).
- **`mesa-amigos`**: a lista de amigos (`servidor-amigos/Amigos.java`, porta 17190). Sem senha (só quem está no Tailscale alcança). Os dados ficam em `amigos/dados.json`.

Para atualizar a lista de amigos: compile `Amigos.java` com `--release 8` e o `gson` do XMage no classpath, copie `classes/` para `/DATA/AppData/mesa/amigos/` e rode `docker restart mesa-amigos`.

### Chefe do dia

- Os chefes ficam em `servidor-amigos/chefes/*.json` (`nome`, `descricao`, `comandante`, `deck` em formato .dck) e são copiados para `/DATA/AppData/mesa/amigos/chefes/` no Zera. O serviço troca o chefe todo dia às 20h (horário de Brasília) e dá +250 XP na primeira vitória de cada um contra o chefe do ciclo.
- Para adicionar um chefe: o deck precisa passar no validador de Commander do XMage (100 cartas, todas existentes no XMage 1.4.63). Prefira decks de força bruta: a IA joga mal decks de combo.
- O nível da IA do chefe é `NIVEL_CHEFE` em `Ponte.java`.
- **Fila e fábrica:** cada ciclo das 20h pega o chefe mais antigo de `chefes/` e o move para `chefes/usados/`. Toda quarta às 6h (e todo dia às 6h se a fila não encheu) o serviço roda `Fabrica.java`, que completa a fila até 8 com decks bracket 4 do Archidekt: pula combo/cEDH/stax, exige 25+ criaturas, nunca repete comandante e valida no XMage. Log em `amigos/fabrica.log`; estado (página e decks já vistos) em `amigos/fabrica.json`.
- O container `mesa-amigos` monta `/DATA/AppData/mesa/mage-server` em `/xmage` (só leitura) para a fábrica validar os decks. Compile a fábrica com o `lib/*` do `mage-server` e o `plugins/mage-deck-constructed-1.4.63.jar` no classpath e copie para `amigos/classes-fabrica/`.
