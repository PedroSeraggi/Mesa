import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Lista de amigos da Mesa. Roda no servidor do grupo (Zera), ao lado do servidor do XMage.
 *
 * Cada Mesa tem um id secreto (gerado nela) e um código curto público (6 letras, derivado do id),
 * que é o que se passa para os amigos. Só quem está no Tailscale do grupo alcança este serviço.
 * Se a variável SENHA existir, todo pedido também precisa dela (cabeçalho X-Senha).
 *
 *   POST /ping  {id, nome, avatar, status, sala}  -> amigos, pedidos, convites, quem mais está online
 *   POST /acao  {id, acao: pedir|aceitar|recusar|remover|convidar|descartar, codigo|nome, sala, convite}
 *   POST /perfil {id, codigo} -> perfil público de alguém (foto, banner, frase, estatísticas, decks, nível, recordes, confronto)
 *   POST /partida {id, partida} -> registra o fim de uma partida: XP, nível, missões diárias, recordes
 *   POST /chefe {id} -> o Chefe do dia (troca às 20h de Brasília): deck, lacaios e quem já o derrotou
 *
 * Variáveis: SENHA (opcional), PORTA (padrão 17190), DADOS (padrão ./dados.json).
 */
public class Amigos {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final long ONLINE_MS = 35_000, CONVITE_MS = 3 * 3600_000L;
    static String SENHA;
    static Path DADOS;

    // ---- dados (tudo em memória; gravado no arquivo quando muda) ----
    static class Usuario {
        String id, codigo, nome = "?", avatar = "", status = "", xmage = ""; JsonObject sala, perfil; long visto;
        long xp = 0; String diaVitoria = ""; String diaMissoes = ""; Map<String, Integer> progresso = new HashMap<>(); Set<String> missoesFeitas = new HashSet<>();
        JsonObject recordes = new JsonObject(); List<JsonObject> historico = new ArrayList<>();
    }
    static class Convite { String id, de, para; JsonObject sala; long quando; }
    static class Banco {
        Map<String, Usuario> porId = new LinkedHashMap<>();
        Set<String> amizades = new LinkedHashSet<>();     // "AAAAAA|BBBBBB" (ordem alfabética)
        Set<String> pedidos = new LinkedHashSet<>();      // "DE>PARA"
        List<Convite> convites = new ArrayList<>();
        Map<String, List<Map<String, Object>>> derrotaramChefe = new HashMap<>();   // ciclo -> [{codigo, quando}]
        Map<String, String> chefePorCiclo = new HashMap<>();   // ciclo -> arquivo do chefe (em chefes/usados)
        String fabricaUltima = ""; boolean fabricaPendente = false;
        Set<String> ciclosPremiados = new HashSet<>();      // ciclos cujo 1º lugar já ganhou o prêmio
        Map<String, Object> liderChefe = null;              // último a assumir o 1º lugar (para o aviso)
    }
    static Banco b = new Banco();
    static volatile boolean sujo = false;

    public static void main(String[] a) throws Exception {
        SENHA = Optional.ofNullable(System.getenv("SENHA")).orElse("").trim();
        int porta = Integer.parseInt(Optional.ofNullable(System.getenv("PORTA")).orElse("17190"));
        DADOS = Paths.get(Optional.ofNullable(System.getenv("DADOS")).orElse("dados.json"));
        if (Files.exists(DADOS)) b = GSON.fromJson(new String(Files.readAllBytes(DADOS), StandardCharsets.UTF_8), Banco.class);

        HttpServer srv = HttpServer.create(new InetSocketAddress(porta), 50);
        srv.createContext("/ping", ex -> tratar(ex, Amigos::ping));
        srv.createContext("/acao", ex -> tratar(ex, Amigos::acao));
        srv.createContext("/perfil", ex -> tratar(ex, Amigos::perfil));
        srv.createContext("/partida", ex -> tratar(ex, Amigos::partida));
        srv.createContext("/chefe", ex -> tratar(ex, Amigos::chefe));
        srv.createContext("/fabrica", ex -> tratar(ex, c -> { usuario(c); if (c.has("rodar")) talvezRodarFabrica(true);
            Map<String, Object> r = new LinkedHashMap<>(); r.put("rodando", fabricaRodando); r.put("fila", arquivosChefes().size()); r.put("ultima", b.fabricaUltima); r.put("pendente", b.fabricaPendente); return r; }));
        srv.createContext("/saude", ex -> responder(ex, 200, "{\"ok\":true}"));
        srv.setExecutor(Executors.newFixedThreadPool(8));
        srv.start();
        // grava no arquivo a cada 20 s se algo mudou ("visto por último" muda o tempo todo; não precisa gravar a cada ping)
        new Timer(true).scheduleAtFixedRate(new TimerTask() { public void run() { gravar(); } }, 20_000, 20_000);
        new Timer(true).scheduleAtFixedRate(new TimerTask() { public void run() { try { synchronized (b) { talvezRodarFabrica(false); premiarCiclosFechados(); } } catch (Exception e) { e.printStackTrace(); } } }, 60_000, 300_000);
        Runtime.getRuntime().addShutdownHook(new Thread(Amigos::gravar));
        System.out.println("Amigos da Mesa no ar na porta " + porta);
    }

    interface Rota { Object rodar(JsonObject corpo) throws Exception; }

    static void tratar(HttpExchange ex, Rota r) throws IOException {
        try {
            if (!SENHA.isEmpty() && !SENHA.equals(ex.getRequestHeaders().getFirst("X-Senha"))) { responder(ex, 401, "{\"erro\":\"Senha do grupo errada.\"}"); return; }
            if (!"POST".equals(ex.getRequestMethod())) { responder(ex, 405, "{\"erro\":\"use POST\"}"); return; }
            JsonObject c = JsonParser.parseString(new String(ler(ex.getRequestBody()), StandardCharsets.UTF_8)).getAsJsonObject();
            Object res;
            synchronized (b) { res = r.rodar(c); }
            responder(ex, 200, GSON.toJson(res));
        } catch (IllegalArgumentException e) {
            responder(ex, 400, GSON.toJson(Collections.singletonMap("erro", e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            responder(ex, 500, GSON.toJson(Collections.singletonMap("erro", String.valueOf(e.getMessage()))));
        }
    }

    // ---------------- rotas ----------------
    static Object ping(JsonObject c) {
        Usuario u = usuario(c);
        u.nome = texto(c, "nome", 30, u.nome);
        String av = texto(c, "avatar", 60_000, "");
        if (av.isEmpty() || av.startsWith("data:image/")) u.avatar = av;
        u.status = texto(c, "status", 80, "");
        u.xmage = texto(c, "xmage", 30, u.xmage == null ? "" : u.xmage);
        u.sala = c.has("sala") && c.get("sala").isJsonObject() ? c.getAsJsonObject("sala") : null;
        if (c.has("perfil") && c.get("perfil").isJsonObject()) u.perfil = c.getAsJsonObject("perfil");   // só vem quando muda
        u.visto = System.currentTimeMillis();
        sujo = true;
        return foto(u);
    }

    static Object perfil(JsonObject c) {
        Usuario eu = usuario(c);
        Usuario o = porCodigo(texto(c, "codigo", 10, ""));
        if (o == null) throw new IllegalArgumentException("Não achei esse jogador.");
        Map<String, Object> r = resumo(o);
        boolean on = System.currentTimeMillis() - o.visto < ONLINE_MS;
        r.put("online", on); r.put("visto", o.visto); r.put("amigo", b.amizades.contains(par(eu, o)));
        if (on) r.put("status", o.status);
        r.put("perfil", o.perfil);
        r.put("nivel", nivelInfo(o));
        r.put("recordes", o.recordes);
        // confronto direto: partidas que os dois registraram (mesmo id de partida)
        Map<String, JsonObject> minhas = new HashMap<>();
        for (JsonObject h : eu.historico) minhas.put(h.get("gameId").getAsString(), h);
        int vEu = 0, vEle = 0; List<Map<String, Object>> juntos = new ArrayList<>();
        for (int i = o.historico.size() - 1; i >= 0; i--) {
            JsonObject h = o.historico.get(i), m = minhas.get(h.get("gameId").getAsString());
            if (m == null || o == eu) continue;
            boolean ele = h.get("venceu").getAsBoolean(), euV = m.get("venceu").getAsBoolean();
            if (ele) vEle++; if (euV) vEu++;
            if (juntos.size() < 5) { Map<String, Object> x = new LinkedHashMap<>(); x.put("data", str(h, "data")); x.put("turnos", h.get("turnos")); x.put("venceuEle", ele); x.put("venceuEu", euV);
                x.put("deckEle", str(h, "deck")); x.put("deckEu", str(m, "deck")); juntos.add(x); }
        }
        Map<String, Object> conf = new LinkedHashMap<>(); conf.put("eu", vEu); conf.put("ele", vEle); conf.put("partidas", vEu + vEle + juntos.stream().filter(x -> !(Boolean) x.get("venceuEle") && !(Boolean) x.get("venceuEu")).count()); conf.put("ultimas", juntos);
        r.put("confronto", conf);
        // rival (quem mais o eliminou) e vítima favorita (quem ele mais eliminou)
        Map<String, Integer> porQuem = new HashMap<>(), quem = new HashMap<>();
        for (JsonObject h : o.historico) {
            if (h.has("eliminadoPor") && !h.get("eliminadoPor").isJsonNull()) porQuem.merge(h.get("eliminadoPor").getAsString(), 1, Integer::sum);
            if (h.has("eliminou")) for (JsonElement e : h.getAsJsonArray("eliminou")) quem.merge(e.getAsString(), 1, Integer::sum);
        }
        r.put("rival", topo(porQuem)); r.put("vitima", topo(quem));
        r.put("trofeus", trofeus(o));
        return r;
    }
    static Map<String, Object> topo(Map<String, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> {
            Map<String, Object> x = new LinkedHashMap<>(); x.put("nome", nomeDeXmage(e.getKey())); x.put("vezes", e.getValue()); return x; }).orElse(null);
    }
    static String nomeDeXmage(String xm) {
        for (Usuario u : b.porId.values()) if (xm.equalsIgnoreCase(u.xmage)) return u.nome;
        return xm;
    }
    static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    // ---------------- nível e missões ----------------
    static int xpParaSubir(int nivel) { return 150 + 50 * nivel; }   // do nível N para o N+1
    static Map<String, Object> nivelInfo(Usuario u) {
        int nivel = 1; long resto = u.xp;
        while (resto >= xpParaSubir(nivel)) { resto -= xpParaSubir(nivel); nivel++; }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nivel", nivel); r.put("xp", u.xp); r.put("noNivel", resto); r.put("paraSubir", xpParaSubir(nivel));
        return r;
    }
    // {id, texto, meta, xp, campo}
    static final Object[][] MISSOES = {
        {"jogar2", "Jogue 2 partidas", 2, 50, "partidas"},
        {"vencer1", "Vença 1 partida", 1, 100, "vitorias"},
        {"elim2", "Elimine 2 jogadores", 2, 100, "eliminacoes"},
        {"cri10", "Derrote 10 criaturas", 10, 75, "criaturas"},
        {"comp25", "Compre 25 cartas", 25, 50, "compras"},
        {"mag15", "Lance 15 mágicas", 15, 75, "magias"},
        {"cmd21", "Cause 21 de dano de comandante", 21, 150, "danoCmd"},
        {"dano60", "Cause 60 de dano", 60, 100, "dano"},
        {"amigos", "Jogue uma partida com 2 ou mais amigos", 1, 100, "partidasAmigos"},
    };
    static String hoje() { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()); }
    static List<Object[]> missoesDoDia(Usuario u) {
        if (!hoje().equals(u.diaMissoes)) { u.diaMissoes = hoje(); u.progresso = new HashMap<>(); u.missoesFeitas = new HashSet<>(); sujo = true; }
        List<Object[]> todas = new ArrayList<>(Arrays.asList(MISSOES));
        Collections.shuffle(todas, new Random((u.codigo + hoje()).hashCode()));
        return todas.subList(0, 3);
    }
    static List<Map<String, Object>> missoesJson(Usuario u) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Object[] m : missoesDoDia(u)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m[0]); x.put("texto", m[1]); x.put("meta", m[2]); x.put("xp", m[3]);
            x.put("progresso", Math.min((Integer) m[2], u.progresso.getOrDefault((String) m[4], 0)));
            x.put("feita", u.missoesFeitas.contains((String) m[0]));
            l.add(x);
        }
        return l;
    }

    /** Fim de partida, mandado pela Mesa de cada jogador. */
    static Object partida(JsonObject c) {
        Usuario u = usuario(c);
        JsonObject p = c.getAsJsonObject("partida");
        String gid = str(p, "gameId");
        if (gid.isEmpty()) throw new IllegalArgumentException("partida sem id");
        for (JsonObject h : u.historico) if (gid.equals(str(h, "gameId"))) { Map<String, Object> r = new LinkedHashMap<>(); r.put("repetida", true); r.put("nivel", nivelInfo(u)); r.put("missoes", missoesJson(u)); return r; }
        int turnos = p.get("turnos").getAsInt();
        boolean venceu = p.get("venceu").getAsBoolean();
        double mult = p.has("multiplicador") ? p.get("multiplicador").getAsDouble() : 1.0;
        String cicloChefe = str(p, "chefe");
        final JsonObject[] deckGanho = {null};
        boolean primeiraContraChefe = venceuPartida(p) && !cicloChefe.isEmpty() && cicloChefe.equals(cicloAtual())
                && b.derrotaramChefe.getOrDefault(cicloChefe, Collections.emptyList()).stream().noneMatch(d -> u.codigo.equals(d.get("codigo")));
        if (primeiraContraChefe) mult = 1.0;   // a primeira vitória contra o chefe vale 100%
        int elim = p.getAsJsonArray("eliminou").size();
        int criaturasReais = p.get("criaturas").getAsInt(), comprasReais = p.get("compras").getAsInt();
        int criaturas = Math.min(15, criaturasReais), compras = Math.min(30, comprasReais);
        List<Map<String, Object>> ganhos = new ArrayList<>();
        // cada linha: motivo, quantidade (ou null), XP, se bateu o limite, se sofre o desconto dos bots
        class G { void add(String motivo, Integer qtd, int xp, boolean max, boolean bot) {
            if (xp <= 0) return; Map<String, Object> g = new LinkedHashMap<>(); g.put("motivo", motivo); if (qtd != null) g.put("qtd", qtd); g.put("xp", xp); if (max) g.put("max", true); if (bot) g.put("bot", true); ganhos.add(g); } }
        G ganho = new G();
        boolean desconto = mult < 1.0;
        if (turnos >= 3) {
            ganho.add("Jogar a partida", null, (int) Math.round(50 * mult), false, desconto);
            if (venceu) ganho.add("Vitória", null, (int) Math.round(100 * mult), false, desconto);
            if (elim > 0) ganho.add("Jogadores eliminados", elim, (int) Math.round(20 * elim * mult), false, desconto);
            if (criaturas > 0) ganho.add("Criaturas abatidas", criaturasReais, (int) Math.round(3 * criaturas * mult), criaturasReais >= 15, desconto);
            if (compras > 0) ganho.add("Cartas compradas", comprasReais, (int) Math.round(compras * mult), comprasReais >= 30, desconto);
            // bônus permanente da galeria: +1% por medalha (ouro +2%), sobre o XP da partida (não sobre os bônus fixos)
            int bonusPct = 0, medalhas = 0;
            for (Map<String, Object> t : trofeus(u)) if (Boolean.TRUE.equals(t.get("venceu"))) { medalhas++; bonusPct += "ouro".equals(t.get("medalha")) ? 2 : 1; }
            if (bonusPct > 0) {
                int baseXp = ganhos.stream().mapToInt(g -> (Integer) g.get("xp")).sum();
                ganho.add("Galeria de chefes (" + medalhas + " medalha" + (medalhas > 1 ? "s" : "") + "): +" + bonusPct + "%", null, (int) Math.round(baseXp * bonusPct / 100.0), false, false);
            }
            if (venceu && !hoje().equals(u.diaVitoria)) { u.diaVitoria = hoje(); ganho.add("Primeira vitória do dia", null, 100, false, false); }
            String cicloPartida = str(p, "chefe");
            if (venceu && !cicloPartida.isEmpty() && cicloPartida.equals(cicloAtual())) {
                List<Map<String, Object>> der = b.derrotaramChefe.computeIfAbsent(cicloPartida, k -> new ArrayList<>());
                String liderAntes = der.isEmpty() ? null : String.valueOf(ranking(der).get(0).get("codigo"));
                int vida = p.has("vida") ? p.get("vida").getAsInt() : 0;
                Map<String, Object> minha = der.stream().filter(d -> u.codigo.equals(d.get("codigo"))).findFirst().orElse(null);
                if (minha == null) {
                    Map<String, Object> d = new LinkedHashMap<>(); d.put("codigo", u.codigo); d.put("quando", System.currentTimeMillis());
                    d.put("turnos", turnos); d.put("vida", vida); der.add(d);
                    ganho.add("Derrotou o Chefe do dia", null, 250, false, false);
                    try {   // prêmio: o deck do chefe
                        JsonObject ch = chefeDoCiclo();
                        if (ch != null) { JsonObject dg = new JsonObject(); dg.add("nome", ch.get("nome")); dg.add("deck", ch.get("deck")); dg.add("comandante", ch.get("comandante")); deckGanho[0] = dg; }
                    } catch (Exception ignored) { }
                    soma(u.recordes, "chefesDerrotados", 1);
                } else if (melhor(turnos, vida, minha)) {   // vitória melhor que a anterior: atualiza a marca no ranking
                    minha.put("turnos", turnos); minha.put("vida", vida); minha.put("quando", System.currentTimeMillis());
                }
                String liderAgora = String.valueOf(ranking(der).get(0).get("codigo"));
                if (u.codigo.equals(liderAgora) && !liderAgora.equals(liderAntes)) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("ciclo", cicloPartida); l.put("codigo", u.codigo); l.put("nome", u.nome); l.put("turnos", turnos); l.put("vida", vida); l.put("quando", System.currentTimeMillis());
                    b.liderChefe = l;
                }
            }
            // missões
            missoesDoDia(u);
            Map<String, Integer> soma = new HashMap<>();
            soma.put("partidas", 1); soma.put("vitorias", venceu ? 1 : 0); soma.put("eliminacoes", elim);
            soma.put("criaturas", p.get("criaturas").getAsInt()); soma.put("compras", p.get("compras").getAsInt());
            soma.put("magias", p.get("magias").getAsInt()); soma.put("danoCmd", p.get("danoCmd").getAsInt()); soma.put("dano", p.get("dano").getAsInt());
            soma.put("partidasAmigos", p.get("humanos").getAsInt() >= 3 ? 1 : 0);
            for (Map.Entry<String, Integer> e : soma.entrySet()) u.progresso.merge(e.getKey(), e.getValue(), Integer::sum);
            for (Object[] m : missoesDoDia(u)) {
                if (u.missoesFeitas.contains((String) m[0])) continue;
                if (u.progresso.getOrDefault((String) m[4], 0) >= (Integer) m[2]) { u.missoesFeitas.add((String) m[0]); ganho.add("Missão: " + m[1], null, (Integer) m[3], false, false); }
            }
        }
        int antes = (Integer) nivelInfo(u).get("nivel");
        for (Map<String, Object> g : ganhos) u.xp += (Integer) g.get("xp");
        // recordes
        JsonObject rc = u.recordes;
        soma(rc, "partidas", 1); if (venceu) soma(rc, "vitorias", 1);
        soma(rc, "dano", p.get("dano").getAsInt()); soma(rc, "eliminacoes", elim);
        if (venceu) { soma(rc, "sequencia", 1); if (rc.get("sequencia").getAsInt() > num(rc, "melhorSequencia")) rc.addProperty("melhorSequencia", rc.get("sequencia").getAsInt()); }
        else rc.addProperty("sequencia", 0);
        if (venceu && turnos >= 3 && (num(rc, "vitoriaMaisRapida") == 0 || turnos < num(rc, "vitoriaMaisRapida"))) rc.addProperty("vitoriaMaisRapida", turnos);
        if (turnos > num(rc, "partidaMaisLonga")) rc.addProperty("partidaMaisLonga", turnos);
        if (p.has("maiorGolpe") && p.get("maiorGolpe").isJsonObject()) {
            JsonObject g = p.getAsJsonObject("maiorGolpe");
            if (!rc.has("maiorGolpe") || g.get("n").getAsInt() > rc.getAsJsonObject("maiorGolpe").get("n").getAsInt()) {
                JsonObject gg = g.deepCopy(); gg.addProperty("em", nomeDeXmage(str(g, "em"))); gg.addProperty("data", str(p, "data")); rc.add("maiorGolpe", gg);
            }
        }
        // histórico (para o confronto direto e rival)
        JsonObject h = new JsonObject();
        for (String k : new String[]{"gameId", "data", "venceu", "turnos", "deck", "eliminadoPor"}) if (p.has(k)) h.add(k, p.get(k));
        h.add("eliminou", p.getAsJsonArray("eliminou")); h.add("jogadores", p.has("jogadores") ? p.get("jogadores") : new JsonArray());
        u.historico.add(h);
        while (u.historico.size() > 150) u.historico.remove(0);
        sujo = true;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ganhos", ganhos);
        r.put("multiplicador", mult);
        if (primeiraContraChefe) r.put("xpCheioChefe", true);
        if (deckGanho[0] != null) r.put("deckGanho", deckGanho[0]);
        r.put("total", ganhos.stream().mapToInt(g -> (Integer) g.get("xp")).sum());
        Map<String, Object> ni = nivelInfo(u);
        r.put("nivel", ni); r.put("subiu", (Integer) ni.get("nivel") > antes);
        r.put("missoes", missoesJson(u));
        return r;
    }
    // ---------------- Chefe do dia ----------------
    // Os chefes ficam em ./chefes/*.json: {nome, comandante:{nome,set,num}, deck:"texto .dck", lacaios:["texto .dck", ...]}
    static final java.time.ZoneId BR = java.time.ZoneId.of("America/Sao_Paulo");
    /** O ciclo começa às 20h: das 20h de hoje às 19:59 de amanhã é o mesmo chefe. */
    static java.time.ZonedDateTime inicioCiclo() {
        java.time.ZonedDateTime agora = java.time.ZonedDateTime.now(BR), hoje20 = agora.toLocalDate().atTime(20, 0).atZone(BR);
        return agora.isBefore(hoje20) ? hoje20.minusDays(1) : hoje20;
    }
    static String cicloAtual() { return inicioCiclo().toLocalDate().toString(); }
    static List<java.nio.file.Path> arquivosChefes() {
        List<java.nio.file.Path> l = new ArrayList<>();
        java.nio.file.Path d = DADOS.toAbsolutePath().getParent().resolve("chefes");
        try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(d, "*.json")) { for (java.nio.file.Path f : ds) l.add(f); } catch (Exception ignored) { }
        l.sort(Comparator.comparing(java.nio.file.Path::toString));
        return l;
    }
    static java.nio.file.Path pastaChefes() { return DADOS.toAbsolutePath().getParent().resolve("chefes"); }
    /** Cada ciclo (20h) pega o chefe mais antigo da fila (chefes/*.json) e o move para chefes/usados/.
     *  Fila vazia: repete o chefe usado que está há mais tempo sem aparecer. */
    static synchronized JsonObject chefeDoCiclo() throws IOException {
        String ciclo = cicloAtual();
        java.nio.file.Path usados = pastaChefes().resolve("usados");
        Files.createDirectories(usados);
        String arq = b.chefePorCiclo.get(ciclo);
        if (arq == null || !Files.exists(usados.resolve(arq))) {
            List<java.nio.file.Path> fila = arquivosChefes();
            fila.sort(Comparator.comparingLong(f -> { try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0L; } }));
            if (!fila.isEmpty()) {
                java.nio.file.Path f = fila.get(0);
                arq = f.getFileName().toString();
                Files.move(f, usados.resolve(arq), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Map<String, String> ultimoUso = new HashMap<>();
                for (Map.Entry<String, String> e : b.chefePorCiclo.entrySet()) ultimoUso.merge(e.getValue(), e.getKey(), (x, y) -> x.compareTo(y) > 0 ? x : y);
                List<String> todos = new ArrayList<>();
                try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(usados, "*.json")) { for (java.nio.file.Path f : ds) todos.add(f.getFileName().toString()); }
                if (todos.isEmpty()) return null;
                todos.sort(Comparator.comparing(n -> ultimoUso.getOrDefault(n, "")));
                arq = todos.get(0);
            }
            b.chefePorCiclo.put(ciclo, arq); sujo = true;
        }
        JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(usados.resolve(arq)), StandardCharsets.UTF_8)).getAsJsonObject();
        return o;
    }

    // ---------------- Fábrica de chefes: quarta às 6h completa a fila até 8 (e tenta todo dia às 6h se não conseguiu) ----------------
    static volatile boolean fabricaRodando = false;
    static void talvezRodarFabrica(boolean forcar) {
        java.time.ZonedDateTime agora = java.time.ZonedDateTime.now(BR);
        String hoje = agora.toLocalDate().toString();
        boolean quarta = agora.getDayOfWeek() == java.time.DayOfWeek.WEDNESDAY;
        if (!forcar && (agora.getHour() < 6 || hoje.equals(b.fabricaUltima) || !(quarta || b.fabricaPendente || arquivosChefes().isEmpty()))) return;
        if (fabricaRodando) return;
        fabricaRodando = true; b.fabricaUltima = hoje; sujo = true;
        new Thread(() -> {
            try {
                java.nio.file.Path tmp = java.nio.file.Paths.get("/tmp/fabrica");
                Files.createDirectories(tmp.resolve("db"));
                java.nio.file.Path dbOrig = java.nio.file.Paths.get(Optional.ofNullable(System.getenv("XMAGE")).orElse("/xmage")).resolve("db");
                try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(dbOrig, "cards*")) { for (java.nio.file.Path f : ds) Files.copy(f, tmp.resolve("db").resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
                String xm = Optional.ofNullable(System.getenv("XMAGE")).orElse("/xmage");
                ProcessBuilder pb = new ProcessBuilder("java", "-Xmx900m", "-Dfile.encoding=UTF-8",
                        "-cp", "/srv/classes-fabrica:" + xm + "/lib/*:" + xm + "/plugins/mage-deck-constructed-1.4.63.jar",
                        "Fabrica", pastaChefes().toString(), DADOS.toAbsolutePath().getParent().resolve("fabrica.json").toString(), "8");
                pb.directory(tmp.toFile()); pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(DADOS.toAbsolutePath().getParent().resolve("fabrica.log").toFile()));
                int cod = pb.start().waitFor();
                int fila = arquivosChefes().size();
                b.fabricaPendente = fila < 8; sujo = true;
                System.out.println("Fábrica de chefes terminou (código " + cod + "): fila com " + fila);
            } catch (Exception e) { e.printStackTrace(); b.fabricaPendente = true; sujo = true; }
            finally { fabricaRodando = false; }
        }, "fabrica").start();
    }
    static Object chefe(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        JsonObject ch = chefeDoCiclo();
        Map<String, Object> r = new LinkedHashMap<>();
        if (ch == null) { r.put("vazio", true); return r; }
        String ciclo = cicloAtual();
        r.put("ciclo", ciclo);
        r.put("fila", arquivosChefes().size());
        r.put("fim", inicioCiclo().plusDays(1).toInstant().toEpochMilli());
        r.put("nome", str(ch, "nome"));
        r.put("comandante", ch.get("comandante"));
        if (ch.has("descricao")) r.put("descricao", str(ch, "descricao"));
        r.put("deck", str(ch, "deck"));
        r.put("lacaios", ch.has("lacaios") ? ch.get("lacaios") : new JsonArray());
        List<Map<String, Object>> der = new ArrayList<>();
        boolean euVenci = false;
        for (Map<String, Object> d : b.derrotaramChefe.getOrDefault(ciclo, Collections.emptyList())) {
            Usuario o = porCodigo(String.valueOf(d.get("codigo")));
            if (o == null) continue;
            if (o == u) euVenci = true;
            Map<String, Object> x = resumo(o); x.put("quando", d.get("quando")); der.add(x);
        }
        r.put("derrotaram", der); r.put("euVenci", euVenci);
        List<Map<String, Object>> rk = new ArrayList<>();
        for (Map<String, Object> d : ranking(b.derrotaramChefe.getOrDefault(ciclo, Collections.emptyList()))) {
            Usuario o = porCodigo(String.valueOf(d.get("codigo"))); if (o == null) continue;
            Map<String, Object> x = resumo(o); x.put("turnos", d.get("turnos")); x.put("vida", d.get("vida")); x.put("quando", d.get("quando")); x.put("eu", o == u);
            rk.add(x);
        }
        r.put("ranking", rk);
        return r;
    }

    // ---------------- Galeria de chefes (troféus) ----------------
    static final Map<String, JsonObject> infoChefeArq = new HashMap<>();   // arquivo -> {nome, comandante}
    static JsonObject infoChefe(String arq) {
        return infoChefeArq.computeIfAbsent(arq, a -> {
            try {
                JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(pastaChefes().resolve("usados").resolve(a)), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject r = new JsonObject(); r.add("nome", o.get("nome")); r.add("comandante", o.get("comandante")); return r;
            } catch (Exception e) { return null; }
        });
    }
    /** Um item por chefe que já apareceu (mais recente primeiro): venceu? marca, posição e medalha. */
    static List<Map<String, Object>> trofeus(Usuario u) {
        List<String> ciclos = new ArrayList<>(b.chefePorCiclo.keySet());
        ciclos.sort(Comparator.reverseOrder());
        String atual = cicloAtual();
        List<Map<String, Object>> l = new ArrayList<>();
        for (String ciclo : ciclos) {
            JsonObject info = infoChefe(b.chefePorCiclo.get(ciclo));
            if (info == null) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("ciclo", ciclo); t.put("nome", str(info, "nome")); t.put("comandante", info.get("comandante"));
            t.put("hoje", ciclo.equals(atual));
            List<Map<String, Object>> rk = ranking(b.derrotaramChefe.getOrDefault(ciclo, Collections.emptyList()));
            t.put("total", rk.size());
            for (int i = 0; i < rk.size(); i++) {
                Map<String, Object> d = rk.get(i);
                if (!u.codigo.equals(d.get("codigo"))) continue;
                t.put("venceu", true); t.put("turnos", d.get("turnos")); t.put("vida", d.get("vida")); t.put("quando", d.get("quando")); t.put("posicao", i + 1);
                boolean fechado = ciclo.compareTo(atual) < 0;
                t.put("medalha", fechado && Boolean.TRUE.equals(d.get("primeiro")) ? "ouro" : fechado && i < 3 ? "prata" : "bronze");
            }
            l.add(t);
        }
        return l;
    }

    static boolean venceuPartida(JsonObject p) { return p.has("venceu") && p.get("venceu").getAsBoolean(); }
    static int numero(Object o, int padrao) { return o instanceof Number ? ((Number) o).intValue() : padrao; }
    /** a nova marca (turnos, vida) é melhor que a guardada? menos turnos, depois mais vida */
    static boolean melhor(int turnos, int vida, Map<String, Object> d) {
        int t = numero(d.get("turnos"), 9999), v = numero(d.get("vida"), 0);
        return turnos < t || (turnos == t && vida > v);
    }
    /** ordem do ranking: menos turnos, mais vida, quem venceu primeiro */
    static List<Map<String, Object>> ranking(List<Map<String, Object>> der) {
        List<Map<String, Object>> l = new ArrayList<>(der);
        l.sort(Comparator.<Map<String, Object>>comparingInt(d -> numero(d.get("turnos"), 9999))
                .thenComparing(Comparator.<Map<String, Object>>comparingInt(d -> numero(d.get("vida"), 0)).reversed())
                .thenComparingLong(d -> d.get("quando") instanceof Number ? ((Number) d.get("quando")).longValue() : Long.MAX_VALUE));
        return l;
    }
    /** Às 20h o ciclo fecha: o 1º lugar ganha +100 XP (uma vez por ciclo). */
    static void premiarCiclosFechados() {
        String atual = cicloAtual();
        for (Map.Entry<String, List<Map<String, Object>>> e : b.derrotaramChefe.entrySet()) {
            String ciclo = e.getKey();
            if (ciclo.compareTo(atual) >= 0 || b.ciclosPremiados.contains(ciclo) || e.getValue().isEmpty()) continue;
            Map<String, Object> top = ranking(e.getValue()).get(0);
            top.put("primeiro", true);
            Usuario u = porCodigo(String.valueOf(top.get("codigo")));
            if (u != null) { u.xp += 100; soma(u.recordes, "chefesPrimeiro", 1); }
            b.ciclosPremiados.add(ciclo); sujo = true;
            System.out.println("Chefe " + ciclo + ": 1º lugar " + (u == null ? "?" : u.nome) + " ganhou +100 XP");
        }
    }

    static void soma(JsonObject o, String k, int n) { o.addProperty(k, num(o, k) + n); }
    static int num(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; }

    static Object acao(JsonObject c) {
        Usuario u = usuario(c);
        String acao = texto(c, "acao", 20, "");
        switch (acao) {
            case "pedir": {
                Usuario o = acharPorCodigoOuNome(texto(c, "codigo", 40, ""));
                if (o == null) throw new IllegalArgumentException("Não achei ninguém com esse código ou nome.");
                if (o == u) throw new IllegalArgumentException("Esse é você.");
                if (b.amizades.contains(par(u, o))) throw new IllegalArgumentException(o.nome + " já é seu amigo.");
                if (b.pedidos.remove(o.codigo + ">" + u.codigo)) b.amizades.add(par(u, o));   // ele já tinha pedido: viram amigos
                else b.pedidos.add(u.codigo + ">" + o.codigo);
                break;
            }
            case "aceitar": case "recusar": {
                String de = texto(c, "codigo", 10, "");
                if (b.pedidos.remove(de + ">" + u.codigo) && acao.equals("aceitar")) {
                    Usuario o = porCodigo(de);
                    if (o != null) b.amizades.add(par(u, o));
                }
                break;
            }
            case "remover": {
                Usuario o = porCodigo(texto(c, "codigo", 10, ""));
                if (o != null) { b.amizades.remove(par(u, o)); b.pedidos.remove(u.codigo + ">" + o.codigo); }
                break;
            }
            case "convidar": {
                Usuario o = porCodigo(texto(c, "codigo", 10, ""));
                if (o == null || !b.amizades.contains(par(u, o))) throw new IllegalArgumentException("Só dá para convidar amigos.");
                if (!c.has("sala") || !c.get("sala").isJsonObject()) throw new IllegalArgumentException("Entre numa sala antes de convidar.");
                b.convites.removeIf(v -> v.de.equals(u.codigo) && v.para.equals(o.codigo));   // um convite por amigo
                Convite v = new Convite();
                v.id = UUID.randomUUID().toString(); v.de = u.codigo; v.para = o.codigo; v.sala = c.getAsJsonObject("sala"); v.quando = System.currentTimeMillis();
                b.convites.add(v);
                break;
            }
            case "descartar": {
                String id = texto(c, "convite", 40, "");
                b.convites.removeIf(v -> v.id.equals(id) && v.para.equals(u.codigo));
                break;
            }
            default: throw new IllegalArgumentException("ação desconhecida");
        }
        sujo = true;
        return foto(u);
    }

    /** Tudo que a Mesa de u precisa mostrar. */
    static Map<String, Object> foto(Usuario u) {
        long agora = System.currentTimeMillis();
        b.convites.removeIf(v -> agora - v.quando > CONVITE_MS);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("codigo", u.codigo);
        r.put("nivel", nivelInfo(u));
        r.put("missoes", missoesJson(u));
        if (b.liderChefe != null && cicloAtual().equals(b.liderChefe.get("ciclo"))) r.put("liderChefe", b.liderChefe);   // aviso de novo 1º lugar
        List<Map<String, Object>> amigos = new ArrayList<>(), pedidos = new ArrayList<>(), online = new ArrayList<>(), convites = new ArrayList<>();
        List<String> enviados = new ArrayList<>();
        for (Usuario o : b.porId.values()) {
            if (o == u) continue;
            boolean on = agora - o.visto < ONLINE_MS;
            if (b.amizades.contains(par(u, o))) {
                Map<String, Object> m = resumo(o);
                m.put("online", on); m.put("visto", o.visto);
                if (on) { m.put("status", o.status); if (o.sala != null) m.put("sala", o.sala); }
                amigos.add(m);
            } else {
                if (b.pedidos.contains(o.codigo + ">" + u.codigo)) pedidos.add(resumo(o));
                if (b.pedidos.contains(u.codigo + ">" + o.codigo)) enviados.add(o.codigo);
                if (on) online.add(resumo(o));
            }
        }
        amigos.sort((x, y) -> {   // online primeiro, depois por nome
            int on = Boolean.compare((Boolean) y.get("online"), (Boolean) x.get("online"));
            return on != 0 ? on : String.valueOf(x.get("nome")).compareToIgnoreCase(String.valueOf(y.get("nome")));
        });
        for (Convite v : b.convites) if (v.para.equals(u.codigo)) {
            Usuario de = porCodigo(v.de);
            if (de == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id); m.put("de", resumo(de)); m.put("sala", v.sala); m.put("quando", v.quando);
            convites.add(m);
        }
        r.put("amigos", amigos); r.put("pedidos", pedidos); r.put("enviados", enviados); r.put("convites", convites); r.put("online", online);
        return r;
    }

    // ---------------- ajudantes ----------------
    static Usuario usuario(JsonObject c) {
        String id = texto(c, "id", 64, "");
        if (id.length() < 16) throw new IllegalArgumentException("id inválido");
        Usuario u = b.porId.get(id);
        if (u == null) { u = new Usuario(); u.id = id; u.codigo = codigoDe(id); b.porId.put(id, u); sujo = true; }
        return u;
    }
    static Usuario porCodigo(String cod) { for (Usuario u : b.porId.values()) if (u.codigo.equalsIgnoreCase(cod)) return u; return null; }
    static Usuario acharPorCodigoOuNome(String s) {
        s = s.trim().replace("#", "");
        Usuario u = porCodigo(s);
        if (u != null) return u;
        Usuario achou = null;
        for (Usuario o : b.porId.values()) if (o.nome.equalsIgnoreCase(s)) { if (achou != null) throw new IllegalArgumentException("Tem mais de uma pessoa com esse nome: use o código."); achou = o; }
        return achou;
    }
    static String par(Usuario x, Usuario y) { return x.codigo.compareTo(y.codigo) < 0 ? x.codigo + "|" + y.codigo : y.codigo + "|" + x.codigo; }
    static Map<String, Object> resumo(Usuario o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", o.codigo); m.put("nome", o.nome); m.put("avatar", o.avatar); m.put("nivel", nivelInfo(o).get("nivel"));
        return m;
    }
    static String codigoDe(String id) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // sem 0/O, 1/I
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(abc.charAt((h[i] & 0xff) % abc.length()));
            return s.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String texto(JsonObject c, String k, int max, String padrao) {
        if (!c.has(k) || c.get(k).isJsonNull()) return padrao;
        String s = c.get(k).getAsString();
        return s.length() > max ? s.substring(0, max) : s;
    }
    static void gravar() {
        if (!sujo) return;
        try {
            String js;
            synchronized (b) { js = GSON.toJson(b); sujo = false; }
            Path tmp = DADOS.resolveSibling(DADOS.getFileName() + ".tmp");
            Files.write(tmp, js.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, DADOS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { e.printStackTrace(); }
    }
    static byte[] ler(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); if (o.size() > 200_000) throw new IllegalArgumentException("pedido grande demais"); }
        return o.toByteArray();
    }
    static void responder(HttpExchange ex, int cod, String js) throws IOException {
        byte[] d = js.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(cod, d.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(d); }
    }
}
