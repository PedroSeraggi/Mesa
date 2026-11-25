import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.choices.Choice;
import mage.constants.*;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.PlayerType;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.util.MultiAmountMessage;
import mage.utils.MageVersion;
import mage.view.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ponte entre o servidor do XMage (regras) e a interface web da Mesa.
 * Conecta como cliente "sem tela", traduz o estado do jogo para JSON e
 * devolve as respostas do jogador ao servidor.
 */
public class Ponte implements MageClient {

    static int PORTA_WEB = 8792;
    static Path WEB;
    static final int PORTA_XMAGE = 17171;
    static final Gson GSON = new GsonBuilder().serializeNulls().create();

    static Path MESA;      // Desktop\mesa-xmage
    static Path XMAGE;     // Desktop\XMage
    static Path DECKS;     // Desktop\XMage\decks-grimorio
    static Path CACHE;     // mesa-xmage\cache
    static Path GRIMORIO;  // Commander\cards.json (decks do Grimório)

    final MageVersion version = new MageVersion(MageClient.class);
    SessionImpl session;
    UUID roomId, tableId, gameId, playerId;
    volatile UUID gameChatId;
    String meuNome = "Voce";
    boolean pingLigado = false;

    // ----- estado exposto para a interface -----
    final Object trava = new Object();
    String fase = "lobby";               // lobby | conectando | iniciando | jogo | fim
    String erro = null;
    volatile Map<String, Object> jogo = null;     // última foto da mesa
    Map<String, Object> prompt = null;   // pergunta pendente
    int promptSeq = 0;
    final LinkedList<Map<String, Object>> log = new LinkedList<>();
    final LinkedList<Map<String, Object>> avisos = new LinkedList<>();
    Map<String, Object> pedido = null;   // pedido de outro jogador (ex.: voltar o turno)
    UserRequestMessage pedidoMsg = null;
    int avisoSeq = 0;
    Map<String, Object> resultado = null;
    String statusServidor = "";

    final List<OutputStream> ouvintes = new CopyOnWriteArrayList<>();
    final ScheduledExecutorService agenda = Executors.newSingleThreadScheduledExecutor();
    volatile boolean envioPendente = false;

    // =====================================================================
    public static void main(String[] args) throws Exception {
        MESA = Paths.get(System.getProperty("mesa.dir", "C:\\Users\\pedro\\Desktop\\mesa-xmage")).toAbsolutePath().normalize();
        XMAGE = Paths.get(System.getProperty("xmage.dir", "C:\\Users\\pedro\\Desktop\\XMage"));
        PORTA_WEB = Integer.parseInt(System.getProperty("mesa.porta", "8792"));
        WEB = Paths.get(System.getProperty("mesa.web", MESA.resolve("web").toString())).toAbsolutePath().normalize();
        DECKS = MESA.resolve("decks");          // os decks de quem usa esta Mesa
        GRIMORIO = Paths.get(System.getProperty("grimorio.cards", "C:\\Users\\pedro\\Desktop\\Commander\\cards.json"));
        CACHE = MESA.resolve("cache");
        Files.createDirectories(CACHE);
        Files.createDirectories(DECKS);
        // primeira vez: traz os decks que já tinham sido exportados para o XMage
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DECKS)) {
            if (!ds.iterator().hasNext()) {
                Path antigos = XMAGE.resolve("decks-grimorio");
                if (Files.isDirectory(antigos)) {
                    try (DirectoryStream<Path> a = Files.newDirectoryStream(antigos, "*.dck")) {
                        for (Path f : a) Files.copy(f, DECKS.resolve(f.getFileName().toString()));
                    }
                }
            }
        }
        Ponte p = new Ponte();
        p.iniciarWeb();
        new Thread(p::lacoAmigos, "amigos").start();
        System.out.println("Mesa no ar: http://localhost:" + PORTA_WEB);
    }

    // =====================================================================
    // Servidor web
    void iniciarWeb() throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", PORTA_WEB), 50);
        srv.setExecutor(Executors.newCachedThreadPool());
        srv.createContext("/", this::estatico);
        srv.createContext("/api/estado", ex -> json(ex, 200, estado()));
        srv.createContext("/api/eventos", this::eventos);
        srv.createContext("/api/decks", ex -> json(ex, 200, listarDecksInfo()));
        srv.createContext("/api/deck", this::rotaDeck);
        srv.createContext("/api/perfil", this::rotaPerfil);
        srv.createContext("/api/ia", this::rotaIa);
        srv.createContext("/api/mundo", this::rotaMundo);
        srv.createContext("/api/versao", ex -> {   // versão instalada (versao.txt vem no instalador; sem ele = cópia de desenvolvimento)
            String v = "dev";
            try { Path f = MESA.resolve("versao.txt"); if (Files.exists(f)) v = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim(); } catch (Exception ignored) { }
            json(ex, 200, Collections.singletonMap("versao", v));
        });
        srv.createContext("/api/atualizar", this::rotaAtualizar);
        srv.createContext("/api/amigos", this::rotaAmigos);
        srv.createContext("/api/prefs", ex -> {   // configurações da tela (cursor, música, volume...) num arquivo: não somem se o navegador limpar os dados
            Path f = MESA.resolve("prefs.json");
            try {
                synchronized (travaPrefs) {
                    JsonObject o = Files.exists(f) ? JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
                    if ("POST".equals(ex.getRequestMethod())) {
                        JsonObject b = corpo(ex);
                        String k = b.get("k").getAsString();
                        if (b.has("v") && !b.get("v").isJsonNull()) o.addProperty(k, b.get("v").getAsString()); else o.remove(k);
                        Files.write(f, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
                    }
                    json(ex, 200, o);
                }
            } catch (Exception e) { json(ex, 200, new JsonObject()); }
        });
        srv.createContext("/api/reconectar", ex -> {   // voltar para a partida em andamento (caiu, fechou ou travou)
            try {
                if ("POST".equals(ex.getRequestMethod())) {
                    new Thread(() -> reconectar(false)).start();
                    json(ex, 200, Collections.singletonMap("ok", true));
                } else json(ex, 200, partidaSalvaInfo());
            } catch (Throwable t) { json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage()))); }
        });
        srv.createContext("/api/chat", ex -> {   // manda mensagem no chat da partida (todos os jogadores recebem)
            try {
                JsonObject b = corpo(ex);
                if (session == null || gameChatId == null) throw new IllegalStateException("Fora de partida: o chat da mesa só funciona durante um jogo.");
                String t = b.get("texto").getAsString();
                if (t.length() > 1000) t = t.substring(0, 1000);
                boolean ok = session.sendChatMessage(gameChatId, t);
                json(ex, 200, Collections.singletonMap("ok", ok));
            } catch (Throwable t) {
                json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
            }
        });
        srv.createContext("/api/salasRede", ex -> {   // salas anunciadas por outras Mesas na rede (Radmin/LAN)
            List<Map<String, Object>> l = new ArrayList<>();
            long agora = System.currentTimeMillis();
            synchronized (salasRede) {
                salasRede.values().removeIf(x -> agora - (Long) x.get("_t") > 7000);
                for (Map<String, Object> x : salasRede.values()) { Map<String, Object> c = new LinkedHashMap<>(x); c.remove("_t"); l.add(c); }
            }
            json(ex, 200, l);
        });
        srv.createContext("/api/novo", this::novo);
        srv.createContext("/api/acao", this::acao);
        srv.createContext("/api/sair", ex -> {
            new Thread(this::abandonar).start();
            json(ex, 200, Collections.singletonMap("ok", true));
        });
        srv.createContext("/img", this::imagem);
        srv.createContext("/api/paradas", this::rotaParadas);
        srv.createContext("/api/config", this::rotaConfig);
        srv.createContext("/api/mesas", this::rotaMesas);
        srv.createContext("/api/mesa", this::rotaMesa);
        srv.createContext("/api/musicas", ex -> {   // música de fundo: tudo que estiver em web\musica
            List<String> l = new ArrayList<>();
            Path d = WEB.resolve("musica");
            if (Files.isDirectory(d)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                    for (Path f : ds) {
                        String n = f.getFileName().toString().toLowerCase();
                        if (n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".wav")) l.add("musica/" + f.getFileName());
                    }
                }
            }
            Collections.sort(l);
            json(ex, 200, l);
        });
        srv.createContext("/api/fundos", ex -> {   // imagens de fundo do menu: tudo que estiver em web\fundos
            List<String> l = new ArrayList<>();
            Path d = WEB.resolve("fundos");
            if (Files.isDirectory(d)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                    for (Path f : ds) {
                        String n = f.getFileName().toString().toLowerCase();
                        if (n.endsWith(".webp") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) l.add("fundos/" + f.getFileName());
                    }
                }
            }
            Collections.sort(l);
            json(ex, 200, l);
        });
        srv.createContext("/api/desligar", ex -> {
            json(ex, 200, Collections.singletonMap("ok", true));
            new Thread(this::desligar).start();
        });
        srv.start();
        iniciarDescoberta();
    }

    void estatico(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path f = WEB.resolve(p.substring(1)).normalize();
        if (!f.startsWith(WEB) || !Files.isRegularFile(f)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String tipo = p.endsWith(".html") ? "text/html; charset=utf-8" : p.endsWith(".js") ? "application/javascript" :
                p.endsWith(".css") ? "text/css" : p.endsWith(".svg") ? "image/svg+xml" :
                p.endsWith(".webp") ? "image/webp" : p.endsWith(".png") ? "image/png" :
                (p.endsWith(".jpg") || p.endsWith(".jpeg")) ? "image/jpeg" :
                p.endsWith(".mp3") ? "audio/mpeg" : (p.endsWith(".ogg") || p.endsWith(".opus")) ? "audio/ogg" :
                p.endsWith(".m4a") ? "audio/mp4" : p.endsWith(".wav") ? "audio/wav" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void json(HttpExchange ex, int code, Object o) throws IOException {
        byte[] b = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static JsonObject corpo(HttpExchange ex) throws IOException {
        String s = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
        return s.isEmpty() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    void eventos(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        try {
            enviar(out, GSON.toJson(estado()));
            ouvintes.add(out);
            while (ouvintes.contains(out)) {   // mantém a conexão viva
                Thread.sleep(15000);
                synchronized (out) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception e) {
            ouvintes.remove(out);
        } finally {
            try { ex.close(); } catch (Exception ignored) { }
        }
    }

    static void enviar(OutputStream out, String dados) throws IOException {
        synchronized (out) {
            out.write(("data: " + dados + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** Agrupa várias mudanças seguidas num envio só. */
    void mudou() {
        if (envioPendente) return;
        envioPendente = true;
        agenda.schedule(() -> {
            envioPendente = false;
            String s = GSON.toJson(estado());
            for (OutputStream o : ouvintes) {
                try { enviar(o, s); } catch (Exception e) { ouvintes.remove(o); }
            }
        }, 60, TimeUnit.MILLISECONDS);
    }

    Map<String, Object> estado() {
        synchronized (trava) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fase", fase);
            m.put("erro", erro);
            m.put("servidor", statusServidor);
            m.put("eu", playerId == null ? null : playerId.toString());
            m.put("jogo", jogo);
            m.put("prompt", prompt);
            m.put("log", new ArrayList<>(log));
            m.put("avisos", new ArrayList<>(avisos));
            m.put("resultado", resultado);
            m.put("pedido", pedido);
            m.put("reconexao", reconexao);
            m.put("assistindo", assistindo);
            if ("lobby".equals(fase)) m.put("partidaSalva", partidaSalvaInfo());
            m.put("cartoes", "jogo".equals(fase) || "fim".equals(fase) ? cartoesPartida : null);
            return m;
        }
    }

    void aviso(String texto) {
        synchronized (trava) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", ++avisoSeq);
            a.put("texto", texto);
            avisos.add(a);
            while (avisos.size() > 8) avisos.removeFirst();
        }
        mudou();
    }

    List<String> listarDecks() throws IOException {
        List<String> r = new ArrayList<>();
        if (Files.isDirectory(DECKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(DECKS)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".dck") || n.endsWith(".txt")) r.add(n);
                }
            }
        }
        Collections.sort(r, String.CASE_INSENSITIVE_ORDER);
        return r;
    }

    // =====================================================================
    // Meus decks: lista, detalhes, importar do Grimório, colar lista, apagar
    /** Lê um deck (.dck do XMage ou .txt de texto) e devolve as cartas: [qtd, nome, set, num, éComandante]. */
    static List<Object[]> lerCartas(Path p) throws IOException {
        List<Object[]> r = new ArrayList<>();
        boolean sideboard = false;
        for (String bruta : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\r?\n")) {
            String l = bruta.trim();
            if (l.isEmpty() || l.startsWith("NAME:") || l.startsWith("//") || l.startsWith("#")) continue;
            if (l.equalsIgnoreCase("sideboard") || l.equalsIgnoreCase("sideboard:") || l.equalsIgnoreCase("commander") || l.equalsIgnoreCase("commander:")) { sideboard = true; continue; }
            boolean sb = sideboard;
            if (l.startsWith("SB:")) { sb = true; l = l.substring(3).trim(); }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)x?\\s+(?:\\[([^:\\]]*):([^\\]]*)\\]\\s+)?(.+)$").matcher(l);
            if (!m.matches()) continue;
            r.add(new Object[]{Integer.parseInt(m.group(1)), m.group(4).trim(), m.group(2) == null ? "" : m.group(2), m.group(3) == null ? "" : m.group(3), sb});
        }
        return r;
    }

    static String nomeDoArquivo(String arquivo) {
        return arquivo.replaceAll("\\.(dck|txt)$", "").replaceAll("\\s+", " ").trim();
    }

    List<Map<String, Object>> listarDecksInfo() throws IOException {
        List<Map<String, Object>> r = new ArrayList<>();
        for (String a : listarDecks()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("arquivo", a);
            d.put("nome", nomeDoArquivo(a));
            try {
                List<Object[]> cs = lerCartas(DECKS.resolve(a));
                int total = 0;
                List<Map<String, Object>> cmd = new ArrayList<>();
                for (Object[] c : cs) {
                    total += (Integer) c[0];
                    if ((Boolean) c[4]) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("nome", c[1]); x.put("set", c[2]); x.put("num", c[3]);
                        cmd.add(x);
                    }
                }
                d.put("cartas", total);
                d.put("comandantes", cmd);
            } catch (Exception e) {
                d.put("erro", "não consegui ler");
            }
            r.add(d);
        }
        return r;
    }

    Path arquivoDeck(String arquivo) {
        Path p = DECKS.resolve(arquivo).normalize();
        if (!p.startsWith(DECKS) || !(arquivo.endsWith(".dck") || arquivo.endsWith(".txt"))) throw new IllegalArgumentException("deck inválido");
        return p;
    }

    static String arquivoSeguro(String nome) {
        String n = nome.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) n = "Deck";
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    static String semAcento(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    void rotaDeck(HttpExchange ex) throws IOException {
        String acao = ex.getRequestURI().getPath().replaceFirst("^/api/deck/?", "");
        try {
            if (acao.isEmpty() || acao.equals("ver")) {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                List<Map<String, Object>> cs = new ArrayList<>();
                for (Object[] c : lerCartas(arquivoDeck(q.get("arquivo")))) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("qtd", c[0]); x.put("nome", c[1]); x.put("set", c[2]); x.put("num", c[3]); x.put("comandante", c[4]);
                    cs.add(x);
                }
                json(ex, 200, cs);
            } else if (acao.equals("importar")) {
                json(ex, 200, importarGrimorio());
            } else if (acao.equals("colar")) {
                JsonObject b = corpo(ex);
                json(ex, 200, colarLista(b.get("nome").getAsString(), b.has("comandante") ? b.get("comandante").getAsString() : "", b.get("lista").getAsString()));
            } else if (acao.equals("salvar")) {
                json(ex, 200, salvarDeck(corpo(ex)));
            } else if (acao.equals("tokens")) {   // arte escolhida de cada token, por deck (tokens-decks.json)
                Path f = MESA.resolve("tokens-decks.json");
                String arq = query(ex.getRequestURI().getRawQuery()).get("arquivo");
                JsonObject todos;
                synchronized (this) {
                    todos = Files.exists(f) ? JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
                    if ("POST".equals(ex.getRequestMethod())) {
                        JsonObject b = corpo(ex);
                        arq = b.get("arquivo").getAsString();
                        todos.add(arq, b.getAsJsonObject("tokens"));
                        Files.write(f, GSON.toJson(todos).getBytes(StandardCharsets.UTF_8));
                    }
                }
                json(ex, 200, arq != null && todos.has(arq) ? todos.get(arq) : new JsonObject());
            } else if (acao.equals("historico")) {   // o que entrou/saiu de cada deck, salvo em historico-decks.json
                Path f = MESA.resolve("historico-decks.json");
                JsonObject todos; String arqResp = query(ex.getRequestURI().getRawQuery()).get("arquivo");
                synchronized (this) {
                    todos = Files.exists(f) ? JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
                    if ("POST".equals(ex.getRequestMethod())) {
                        JsonObject b = corpo(ex);
                        String arq = b.get("arquivo").getAsString();
                        if (b.has("arquivoAntigo") && !b.get("arquivoAntigo").isJsonNull()) {      // deck renomeado: leva o histórico junto
                            String ant = b.get("arquivoAntigo").getAsString();
                            if (!ant.equals(arq) && todos.has(ant)) { todos.add(arq, todos.get(ant)); todos.remove(ant); }
                        }
                        com.google.gson.JsonArray l = todos.has(arq) ? todos.getAsJsonArray(arq) : new com.google.gson.JsonArray();
                        if (b.has("registro")) l.add(b.get("registro"));
                        while (l.size() > 60) l.remove(0);
                        todos.add(arq, l); arqResp = arq;
                        Files.write(f, GSON.toJson(todos).getBytes(StandardCharsets.UTF_8));
                    }
                }
                json(ex, 200, arqResp != null && todos.has(arqResp) ? todos.get(arqResp) : new com.google.gson.JsonArray());
            } else if (acao.equals("apagar")) {
                JsonObject b = corpo(ex);
                Files.deleteIfExists(arquivoDeck(b.get("arquivo").getAsString()));
                json(ex, 200, Collections.singletonMap("ok", true));
            } else {
                json(ex, 404, Collections.singletonMap("erro", "rota desconhecida"));
            }
        } catch (Exception e) {
            json(ex, 400, Collections.singletonMap("erro", String.valueOf(e.getMessage())));
        }
    }

    /** Lê os decks do Grimório (cards.json) e grava um .dck para cada um. */
    Map<String, Object> importarGrimorio() throws IOException {
        if (!Files.exists(GRIMORIO)) throw new IllegalStateException("Não achei o Grimório em " + GRIMORIO);
        JsonObject g = JsonParser.parseString(new String(Files.readAllBytes(GRIMORIO), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> feitos = new ArrayList<>();
        java.util.regex.Pattern uri = java.util.regex.Pattern.compile("scryfall\\.com/card/([^/]+)/([^/]+)/");
        for (com.google.gson.JsonElement de : g.getAsJsonArray("decks")) {
            JsonObject dk = de.getAsJsonObject();
            String nomeDeck = dk.get("nome").getAsString();
            String cmd = dk.has("commander") && !dk.get("commander").isJsonNull() ? dk.get("commander").getAsString() : "";
            // artes já escolhidas na Mesa para este deck: a importação mantém (nome da carta -> [set, num])
            Map<String, String[]> artes = new HashMap<>();
            Path existente = DECKS.resolve(arquivoSeguro(nomeDeck) + ".dck");
            if (Files.exists(existente)) {
                for (Object[] x : lerCartas(existente)) {
                    if (!((String) x[2]).isEmpty() && !((String) x[3]).isEmpty()) artes.put((String) x[1], new String[]{(String) x[2], (String) x[3]});
                }
            }
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement ce : dk.getAsJsonArray("cartas")) {
                JsonObject c = ce.getAsJsonObject();
                String nome = semAcento(c.get("nome").getAsString().split(" // ")[0]);
                int qtd = c.has("qtd") ? c.get("qtd").getAsInt() : 1;
                String set = "", num = "";
                if (c.has("scryfall") && c.get("scryfall").isJsonObject()) {
                    JsonObject s = c.getAsJsonObject("scryfall");
                    if (s.has("scryfall_uri") && !s.get("scryfall_uri").isJsonNull()) {
                        java.util.regex.Matcher m = uri.matcher(s.get("scryfall_uri").getAsString());
                        if (m.find()) { set = m.group(1).toUpperCase(); num = m.group(2); }
                    }
                }
                if (artes.containsKey(nome)) { set = artes.get(nome)[0]; num = artes.get(nome)[1]; }
                boolean ehCmd = "COMMANDER".equals(c.has("grupo") ? c.get("grupo").getAsString() : "") || nome.equals(semAcento(cmd));
                sb.append(ehCmd ? "SB: " : "").append(qtd).append(set.isEmpty() ? " " : " [" + set + ":" + num + "] ").append(nome).append('\n');
            }
            String arq = arquivoSeguro(nomeDeck) + ".dck";
            Files.write(DECKS.resolve(arq), sb.toString().getBytes(StandardCharsets.UTF_8));
            feitos.add(nomeDoArquivo(arq));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("decks", feitos);
        return r;
    }

    /** Editor de deck: grava {nome, cartas:[{qtd, nome, set, num, comandante}]} como .dck (com edição quando conhecida). */
    Map<String, Object> salvarDeck(JsonObject b) throws IOException {
        String nome = b.get("nome").getAsString().trim();
        if (nome.isEmpty()) throw new IllegalArgumentException("Dê um nome ao deck.");
        StringBuilder sb = new StringBuilder();
        int total = 0;
        boolean semEdicao = false;
        for (com.google.gson.JsonElement e : b.getAsJsonArray("cartas")) {
            JsonObject c = e.getAsJsonObject();
            String n = semAcento(c.get("nome").getAsString().split(" // ")[0]);
            int q = c.get("qtd").getAsInt();
            if (q <= 0) continue;
            String set = c.has("set") && !c.get("set").isJsonNull() ? c.get("set").getAsString().toUpperCase() : "";
            String num = c.has("num") && !c.get("num").isJsonNull() ? c.get("num").getAsString() : "";
            boolean cmd = c.has("comandante") && c.get("comandante").getAsBoolean();
            if (set.isEmpty() || num.isEmpty()) {
                try {
                    List<mage.cards.repository.CardInfo> ci = mage.cards.repository.CardRepository.instance.findCards(n);
                    if (!ci.isEmpty()) { set = ci.get(0).getSetCode(); num = ci.get(0).getCardNumber(); }
                } catch (Exception ignored) { }
            }
            if (set.isEmpty() || num.isEmpty()) semEdicao = true;
            sb.append(cmd ? "SB: " : "").append(q).append(set.isEmpty() || num.isEmpty() ? " [:]" : " [" + set + ":" + num + "]").append(' ').append(n).append('\n');
            total += q;
        }
        String texto = sb.toString();
        String ext = ".dck";
        if (semEdicao) {   // alguma carta sem edição: grava em texto simples, que o XMage acha só pelo nome
            StringBuilder main = new StringBuilder(), side = new StringBuilder();
            for (String l : texto.split("\n")) {
                if (l.isEmpty()) continue;
                boolean cmd = l.startsWith("SB: ");
                String x = (cmd ? l.substring(4) : l).replaceFirst(" \\[[^\\]]*\\]", "");
                (cmd ? side : main).append(x).append('\n');
            }
            texto = main + "\nSideboard\n" + side;
            ext = ".txt";
        }
        String arq = arquivoSeguro(nome) + ext;
        Files.write(DECKS.resolve(arq), texto.getBytes(StandardCharsets.UTF_8));
        // renomeou ou trocou de formato: apaga o arquivo antigo
        if (b.has("arquivoAntigo") && !b.get("arquivoAntigo").isJsonNull()) {
            String antigo = b.get("arquivoAntigo").getAsString();
            if (!antigo.isEmpty() && !antigo.equals(arq)) Files.deleteIfExists(arquivoDeck(antigo));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("arquivo", arq);
        r.put("cartas", total);
        return r;
    }

    /** Salva uma lista colada ("1 Sol Ring", uma por linha) como deck de texto. */
    Map<String, Object> colarLista(String nome, String comandante, String lista) throws IOException {
        StringBuilder main = new StringBuilder(), side = new StringBuilder();
        int total = 0;
        String cmd = semAcento(comandante.trim());
        boolean achouCmd = false;
        for (String bruta : lista.split("\r?\n")) {
            String l = bruta.trim();
            if (l.isEmpty() || l.matches("(?i)^(deck|sideboard|commander|comandante)\\s*:?$")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)x?\\s+(.+?)(\\s+\\([A-Za-z0-9]+\\)\\s*\\S*)?$").matcher(l);
            int q; String n;
            if (m.matches()) { q = Integer.parseInt(m.group(1)); n = m.group(2); } else { q = 1; n = l; }
            n = semAcento(n.split(" // ")[0].trim());
            total += q;
            if (!cmd.isEmpty() && n.equalsIgnoreCase(cmd) && !achouCmd) { side.append(q).append(' ').append(n).append('\n'); achouCmd = true; }
            else main.append(q).append(' ').append(n).append('\n');
        }
        if (!cmd.isEmpty() && !achouCmd) { side.append("1 ").append(cmd).append('\n'); total++; }
        String arq = arquivoSeguro(nome) + ".txt";
        Files.write(DECKS.resolve(arq), (main + "\nSideboard\n" + side).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("arquivo", arq);
        r.put("cartas", total);
        r.put("comandante", achouCmd || !cmd.isEmpty());
        return r;
    }

    // =====================================================================
    // Perfil: nome no servidor, cor do avatar e estatísticas das partidas
    final Object travaPerfil = new Object();
    JsonObject perfil = null;
    // =====================================================================
    // Resumo da partida: a ponte lê o histórico ("X loses 3 life at combat from Y [id]", "TURN 5 for Z (40 - 38)"...)
    // e as fotos da mesa, e monta dano entre jogadores, eliminações, vida por turno e destaques.
    static class Estatisticas {
        String gameId; long inicio = System.currentTimeMillis();
        List<String> ordem = new ArrayList<>();
        Map<String, Map<String, Integer>> dano = new LinkedHashMap<>(), danoCmd = new LinkedHashMap<>();
        Map<String, Integer> magias = new HashMap<>(), compras = new HashMap<>(), vidaGanha = new HashMap<>(), ataques = new HashMap<>();
        Map<String, String> donoCarta = new HashMap<>();          // [id] da carta -> jogador
        Map<String, String> ultimoAgressor = new HashMap<>();     // vítima -> quem tirou vida por último
        Map<String, Integer> danoPorCarta = new HashMap<>();      // "jogador|carta" -> dano total
        Map<String, Object[]> maiorGolpe = new HashMap<>();       // jogador -> {n, carta, vítima, turno}
        List<Map<String, Object>> eliminacoes = new ArrayList<>();
        List<Map<String, Object>> vidaPorTurno = new ArrayList<>();
        Set<String> comandantes = new HashSet<>();
        Map<String, Integer> criaturasDerrotadas = new HashMap<>();
        Map<String, String> criaturaDono = new HashMap<>(), criaturaAtacando = new HashMap<>();
        String atacante = null; int turno = 0;

        static final java.util.regex.Pattern TURNO = java.util.regex.Pattern.compile("^TURN (\\d+) for (.+?) \\(([-\\d ]+)\\)");
        static final java.util.regex.Pattern ATACA = java.util.regex.Pattern.compile("^(.+?) attacks (.+?) with \\d+ creature");
        static final java.util.regex.Pattern PERDE = java.util.regex.Pattern.compile("^(.+?) loses (\\d+) life( at combat)? from (.+?) \\[([0-9a-f]+)\\]");
        static final java.util.regex.Pattern LANCA = java.util.regex.Pattern.compile("^(.+?) casts (.+?) \\[([0-9a-f]+)\\]");
        static final java.util.regex.Pattern POE = java.util.regex.Pattern.compile("^(.+?) (?:plays|puts) (.+?) \\[([0-9a-f]+)\\]");
        static final java.util.regex.Pattern COMPRA = java.util.regex.Pattern.compile("^(.+?) draws (a|\\d+) cards?");
        static final java.util.regex.Pattern GANHA = java.util.regex.Pattern.compile("^(.+?) gains (\\d+) life");

        static void soma(Map<String, Integer> m, String k, int n) { m.merge(k, n, Integer::sum); }
        static void soma2(Map<String, Map<String, Integer>> m, String a, String b, int n) { m.computeIfAbsent(a, x -> new LinkedHashMap<>()).merge(b, n, Integer::sum); }

        void linha(String bruta) {
            String t = bruta.replaceAll("<[^>]+>", "").trim();
            java.util.regex.Matcher m;
            if ((m = TURNO.matcher(t)).find()) {
                turno = Integer.parseInt(m.group(1)); atacante = null;
                String[] v = m.group(3).trim().split("\\s*-\\s*");
                // quem já foi eliminado some da linha "TURN": usa só os que continuam vivos, na mesma ordem
                List<String> vivos = new ArrayList<>(ordem);
                if (vivos.size() != v.length) vivos.removeIf(n -> eliminacoes.stream().anyMatch(e -> n.equals(e.get("nome"))));
                if (vivos.size() == v.length) {
                    Map<String, Object> f = new LinkedHashMap<>(); f.put("turno", turno);
                    Map<String, Integer> vidas = new LinkedHashMap<>();
                    for (int i = 0; i < v.length; i++) try { vidas.put(vivos.get(i), Integer.parseInt(v[i].trim())); } catch (NumberFormatException ignored) { }
                    f.put("vidas", vidas); vidaPorTurno.add(f);
                }
            } else if ((m = ATACA.matcher(t)).find()) {
                atacante = m.group(1); soma(ataques, atacante, 1);
            } else if ((m = PERDE.matcher(t)).find()) {
                String vitima = m.group(1), carta = m.group(4), id = m.group(5);
                int n = Integer.parseInt(m.group(2));
                String quem = donoCarta.get(id);
                if (quem == null && m.group(3) != null) quem = atacante;
                if (quem == null || quem.equals(vitima)) return;
                soma2(dano, quem, vitima, n);
                if (m.group(3) != null && comandantes.contains(carta)) soma2(danoCmd, quem, vitima, n);
                ultimoAgressor.put(vitima, quem);
                soma(danoPorCarta, quem + "|" + carta, n);
                Object[] g = maiorGolpe.get(quem);
                if (g == null || (Integer) g[0] < n) maiorGolpe.put(quem, new Object[]{n, carta, vitima, turno});
            } else if ((m = LANCA.matcher(t)).find()) {
                soma(magias, m.group(1), 1); donoCarta.put(m.group(3), m.group(1));
            } else if ((m = POE.matcher(t)).find()) {
                donoCarta.put(m.group(3), m.group(1));
            } else if ((m = COMPRA.matcher(t)).find()) {
                if (turno > 0) soma(compras, m.group(1), m.group(2).equals("a") ? 1 : Integer.parseInt(m.group(2)));
            } else if ((m = GANHA.matcher(t)).find()) {
                soma(vidaGanha, m.group(1), Integer.parseInt(m.group(2)));
            }
        }

        @SuppressWarnings("unchecked")
        void foto(Map<String, Object> jogo) {
            if (jogo == null) return;
            List<Map<String, Object>> js = (List<Map<String, Object>>) jogo.get("jogadores");
            if (js == null) return;
            if (ordem.isEmpty()) for (Map<String, Object> p : js) ordem.add((String) p.get("nome"));
            int t = jogo.get("turno") instanceof Number ? ((Number) jogo.get("turno")).intValue() : turno;
            // criaturas que saíram do campo: conta para o jogador do turno (se não era dele) ou para quem ela atacava
            Map<String, String> donoAgora = new HashMap<>(), atacandoAgora = new HashMap<>();
            for (Map<String, Object> p : js)
                for (Object c : (List<Object>) p.getOrDefault("campo", Collections.emptyList()))
                    if (c instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) c).get("criatura"))) donoAgora.put(String.valueOf(((Map<String, Object>) c).get("id")), (String) p.get("nome"));
            for (Object cb : (List<Object>) jogo.getOrDefault("combate", Collections.emptyList())) if (cb instanceof Map) {
                Map<String, Object> cm = (Map<String, Object>) cb;
                for (Object a : (List<Object>) cm.getOrDefault("atacantes", Collections.emptyList())) atacandoAgora.put(String.valueOf(a), String.valueOf(cm.get("defensor")));
            }
            String ativo = jogo.get("ativo") == null ? null : String.valueOf(jogo.get("ativo"));
            for (Map.Entry<String, String> e : criaturaDono.entrySet()) {
                if (donoAgora.containsKey(e.getKey())) continue;
                String dono = e.getValue(), credito = null;
                if (ativo != null && !ativo.equals(dono)) credito = ativo;
                else if (criaturaAtacando.containsKey(e.getKey())) credito = criaturaAtacando.get(e.getKey());
                if (credito != null && !credito.equals(dono) && ordem.contains(credito)) soma(criaturasDerrotadas, credito, 1);
            }
            criaturaDono = donoAgora;
            for (Map.Entry<String, String> e : atacandoAgora.entrySet()) criaturaAtacando.put(e.getKey(), e.getValue());
            criaturaAtacando.keySet().retainAll(donoAgora.keySet());
            for (Map<String, Object> p : js) {
                String nome = (String) p.get("nome");
                for (Object c : (List<Object>) p.getOrDefault("comando", Collections.emptyList()))
                    if (c instanceof Map) comandantes.add(String.valueOf(((Map<String, Object>) c).get("nome")));
                int vida = p.get("vida") instanceof Number ? ((Number) p.get("vida")).intValue() : 1;
                boolean saiu = Boolean.TRUE.equals(p.get("saiu"));
                if ((vida <= 0 || saiu) && eliminacoes.stream().noneMatch(e -> nome.equals(e.get("nome")))) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("nome", nome); e.put("turno", t);
                    e.put("por", vida <= 0 ? ultimoAgressor.get(nome) : null);
                    e.put("como", vida <= 0 ? "vida" : "saiu");
                    eliminacoes.add(e);
                }
            }
        }

        Map<String, Object> resumo(Map<String, Object> jogo, String vencedorTexto) {
            foto(jogo);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("turnos", turno);
            r.put("minutos", Math.max(1, (System.currentTimeMillis() - inicio) / 60000));
            r.put("ordem", ordem);
            r.put("dano", dano); r.put("danoCmd", danoCmd);
            r.put("eliminacoes", eliminacoes);
            r.put("vidaPorTurno", vidaPorTurno);
            List<Map<String, Object>> jog = new ArrayList<>();
            for (String nome : ordem) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nome", nome);
                int causado = dano.getOrDefault(nome, Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum();
                int recebido = 0; for (Map<String, Integer> d : dano.values()) recebido += d.getOrDefault(nome, 0);
                int cmd = danoCmd.getOrDefault(nome, Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum();
                x.put("danoCausado", causado); x.put("danoRecebido", recebido); x.put("danoCmd", cmd);
                x.put("magias", magias.getOrDefault(nome, 0)); x.put("compras", compras.getOrDefault(nome, 0));
                x.put("vidaGanha", vidaGanha.getOrDefault(nome, 0)); x.put("ataques", ataques.getOrDefault(nome, 0));
                x.put("criaturas", criaturasDerrotadas.getOrDefault(nome, 0));
                Object[] g = maiorGolpe.get(nome);
                if (g != null) { Map<String, Object> gm = new LinkedHashMap<>(); gm.put("n", g[0]); gm.put("carta", g[1]); gm.put("em", g[2]); gm.put("turno", g[3]); x.put("maiorGolpe", gm); }
                String mvp = null; int mvpN = 0;
                for (Map.Entry<String, Integer> e : danoPorCarta.entrySet()) if (e.getKey().startsWith(nome + "|") && e.getValue() > mvpN) { mvpN = e.getValue(); mvp = e.getKey().substring(nome.length() + 1); }
                if (mvp != null) { Map<String, Object> mm = new LinkedHashMap<>(); mm.put("carta", mvp); mm.put("dano", mvpN); x.put("mvp", mm); }
                List<String> matou = new ArrayList<>();
                for (Map<String, Object> e : eliminacoes) if (nome.equals(e.get("por"))) matou.add((String) e.get("nome"));
                x.put("eliminou", matou);
                eliminacoes.stream().filter(e -> nome.equals(e.get("nome"))).findFirst().ifPresent(e -> x.put("eliminado", e));
                try {
                    for (Map<String, Object> p : (List<Map<String, Object>>) jogo.get("jogadores")) if (nome.equals(p.get("nome"))) x.put("vidaFinal", p.get("vida"));
                } catch (Exception ignored) { }
                jog.add(x);
            }
            r.put("jogadores", jog);
            r.put("texto", vencedorTexto);
            return r;
        }
    }
    volatile Estatisticas stats = null;
    volatile boolean assistindo = false;
    /** Assistir a uma partida (sem ver mãos, sem jogar). O servidor responde com WATCHGAME. */
    void assistir(UUID mesa) throws Exception {
        sairDoJogoAtual();
        sairDaSala();
        conectar();
        synchronized (trava) { fase = "iniciando"; erro = null; jogo = null; prompt = null; resultado = null; log.clear(); avisos.clear(); }
        status("Entrando como espectador...");
        mudou();
        assistindo = true; tableId = mesa; playerId = null;
        if (!session.watchTable(roomId, mesa)) {
            assistindo = false; tableId = null;
            synchronized (trava) { fase = "lobby"; }
            mudou();
            throw new IllegalStateException("Não deu para assistir: a partida pode ter acabado.");
        }
    }
    // para a revanche: como foi criada a última partida
    Map<String, Object> ultimaCriacao = null;

    String deckAtual = "";
    List<String> oponentesAtuais = new ArrayList<>();

    JsonObject perfil() {
        synchronized (travaPerfil) {
            if (perfil == null) {
                try {
                    Path f = MESA.resolve("perfil.json");
                    perfil = Files.exists(f) ? JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
                } catch (Exception e) {
                    perfil = new JsonObject();
                }
                if (!perfil.has("nome")) perfil.addProperty("nome", "Voce");
                if (!perfil.has("cor")) perfil.addProperty("cor", "#C0982F");
                if (!perfil.has("partidas")) perfil.add("partidas", new com.google.gson.JsonArray());
            }
            return perfil;
        }
    }

    void salvarPerfil() {
        synchronized (travaPerfil) {
            try { Files.write(MESA.resolve("perfil.json"), GSON.toJson(perfil()).getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        }
    }

    /** Nome usado no servidor do XMage: só letras e números. */
    String nomeServidor() {
        String n = semAcento(perfil().get("nome").getAsString()).replaceAll("[^A-Za-z0-9_]", "");
        if (n.length() < 3) n = "Voce";
        return n.length() > 14 ? n.substring(0, 14) : n;
    }

    void rotaPerfil(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            JsonObject b = corpo(ex);
            synchronized (travaPerfil) {
                if (b.has("nome")) {
                    String n = b.get("nome").getAsString().trim();
                    if (n.length() > 20) n = n.substring(0, 20);
                    if (!n.isEmpty()) perfil().addProperty("nome", n);
                }
                if (b.has("cor")) perfil().addProperty("cor", b.get("cor").getAsString());
                if (b.has("frase")) { String f = b.get("frase").getAsString().trim(); perfil().addProperty("frase", f.length() > 60 ? f.substring(0, 60) : f); }
                // foto e banner chegam já reduzidos pela tela (data:image/...); limite de segurança de ~700 KB cada
                for (String k : new String[]{"avatar", "banner"}) if (b.has(k)) {
                    String v = b.get(k).isJsonNull() ? "" : b.get(k).getAsString();
                    if (v.isEmpty()) perfil().remove(k);
                    else if ((v.startsWith("data:image/") && v.length() < 1_200_000) || v.matches("fundos/[A-Za-z0-9_.-]+")) perfil().addProperty(k, v);
                }
                if (b.has("zerar") && b.get("zerar").getAsBoolean()) perfil().add("partidas", new com.google.gson.JsonArray());
            }
            salvarPerfil();
        }
        JsonObject r;
        synchronized (travaPerfil) { r = perfil().deepCopy(); }
        r.addProperty("nomeServidor", nomeServidor());
        json(ex, 200, r);
    }

    void registrarPartida(boolean venceu, int turnos) {
        synchronized (travaPerfil) {
            JsonObject p = new JsonObject();
            p.addProperty("data", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            p.addProperty("venceu", venceu);
            p.addProperty("deck", nomeDoArquivo(deckAtual));
            com.google.gson.JsonArray ops = new com.google.gson.JsonArray();
            List<String> outros = outrosJogadores();
            if (!outros.isEmpty()) for (String o : outros) ops.add(o);
            else for (String o : oponentesAtuais) ops.add(nomeDoArquivo(o));
            p.add("oponentes", ops);
            p.addProperty("turnos", turnos);
            com.google.gson.JsonArray l = perfil().getAsJsonArray("partidas");
            l.add(p);
            while (l.size() > 200) l.remove(0);
        }
        salvarPerfil();
        enviarPartidaAoZera(venceu, turnos);
    }
    @SuppressWarnings("unchecked")
    List<String> outrosJogadores() {
        List<String> r = new ArrayList<>();
        try { for (Map<String, Object> p : (List<Map<String, Object>>) jogo.get("jogadores")) if (!Boolean.TRUE.equals(p.get("eu"))) r.add(String.valueOf(p.get("nome"))); } catch (Exception ignored) { }
        return r;
    }
    static boolean ehBot(String nome) { return nome != null && nome.matches("^(IA|Bot) \\d+ .*"); }
    /** XP: o Zera calcula e guarda (nível, missões, recordes). Contra bots vale 30/40/50% (fácil/normal/difícil). */
    @SuppressWarnings("unchecked")
    void enviarPartidaAoZera(boolean venceu, int turnos) {
        final Estatisticas st = stats;
        final UUID gid = gameId;
        final Map<String, Object> jg = jogo;
        if (st == null || gid == null) return;
        new Thread(() -> {
            try {
                Map<String, Object> res = st.resumo(jg, "");
                Map<String, Object> eu = null;
                for (Map<String, Object> x : (List<Map<String, Object>>) res.get("jogadores")) if (meuNome != null && meuNome.equals(x.get("nome"))) eu = x;
                if (eu == null) return;
                List<String> nomes = (List<String>) res.get("ordem");
                boolean temBot = nomes.stream().anyMatch(Ponte::ehBot);
                int humanos = (int) nomes.stream().filter(n -> !ehBot(n)).count();
                double mult = 1.0;
                if (temBot) {
                    int nivel = ultimaCriacao != null && ultimaCriacao.get("nivel") instanceof Number ? ((Number) ultimaCriacao.get("nivel")).intValue() : 4;
                    mult = nivel >= 6 ? 0.5 : nivel <= 2 ? 0.3 : 0.4;
                }
                JsonObject p = new JsonObject();
                p.addProperty("gameId", gid.toString());
                p.addProperty("data", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                p.addProperty("venceu", venceu); p.addProperty("turnos", turnos);
                p.addProperty("deck", nomeDoArquivo(deckAtual)); p.addProperty("multiplicador", mult); p.addProperty("humanos", humanos);
                p.add("eliminou", GSON.toJsonTree(eu.getOrDefault("eliminou", new ArrayList<>())));
                Object el = eu.get("eliminado");
                if (el instanceof Map && ((Map<String, Object>) el).get("por") != null) p.addProperty("eliminadoPor", String.valueOf(((Map<String, Object>) el).get("por")));
                p.addProperty("criaturas", ((Number) eu.getOrDefault("criaturas", 0)).intValue());
                p.addProperty("compras", ((Number) eu.getOrDefault("compras", 0)).intValue());
                p.addProperty("magias", ((Number) eu.getOrDefault("magias", 0)).intValue());
                p.addProperty("dano", ((Number) eu.getOrDefault("danoCausado", 0)).intValue());
                p.addProperty("danoCmd", ((Number) eu.getOrDefault("danoCmd", 0)).intValue());
                if (eu.get("maiorGolpe") != null) p.add("maiorGolpe", GSON.toJsonTree(eu.get("maiorGolpe")));
                p.add("jogadores", GSON.toJsonTree(nomes));
                JsonObject c = new JsonObject();
                c.addProperty("id", idMesa()); c.add("partida", p);
                Map<String, Object> r = GSON.fromJson(postAmigos("partida", c), Map.class);
                synchronized (trava) { if (resultado != null) resultado.put("xp", r); }
                mudou();
            } catch (Exception e) {
                synchronized (trava) { if (resultado != null) resultado.put("xpErro", "Sem conexão com o servidor do grupo: o XP desta partida não foi contado."); }
                mudou();
            }
        }).start();
    }

    // =====================================================================
    // Servidor XMage: liga se precisar e conecta
    static boolean portaAberta(String host, int porta) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, porta), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean portaAberta(int porta) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", porta), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Servidor do XMage: "localhost" (você é o anfitrião) ou o IP do anfitrião na VPN (Radmin/Tailscale). */
    String servidor() {
        String h = servidorBruto();
        return "zera".equalsIgnoreCase(h) ? enderecoZera() : h;
    }
    /** O que está gravado: "localhost", um IP, ou "zera" (servidor do grupo, sempre ligado). */
    String servidorBruto() {
        JsonObject c = config();
        String h = c.has("servidor") ? c.get("servidor").getAsString().trim() : "";
        return h.isEmpty() ? "localhost" : h;
    }
    // Servidor do grupo (o Zera, ligado 24h): pelo Tailscale (amigos de fora) ou pela rede de casa
    static final String[] ENDERECOS_ZERA = {"100.109.159.116", "192.168.15.13"};
    volatile String zeraEscolhido = null; volatile long zeraQuando = 0;
    String enderecoZera() {
        if (zeraEscolhido != null && System.currentTimeMillis() - zeraQuando < 60_000) return zeraEscolhido;
        String achou = null;
        for (String ip : ENDERECOS_ZERA) {
            try (Socket so = new Socket()) { so.connect(new InetSocketAddress(ip, PORTA_XMAGE), 1500); achou = ip; break; } catch (IOException ignored) { }
        }
        if (achou == null) return zeraEscolhido != null ? zeraEscolhido : ENDERECOS_ZERA[0];
        zeraEscolhido = achou; zeraQuando = System.currentTimeMillis();
        return achou;
    }
    boolean souAnfitriao() {
        String h = servidor();
        return h.equals("localhost") || h.equals("127.0.0.1");
    }
    JsonObject config = null;
    synchronized JsonObject config() {
        if (config == null) {
            try {
                Path f = MESA.resolve("config.json");
                config = Files.exists(f) ? JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            } catch (Exception e) { config = new JsonObject(); }
        }
        return config;
    }

    void garantirServidor() throws Exception {
        if (!souAnfitriao()) {
            if ("zera".equalsIgnoreCase(servidorBruto()) && !portaAberta(servidor(), PORTA_XMAGE)) throw new IllegalStateException("Não consegui alcançar o servidor do grupo (Zera). Confira se o Tailscale está ligado neste PC.");
            if (!portaAberta(servidor(), PORTA_XMAGE)) throw new IllegalStateException("Não consegui alcançar o servidor em " + servidor() + ":" + PORTA_XMAGE
                    + ". Confira se o anfitrião está com a Mesa aberta, se vocês estão na mesma rede (Radmin/Tailscale) e o IP.");
            return;
        }
        if (portaAberta(PORTA_XMAGE)) return;
        Path dir = XMAGE.resolve("xmage").resolve("mage-server");
        Path java = XMAGE.resolve("java").resolve("jre1.8.0_201").resolve("bin").resolve("java.exe");
        String jar = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.resolve("lib"), "mage-server-*.jar")) {
            for (Path p : ds) jar = "./lib/" + p.getFileName();
        }
        ProcessBuilder pb = new ProcessBuilder(Files.exists(java) ? java.toString() : "java",
                "-Xmx1024m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-jar", jar);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(MESA.resolve("servidor-xmage.log").toFile());
        servidorLigadoPorMim = pb.start();
        status("Ligando o servidor de regras do XMage (leva ~1 minuto na primeira vez)...");
        for (int i = 0; i < 240; i++) {
            if (portaAberta(PORTA_XMAGE)) {
                Thread.sleep(3000);
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("O servidor do XMage não ligou em 4 minutos. Veja mesa-xmage\\servidor-xmage.log");
    }

    void status(String s) {
        synchronized (trava) { statusServidor = s; }
        mudou();
    }

    String hostConectado = null;
    void conectar() throws Exception {
        // trocou o servidor ou o nome no perfil: reconecta
        if (session != null && session.isConnected() && (!servidor().equals(hostConectado) || !meuNome.startsWith(nomeServidor()))) {
            try { session.connectStop(false, false); } catch (Exception ignored) { }
            session = null;
        }
        if (session != null && session.isConnected()) return;
        garantirServidor();
        status("Conectando no servidor...");
        session = new SessionImpl(this);
        for (int t = 0; t < 5; t++) {
            Connection c = new Connection();
            c.setHost(servidor());
            c.setPort(PORTA_XMAGE);
            String base = nomeServidor();
            c.setUsername(usuarioForcado != null ? usuarioForcado : (t == 0 ? base : base + (t + 1)));
            c.setProxyType(Connection.ProxyType.NONE);
            c.setUserData(montarUserData());   // em quais fases o jogo para pra você agir
            if (session.connectStart(c)) {
                meuNome = c.getUsername();
                hostConectado = servidor();
                roomId = session.getMainRoomId();
                if (!pingLigado) {   // o servidor derruba quem não manda sinal de vida
                    pingLigado = true;
                    agenda.scheduleAtFixedRate(() -> {
                        try { if (session != null && session.isConnected()) session.ping(); } catch (Throwable ignored) { }
                    }, 5, 5, TimeUnit.SECONDS);
                }
                status("Conectado");
                return;
            }
        }
        throw new IllegalStateException("Não consegui conectar no servidor do XMage");
    }

    // =====================================================================
    // Paradas: em quais fases o jogo te dá a vez (o resto passa sozinho)
    static final String[] FASES = {"manutencao", "compra", "principal1", "inicioCombate", "fimCombate", "principal2", "final"};
    final Map<String, Boolean> paradas = paradasPadrao();

    static Map<String, Boolean> paradasPadrao() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String f : FASES) {
            m.put("eu_" + f, f.equals("principal1") || f.equals("principal2"));   // no seu turno: só as fases principais
            m.put("op_" + f, false);                                              // no turno dos outros: nada
        }
        m.put("atacantes", true);     // seu turno: para pra declarar atacantes
        m.put("bloqueadores", true);  // quando te atacam e você tem permanentes: para pra bloquear
        m.put("pilha", true);         // quando algo novo entra na pilha: para pra você poder responder
        return m;
    }

    void carregarParadas() {
        try {
            Path f = MESA.resolve("paradas.json");
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : paradas.keySet()) if (o.has(k)) paradas.put(k, o.get(k).getAsBoolean());
            }
        } catch (Exception ignored) { }
    }

    static void aplicar(mage.players.net.SkipPrioritySteps s, Map<String, Boolean> p, String pre) {
        s.setUpkeep(p.get(pre + "manutencao"));
        s.setDraw(p.get(pre + "compra"));
        s.setMain1(p.get(pre + "principal1"));
        s.setBeforeCombat(p.get(pre + "inicioCombate"));
        s.setEndOfCombat(p.get(pre + "fimCombate"));
        s.setMain2(p.get(pre + "principal2"));
        s.setEndOfTurn(p.get(pre + "final"));
    }

    mage.players.net.UserData montarUserData() {
        if (!paradasCarregadas) { carregarParadas(); paradasCarregadas = true; }
        mage.players.net.UserData d = mage.players.net.UserData.getDefaultUserDataView();
        mage.players.net.UserSkipPrioritySteps s = d.getUserSkipPrioritySteps();
        aplicar(s.getYourTurn(), paradas, "eu_");
        aplicar(s.getOpponentTurn(), paradas, "op_");
        s.setStopOnDeclareAttackersDuringSkipActions(paradas.get("atacantes"));
        s.setStopOnDeclareBlockersWithAnyPermanents(paradas.get("bloqueadores"));
        s.setStopOnDeclareBlockersWithZeroPermanents(false);
        s.setStopOnStackNewObjects(paradas.get("pilha"));
        s.setStopOnAllMainPhases(false);
        s.setStopOnAllEndPhases(false);
        return d;
    }
    boolean paradasCarregadas = false;

    void rotaParadas(HttpExchange ex) throws IOException {
        if (!paradasCarregadas) { carregarParadas(); paradasCarregadas = true; }
        if ("POST".equals(ex.getRequestMethod())) {
            JsonObject b = corpo(ex);
            for (String k : paradas.keySet()) if (b.has(k)) paradas.put(k, b.get(k).getAsBoolean());
            if (b.has("padrao") && b.get("padrao").getAsBoolean()) paradas.putAll(paradasPadrao());
            Files.write(MESA.resolve("paradas.json"), GSON.toJson(paradas).getBytes(StandardCharsets.UTF_8));
            try {
                if (session != null && session.isConnected()) session.updatePreferencesForServer(montarUserData());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        json(ex, 200, paradas);
    }

    // =====================================================================
    // Jogar com amigos: configuração do servidor, lista de mesas, criar, entrar, começar
    void rotaConfig(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            JsonObject b = corpo(ex);
            synchronized (this) {
                if (b.has("servidor")) config().addProperty("servidor", b.get("servidor").getAsString().trim());
                Files.write(MESA.resolve("config.json"), GSON.toJson(config()).getBytes(StandardCharsets.UTF_8));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("servidor", servidor());
        r.put("anfitriao", souAnfitriao());
        r.put("grupo", "zera".equalsIgnoreCase(servidorBruto()));
        r.put("conectado", session != null && session.isConnected() ? hostConectado : null);
        r.put("meusIps", meusIps());
        r.put("temGrimorio", Files.exists(GRIMORIO));
        json(ex, 200, r);
    }

    /** IPs deste PC (para o anfitrião passar aos amigos): destaca os da VPN (Radmin 26.x, Tailscale 100.x). */
    static List<Map<String, Object>> meusIps() {
        List<Map<String, Object>> r = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    String nome = ni.getDisplayName();
                    String tipo = ip.startsWith("26.") || nome.toLowerCase().contains("radmin") ? "Radmin VPN"
                            : ip.startsWith("100.") || nome.toLowerCase().contains("tailscale") ? "Tailscale"
                            : nome.toLowerCase().contains("zerotier") ? "ZeroTier" : "Rede local";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", ip); m.put("tipo", tipo); m.put("placa", nome);
                    r.add(m);
                }
            }
        } catch (Exception ignored) { }
        r.sort((x, y) -> ("Rede local".equals(x.get("tipo")) ? 1 : 0) - ("Rede local".equals(y.get("tipo")) ? 1 : 0));
        return r;
    }

    static Map<String, Object> mesaJson(TableView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getTableId().toString());
        m.put("nome", t.getTableName());
        m.put("dono", t.getControllerName());
        m.put("estado", t.getTableState() == null ? "" : t.getTableState().name());
        m.put("tipo", t.getGameType());
        List<Map<String, Object>> lugares = new ArrayList<>();
        int livres = 0;
        for (SeatView sv : t.getSeats()) {
            Map<String, Object> l = new LinkedHashMap<>();
            String n = sv.getPlayerName();
            boolean bot = sv.getPlayerType() != null && sv.getPlayerType() != PlayerType.HUMAN;
            l.put("nome", n == null || n.isEmpty() ? null : n);
            l.put("bot", bot);
            if (n == null || n.isEmpty()) livres++;
            lugares.add(l);
        }
        m.put("lugares", lugares);
        m.put("livres", livres);
        return m;
    }

    void rotaMesas(HttpExchange ex) throws IOException {
        try {
            conectar();
            List<Map<String, Object>> r = new ArrayList<>();
            for (TableView t : session.getTables(roomId)) {
                if (t.isTournament() || t.getTableState() == TableState.FINISHED) continue;
                r.add(mesaJson(t));
            }
            r.sort((a, b) -> ("WAITING".equals(a.get("estado")) ? 0 : 1) - ("WAITING".equals(b.get("estado")) ? 0 : 1));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("mesas", r);
            o.put("eu", meuNome);
            o.put("servidor", hostConectado);
            json(ex, 200, o);
        } catch (Throwable t) {
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
        }
    }

    boolean donoDaMesa = false;

    // =====================================================================
    // Lista de amigos: serviço "mesa-amigos" no servidor do grupo (Zera), porta 17190.
    // Esta Mesa avisa a cada 10 s quem é e o que está fazendo, e recebe amigos, pedidos e convites.
    static final int PORTA_AMIGOS = 17190;
    volatile Object amigosFoto = null;   // última resposta do serviço
    volatile String amigosErro = null;
    final Object travaAmigos = new Object();

    String idMesa() {
        synchronized (this) {
            JsonObject c = config();
            if (!c.has("idMesa")) {
                c.addProperty("idMesa", UUID.randomUUID().toString() + UUID.randomUUID().toString().substring(0, 8));
                try { Files.write(MESA.resolve("config.json"), GSON.toJson(c).getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            }
            return c.get("idMesa").getAsString();
        }
    }
    String senhaGrupo() {
        JsonObject c = config();
        return c.has("senhaGrupo") ? c.get("senhaGrupo").getAsString() : "";
    }
    /** O que mostrar para os amigos: "no menu", "na sala X (2/4)", "em partida, turno 7". */
    String statusParaAmigos() {
        String f = fase;
        try {
            if ("jogo".equals(f)) { Map<String, Object> j = jogo; return "em partida" + (j != null && j.get("turno") != null ? ", turno " + j.get("turno") : ""); }
            if ("sala".equals(f)) { Map<String, Object> sl = salaParaAmigos(); return sl == null ? "numa sala" : "na sala " + sl.get("nome") + " (" + sl.get("ocupados") + "/" + sl.get("lugares") + ")"; }
            if ("conectando".equals(f) || "iniciando".equals(f)) return "entrando numa partida";
        } catch (Exception ignored) { }
        return "no menu";
    }
    /** Sala em que estou (para os amigos entrarem), só se ela estiver num servidor que eles alcançam. */
    Map<String, Object> salaParaAmigos() {
        if ("jogo".equals(fase) && tableId != null && !souAnfitriao()) {   // em partida: os amigos podem assistir
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("servidor", servidorBruto()); r.put("mesa", tableId.toString()); r.put("emJogo", true); r.put("livres", 0);
            return r;
        }
        if (!"sala".equals(fase) || tableId == null || session == null || souAnfitriao()) return null;
        try {
            Optional<TableView> t = session.getTable(roomId, tableId);
            if (!t.isPresent()) return null;
            Map<String, Object> mj = mesaJson(t.get());
            List<?> lug = (List<?>) mj.get("lugares");
            int livres = ((Number) mj.get("livres")).intValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("servidor", servidorBruto()); r.put("mesa", tableId.toString()); r.put("nome", mj.get("nome"));
            r.put("lugares", lug.size()); r.put("ocupados", lug.size() - livres); r.put("livres", livres);
            return r;
        } catch (Exception e) { return null; }
    }
    JsonObject postAmigos(String rota, JsonObject corpo) throws IOException {
        String senha = senhaGrupo();
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + enderecoZera() + ":" + PORTA_AMIGOS + "/" + rota).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(4000); c.setReadTimeout(8000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!senha.isEmpty()) c.setRequestProperty("X-Senha", senha);
        try (OutputStream o = c.getOutputStream()) { o.write(GSON.toJson(corpo).getBytes(StandardCharsets.UTF_8)); }
        int cod = c.getResponseCode();
        InputStream in = cod >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        JsonObject r = txt.isEmpty() ? new JsonObject() : JsonParser.parseString(txt).getAsJsonObject();
        if (cod >= 400) throw new IllegalStateException(r.has("erro") ? r.get("erro").getAsString() : "Erro " + cod + " na lista de amigos.");
        return r;
    }
    JsonObject corpoAmigos() {
        JsonObject b = new JsonObject();
        b.addProperty("id", idMesa());
        String nome;
        synchronized (travaPerfil) { nome = perfil().has("nome") ? perfil().get("nome").getAsString() : nomeServidor(); }
        b.addProperty("nome", nome);
        String av = avatarMini(); if (av != null) b.addProperty("avatar", av);
        b.addProperty("status", statusParaAmigos());
        if (meuNome != null) b.addProperty("xmage", meuNome);
        Map<String, Object> sl = salaParaAmigos(); if (sl != null) b.add("sala", GSON.toJsonTree(sl));
        return b;
    }
    String perfilEnviado = null; long perfilEnviadoEm = 0;
    /** O que os amigos veem no perfil: foto, banner, frase, estatísticas, decks mais jogados e últimas partidas. */
    Map<String, Object> perfilPublico() {
        JsonObject p;
        synchronized (travaPerfil) { p = perfil().deepCopy(); }
        Map<String, Object> r = new LinkedHashMap<>();
        for (String k : new String[]{"nome", "cor", "banner", "frase"}) if (p.has(k) && !p.get(k).isJsonNull()) r.put(k, p.get(k).getAsString());
        if (p.has("avatar")) r.put("avatar", p.get("avatar").getAsString());
        List<Map<String, Object>> partidas = new ArrayList<>();
        Map<String, int[]> porDeck = new LinkedHashMap<>();
        int vit = 0, total = 0;
        if (p.has("partidas")) for (com.google.gson.JsonElement e : p.getAsJsonArray("partidas")) {
            JsonObject x = e.getAsJsonObject();
            boolean v = x.has("venceu") && x.get("venceu").getAsBoolean();
            String dk = x.has("deck") ? x.get("deck").getAsString() : "?";
            total++; if (v) vit++;
            int[] c = porDeck.computeIfAbsent(dk, k -> new int[2]); c[0]++; if (v) c[1]++;
            partidas.add(GSON.fromJson(x, Map.class));
        }
        r.put("partidas", total); r.put("vitorias", vit);
        List<Map<String, Object>> decks = new ArrayList<>();
        porDeck.entrySet().stream().sorted((a, b2) -> b2.getValue()[0] != a.getValue()[0] ? b2.getValue()[0] - a.getValue()[0] : b2.getValue()[1] - a.getValue()[1]).limit(6).forEach(en -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nome", en.getKey()); d.put("jogos", en.getValue()[0]); d.put("vitorias", en.getValue()[1]);
            try {   // comandante do deck (para a arte)
                Path arq = DECKS.resolve(en.getKey() + ".dck");
                if (!Files.exists(arq)) arq = DECKS.resolve(en.getKey() + ".txt");
                if (Files.exists(arq)) {
                    List<Map<String, Object>> cmds = new ArrayList<>();
                    for (Object[] x : lerCartas(arq)) if ((Boolean) x[4]) { Map<String, Object> c = new LinkedHashMap<>(); c.put("nome", x[1]); c.put("set", x[2]); c.put("num", x[3]); cmds.add(c); }
                    d.put("cmds", cmds);
                }
            } catch (Exception ignored) { }
            decks.add(d);
        });
        r.put("decks", decks);
        List<Map<String, Object>> todos = new ArrayList<>();
        try {
            for (String a : listarDecks()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("nome", nomeDoArquivo(a));
                List<Map<String, Object>> cs = new ArrayList<>(), cmds = new ArrayList<>(); int qtdCartas = 0;
                for (Object[] x : lerCartas(DECKS.resolve(a))) {
                    Map<String, Object> c = new LinkedHashMap<>(); c.put("qtd", x[0]); c.put("nome", x[1]); c.put("set", x[2]); c.put("num", x[3]);
                    if ((Boolean) x[4]) { c.put("comandante", true); cmds.add(c); }
                    cs.add(c); qtdCartas += (Integer) x[0];
                }
                int[] jv = porDeck.get(nomeDoArquivo(a));
                d.put("cmds", cmds); d.put("cartas", qtdCartas); d.put("jogos", jv == null ? 0 : jv[0]); d.put("vitorias", jv == null ? 0 : jv[1]);
                d.put("lista", cs);
                todos.add(d);
            }
        } catch (Exception ignored) { }
        r.put("todosDecks", todos);
        Collections.reverse(partidas);
        r.put("ultimas", partidas.size() > 10 ? partidas.subList(0, 10) : partidas);
        return r;
    }
    void pingAmigos() {
        synchronized (travaAmigos) {
            JsonObject corpo = corpoAmigos();
            String pf = null;
            try {
                pf = GSON.toJson(perfilPublico());
                if (!pf.equals(perfilEnviado) || System.currentTimeMillis() - perfilEnviadoEm > 300_000) corpo.add("perfil", JsonParser.parseString(pf));
                else pf = null;
            } catch (Exception ignored) { pf = null; }
            try {
                amigosFoto = GSON.fromJson(postAmigos("ping", corpo), Map.class); amigosErro = null;
                if (pf != null) { perfilEnviado = pf; perfilEnviadoEm = System.currentTimeMillis(); }
            }
            catch (Exception e) { amigosErro = (e instanceof IOException) ? "Sem conexão com o servidor do grupo. O Tailscale está ligado?" : (e.getMessage() == null ? "Sem conexão com a lista de amigos." : e.getMessage()); }
        }
    }
    void lacoAmigos() {
        while (true) {
            try { pingAmigos(); } catch (Throwable ignored) { }
            try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
        }
    }
    void rotaAmigos(HttpExchange ex) throws IOException {
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                JsonObject b = corpo(ex);
                String acao = b.get("acao").getAsString();
                if (acao.equals("perfil")) {
                    JsonObject c = new JsonObject();
                    c.addProperty("id", idMesa()); c.add("codigo", b.get("codigo"));
                    json(ex, 200, GSON.fromJson(postAmigos("perfil", c), Map.class));
                    return;
                }
                if (acao.equals("senha")) {
                    synchronized (this) {
                        config().addProperty("senhaGrupo", b.get("senha").getAsString().trim());
                        Files.write(MESA.resolve("config.json"), GSON.toJson(config()).getBytes(StandardCharsets.UTF_8));
                    }
                    pingAmigos();
                } else {
                    JsonObject c = corpoAmigos();   // id + o que faço agora
                    c.addProperty("acao", acao);
                    for (String k : new String[]{"codigo", "convite"}) if (b.has(k)) c.add(k, b.get(k));
                    if (acao.equals("convidar")) {
                        Map<String, Object> sl = salaParaAmigos();
                        if (sl == null) throw new IllegalStateException("Entre numa sala do servidor do grupo antes de convidar.");
                        c.add("sala", GSON.toJsonTree(sl));
                    }
                    synchronized (travaAmigos) { amigosFoto = GSON.fromJson(postAmigos("acao", c), Map.class); amigosErro = null; }
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("configurado", true);
            r.put("erro", amigosErro);
            r.put("foto", amigosFoto);
            r.put("possoConvidar", salaParaAmigos() != null);
            r.put("minhaMesa", "sala".equals(fase) && tableId != null ? tableId.toString() : null);
            json(ex, 200, r);
        } catch (Exception e) {
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(e.getMessage())));
        }
    }

    // =====================================================================
    // Reconectar: a Mesa anota a partida em andamento (partida-atual.json). Se a conexão cair, ou se a Mesa
    // for reaberta, ela entra de novo no servidor com o MESMO nome: o XMage devolve o lugar (reenvia START_GAME)
    // enquanto o jogador não expirar (uns 3 minutos).
    volatile Map<String, Object> reconexao = null;   // {segundos, tentativa} enquanto tenta voltar
    volatile boolean reconectando = false;
    volatile int fotos = 0;   // quantas vezes o servidor mandou a mesa inteira (GAME_INIT)
    volatile String usuarioForcado = null;
    Path arqPartida() { return MESA.resolve("partida-atual.json"); }
    void salvarPartida() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("servidor", servidorBruto());
            o.addProperty("usuario", meuNome);
            o.addProperty("tableId", tableId == null ? "" : tableId.toString());
            o.addProperty("gameId", gameId == null ? "" : gameId.toString());
            o.addProperty("quando", System.currentTimeMillis());
            Files.write(arqPartida(), GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    void apagarPartida() { try { Files.deleteIfExists(arqPartida()); } catch (Exception ignored) { } }
    JsonObject lerPartida() {
        try {
            if (!Files.exists(arqPartida())) return null;
            JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(arqPartida()), StandardCharsets.UTF_8)).getAsJsonObject();
            if (System.currentTimeMillis() - o.get("quando").getAsLong() > 6 * 3600_000L) { apagarPartida(); return null; }   // velha demais
            return o;
        } catch (Exception e) { return null; }
    }
    Map<String, Object> partidaSalvaInfo() {
        JsonObject o = lerPartida();
        if (o == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("servidor", o.get("servidor").getAsString());
        r.put("minutos", (System.currentTimeMillis() - o.get("quando").getAsLong()) / 60000);
        return r;
    }
    /** automatico=true: a conexão caiu com a Mesa aberta (tenta por ~3 min); false: a pessoa clicou em "Voltar". */
    final Object travaReconexao = new Object(), travaPrefs = new Object();
    /** Não segura a trava geral (this): enquanto tenta voltar, o resto da Mesa (tela, configurações) continua respondendo. */
    void reconectar(boolean automatico) {
        synchronized (travaReconexao) { reconectarDeVerdade(automatico); }
    }
    void reconectarDeVerdade(boolean automatico) {
        JsonObject o = lerPartida();
        if (o == null || reconectando || (gameId != null && "jogo".equals(fase) && jogo != null && !automatico)) return;
        reconectando = true;
        try {
            UUID mesa = UUID.fromString(o.get("tableId").getAsString());
            long fim = System.currentTimeMillis() + (automatico ? 180_000 : 60_000);
            int tentativa = 0;
            while (System.currentTimeMillis() < fim) {
                tentativa++;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("segundos", Math.max(0, (fim - System.currentTimeMillis()) / 1000)); r.put("tentativa", tentativa); r.put("fim", fim);
                reconexao = r; mudou();
                try {
                    if (session != null) { try { session.connectStop(false, false); } catch (Exception ignored) { } session = null; }
                    synchronized (this) { config().addProperty("servidor", o.get("servidor").getAsString()); }
                    usuarioForcado = o.get("usuario").getAsString();
                    tableId = mesa; gameId = null; int fotosAntes = fotos;
                    synchronized (trava) { if (!"jogo".equals(fase)) fase = "iniciando"; }
                    status("Voltando para a partida...");
                    conectar();
                    // o servidor reenvia START_GAME se o lugar ainda existe
                    for (int i = 0; i < 24 && gameId == null; i++) Thread.sleep(500);
                    if (gameId != null) {
                        for (int i = 0; i < 20 && fotos == fotosAntes; i++) Thread.sleep(500);
                        reconexao = null; status("De volta à partida!"); aviso("Você voltou para a partida."); mudou(); return;
                    }
                } catch (Exception e) { /* servidor fora do ar ainda: tenta de novo */ }
                Thread.sleep(4000);
            }
            reconexao = null; tableId = null;
            apagarPartida();
            synchronized (trava) { fase = "lobby"; jogo = null; prompt = null; erro = "Não deu para voltar: a partida terminou ou o seu lugar expirou."; }
            mudou();
        } catch (Exception e) {
            reconexao = null;
        } finally {
            reconectando = false; usuarioForcado = null;
        }
    }

    // Baixa o instalador da versão nova (só do GitHub da Mesa), abre ele e fecha a Mesa
    void rotaAtualizar(HttpExchange ex) throws IOException {
        try {
            JsonObject b = corpo(ex);
            String url = b.get("url").getAsString();
            if (!url.startsWith("https://github.com/PedroSeraggi/Mesa/releases/download/")) throw new IllegalStateException("Endereço de atualização inválido.");
            String nome = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
            if (!nome.toLowerCase().endsWith(".exe")) throw new IllegalStateException("Arquivo de atualização inválido.");
            Path destino = Paths.get(System.getProperty("java.io.tmpdir"), nome);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true); c.setConnectTimeout(15000); c.setReadTimeout(120000);
            int cod = c.getResponseCode();
            if (cod >= 300 && cod < 400) { c = (HttpURLConnection) new URL(c.getHeaderField("Location")).openConnection(); cod = c.getResponseCode(); }
            if (cod != 200) throw new IllegalStateException("Não consegui baixar a atualização (HTTP " + cod + ").");
            try (InputStream in = c.getInputStream()) { Files.copy(in, destino, StandardCopyOption.REPLACE_EXISTING); }
            new ProcessBuilder(destino.toString()).start();
            json(ex, 200, Collections.singletonMap("ok", true));
            new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } desligar(); }).start();
        } catch (Throwable t) {
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
        }
    }

    // =====================================================================
    // Rogerinho: assistente de regras (Google Gemini). A chave fica só neste PC (config.json) e nunca volta para a tela.
    // o "lite" responde em ~1 s; o flash completo chega a levar 50 s no plano grátis, então fica de reserva
    static final String[] MODELOS_IA = {"gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-3.5-flash"};
    static final String INSTRUCAO_IA =
        "Você é o Oráculo, um sábio juiz de Magic: The Gathering e parceiro de mesa de um grupo de amigos que joga Commander (EDH) multiplayer pela Mesa, uma interface em cima do XMage. "
      + "Responda SEMPRE em português do Brasil, de forma curta e direta (de 2 a 6 frases; use tópicos só se ajudar), com tom amigável, levemente solene como um sábio de taverna, mas sem enrolar. "
      + "Baseie-se nas Regras Abrangentes de Magic e nas regras oficiais de Commander (40 de vida, identidade de cor, singleton, dano de comandante 21, imposto de comandante, zona de comando). "
      + "Quando a pergunta envolver cartas, use o TEXTO OFICIAL que vem no contexto (não invente texto de carta). Se uma carta citada não estiver no contexto e você não tiver certeza do texto dela, diga isso. "
      + "Cite o número da regra quando for útil (ex.: 702.19 Atropelar). Se a situação for ambígua ou depender de algo que você não sabe, diga o que falta e qual seria a resposta em cada caso. "
      + "Se houver uma imagem, ela é uma print da carta ou da mesa: leia o que for possível dela. "
      + "Use os nomes das cartas como aparecem no contexto. Não invente o estado do jogo além do que foi informado.";

    void rotaIa(HttpExchange ex) throws IOException {
        String acao = ex.getRequestURI().getPath().replaceFirst("^/api/ia/?", "");
        try {
            JsonObject b = "POST".equals(ex.getRequestMethod()) ? corpo(ex) : new JsonObject();
            if ("chave".equals(acao)) {                    // salvar ou apagar a chave
                synchronized (this) {
                    String k = b.has("chave") && !b.get("chave").isJsonNull() ? b.get("chave").getAsString().trim() : "";
                    if (k.isEmpty()) config().remove("geminiKey"); else config().addProperty("geminiKey", k);
                    Files.write(MESA.resolve("config.json"), GSON.toJson(config()).getBytes(StandardCharsets.UTF_8));
                }
            }
            if ("perguntar".equals(acao)) {
                json(ex, 200, perguntarIa(b));
                return;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            String k = chaveIa();
            r.put("temChave", k != null);
            boolean doConfig; synchronized (this) { doConfig = config().has("geminiKey"); }
            r.put("origem", k == null ? null : doConfig ? "config" : "env");
            r.put("final", k == null ? null : k.substring(Math.max(0, k.length() - 4)));   // só os 4 últimos, para reconhecer
            json(ex, 200, r);
        } catch (Throwable t) {
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
        }
    }

    /** Chave do Gemini: a salva nas Configurações (se houver) ou a do arquivo .env que vai junto com a Mesa. */
    String chaveIa() {
        synchronized (this) { if (config().has("geminiKey")) return config().get("geminiKey").getAsString(); }
        return chaveEnv();
    }
    static String chaveEnv() {
        try {
            Path f = MESA.resolve(".env");
            if (!Files.exists(f)) return null;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                l = l.trim();
                if (l.startsWith("GEMINI_API_KEY=")) {
                    String v = l.substring("GEMINI_API_KEY=".length()).trim().replaceAll("^[\"']|[\"']$", "");
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** b = {pergunta, contexto, historico:[{quem:'eu'|'ia', texto}], imagem:'data:image/...;base64,...'} */
    Map<String, Object> perguntarIa(JsonObject b) throws Exception {
        String chave = chaveIa();
        if (chave == null) throw new IllegalStateException("O Oráculo ainda não tem chave. Cole a chave do Gemini em Configurações → Oráculo.");
        com.google.gson.JsonArray contents = new com.google.gson.JsonArray();
        if (b.has("historico")) for (com.google.gson.JsonElement e : b.getAsJsonArray("historico")) {
            JsonObject h = e.getAsJsonObject();
            JsonObject c = new JsonObject();
            c.addProperty("role", "ia".equals(h.get("quem").getAsString()) ? "model" : "user");
            com.google.gson.JsonArray ps = new com.google.gson.JsonArray();
            JsonObject t = new JsonObject(); t.addProperty("text", h.get("texto").getAsString()); ps.add(t);
            c.add("parts", ps); contents.add(c);
        }
        JsonObject atual = new JsonObject();
        atual.addProperty("role", "user");
        com.google.gson.JsonArray partes = new com.google.gson.JsonArray();
        String ctx = b.has("contexto") ? b.get("contexto").getAsString() : "";
        JsonObject txt = new JsonObject();
        txt.addProperty("text", (ctx.isEmpty() ? "" : "CONTEXTO (gerado pela Mesa):\n" + ctx + "\n\n") + "PERGUNTA: " + b.get("pergunta").getAsString());
        partes.add(txt);
        if (b.has("imagem") && !b.get("imagem").isJsonNull()) {
            String im = b.get("imagem").getAsString();
            int v = im.indexOf(',');
            if (im.startsWith("data:image/") && v > 0) {
                JsonObject inl = new JsonObject();
                inl.addProperty("mime_type", im.substring(5, im.indexOf(';')));
                inl.addProperty("data", im.substring(v + 1));
                JsonObject pi = new JsonObject(); pi.add("inline_data", inl);
                partes.add(pi);
            }
        }
        atual.add("parts", partes);
        contents.add(atual);
        JsonObject req = new JsonObject();
        JsonObject sys = new JsonObject();
        // o montador de deck manda as próprias instruções e pede resposta em JSON
        String instr = b.has("sistema") && !b.get("sistema").isJsonNull() ? b.get("sistema").getAsString() : INSTRUCAO_IA;
        boolean modoJson = b.has("json") && b.get("json").getAsBoolean();
        com.google.gson.JsonArray sp = new com.google.gson.JsonArray(); JsonObject st = new JsonObject(); st.addProperty("text", instr); sp.add(st);
        sys.add("parts", sp);
        req.add("system_instruction", sys);
        req.add("contents", contents);
        JsonObject gc = new JsonObject(); gc.addProperty("temperature", modoJson ? 0.6 : 0.3); gc.addProperty("maxOutputTokens", modoJson ? 16384 : 4096);
        if (modoJson) gc.addProperty("responseMimeType", "application/json");
        req.add("generationConfig", gc);
        byte[] corpo = GSON.toJson(req).getBytes(StandardCharsets.UTF_8);

        String ultimoErro = "";
        List<String> tentativas = new ArrayList<>();
        for (String m : MODELOS_IA) { tentativas.add(m); if (m.contains("lite")) tentativas.add(m); }   // os rápidos ganham uma 2ª chance
        String anterior = null;
        for (String modelo : tentativas) {
            if (modelo.equals(anterior)) Thread.sleep(1500);   // "muita demanda" costuma passar em segundos
            anterior = modelo;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelo + ":generateContent").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000); c.setReadTimeout(modoJson ? (modelo.contains("lite") ? 60000 : 75000) : modelo.contains("lite") ? 20000 : 40000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("x-goog-api-key", chave);
                try (OutputStream o = c.getOutputStream()) { o.write(corpo); }
                int cod = c.getResponseCode();
                InputStream in = cod >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = in == null ? "" : new String(lerTudo(in), StandardCharsets.UTF_8);
                if (cod == 429 || cod >= 500) { ultimoErro = modelo + (cod == 503 ? ": Google com muita demanda" : cod == 429 ? ": limite grátis por minuto" : ": HTTP " + cod); continue; }   // sem cota ou sobrecarregado: próximo modelo
                if (cod == 400 || cod == 403) {
                    String msg = resp.contains("API_KEY") || resp.contains("API key") ? "A chave do Gemini foi recusada. Confira se colou ela inteira." : "O Gemini recusou o pedido (HTTP " + cod + ").";
                    if (cod == 404 || resp.contains("not found")) { ultimoErro = modelo + ": não encontrado"; continue; }
                    throw new IllegalStateException(msg);
                }
                if (cod == 404) { ultimoErro = modelo + ": não encontrado"; anterior = null; continue; }
                JsonObject j = JsonParser.parseString(resp).getAsJsonObject();
                StringBuilder sb = new StringBuilder();
                if (j.has("candidates") && j.getAsJsonArray("candidates").size() > 0) {
                    JsonObject cand = j.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    if (cand.has("content") && cand.getAsJsonObject("content").has("parts"))
                        for (com.google.gson.JsonElement pe : cand.getAsJsonObject("content").getAsJsonArray("parts")) {
                            JsonObject po = pe.getAsJsonObject();
                            if (po.has("thought") && po.get("thought").getAsBoolean()) continue;   // raciocínio interno não vai para a tela
                            if (po.has("text")) sb.append(po.get("text").getAsString());
                        }
                }
                if (sb.length() == 0) { ultimoErro = modelo + ": resposta vazia"; continue; }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("resposta", sb.toString().trim());
                r.put("modelo", modelo);
                return r;
            } catch (java.net.SocketTimeoutException e) {
                ultimoErro = modelo + ": demorou demais";
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new IllegalStateException("O Oráculo não conseguiu responder agora (" + ultimoErro + "). O limite grátis do Gemini pode ter acabado por agora; tente de novo daqui a pouco.");
    }

    static byte[] lerTudo(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // =====================================================================
    // Descoberta de salas: o dono de uma sala anuncia por broadcast UDP na rede (Radmin repassa broadcast);
    // as outras Mesas escutam e mostram a sala sozinhas, sem ninguém digitar IP.
    static final int PORTA_DESCOBERTA = 17180;
    final String instancia = UUID.randomUUID().toString();
    final Map<String, Map<String, Object>> salasRede = new LinkedHashMap<>();

    final Map<String, Map<String, Object>> cartoesRede = new LinkedHashMap<>();
    volatile List<Map<String, Object>> cartoesPartida = new ArrayList<>();   // fotos/decks guardados quando a partida começa
    String avatarMiniDe = null, avatarMini = null;

    /** Foto do perfil reduzida a 72x72 (cabe num pacote da rede). */
    String avatarMini() {
        String a;
        synchronized (travaPerfil) { a = perfil().has("avatar") ? perfil().get("avatar").getAsString() : null; }
        if (a == null) return null;
        if (a.equals(avatarMiniDe)) return avatarMini;
        try {
            byte[] bytes = Base64.getDecoder().decode(a.substring(a.indexOf(',') + 1));
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            java.awt.image.BufferedImage p = new java.awt.image.BufferedImage(72, 72, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = p.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(im, 0, 0, 72, 72, null); g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(p, "jpg", out);
            avatarMini = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) { avatarMini = null; }
        avatarMiniDe = a;
        return avatarMini;
    }

    Map<String, Object> cartaoDeck(String jogador, String deckArq) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("jogador", jogador);
        c.put("deck", deckArq == null ? "" : nomeDoArquivo(deckArq));
        List<Map<String, Object>> cmd = new ArrayList<>();
        try {
            for (Object[] x : lerCartas(DECKS.resolve(deckArq))) if ((Boolean) x[4]) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nome", x[1]); m.put("set", x[2]); m.put("num", x[3]);
                cmd.add(m);
            }
        } catch (Exception ignored) { }
        c.put("cmds", cmd);
        try {
            Map<String, Object> artes = new LinkedHashMap<>();
            for (Object[] x : lerCartas(DECKS.resolve(deckArq))) {
                String st = (String) x[2], nm = (String) x[3];
                if (st.isEmpty() || nm.isEmpty()) continue;
                if (mage.cards.repository.CardRepository.instance.findCard(st, nm) == null) {
                    Map<String, Object> a = new LinkedHashMap<>(); a.put("set", st); a.put("num", nm);
                    artes.put((String) x[1], a);
                }
            }
            if (!artes.isEmpty()) c.put("artes", artes);
        } catch (Exception ignored) { }
        try {   // tokens com arte escolhida para este deck (os outros jogadores veem a mesma arte)
            Path f = MESA.resolve("tokens-decks.json");
            if (deckArq != null && Files.exists(f)) {
                JsonObject t = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
                if (t.has(deckArq)) c.put("tokens", GSON.fromJson(t.get(deckArq), Map.class));
            }
        } catch (Exception ignored) { }
        return c;
    }

    /** Cartões que eu sei (o meu e, se sou o dono, os dos bots). */
    List<Map<String, Object>> meusCartoes() {
        List<Map<String, Object>> l = new ArrayList<>();
        if (deckAtual == null) return l;
        Map<String, Object> eu = cartaoDeck(meuNome, deckAtual);
        synchronized (travaPerfil) {
            eu.put("nome", perfil().get("nome").getAsString());
            eu.put("cor", perfil().has("cor") ? perfil().get("cor").getAsString() : "#C0982F");
        }
        eu.put("avatar", avatarMini());
        l.add(eu);
        if (donoDaMesa && oponentesAtuais != null) for (int i = 0; i < oponentesAtuais.size(); i++) {
            Map<String, Object> b = cartaoDeck("Bot " + (i + 1) + " · " + nomeDeck(oponentesAtuais.get(i)), oponentesAtuais.get(i));
            b.put("bot", true);
            l.add(b);
        }
        return l;
    }

    List<Map<String, Object>> cartoesDaMesa() {
        List<Map<String, Object>> l = new ArrayList<>(meusCartoes());
        if (tableId == null) return l;
        String id = tableId.toString();
        long agora = System.currentTimeMillis();
        synchronized (cartoesRede) {
            cartoesRede.values().removeIf(x -> agora - (Long) x.get("_t") > 9000);
            for (Map<String, Object> x : cartoesRede.values()) if (id.equals(x.get("_mesa"))) {
                Map<String, Object> c = new LinkedHashMap<>(x); c.remove("_t"); c.remove("_mesa"); l.add(c);
            }
        }
        return l;
    }

    // ---- "mundo compartilhado": cursores, desenhos e pings dos outros jogadores da mesma partida ----
    final LinkedList<Map<String, Object>> eventosMundo = new LinkedList<>();
    final LinkedHashSet<String> midsVistos = new LinkedHashSet<>();
    long seqMundo = 0;
    DatagramSocket socketMundo;
    void rotaMundo(HttpExchange ex) throws IOException {
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                JsonObject b = corpo(ex);
                if (gameId != null) {
                    b.addProperty("mesamundo", 1);
                    b.addProperty("mid", UUID.randomUUID().toString().substring(0, 12));   // a mesma mensagem chega por várias redes: só vale a 1ª
                    b.addProperty("inst", instancia);
                    b.addProperty("jogo", gameId.toString());
                    synchronized (travaPerfil) {
                        b.addProperty("quem", perfil().get("nome").getAsString());
                        b.addProperty("cor", perfil().has("cor") ? perfil().get("cor").getAsString() : "#C0982F");
                    }
                    byte[] d = GSON.toJson(b).getBytes(StandardCharsets.UTF_8);
                    if (socketMundo == null) { socketMundo = new DatagramSocket(); socketMundo.setBroadcast(true); }
                    for (InetAddress a : destinosBroadcast()) {
                        try { socketMundo.send(new DatagramPacket(d, d.length, a, PORTA_DESCOBERTA)); } catch (Exception ignored) { }
                    }
                }
                json(ex, 200, Collections.singletonMap("ok", true));
                return;
            }
            long desde = 0;
            try { desde = Long.parseLong(query(ex.getRequestURI().getRawQuery()).getOrDefault("desde", "0")); } catch (Exception ignored) { }
            List<Map<String, Object>> l = new ArrayList<>();
            long seq;
            synchronized (eventosMundo) { seq = seqMundo; for (Map<String, Object> e : eventosMundo) if ((Long) e.get("seq") > desde) l.add(e); }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq", seq); r.put("eventos", l);
            json(ex, 200, r);
        } catch (Throwable t) {
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
        }
    }
    static Set<InetAddress> destinosBroadcast() throws Exception {
        Set<InetAddress> destinos = new LinkedHashSet<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) destinos.add(ia.getBroadcast());
        }
        destinos.add(InetAddress.getByName("255.255.255.255"));
        return destinos;
    }

    void iniciarDescoberta() {
        Thread escuta = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(PORTA_DESCOBERTA));
                byte[] buf = new byte[65535];
                while (true) {
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    ds.receive(pk);
                    try {
                        JsonObject o = GSON.fromJson(new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8), JsonObject.class);
                        if (o.has("inst") && instancia.equals(o.get("inst").getAsString())) continue;
                        if (o.has("mesamundo")) {
                            if (gameId == null || !gameId.toString().equals(o.get("jogo").getAsString())) continue;
                            String mid = o.has("mid") ? o.get("mid").getAsString() : null;
                            if (mid != null) synchronized (midsVistos) { if (!midsVistos.add(mid)) continue; if (midsVistos.size() > 800) { java.util.Iterator<String> it = midsVistos.iterator(); it.next(); it.remove(); } }
                            @SuppressWarnings("unchecked") Map<String, Object> e = GSON.fromJson(o, Map.class);
                            synchronized (eventosMundo) {
                                e.put("seq", ++seqMundo);
                                eventosMundo.add(e);
                                while (eventosMundo.size() > 400) eventosMundo.removeFirst();
                            }
                            continue;
                        }
                        if (o.has("mesacartoes")) {
                            String mesa = o.get("mesaId").getAsString();
                            for (com.google.gson.JsonElement e : o.getAsJsonArray("cartoes")) {
                                @SuppressWarnings("unchecked") Map<String, Object> c = GSON.fromJson(e, Map.class);
                                c.put("_t", System.currentTimeMillis()); c.put("_mesa", mesa);
                                synchronized (cartoesRede) { cartoesRede.put(mesa + "|" + c.get("jogador"), c); }
                            }
                            continue;
                        }
                        if (!o.has("mesaxmage")) continue;
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("ip", pk.getAddress().getHostAddress());   // o IP de onde veio é o que funciona para conectar
                        x.put("anfitriao", o.get("anfitriao").getAsString());
                        x.put("mesaId", o.get("mesaId").getAsString());
                        x.put("nome", o.get("nome").getAsString());
                        x.put("livres", o.get("livres").getAsInt());
                        x.put("total", o.get("total").getAsInt());
                        x.put("jogadores", GSON.fromJson(o.get("jogadores"), List.class));
                        x.put("_t", System.currentTimeMillis());
                        synchronized (salasRede) { salasRede.put(o.get("mesaId").getAsString(), x); }
                    } catch (Exception ignored) { }
                }
            } catch (Exception e) { System.err.println("Descoberta (escuta) desligada: " + e); }
        }, "descoberta-escuta");
        escuta.setDaemon(true);
        escuta.start();

        Thread anuncia = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (true) {
                    try {
                        Thread.sleep(2000);
                        if (tableId == null || session == null || !"sala".equals(fase)) continue;
                        Set<InetAddress> destinos = new LinkedHashSet<>();
                        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                            if (!ni.isUp() || ni.isLoopback()) continue;
                            for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) destinos.add(ia.getBroadcast());
                        }
                        destinos.add(InetAddress.getByName("255.255.255.255"));
                        JsonObject pc = new JsonObject();
                        pc.addProperty("mesacartoes", 1);
                        pc.addProperty("inst", instancia);
                        pc.addProperty("mesaId", tableId.toString());
                        pc.add("cartoes", GSON.toJsonTree(meusCartoes()));
                        byte[] dc = GSON.toJson(pc).getBytes(StandardCharsets.UTF_8);
                        for (InetAddress d : destinos) {
                            try { ds.send(new DatagramPacket(dc, dc.length, d, PORTA_DESCOBERTA)); } catch (Exception ignored) { }
                        }
                        if (!donoDaMesa || !souAnfitriao()) continue;
                        Optional<TableView> t = session.getTable(roomId, tableId);
                        if (!t.isPresent()) continue;
                        Map<String, Object> m = mesaJson(t.get());
                        JsonObject o = new JsonObject();
                        o.addProperty("mesaxmage", 1);
                        o.addProperty("inst", instancia);
                        o.addProperty("anfitriao", perfil().get("nome").getAsString());
                        o.addProperty("mesaId", tableId.toString());
                        o.addProperty("nome", String.valueOf(m.get("nome")));
                        o.addProperty("livres", (Integer) m.get("livres"));
                        @SuppressWarnings("unchecked") List<Map<String, Object>> lug = (List<Map<String, Object>>) m.get("lugares");
                        o.addProperty("total", lug.size());
                        com.google.gson.JsonArray js = new com.google.gson.JsonArray();
                        for (Map<String, Object> l : lug) if (l.get("nome") != null) js.add(String.valueOf(l.get("nome")));
                        o.add("jogadores", js);
                        byte[] dados = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
                        for (InetAddress d : destinos) {
                            try { ds.send(new DatagramPacket(dados, dados.length, d, PORTA_DESCOBERTA)); } catch (Exception ignored) { }
                        }
                    } catch (InterruptedException e) { return; } catch (Exception ignored) { }
                }
            } catch (Exception e) { System.err.println("Descoberta (anúncio) desligada: " + e); }
        }, "descoberta-anuncio");
        anuncia.setDaemon(true);
        anuncia.start();
    }

    void rotaMesa(HttpExchange ex) throws IOException {
        String acao = ex.getRequestURI().getPath().replaceFirst("^/api/mesa/?", "");
        try {
            JsonObject b = "POST".equals(ex.getRequestMethod()) ? corpo(ex) : new JsonObject();
            switch (acao) {
                case "criar": {
                    List<String> bots = new ArrayList<>();
                    if (b.has("bots")) b.getAsJsonArray("bots").forEach(e -> bots.add(e.getAsString()));
                    criarMesaAmigos(b.get("deck").getAsString(), b.get("lugares").getAsInt(), bots,
                            b.has("nivel") ? b.get("nivel").getAsInt() : 4, b.has("nome") ? b.get("nome").getAsString() : "");
                    break;
                }
                case "entrar":
                    entrarMesa(UUID.fromString(b.get("mesa").getAsString()), b.get("deck").getAsString());
                    break;
                case "comecar":
                    if (tableId == null || !session.startMatch(roomId, tableId)) throw new IllegalStateException("Não deu para começar: todos os lugares precisam estar ocupados.");
                    synchronized (trava) { fase = "iniciando"; }
                    status("Embaralhando...");
                    mudou();
                    break;
                case "sair":
                    sairDaSala();
                    break;
                case "assistir":
                    assistir(UUID.fromString(b.get("mesa").getAsString()));
                    break;
                case "revanche": {
                    Map<String, Object> u = ultimaCriacao;
                    if (u == null) throw new IllegalStateException("A revanche é com quem criou a sala.");
                    sairDoJogoAtual();
                    synchronized (trava) { fase = "lobby"; resultado = null; }
                    if ("bots".equals(u.get("tipo"))) {
                        @SuppressWarnings("unchecked") List<String> ops = (List<String>) u.get("oponentes");
                        new Thread(() -> { try { criarPartida((String) u.get("deck"), ops, ((Number) u.get("nivel")).intValue()); }
                            catch (Throwable t) { synchronized (trava) { fase = "lobby"; erro = String.valueOf(t.getMessage()); } mudou(); } }).start();
                    } else {
                        @SuppressWarnings("unchecked") List<String> bots = (List<String>) u.get("bots");
                        criarMesaAmigos((String) u.get("deck"), ((Number) u.get("lugares")).intValue(), bots, ((Number) u.get("nivel")).intValue(), (String) u.get("nome"));
                    }
                    break;
                }
                default:
                    break;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            if (tableId != null && session != null) {
                Optional<TableView> t = session.getTable(roomId, tableId);
                if (t.isPresent()) { r.put("mesa", mesaJson(t.get())); r.put("dono", donoDaMesa); r.put("cartoes", cartoesDaMesa()); r.put("eu", meuNome); }
                else if ("sala".equals(fase)) {   // o dono cancelou a mesa
                    tableId = null;
                    synchronized (trava) { fase = "lobby"; }
                    aviso("A mesa foi fechada pelo dono.");
                }
            }
            json(ex, 200, r);
        } catch (Throwable t) {
            t.printStackTrace();
            json(ex, 200, Collections.singletonMap("erro", String.valueOf(t.getMessage())));
        }
    }

    MatchOptions opcoesCommander(String nomeMesa, int humanos, int bots) {
        GameTypeView tipo = null;
        for (GameTypeView g : session.getGameTypes()) if (g.getName().equals("Commander Free For All")) tipo = g;
        if (tipo == null) throw new IllegalStateException("Tipo de jogo Commander não encontrado no servidor");
        String deckType = Arrays.asList(session.getDeckTypes()).contains("Variant Magic - Commander") ? "Variant Magic - Commander" : "Variant Magic - Freeform Commander";
        MatchOptions op = new MatchOptions(nomeMesa, tipo.getName(), true);
        for (int i = 0; i < humanos; i++) op.getPlayerTypes().add(PlayerType.HUMAN);
        for (int i = 0; i < bots; i++) op.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        op.setDeckType(deckType);
        op.setLimited(false);
        op.setAttackOption(MultiplayerAttackOption.MULTIPLE);
        op.setRange(RangeOfInfluence.ALL);
        op.setWinsNeeded(1);
        op.setMatchTimeLimit(MatchTimeLimit.NONE);
        op.setSkillLevel(SkillLevel.CASUAL);
        op.setRollbackTurnsAllowed(true);
        op.setSpectatorsAllowed(false);
        op.setQuitRatio(100);
        op.setMinimumRating(0);
        return op;
    }

    // O servidor explica por que recusou um deck numa mensagem separada (SHOW_USERMESSAGE), que pode chegar logo depois.
    volatile String ultimaMsgServidor = null; volatile long ultimaMsgQuando = 0;
    String motivoRecusa(long desde) {
        for (int i = 0; i < 25 && ultimaMsgQuando < desde; i++) { try { Thread.sleep(100); } catch (InterruptedException e) { break; } }
        if (ultimaMsgQuando < desde || ultimaMsgServidor == null) return "";
        String t = ultimaMsgServidor.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
        return "\n\nO servidor disse:\n" + t;
    }

    void criarMesaAmigos(String meuDeck, int lugares, List<String> decksBots, int skill, String nomeMesa) throws Exception {
        sairDoJogoAtual();
        sairDaSala();
        conectar();
        int bots = decksBots.size();
        int humanos = Math.max(2, Math.min(6, lugares)) - bots;
        if (humanos < 1) throw new IllegalStateException("Precisa sobrar pelo menos o seu lugar.");
        DeckCardLists meu = carregarDeck(meuDeck);
        String nome = nomeMesa.trim().isEmpty() ? "Mesa de " + perfil().get("nome").getAsString() : nomeMesa.trim();
        TableView t = session.createTable(roomId, opcoesCommander(nome, humanos, bots));
        if (t == null) throw new IllegalStateException("O servidor recusou criar a mesa");
        tableId = t.getTableId();
        donoDaMesa = true;
        long antesJoin = System.currentTimeMillis();
        if (!session.joinTable(roomId, tableId, meuNome, PlayerType.HUMAN, 1, meu, "")) throw new IllegalStateException("Seu deck foi recusado." + motivoRecusa(antesJoin));
        for (int i = 0; i < bots; i++) {
            long antesBot = System.currentTimeMillis();
            if (!session.joinTable(roomId, tableId, "Bot " + (i + 1) + " · " + nomeDeck(decksBots.get(i)), PlayerType.COMPUTER_MAD, skill, carregarDeck(decksBots.get(i)), "")) {
                throw new IllegalStateException("Deck do bot recusado: " + nomeDeck(decksBots.get(i)) + motivoRecusa(antesBot));
            }
        }
        deckAtual = meuDeck;
        oponentesAtuais = new ArrayList<>(decksBots);
        ultimaCriacao = new LinkedHashMap<>();
        ultimaCriacao.put("tipo", "sala"); ultimaCriacao.put("deck", meuDeck); ultimaCriacao.put("lugares", lugares);
        ultimaCriacao.put("bots", new ArrayList<>(decksBots)); ultimaCriacao.put("nivel", skill); ultimaCriacao.put("nome", nomeMesa);
        synchronized (trava) { fase = "sala"; erro = null; jogo = null; prompt = null; resultado = null; log.clear(); avisos.clear(); }
        status("Esperando os amigos entrarem...");
        mudou();
    }

    void entrarMesa(UUID mesa, String meuDeck) throws Exception {
        sairDoJogoAtual();
        sairDaSala();
        conectar();
        DeckCardLists meu = carregarDeck(meuDeck);
        tableId = mesa;
        donoDaMesa = false;
        long antesJoin = System.currentTimeMillis();
        if (!session.joinTable(roomId, mesa, meuNome, PlayerType.HUMAN, 1, meu, "")) {
            tableId = null;
            String motivo = motivoRecusa(antesJoin);
            throw new IllegalStateException((motivo.isEmpty() ? "Não consegui entrar: a mesa pode estar cheia ou o deck foi recusado." : "Seu deck foi recusado ao entrar na mesa.") + motivo);
        }
        deckAtual = meuDeck;
        oponentesAtuais = new ArrayList<>();
        ultimaCriacao = null;   // entrou na sala de outra pessoa: a revanche é com o dono
        synchronized (trava) { fase = "sala"; erro = null; jogo = null; prompt = null; resultado = null; log.clear(); avisos.clear(); }
        status("Você entrou na mesa. Esperando o dono começar...");
        mudou();
    }

    void sairDaSala() {
        if (!"sala".equals(fase) || tableId == null || session == null) return;
        UUID t = tableId;
        try {
            if (donoDaMesa) session.removeTable(roomId, t); else session.leaveTable(roomId, t);
        } catch (Exception ignored) { }
        tableId = null;
        donoDaMesa = false;
        synchronized (trava) { fase = "lobby"; }
        mudou();
    }

    // =====================================================================
    // Nova partida
    void novo(HttpExchange ex) throws IOException {
        JsonObject b = corpo(ex);
        String meu = b.get("deck").getAsString();
        List<String> ops = new ArrayList<>();
        b.getAsJsonArray("oponentes").forEach(e -> ops.add(e.getAsString()));
        int skill = b.has("nivel") ? b.get("nivel").getAsInt() : 4;
        json(ex, 200, Collections.singletonMap("ok", true));
        new Thread(() -> {
            try {
                criarPartida(meu, ops, skill);
            } catch (Throwable t) {
                t.printStackTrace();
                synchronized (trava) {
                    fase = "lobby";
                    erro = String.valueOf(t.getMessage());
                }
                mudou();
            }
        }).start();
    }

    void criarPartida(String meuDeck, List<String> oponentes, int skill) throws Exception {
        synchronized (trava) {
            fase = "conectando";
            erro = null;
            jogo = null;
            prompt = null;
            resultado = null;
            log.clear();
            avisos.clear();
        }
        mudou();
        sairDoJogoAtual();
        // mudou o nome no perfil: reconecta com o nome novo
        if (session != null && session.isConnected() && !meuNome.startsWith(nomeServidor())) {
            try { session.connectStop(false, false); } catch (Exception ignored) { }
            session = null;
        }
        conectar();
        deckAtual = meuDeck;
        oponentesAtuais = new ArrayList<>(oponentes);
        ultimaCriacao = new LinkedHashMap<>();
        ultimaCriacao.put("tipo", "bots"); ultimaCriacao.put("deck", meuDeck); ultimaCriacao.put("oponentes", new ArrayList<>(oponentes)); ultimaCriacao.put("nivel", skill);
        synchronized (trava) { fase = "iniciando"; }
        status("Carregando os decks...");

        GameTypeView tipo = null;
        for (GameTypeView g : session.getGameTypes()) {
            if (g.getName().equals("Commander Free For All")) tipo = g;
        }
        if (tipo == null) throw new IllegalStateException("Tipo de jogo Commander não encontrado no servidor");
        String deckType = "Variant Magic - Commander";
        if (!Arrays.asList(session.getDeckTypes()).contains(deckType)) {
            deckType = "Variant Magic - Freeform Commander";
        }

        DeckCardLists meu = carregarDeck(meuDeck);
        List<DeckCardLists> decks = new ArrayList<>();
        for (String o : oponentes) decks.add(carregarDeck(o));

        MatchOptions op = new MatchOptions("Mesa", tipo.getName(), true);
        op.getPlayerTypes().add(PlayerType.HUMAN);
        for (int i = 0; i < decks.size(); i++) op.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        op.setDeckType(deckType);
        op.setLimited(false);
        op.setAttackOption(MultiplayerAttackOption.MULTIPLE);
        op.setRange(RangeOfInfluence.ALL);
        op.setWinsNeeded(1);
        op.setMatchTimeLimit(MatchTimeLimit.NONE);
        op.setSkillLevel(SkillLevel.CASUAL);
        op.setRollbackTurnsAllowed(true);
        op.setSpectatorsAllowed(false);
        op.setQuitRatio(100);       // mesa local: aceita qualquer taxa de desistência (sair no meio conta como desistência)
        op.setMinimumRating(0);

        status("Criando a mesa...");
        TableView t = session.createTable(roomId, op);
        if (t == null) throw new IllegalStateException("O servidor recusou criar a mesa");
        tableId = t.getTableId();
        if (!session.joinTable(roomId, tableId, meuNome, PlayerType.HUMAN, 1, meu, "")) {
            throw new IllegalStateException("Seu deck foi recusado pelo servidor (" + meuDeck + "). Confira se tem 100 cartas.");
        }
        for (int i = 0; i < decks.size(); i++) {
            String nome = nomeDeck(oponentes.get(i));
            if (!session.joinTable(roomId, tableId, "IA " + (i + 1) + " · " + nome, PlayerType.COMPUTER_MAD, skill, decks.get(i), "")) {
                throw new IllegalStateException("Deck da IA recusado: " + oponentes.get(i));
            }
        }
        status("Embaralhando...");
        if (!session.startMatch(roomId, tableId)) throw new IllegalStateException("Não consegui iniciar a partida");
    }

    static String nomeDeck(String arquivo) {
        String n = arquivo.replace(".dck", "");
        int a = n.indexOf('('), b = n.indexOf(')');
        if (a >= 0 && b > a) return n.substring(a + 1, b);
        return n.length() > 18 ? n.substring(0, 18) : n;
    }

    DeckCardLists carregarDeck(String arquivo) {
        Path p = arquivoDeck(arquivo);
        // o Java 8 do XMage não abre nomes com acento: copia para um nome simples
        Path tmp;
        try {
            tmp = CACHE.resolve("deck_" + Math.abs(arquivo.hashCode()) + (arquivo.endsWith(".txt") ? ".txt" : ".dck"));
            // decks salvos pelo editor do XMage podem ter bytes inválidos na linha NAME
            String txt = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            StringBuilder limpo = new StringBuilder();
            for (String linha : txt.split("\r?\n")) {
                if (linha.startsWith("NAME:")) continue;
                limpo.append(linha).append('\n');
            }
            Files.write(tmp, limpo.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui ler o deck " + arquivo + ": " + e.getMessage());
        }
        StringBuilder erros = new StringBuilder();
        DeckCardLists d = DeckImporter.importDeckFromFile(tmp.toString(), erros, false);
        if (erros.length() > 0) System.out.println("Avisos do deck " + arquivo + ": " + erros);
        return d;
    }

    /** Jogos que você abandonou: as IAs podem continuar jogando, mas a tela ignora. */
    final Set<UUID> abandonados = ConcurrentHashMap.newKeySet();

    void sairDoJogoAtual() {
        if (assistindo) {
            UUID g = gameId;
            assistindo = false; gameId = null; tableId = null; gameChatId = null;
            try { if (g != null && session != null) session.stopWatching(g); } catch (Exception ignored) { }
            return;
        }
        apagarPartida();
        UUID g = gameId, t = tableId;
        if (g != null) abandonados.add(g);
        if (g != null && "jogo".equals(fase)) {   // saiu no meio: conta como derrota (no XMage, sair é desistir)
            int turnos = 0;
            try { turnos = ((Number) jogo.get("turno")).intValue(); } catch (Exception ignored) { }
            registrarPartida(false, turnos);
        }
        gameId = null;
        tableId = null;
        gameChatId = null;
        if (session == null) return;
        try { if (g != null) session.sendPlayerAction(PlayerAction.CONCEDE, g, null); } catch (Exception ignored) { }
        try { if (g != null) session.quitMatch(g); } catch (Exception ignored) { }
        try { if (t != null) session.removeTable(roomId, t); } catch (Exception ignored) { }
    }

    /** Servidor do XMage que esta ponte ligou (só esse ela desliga ao sair). */
    volatile Process servidorLigadoPorMim = null;

    /** Botão "Sair" do menu: desiste da partida, sai da sala, desliga o servidor (se fomos nós que ligamos) e fecha a ponte. */
    void desligar() {
        // trava de segurança: aconteça o que acontecer, a ponte fecha em até 20 segundos
        Thread guarda = new Thread(() -> {
            try { Thread.sleep(20000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        });
        guarda.setDaemon(true);
        guarda.start();
        System.out.println("Sair: fechando a Mesa...");
        // desiste da partida em segundo plano (não pode travar o resto)
        Thread t = new Thread(() -> { try { abandonar(); } catch (Throwable ignored) { } });
        t.setDaemon(true);
        t.start();
        try { t.join(3000); } catch (InterruptedException ignored) { }
        try {
            if (servidorLigadoPorMim != null) servidorLigadoPorMim.destroy();
            if (souAnfitriao()) {
                // garante que nenhum servidor do XMage fique esquecido rodando neste PC
                System.out.println("Sair: desligando o servidor do XMage...");
                String script = "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and $_.CommandLine -like '*mage-server*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }";
                // comando codificado (UTF-16LE em base64): evita problemas de aspas entre Java e PowerShell
                String cod = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
                new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", cod)
                        .redirectErrorStream(true).redirectOutput(MESA.resolve("sair.log").toFile()).start().waitFor(15, TimeUnit.SECONDS);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        System.out.println("Sair: pronto.");
        Runtime.getRuntime().halt(0);   // fecha na hora, sem esperar a conexão com o servidor
    }

    void abandonar() {
        sairDaSala();
        sairDoJogoAtual();
        synchronized (trava) {
            fase = "lobby";
            jogo = null;
            prompt = null;
        }
        mudou();
    }

    // =====================================================================
    // Respostas do jogador
    void acao(HttpExchange ex) throws IOException {
        JsonObject b = corpo(ex);
        String t = b.get("t").getAsString();
        boolean ok = false;
        try {
            if ("pedido".equals(t)) {   // resposta a um pedido de outro jogador
                UserRequestMessage r; synchronized (trava) { r = pedidoMsg; pedido = null; pedidoMsg = null; }
                if (r != null && session != null && b.has("v") && !b.get("v").isJsonNull()) {
                    UUID g = r.getGameId() != null ? r.getGameId() : gameId;
                    ok = session.sendPlayerAction(PlayerAction.valueOf(b.get("v").getAsString()), g, r.getRelatedUserId());
                } else ok = true;
                mudou();
            } else
            if (gameId != null && session != null) {
                switch (t) {
                    case "uuid":
                        ok = session.sendPlayerUUID(gameId, b.get("v").isJsonNull() ? null : UUID.fromString(b.get("v").getAsString()));
                        break;
                    case "bool":
                        ok = session.sendPlayerBoolean(gameId, b.get("v").getAsBoolean());
                        break;
                    case "int":
                        ok = session.sendPlayerInteger(gameId, b.get("v").getAsInt());
                        break;
                    case "str":
                        ok = session.sendPlayerString(gameId, b.get("v").isJsonNull() ? null : b.get("v").getAsString());
                        break;
                    case "mana":
                        ok = session.sendPlayerManaType(gameId, playerId, ManaType.valueOf(b.get("v").getAsString()));
                        break;
                    case "acao": {
                        PlayerAction pa = PlayerAction.valueOf(b.get("v").getAsString());
                        Object dado = null;
                        if (b.has("d") && !b.get("d").isJsonNull()) dado = b.get("d").getAsInt();
                        ok = session.sendPlayerAction(pa, gameId, dado);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // respostas de diálogo encerram a pergunta atual; ações de passar não
        if (ok && !t.equals("acao") && !t.equals("mana")) {
            synchronized (trava) {
                if (prompt != null) prompt.put("respondido", true);
            }
            mudou();
        }
        json(ex, 200, Collections.singletonMap("ok", ok));
    }

    // =====================================================================
    // MageClient
    @Override public MageVersion getVersion() { return version; }
    @Override public void connected(String message) { }
    @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        status("Desconectado do servidor");
        if (!reconectando && gameId != null && "jogo".equals(fase) && lerPartida() != null) new Thread(() -> reconectar(true)).start();
    }
    @Override public void showMessage(String message) { aviso(message); }
    @Override public void showError(String message) { aviso("Erro: " + message); }
    @Override public void onNewConnection() { }

    @Override
    public void onCallback(ClientCallback cb) {
        try {
            cb.decompressData();
            tratar(cb);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    void tratar(ClientCallback cb) {
        Object d = cb.getData();
        if (cb.getObjectId() != null && abandonados.contains(cb.getObjectId())) return;   // jogo que você já largou
        ClientCallbackMethod met = cb.getMethod();
        if (met == ClientCallbackMethod.START_GAME) {
            TableClientMessage m = (TableClientMessage) d;
            // só aceita o início da partida da mesa que esta ponte criou
            if (tableId == null && !abandonados.contains(m.getGameId())) {   // Mesa reaberta: o servidor devolveu a partida anotada
                JsonObject sv = lerPartida();
                if (sv != null && sv.get("tableId").getAsString().equals(String.valueOf(m.getCurrentTableId()))) tableId = m.getCurrentTableId();
            }
            if (abandonados.contains(m.getGameId()) || tableId == null || !tableId.equals(m.getCurrentTableId())) return;
            if (reconectando && gameId != null) return;   // já voltou
        } else if (met == ClientCallbackMethod.CHATMESSAGE) {
            if (gameChatId == null || !gameChatId.equals(cb.getObjectId())) return;   // chat de outra partida
        } else if (met.name().startsWith("GAME_") || met == ClientCallbackMethod.END_GAME_INFO) {
            // atualizações de jogo: só da partida atual (partidas antigas podem continuar rodando no servidor)
            UUID o = cb.getObjectId();
            if (gameId == null || (o != null && !o.equals(gameId) && !o.equals(tableId))) return;
        }
        switch (met) {
            case WATCHGAME: {
                TableClientMessage m = (TableClientMessage) d;
                if (!assistindo || tableId == null || !tableId.equals(m.getCurrentTableId())) break;
                gameId = m.getGameId() != null ? m.getGameId() : cb.getObjectId(); playerId = null;   // no WATCHGAME o jogo vem como id da mensagem
                stats = new Estatisticas(); stats.gameId = String.valueOf(gameId);
                session.watchGame(gameId);
                try { session.getGameChatId(gameId).ifPresent(id -> { gameChatId = id; session.joinChat(id); }); } catch (Exception ignored) { }
                synchronized (trava) { fase = "jogo"; erro = null; }
                status("Assistindo");
                mudou();
                break;
            }
            case START_GAME: {
                TableClientMessage m = (TableClientMessage) d;
                gameId = m.getGameId();
                playerId = m.getPlayerId();
                if (stats == null || !String.valueOf(gameId).equals(stats.gameId)) { stats = new Estatisticas(); stats.gameId = String.valueOf(gameId); }
                assistindo = false;
                session.joinGame(gameId);
                final int fotosAntes = fotos; final UUID gId = gameId;
                new Thread(() -> {   // às vezes (reconexão) o estado da partida se perde logo depois do login: pede de novo até chegar
                    try { for (int i = 0; i < 10 && fotos == fotosAntes && gId.equals(gameId); i++) { Thread.sleep(1500); if (fotos == fotosAntes) session.joinGame(gId); } } catch (Exception ignored) { }
                }).start();
                try { cartoesPartida = cartoesDaMesa(); } catch (Exception ignored) { cartoesPartida = new ArrayList<>(); }
                synchronized (trava) { fase = "jogo"; erro = null; }
                salvarPartida();
                status("Partida começou");
                break;
            }
            case GAME_INIT: {
                fotos++;
                try {
                    session.getGameChatId(gameId).ifPresent(id -> { gameChatId = id; session.joinChat(id); });
                } catch (Exception ignored) { }
                atualizar((GameView) d);
                break;
            }
            case GAME_UPDATE:
                atualizar((GameView) d);
                break;
            case GAME_UPDATE_AND_INFORM: {
                GameClientMessage m = (GameClientMessage) d;
                atualizar(m.getGameView());
                break;
            }
            case CHATMESSAGE: {
                ChatMessage m = (ChatMessage) d;
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("turno", m.getTurnInfo());
                l.put("quem", m.getUsername());
                l.put("msg", m.getMessage());
                l.put("tipo", String.valueOf(m.getMessageType()));
                synchronized (trava) {
                    log.add(l);
                    while (log.size() > 250) log.removeFirst();
                }
                Estatisticas st = stats;
                if (st != null) try { st.linha(String.valueOf(m.getMessage())); } catch (Exception ignored) { }
                mudou();
                break;
            }
            case USER_REQUEST_DIALOG: {
                UserRequestMessage r = (UserRequestMessage) d;
                Map<String, Object> pd = new LinkedHashMap<>();
                pd.put("titulo", r.getTitle());
                pd.put("texto", r.getMessage());
                List<Map<String, Object>> bts = new ArrayList<>();
                String[][] b3 = {{r.getButton1Text(), r.getButton1Action()==null?null:r.getButton1Action().name()},
                                 {r.getButton2Text(), r.getButton2Action()==null?null:r.getButton2Action().name()},
                                 {r.getButton3Text(), r.getButton3Action()==null?null:r.getButton3Action().name()}};
                for (String[] bt : b3) if (bt[0] != null && !bt[0].isEmpty()) {
                    Map<String, Object> x = new LinkedHashMap<>(); x.put("texto", bt[0]); x.put("acao", bt[1]); bts.add(x); }
                pd.put("botoes", bts);
                synchronized (trava) { pedido = pd; pedidoMsg = r; }
                mudou();
                break;
            }
            case SHOW_USERMESSAGE: {
                @SuppressWarnings("unchecked") List<String> m = (List<String>) d;
                ultimaMsgServidor = String.join("\n", m); ultimaMsgQuando = System.currentTimeMillis();
                aviso(String.join(": ", m));
                break;
            }
            case GAME_INFORM_PERSONAL:
                aviso(((GameClientMessage) d).getMessage());
                break;
            case GAME_ERROR:
                aviso("Erro do jogo: " + d);
                break;
            case SERVER_MESSAGE:
                break;
            case GAME_ASK: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("ask", m.getMessage(), m.getGameView(), m.getOptions(), false);
                p.put("sim", texto(m.getOptions(), "UI.left.btn.text", "Sim"));
                p.put("nao", texto(m.getOptions(), "UI.right.btn.text", "Não"));
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_TARGET: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("target", m.getMessage(), m.getGameView(), m.getOptions(), false);
                p.put("alvos", ids(m.getTargets()));
                p.put("cartas", cartas(m.getCardsView1()));
                p.put("obrigatorio", m.isFlag());
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_SELECT: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("select", m.getMessage(), m.getGameView(), m.getOptions(), true);
                p.put("botao", texto(m.getOptions(), "UI.right.btn.text", "Passar"));
                if (m.getOptions() != null && m.getOptions().containsKey("UI.left.btn.text")) {
                    p.put("botaoEsq", m.getOptions().get("UI.left.btn.text").toString());
                }
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_PLAY_MANA: {
                GameClientMessage m = (GameClientMessage) d;
                perguntar(base("mana", m.getMessage(), m.getGameView(), m.getOptions(), true), m.getGameView());
                break;
            }
            case GAME_PLAY_XMANA: {
                GameClientMessage m = (GameClientMessage) d;
                perguntar(base("xmana", m.getMessage(), m.getGameView(), m.getOptions(), true), m.getGameView());
                break;
            }
            case GAME_CHOOSE_ABILITY: {
                AbilityPickerView a = (AbilityPickerView) d;
                Map<String, Object> p = base("ability", a.getMessage(), a.getGameView(), null, false);
                List<Map<String, Object>> ops = new ArrayList<>();
                for (Map.Entry<UUID, String> e : a.getChoices().entrySet()) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", e.getKey().toString());
                    o.put("texto", e.getValue());
                    ops.add(o);
                }
                p.put("opcoes", ops);
                perguntar(p, a.getGameView());
                break;
            }
            case GAME_CHOOSE_CHOICE: {
                GameClientMessage m = (GameClientMessage) d;
                Choice c = m.getChoice();
                Map<String, Object> p = base("choice", c.getMessage(), m.getGameView(), m.getOptions(), false);
                p.put("sub", c.getSubMessage());
                p.put("obrigatorio", c.isRequired());
                List<Map<String, Object>> ops = new ArrayList<>();
                if (c.isKeyChoice()) {
                    for (Map.Entry<String, String> e : c.getKeyChoices().entrySet()) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("chave", e.getKey());
                        o.put("texto", e.getValue());
                        ops.add(o);
                    }
                } else {
                    List<String> l = new ArrayList<>(c.getChoices());
                    if (c.isSortEnabled() || l.size() > 12) Collections.sort(l);
                    for (String s : l) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("chave", s);
                        o.put("texto", s);
                        ops.add(o);
                    }
                }
                p.put("opcoes", ops);
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_CHOOSE_PILE: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("pile", m.getMessage(), m.getGameView(), m.getOptions(), false);
                p.put("pilha1", cartas(m.getCardsView1()));
                p.put("pilha2", cartas(m.getCardsView2()));
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_GET_AMOUNT: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("amount", m.getMessage(), m.getGameView(), m.getOptions(), false);
                p.put("min", m.getMin());
                p.put("max", m.getMax());
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_GET_MULTI_AMOUNT: {
                GameClientMessage m = (GameClientMessage) d;
                Map<String, Object> p = base("multiamount", "Distribua os valores", m.getGameView(), m.getOptions(), false);
                List<Map<String, Object>> itens = new ArrayList<>();
                for (MultiAmountMessage mm : m.getMessages()) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("texto", mm.message);
                    o.put("min", mm.min);
                    o.put("max", mm.max);
                    o.put("padrao", mm.defaultValue);
                    itens.add(o);
                }
                p.put("itens", itens);
                p.put("min", m.getMin());
                p.put("max", m.getMax());
                perguntar(p, m.getGameView());
                break;
            }
            case GAME_OVER: {
                apagarPartida();
                synchronized (trava) {
                    fase = "fim";
                    prompt = null;
                    if (resultado == null) {
                        resultado = new LinkedHashMap<>();
                        resultado.put("texto", d == null ? "Fim de jogo" : d.toString());
                    }
                    if (!resultado.containsKey("resumo") && stats != null) try { resultado.put("resumo", stats.resumo(jogo, String.valueOf(resultado.get("texto")))); } catch (Exception ignored) { }
                    if (assistindo) resultado.put("assistindo", true);
                }
                mudou();
                break;
            }
            case END_GAME_INFO: {
                GameEndView g = (GameEndView) d;
                synchronized (trava) {
                    fase = "fim";
                    prompt = null;
                    resultado = new LinkedHashMap<>();
                    resultado.put("venceu", g.getWins() > 0);
                    resultado.put("texto", limpar(g.getGameInfo()));
                    if (stats != null) try { resultado.put("resumo", stats.resumo(jogo, limpar(g.getGameInfo()))); } catch (Exception e) { e.printStackTrace(); }
                    resultado.put("revanche", ultimaCriacao != null && !assistindo);
                }
                int turnos = 0;
                try { turnos = ((Number) jogo.get("turno")).intValue(); } catch (Exception ignored) { }
                if (!assistindo) registrarPartida(g.getWins() > 0, turnos);
                mudou();
                break;
            }
            default:
                break;
        }
    }

    static String limpar(String html) {
        return html == null ? "" : html.replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
    }

    static String texto(Map<String, Serializable> o, String k, String padrao) {
        return o != null && o.get(k) != null ? o.get(k).toString() : padrao;
    }

    Map<String, Object> base(String tipo, String msg, GameView g, Map<String, Serializable> opcoes, boolean jogaveis) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tipo", tipo);
        p.put("msg", msg);
        p.put("jogaveis", jogaveis);
        p.put("especial", g != null && g.getSpecial());
        Map<String, Object> o = new LinkedHashMap<>();
        if (opcoes != null) {
            for (Map.Entry<String, Serializable> e : opcoes.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put(e.getKey(), v);
                else if (v instanceof UUID) o.put(e.getKey(), v.toString());
                else if (v instanceof Collection) {
                    List<String> l = new ArrayList<>();
                    for (Object x : (Collection<?>) v) l.add(String.valueOf(x));
                    o.put(e.getKey(), l);
                } else if (v != null) o.put(e.getKey(), v.toString());
            }
        }
        p.put("opcoes", o);
        return p;
    }

    void perguntar(Map<String, Object> p, GameView g) {
        synchronized (trava) {
            p.put("seq", ++promptSeq);
            prompt = p;
            fase = "jogo";
            if (g != null) jogo = mapearJogo(g, Boolean.TRUE.equals(p.get("jogaveis")));
        }
        mudou();
    }

    void atualizar(GameView g) {
        if (g == null) return;
        synchronized (trava) {
            jogo = mapearJogo(g, false);
            if (!"fim".equals(fase)) fase = "jogo";
        }
        Estatisticas st = stats;
        if (st != null) try { st.foto(jogo); } catch (Exception ignored) { }
        mudou();
    }

    // =====================================================================
    // GameView -> JSON
    static List<String> ids(Collection<UUID> c) {
        List<String> l = new ArrayList<>();
        if (c != null) for (UUID u : c) l.add(u.toString());
        return l;
    }

    Map<String, Object> mapearJogo(GameView g, boolean comJogaveis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turno", g.getTurn());
        m.put("fase", g.getPhase() == null ? null : g.getPhase().toString());
        m.put("passo", g.getStep() == null ? null : g.getStep().toString());
        m.put("ativo", g.getActivePlayerName());
        m.put("ativoId", g.getActivePlayerId() == null ? null : g.getActivePlayerId().toString());
        m.put("prioridade", g.getPriorityPlayerName());
        m.put("pilha", cartas(g.getStack()));
        List<Map<String, Object>> combate = new ArrayList<>();
        if (g.getCombat() != null) {
            for (CombatGroupView c : g.getCombat()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("defensor", c.getDefenderName());
                x.put("defensorId", c.getDefenderId() == null ? null : c.getDefenderId().toString());
                x.put("atacantes", ids(c.getAttackers().keySet()));
                x.put("bloqueadores", ids(c.getBlockers().keySet()));
                x.put("bloqueado", c.isBlocked());
                combate.add(x);
            }
        }
        m.put("combate", combate);
        List<Map<String, Object>> jogadores = new ArrayList<>();
        for (PlayerView p : g.getPlayers()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id", p.getPlayerId().toString());
            j.put("nome", p.getName());
            j.put("vida", p.getLife());
            j.put("mao", p.getHandCount());
            j.put("grimorio", p.getLibraryCount());
            j.put("ativo", p.isActive());
            j.put("prioridade", p.hasPriority());
            j.put("saiu", p.hasLeft());
            j.put("eu", p.getPlayerId().equals(playerId));
            j.put("monarca", p.isMonarch());
            if (p.getTopCard() != null) j.put("topo", carta(p.getTopCard()));
            j.put("iniciativa", p.isInitiative());
            List<Object> cont = new ArrayList<>();
            for (CounterView c : p.getCounters()) cont.add(Arrays.asList(c.getName(), c.getCount()));
            j.put("marcadores", cont);
            ManaPoolView mp = p.getManaPool();
            Map<String, Integer> mana = new LinkedHashMap<>();
            mana.put("WHITE", mp.getWhite());
            mana.put("BLUE", mp.getBlue());
            mana.put("BLACK", mp.getBlack());
            mana.put("RED", mp.getRed());
            mana.put("GREEN", mp.getGreen());
            mana.put("COLORLESS", mp.getColorless());
            j.put("mana", mana);
            j.put("cemiterio", cartas(p.getGraveyard()));
            j.put("exilio", cartas(p.getExile()));
            List<Map<String, Object>> campo = new ArrayList<>();
            for (PermanentView pv : p.getBattlefield().values()) campo.add(carta(pv));
            j.put("campo", campo);
            List<Map<String, Object>> cmd = new ArrayList<>();
            for (CommandObjectView co : p.getCommandObjectList()) {
                if (co instanceof CardView) cmd.add(carta((CardView) co));
                else {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", co.getId().toString());
                    c.put("nome", co.getName());
                    c.put("set", co.getExpansionSetCode());
                    c.put("regras", co.getRules());
                    cmd.add(c);
                }
            }
            j.put("comando", cmd);
            jogadores.add(j);
        }
        m.put("jogadores", jogadores);
        m.put("mao", cartas(g.getMyHand()));
        List<Map<String, Object>> zonas = new ArrayList<>();
        if (g.getRevealed() != null) {
            for (RevealedView r : g.getRevealed()) {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("nome", "Revelado: " + r.getName());
                z.put("cartas", cartas(r.getCards()));
                zonas.add(z);
            }
        }
        if (g.getLookedAt() != null) {
            for (LookedAtView r : g.getLookedAt()) {
                Map<String, Object> z = new LinkedHashMap<>();
                z.put("nome", "Olhando: " + r.getName());
                List<Map<String, Object>> l = new ArrayList<>();
                for (SimpleCardView s : r.getCards().values()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", s.getId().toString());
                    c.put("set", s.getExpansionSetCode());
                    c.put("num", s.getCardNumber());
                    c.put("nome", "");
                    l.add(c);
                }
                z.put("cartas", l);
                zonas.add(z);
            }
        }
        // mãos que você pode ver (Telepathy, Glasses of Urza...)
        java.util.function.BiConsumer<String, SimpleCardsView> mao = (nome, cs) -> {
            if (cs == null || cs.isEmpty()) return;
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("nome", "Mão de " + nome);
            List<Map<String, Object>> l = new ArrayList<>();
            for (SimpleCardView sc : cs.values()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", sc.getId().toString()); c.put("set", sc.getExpansionSetCode()); c.put("num", sc.getCardNumber()); c.put("nome", "");
                l.add(c);
            }
            z.put("cartas", l); zonas.add(z);
        };
        if (g.getOpponentHands() != null) g.getOpponentHands().forEach(mao);
        if (g.getWatchedHands() != null) g.getWatchedHands().forEach(mao);
        m.put("zonas", zonas);
        List<String> jog = new ArrayList<>();
        if (comJogaveis && g.getCanPlayObjects() != null) jog = ids(g.getCanPlayObjects().getObjects().keySet());
        m.put("jogaveis", jog);
        // nomes das jogadas de cada objeto jogável (ex.: "Cast Lightning Bolt"): serve para mostrar cartas
        // jogáveis que não estão em nenhuma zona visível, como o topo do grimório com Bolas's Citadel
        Map<String, Object> nomesJog = new LinkedHashMap<>();
        if (comJogaveis && g.getCanPlayObjects() != null) {
            try {
                for (Map.Entry<UUID, mage.players.PlayableObjectStats> e : g.getCanPlayObjects().getObjects().entrySet()) {
                    List<String> n = e.getValue().getPlayableAbilityNames();
                    if (n != null && !n.isEmpty()) nomesJog.put(e.getKey().toString(), n);
                }
            } catch (Throwable ignored) { }
        }
        m.put("nomesJogaveis", nomesJog);
        return m;
    }

    static List<Map<String, Object>> cartas(Map<UUID, ? extends CardView> cv) {
        List<Map<String, Object>> l = new ArrayList<>();
        if (cv != null) for (CardView c : cv.values()) l.add(carta(c));
        return l;
    }

    static final String[] CHAVES = {"Flying", "First strike", "Double strike", "Deathtouch", "Lifelink", "Vigilance", "Trample",
            "Menace", "Haste", "Reach", "Hexproof", "Shroud", "Indestructible", "Defender", "Infect", "Flash", "Ward", "Protection"};

    /** Habilidades-chave atuais da carta (inclui as ganhas por efeitos, ex. lifelink de um buff). */
    static List<String> palavrasChave(CardView c) {
        LinkedHashSet<String> r = new LinkedHashSet<>();
        try {
            if (c.getCardIcons() != null) {
                for (mage.abilities.icon.CardIcon i : c.getCardIcons()) {
                    String n = i.getIconType().name();
                    if (n.startsWith("ABILITY_") && !n.equals("ABILITY_CREW") && !n.equals("ABILITY_CLASS_LEVEL")) {
                        String k = n.substring(8).replace('_', ' ').toLowerCase();
                        r.add(Character.toUpperCase(k.charAt(0)) + k.substring(1));
                    }
                }
            }
        } catch (Exception ignored) { }
        if (c.getRules() != null) {
            for (String regra : c.getRules()) {
                String t = regra.replaceAll("<[^>]+>", "").trim();
                for (String parte : t.split("[,;]")) {
                    String p = parte.trim().toLowerCase();
                    for (String k : CHAVES) {
                        String kl = k.toLowerCase();
                        if (p.equals(kl) || p.startsWith(kl + " ") || p.startsWith(kl + "—") || p.startsWith(kl + " from")) r.add(k);
                    }
                }
            }
        }
        return new ArrayList<>(r);
    }

    static Map<String, Object> carta(CardView c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId().toString());
        m.put("nome", c.getDisplayName() != null ? c.getDisplayName() : c.getName());
        m.put("set", c.getExpansionSetCode());
        m.put("num", c.getCardNumber());
        m.put("ficha", c.isToken());
        m.put("viradaPraBaixo", c.isFaceDown());
        m.put("tipo", c.getTypeText());
        m.put("custo", c.getManaCostStr());
        m.put("mv", c.getManaValue());
        m.put("criatura", c.isCreature());
        m.put("terreno", c.isLand());
        m.put("pw", c.isPlaneswalker());
        m.put("habilidade", c.isAbility());
        if (c.showPT()) {
            m.put("poder", c.getPower());
            m.put("resist", c.getToughness());
        }
        if (c.isPlaneswalker()) m.put("lealdade", c.getLoyalty());
        if (c.isBattle()) m.put("defesa", c.getDefense());
        m.put("regras", c.getRules());
        if (c instanceof StackAbilityView && ((StackAbilityView) c).getSourceCard() != null) {
            // habilidade na pilha: mostra a carta de onde ela veio
            CardView f = ((StackAbilityView) c).getSourceCard();
            m.put("origem", f.getDisplayName() != null ? f.getDisplayName() : f.getName());
            m.put("nome", m.get("origem"));
            m.put("set", f.getExpansionSetCode());
            m.put("num", f.getCardNumber());
            m.put("ficha", f.isToken());
            AbilityType at = ((StackAbilityView) c).getAbilityType();
            m.put("tipoPilha", at == null ? "HABILIDADE" : at.name());   // TRIGGERED / ACTIVATED / ...
        }
        m.put("chaves", palavrasChave(c));
        // portador do Anel (O Senhor dos Anéis) e de quê é a proteção ("from red", cor escolhida...)
        boolean portador = false; LinkedHashSet<String> prot = new LinkedHashSet<>();
        try {
            if (c.getCardIcons() != null) for (mage.abilities.icon.CardIcon i : c.getCardIcons()) {
                String n = i.getIconType().name();
                if (n.equals("RINGBEARER")) portador = true;
                if (n.equals("ABILITY_PROTECTION") && i.getHint() != null) {
                    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?i)protection from ([^.;<(]+)").matcher(i.getHint().replaceAll("<[^>]+>", " "));
                    while (mm.find()) prot.add(mm.group(1).trim());
                }
            }
        } catch (Exception ignored) { }
        if (c.getRules() != null) for (String regra : c.getRules()) {
            String t = regra.replaceAll("<[^>]+>", " ");
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?i)protection from ([^.;(]+)").matcher(t);
            while (mm.find()) prot.add(mm.group(1).trim());
            java.util.regex.Matcher cc = java.util.regex.Pattern.compile("(?i)chosen color:? *(white|blue|black|red|green)").matcher(t);
            while (cc.find()) prot.add("cor escolhida: " + cc.group(1));
            if (t.toLowerCase().contains("ring-bearer") && t.toLowerCase().contains("is your ring-bearer")) portador = true;
        }
        if (portador) m.put("portadorAnel", true);
        if (!prot.isEmpty()) m.put("protecoes", new ArrayList<>(prot));
        m.put("dividida", c.isSplitCard() || (c.getTypeText() != null && c.getTypeText().contains("Room")));
        if (c.getTargets() != null && !c.getTargets().isEmpty()) m.put("alvos", ids(c.getTargets()));
        List<Object> cont = new ArrayList<>();
        if (c.getCounters() != null) for (CounterView k : c.getCounters()) cont.add(Arrays.asList(k.getName(), k.getCount()));
        m.put("marcadores", cont);
        if (c.getSecondCardFace() != null) m.put("verso", c.getSecondCardFace().getName());
        if (c instanceof PermanentView) {
            PermanentView p = (PermanentView) c;
            m.put("virada", p.isTapped());
            m.put("dano", p.getDamage());
            m.put("enjoo", p.hasSummoningSickness());
            m.put("copia", p.isCopy());
            if (p.getAttachedTo() != null) m.put("presoEm", p.getAttachedTo().toString());
            m.put("anexos", ids(p.getAttachments()));
            m.put("dono", p.getNameOwner());
            m.put("controlador", p.getNameController());
        }
        return m;
    }

    // =====================================================================
    // Imagens (Scryfall) com cache em disco
    final Object travaScry = new Object();
    long ultimaScry = 0;

    void imagem(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String set = q.getOrDefault("s", ""), num = q.getOrDefault("n", ""), nome = q.getOrDefault("nm", "");
        boolean ficha = "1".equals(q.get("f"));
        if (ficha) nome = nome.replaceAll("(?i)\\s+token$", "").trim();   // "Vampire Token" -> "Vampire"
        String ver = "art".equals(q.get("v")) ? "art_crop" : "normal";   // art_crop = só a arte (cartas minimizadas)
        boolean pt = "pt".equals(q.get("l")) && !ficha;   // imagem em português, quando existir
        String chave = (pt ? "pt_" : "") + (ver.equals("art_crop") ? "art_" : "") + (ficha ? "tok_" + nome : set + "_" + num + "_" + nome).replaceAll("[^A-Za-z0-9_.-]", "_");
        if (chave.length() > 120) chave = chave.substring(0, 120);
        Path f = CACHE.resolve(chave + ".jpg");
        Path falha = CACHE.resolve(chave + ".falhou");
        if (!Files.exists(f) && !Files.exists(falha)) {
            byte[] b = null;
            try {
                if (pt && !set.isEmpty() && !num.isEmpty()) {   // mesma edição, impressão em português
                    b = baixar("https://api.scryfall.com/cards/" + enc(set.toLowerCase()) + "/" + enc(num) + "/pt?format=image&version=" + ver);
                }
                // a edição escolhida não existe em português: usa essa mesma edição em inglês (a arte escolhida vale mais que o idioma)
                if (b == null && pt && !set.isEmpty() && !num.isEmpty()) {
                    b = baixar("https://api.scryfall.com/cards/" + enc(set.toLowerCase()) + "/" + enc(num) + "?format=image&version=" + ver);
                }
                if (b == null && pt && !nome.isEmpty() && (set.isEmpty() || num.isEmpty())) {   // sem edição definida: qualquer impressão em português
                    byte[] js = baixar("https://api.scryfall.com/cards/search?unique=prints&include_multilingual=true&q=" + enc("!\"" + nome + "\" lang:pt"));
                    if (js != null) {
                        JsonObject o = JsonParser.parseString(new String(js, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (com.google.gson.JsonElement e : o.getAsJsonArray("data")) {
                            JsonObject c = e.getAsJsonObject();
                            JsonObject iu = c.has("image_uris") ? c.getAsJsonObject("image_uris")
                                    : c.has("card_faces") ? c.getAsJsonArray("card_faces").get(0).getAsJsonObject().getAsJsonObject("image_uris") : null;
                            if (iu != null && iu.has(ver) && !"placeholder".equals(c.has("image_status") ? c.get("image_status").getAsString() : "")) {
                                b = baixar(iu.get(ver).getAsString());
                                if (b != null) break;
                            }
                        }
                    }
                }
                if (b == null && !ficha && !set.isEmpty() && !num.isEmpty()) {
                    b = baixar("https://api.scryfall.com/cards/" + enc(set.toLowerCase()) + "/" + enc(num) + "?format=image&version=" + ver);
                }
                if (b == null && !ficha && !nome.isEmpty()) {
                    b = baixar("https://api.scryfall.com/cards/named?exact=" + enc(nome) + "&format=image&version=" + ver);
                    if (b != null && !set.isEmpty() && !num.isEmpty()) {   // reserva: mostra agora, tenta a edição certa da próxima vez
                        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
                        ex.getResponseHeaders().set("Cache-Control", "no-store");
                        ex.sendResponseHeaders(200, b.length);
                        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
                        return;
                    }
                }
                if (b == null && !nome.isEmpty()) {
                    byte[] js = baixar("https://api.scryfall.com/cards/search?unique=art&q=" + enc("!\"" + nome + "\" t:token"));
                    if (js != null) {
                        JsonObject o = JsonParser.parseString(new String(js, StandardCharsets.UTF_8)).getAsJsonObject();
                        // fichas de duas faces (ex.: "Dinosaur // Treasure"): usa a face com o nome certo,
                        // e dá preferência às fichas de uma face só
                        JsonObject iu = null;
                        for (com.google.gson.JsonElement e : o.getAsJsonArray("data")) {
                            JsonObject c = e.getAsJsonObject();
                            if (c.has("image_uris") && nome.equalsIgnoreCase(c.get("name").getAsString())) { iu = c.getAsJsonObject("image_uris"); break; }
                        }
                        if (iu == null) {
                            for (com.google.gson.JsonElement e : o.getAsJsonArray("data")) {
                                JsonObject c = e.getAsJsonObject();
                                if (!c.has("card_faces")) continue;
                                for (com.google.gson.JsonElement fe : c.getAsJsonArray("card_faces")) {
                                    JsonObject face = fe.getAsJsonObject();
                                    if (nome.equalsIgnoreCase(face.get("name").getAsString())) {
                                        iu = face.has("image_uris") ? face.getAsJsonObject("image_uris") : c.getAsJsonObject("image_uris");
                                        break;
                                    }
                                }
                                if (iu != null) break;
                            }
                        }
                        if (iu != null && iu.has(ver)) b = baixar(iu.get(ver).getAsString());
                    }
                }
            } catch (Exception e) {
                b = null;
            }
            if (b != null) Files.write(f, b);
            else Files.write(falha, new byte[0]);
        }
        if (Files.exists(f)) {
            byte[] b = Files.readAllBytes(f);
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "max-age=604800");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        } else {
            ex.sendResponseHeaders(404, -1);
        }
        ex.close();
    }

    byte[] baixar(String url) {
        synchronized (travaScry) {   // Scryfall pede no máximo ~10 pedidos/segundo
            long espera = 120 - (System.currentTimeMillis() - ultimaScry);
            if (espera > 0) {
                try { Thread.sleep(espera); } catch (InterruptedException ignored) { }
            }
            ultimaScry = System.currentTimeMillis();
        }
        try {
            String atual = url;
            for (int i = 0; i < 4; i++) {
                HttpURLConnection c = (HttpURLConnection) new URL(atual).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "MesaXMage/0.1");
                c.setRequestProperty("Accept", "*/*");
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    atual = c.getHeaderField("Location");
                    continue;
                }
                if (code != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    return readAll(in);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    static Map<String, String> query(String q) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(par.substring(0, i), "UTF-8"), URLDecoder.decode(par.substring(i + 1), "UTF-8"));
        }
        return m;
    }
}
