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
        String playmat = ""; long playmatVer = 0;
        Map<String, Long> conquistas = new LinkedHashMap<>();   // id -> quando desbloqueou
        long ouro = 0; int boosters = 0; boolean boasVindas = false;   // economia: Ouro, boosters fechados, saldo inicial já dado
        Map<String, Integer> colecao = new LinkedHashMap<>();        // "nome|set|num" -> quantidade   // playmat: "" (padrão), nome de um modelo pronto ou "foto" (imagem em playmats/<código>.jpg)
        int nivelPremiado = 0; Set<String> desbloqueios = new LinkedHashSet<>();   // trilha de níveis: até que nível já recebeu prêmio; "pm:brasas", "moldura:10", "costas", ...
        String moldura = "", titulo = ""; int escolhaRara = 0, escolhaMitica = 0;   // moldura equipada (""/10/20/...), título, cartas à escolha pendentes
        String bazarDia = ""; List<JsonObject> bazarCartas = new ArrayList<>(); Set<Integer> bazarVendidas = new HashSet<>(); int bazarRenovou = 0;   // o Bazar: mercadoria do dia
        Set<String> desejos = new LinkedHashSet<>();   // lista de desejos (nomes de cartas)
        JsonObject campanha;   // {cor, mundo, batalha, comandante:{nome,set,num}, deck:[{nome,set,num}]} (null = ainda não escolheu)
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
        Map<String, String> deckDiaPorCiclo = new HashMap<>();   // ciclo -> arquivo do Deck do dia (em deckdia/usados)
        Map<String, List<String>> venceramDeckDia = new HashMap<>();   // ciclo -> códigos de quem venceu com ele
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
        srv.createContext("/deckDia", ex -> tratar(ex, Amigos::deckDia));
        srv.createContext("/mao", ex -> tratar(ex, Amigos::mao));
        srv.createContext("/times", ex -> tratar(ex, Amigos::times));
        srv.createContext("/playmat", ex -> tratar(ex, Amigos::playmat));
        srv.createContext("/loja", ex -> tratar(ex, Amigos::loja));
        srv.createContext("/campanha", ex -> tratar(ex, Amigos::campanha));
        srv.createContext("/nivel", ex -> tratar(ex, Amigos::nivel));
        srv.createContext("/bazar", ex -> tratar(ex, Amigos::bazar));
        srv.createContext("/troca", ex -> tratar(ex, Amigos::troca));
        srv.createContext("/playmats", ex -> tratar(ex, Amigos::playmats));
        srv.createContext("/playmatImg", Amigos::playmatImg);
        srv.createContext("/decksChefe", ex -> tratar(ex, c -> {   // todos os decks de chefe que a pessoa já ganhou (a Mesa baixa os que faltam)
            Usuario u = usuario(c); List<Map<String, Object>> l = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : b.derrotaramChefe.entrySet()) {
                if (e.getValue().stream().noneMatch(d -> u.codigo.equals(d.get("codigo")))) continue;
                String arq = b.chefePorCiclo.get(e.getKey()); if (arq == null) continue;
                try {
                    JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(pastaChefes().resolve("usados").resolve(arq)), StandardCharsets.UTF_8)).getAsJsonObject();
                    Map<String, Object> x = new LinkedHashMap<>(); x.put("nome", str(o, "nome")); x.put("deck", str(o, "deck")); l.add(x);
                } catch (Exception ignored) { }
            }
            return Collections.singletonMap("decks", l);
        }));
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
        if (o.conquistas == null) o.conquistas = new LinkedHashMap<>();
        r.put("conquistas", conquistasJson(o));
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
        boolean primeiraVitoriaDoDia = venceu && !hoje().equals(u.diaVitoria);   // antes do XP marcar o dia
        List<Map<String, Object>> ouroGanhos = new ArrayList<>();
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
            String cicloDeck = str(p, "deckDia");
            if (venceu && !cicloDeck.isEmpty() && cicloDeck.equals(cicloAtual())) {
                List<String> v = b.venceramDeckDia.computeIfAbsent(cicloDeck, k -> new ArrayList<>());
                if (!v.contains(u.codigo)) {
                    v.add(u.codigo);
                    ganho.add("Venceu com o Deck do dia", null, 150, false, false);
                    try {   // prêmio: o próprio deck
                        JsonObject dd = deckDoCiclo();
                        if (dd != null) { JsonObject dg = new JsonObject(); dg.add("nome", dd.get("nome")); dg.add("deck", dd.get("deck")); dg.add("comandante", dd.get("comandante")); dg.addProperty("tipo", "deckDia"); deckGanho[0] = dg; }
                    } catch (Exception ignored) { }
                    soma(u.recordes, "decksDoDia", 1);
                }
            }
            // Ouro: base redonda; criaturas e cartas dão valores "quebrados"
            {
                boolean ehChefe = !str(p, "chefe").isEmpty(), ehDeckDia = !str(p, "deckDia").isEmpty();
                int campN = p.has("campanha") ? p.get("campanha").getAsInt() : 0;
                ganhoOuro(ouroGanhos, "Jogar a partida", null, 60, false);
                if (venceu && campN > 0) ganhoOuro(ouroGanhos, "Vitória na campanha (batalha " + campN + ")", null, batalhaTripla(campN) ? 500 : 250, false);
                else if (venceu) ganhoOuro(ouroGanhos, ehChefe ? "Vitória contra o Chefe do dia" : ehDeckDia ? "Vitória com o Deck do dia" : "Vitória", null, ehChefe ? 200 : 150, false);
                if (criaturasReais > 0) ganhoOuro(ouroGanhos, "Criaturas abatidas", criaturasReais, Math.min(35, criaturasReais), criaturasReais >= 35);
                if (comprasReais > 0) ganhoOuro(ouroGanhos, "Cartas compradas", comprasReais, Math.min(20, comprasReais), comprasReais >= 20);
                if (primeiraVitoriaDoDia) ganhoOuro(ouroGanhos, "Primeira vitória do dia", null, 112, false);
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
                if (u.progresso.getOrDefault((String) m[4], 0) >= (Integer) m[2]) { u.missoesFeitas.add((String) m[0]); ganho.add("Missão: " + m[1], null, (Integer) m[3], false, false);
                    ganhoOuro(ouroGanhos, "Missão: " + m[1], null, ouroMissao((String) m[0]), false); }
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
        for (String k : new String[]{"maxDanoTurno", "maxCriaturas", "maxVida", "maxElimTurno"})
            if (p.has(k) && p.get(k).getAsInt() > num(rc, k)) rc.addProperty(k, p.get(k).getAsInt());
        if (venceu && p.has("time") && p.get("time").getAsBoolean()) soma(rc, "vitoriasTime", 1);
        if (u.conquistas == null) u.conquistas = new LinkedHashMap<>();
        List<Map<String, Object>> conqNovas = conferirConquistas(u);
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
        List<Map<String, Object>> premios = premiarNiveis(u);
        if (!premios.isEmpty()) r.put("premiosNivel", premios);
        r.put("missoes", missoesJson(u));
        if (!conqNovas.isEmpty()) r.put("conquistasNovas", conqNovas);
        // campanha: vitória libera a próxima batalha; a primeira vitória em cada batalha dá 1 booster
        if (p.has("campanha") && u.campanha != null) {
            int n = p.get("campanha").getAsInt();
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("batalha", n); cr.put("venceu", venceu);
            if (venceu) {
                JsonArray v = u.campanha.has("vencidas") ? u.campanha.getAsJsonArray("vencidas") : new JsonArray();
                boolean primeira = true; for (JsonElement e : v) if (e.getAsInt() == n) primeira = false;
                if (primeira) { v.add(n); u.campanha.add("vencidas", v); u.boosters++; cr.put("booster", true); }
                int atual = u.campanha.get("batalha").getAsInt();
                if (n == atual && atual <= 15) { u.campanha.addProperty("batalha", atual + 1); cr.put("liberou", atual + 1); }
                if (n == 15 && primeira) {   // venceu o chefe do mundo pela primeira vez: mundo concluído
                    soma(u.recordes, "mundosConcluidos", 1); cr.put("mundoConcluido", 1);
                    List<Map<String, Object>> novas = conferirConquistas(u);
                    if (!novas.isEmpty()) { List<Map<String, Object>> todas = new ArrayList<>(conqNovas); todas.addAll(novas); r.put("conquistasNovas", todas); }
                }
                sujo = true;
            }
            cr.put("estado", u.campanha);
            r.put("campanha", cr);
        }
        {   // Ouro da partida
            darBoasVindas(u);
            int tot = ouroGanhos.stream().mapToInt(g -> (Integer) g.get("ouro")).sum();
            u.ouro += tot;
            Map<String, Object> o = new LinkedHashMap<>(); o.put("ganhos", ouroGanhos); o.put("total", tot); o.put("saldo", u.ouro);
            r.put("ouro", o);
        }
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
    static synchronized JsonObject chefeDoCiclo() throws IOException { return doCiclo(pastaChefes(), b.chefePorCiclo); }
    static java.nio.file.Path pastaDeckDia() { return DADOS.toAbsolutePath().getParent().resolve("deckdia"); }
    static List<java.nio.file.Path> arquivos(java.nio.file.Path d) {
        List<java.nio.file.Path> l = new ArrayList<>();
        try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(d, "*.json")) { for (java.nio.file.Path f : ds) l.add(f); } catch (Exception ignored) { }
        return l;
    }
    static synchronized JsonObject deckDoCiclo() throws IOException { Files.createDirectories(pastaDeckDia()); return doCiclo(pastaDeckDia(), b.deckDiaPorCiclo); }
    /** Fila de uma pasta: cada ciclo pega o arquivo mais antigo e o move para usados/ (fila vazia: repete o usado há mais tempo). */
    static synchronized JsonObject doCiclo(java.nio.file.Path pasta, Map<String, String> porCiclo) throws IOException {
        String ciclo = cicloAtual();
        java.nio.file.Path usados = pasta.resolve("usados");
        Files.createDirectories(usados);
        String arq = porCiclo.get(ciclo);
        if (arq == null || !Files.exists(usados.resolve(arq))) {
            List<java.nio.file.Path> fila = arquivos(pasta);
            fila.sort(Comparator.comparingLong(f -> { try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0L; } }));
            if (!fila.isEmpty()) {
                java.nio.file.Path f = fila.get(0);
                arq = f.getFileName().toString();
                Files.move(f, usados.resolve(arq), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Map<String, String> ultimoUso = new HashMap<>();
                for (Map.Entry<String, String> e : porCiclo.entrySet()) ultimoUso.merge(e.getValue(), e.getKey(), (x, y) -> x.compareTo(y) > 0 ? x : y);
                List<String> todos = new ArrayList<>();
                try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(usados, "*.json")) { for (java.nio.file.Path f : ds) todos.add(f.getFileName().toString()); }
                if (todos.isEmpty()) return null;
                todos.sort(Comparator.comparing(n -> ultimoUso.getOrDefault(n, "")));
                arq = todos.get(0);
            }
            porCiclo.put(ciclo, arq); sujo = true;
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
        if (!forcar && (agora.getHour() < 6 || hoje.equals(b.fabricaUltima) || !(quarta || b.fabricaPendente || arquivosChefes().isEmpty() || arquivos(pastaDeckDia()).size() < 3))) return;
        if (fabricaRodando) return;
        fabricaRodando = true; b.fabricaUltima = hoje; sujo = true;
        new Thread(() -> {
            try {
                java.nio.file.Path tmp = java.nio.file.Paths.get("/tmp/fabrica");
                Files.createDirectories(tmp.resolve("db"));
                java.nio.file.Path dbOrig = java.nio.file.Paths.get(Optional.ofNullable(System.getenv("XMAGE")).orElse("/xmage")).resolve("db");
                try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(dbOrig, "cards*")) { for (java.nio.file.Path f : ds) Files.copy(f, tmp.resolve("db").resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
                String xm = Optional.ofNullable(System.getenv("XMAGE")).orElse("/xmage");
                int cod = 0;
                if (quarta || b.fabricaPendente || arquivosChefes().isEmpty() || forcar) {
                    ProcessBuilder pb = new ProcessBuilder("java", "-Xmx900m", "-Dfile.encoding=UTF-8",
                            "-cp", "/srv/classes-fabrica:" + xm + "/lib/*:" + xm + "/plugins/mage-deck-constructed-1.4.63.jar",
                            "Fabrica", pastaChefes().toString(), DADOS.toAbsolutePath().getParent().resolve("fabrica.json").toString(), "8");
                    pb.directory(tmp.toFile()); pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(DADOS.toAbsolutePath().getParent().resolve("fabrica.log").toFile()));
                    cod = pb.start().waitFor();
                }
                int fila = arquivosChefes().size();
                b.fabricaPendente = fila < 8; sujo = true;
                System.out.println("Fábrica de chefes terminou (código " + cod + "): fila com " + fila);
                if (arquivos(pastaDeckDia()).size() < 3) {   // Deck do dia: completa até 8 quando sobra pouco
                    Files.createDirectories(pastaDeckDia());
                    ProcessBuilder pd = new ProcessBuilder("java", "-Xmx900m", "-Dfile.encoding=UTF-8",
                            "-cp", "/srv/classes-fabrica:" + xm + "/lib/*:" + xm + "/plugins/mage-deck-constructed-1.4.63.jar",
                            "Fabrica", pastaDeckDia().toString(), DADOS.toAbsolutePath().getParent().resolve("fabrica-deckdia.json").toString(), "8", "deckdia");
                    pd.directory(tmp.toFile()); pd.redirectErrorStream(true);
                    pd.redirectOutput(ProcessBuilder.Redirect.appendTo(DADOS.toAbsolutePath().getParent().resolve("fabrica-deckdia.log").toFile()));
                    int c2 = pd.start().waitFor();
                    System.out.println("Fábrica do Deck do dia terminou (código " + c2 + "): fila com " + arquivos(pastaDeckDia()).size());
                }
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
        if (ch.has("tipo")) r.put("tipo", str(ch, "tipo"));
        if (ch.has("estrategia")) r.put("estrategia", str(ch, "estrategia"));
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

    // ---------------- Conquistas ----------------
    // {id, nome, descrição, ícone, moldura (bronze/prata/ouro/lendaria), recorde que conta, meta, "max" (recorde máximo) ou "min" (menor é melhor)}
    static final Object[][] CONQ = {
        {"p1", "A primeira de muitas", "Jogue a sua primeira partida", "cartas", "bronze", "partidas", 1, "max"},
        {"p10", "Pegando o jeito", "Jogue 10 partidas", "cartas", "bronze", "partidas", 10, "max"},
        {"p25", "Jogador experiente", "Jogue 25 partidas", "cartas", "prata", "partidas", 25, "max"},
        {"p50", "Veterano da mesa", "Jogue 50 partidas", "cartas", "ouro", "partidas", 50, "max"},
        {"p100", "Magic: The Gathering", "Jogue 100 partidas", "cartas", "lendaria", "partidas", 100, "max"},
        {"v1", "Primeira vitória", "Vença uma partida", "coroa", "bronze", "vitorias", 1, "max"},
        {"v10", "Vencedor nato", "Vença 10 partidas", "coroa", "prata", "vitorias", 10, "max"},
        {"v50", "Lenda da mesa", "Vença 50 partidas", "coroa", "ouro", "vitorias", 50, "max"},
        {"d50", "Golpe certeiro", "Cause 50 de dano num só turno", "espada", "prata", "maxDanoTurno", 50, "max"},
        {"d100", "Devastador", "Cause 100 de dano num só turno", "espada", "ouro", "maxDanoTurno", 100, "max"},
        {"d1000", "Mil de uma vez", "Cause 1000 de dano num só turno", "espada", "lendaria", "maxDanoTurno", 1000, "max"},
        {"c20", "Exército", "Tenha 20 criaturas em campo ao mesmo tempo", "escudo", "ouro", "maxCriaturas", 20, "max"},
        {"vida100", "Imortal", "Chegue a 100 de vida numa partida", "coracao", "ouro", "maxVida", 100, "max"},
        {"rapida", "Blitz", "Vença uma partida antes do turno 10", "ampulheta", "ouro", "vitoriaMaisRapida", 9, "min"},
        {"elim2", "Dois de uma vez", "Elimine dois jogadores no mesmo turno", "caveira", "ouro", "maxElimTurno", 2, "max"},
        {"chefe", "Caçador de chefes", "Derrote o Chefe do dia", "chifres", "prata", "chefesDerrotados", 1, "max"},
        {"deckdia", "Explorador", "Vença com o Deck do dia", "livro", "prata", "decksDoDia", 1, "max"},
        {"time", "Dupla dinâmica", "Vença uma partida 2 contra 2", "escudos", "prata", "vitoriasTime", 1, "max"},
        {"mundo1", "Libertador das Terras de Origem", "Derrote o Krenko e conclua o Mundo 1 da campanha", "coroa", "ouro", "mundosConcluidos", 1, "max"},
    };
    static List<Map<String, Object>> conquistasJson(Usuario u) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Object[] c : CONQ) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c[0]); x.put("nome", c[1]); x.put("desc", c[2]); x.put("icone", c[3]); x.put("moldura", c[4]); x.put("meta", c[6]);
            int v = num(u.recordes, (String) c[5]);
            x.put("progresso", "min".equals(c[7]) ? (v > 0 && v <= (Integer) c[6] ? 1 : 0) : Math.min(v, (Integer) c[6]));
            if ("min".equals(c[7])) x.put("meta", 1);
            Long q = u.conquistas.get((String) c[0]);
            if (q != null) x.put("quando", q);
            l.add(x);
        }
        return l;
    }
    /** Confere os recordes e desbloqueia o que faltava; devolve as novas. */
    static List<Map<String, Object>> conferirConquistas(Usuario u) {
        List<Map<String, Object>> novas = new ArrayList<>();
        for (Object[] c : CONQ) {
            if (u.conquistas.containsKey((String) c[0])) continue;
            int v = num(u.recordes, (String) c[5]), meta = (Integer) c[6];
            boolean ok = "min".equals(c[7]) ? (v > 0 && v <= meta) : v >= meta;
            if (!ok) continue;
            u.conquistas.put((String) c[0], System.currentTimeMillis());
            Map<String, Object> x = new LinkedHashMap<>(); x.put("id", c[0]); x.put("nome", c[1]); x.put("desc", c[2]); x.put("icone", c[3]); x.put("moldura", c[4]);
            novas.add(x);
        }
        return novas;
    }

    // ---------------- Economia: Ouro, boosters e coleção ----------------
    static final int PRECO_BOOSTER = 450;
    static void ganhoOuro(List<Map<String, Object>> l, String motivo, Integer qtd, int ouro, boolean max) {
        if (ouro <= 0) return;
        Map<String, Object> g = new LinkedHashMap<>(); g.put("motivo", motivo); if (qtd != null) g.put("qtd", qtd); g.put("ouro", ouro); if (max) g.put("max", true);
        l.add(g);
    }
    /** Missão diária: 50 a 75 de Ouro (sempre o mesmo valor para a mesma missão). */
    static int ouroMissao(String id) { return 50 + Math.floorMod(id.hashCode(), 26); }
    /** Saldo inicial: 500 de Ouro e 1 booster, uma vez só. */
    static void darBoasVindas(Usuario u) {
        if (u.colecao == null) u.colecao = new LinkedHashMap<>();
        if (u.boasVindas) return;
        u.boasVindas = true; u.ouro += 500; u.boosters += 1; sujo = true;
    }
    // pool de cartas de cada mundo (pools/mundoN.json): comum, incomum, rara, mitica, terreno
    static final Map<String, JsonObject> pools = new HashMap<>();
    static final Map<String, JsonObject> cartaPorChave = new HashMap<>();
    static JsonObject pool(String mundo) throws IOException {
        JsonObject p = pools.get(mundo);
        if (p == null) {
            java.nio.file.Path f = DADOS.toAbsolutePath().getParent().resolve("pools").resolve(mundo + ".json");
            p = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
            for (String r : new String[]{"comum", "incomum", "rara", "mitica", "terreno"})
                for (JsonElement e : p.getAsJsonArray(r)) { JsonObject c = e.getAsJsonObject();
                    String fx = r;
                    if (r.equals("terreno")) { String ra = str(c, "raridade"); fx = ra.equals("Mythic") ? "mitica" : ra.equals("Rare") ? "rara" : ra.equals("Uncommon") ? "incomum" : "comum"; }   // terreno mostra a raridade real
                    c.addProperty("faixa", fx); cartaPorChave.put(chaveCarta(c), c); }
            pools.put(mundo, p);
        }
        return p;
    }
    static String chaveCarta(JsonObject c) { return str(c, "nome") + "|" + str(c, "set") + "|" + str(c, "num"); }
    static final Random sorte = new Random();
    static JsonObject sortear(JsonObject pool, String faixa, Set<String> ja) {
        JsonArray a = pool.getAsJsonArray(faixa);
        for (int t = 0; t < 30; t++) { JsonObject c = a.get(sorte.nextInt(a.size())).getAsJsonObject(); if (ja.add(str(c, "nome"))) return c; }
        return a.get(sorte.nextInt(a.size())).getAsJsonObject();
    }
    /** Booster de 15, como no Magic: 10 comuns, 3 incomuns, 1 rara (1 em 6 vira mítica) e 1 terreno não básico (ou mais uma comum).
     *  Com 290 raras, uma rara específica (ex.: Krenko) sai em ~1 a cada 350 boosters; uma mítica específica, ~1 a cada 500. */
    static List<JsonObject> gerarBooster(String mundo) throws IOException {
        JsonObject p = pool(mundo); Set<String> ja = new HashSet<>(); List<JsonObject> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(sortear(p, "comum", ja));
        for (int i = 0; i < 3; i++) l.add(sortear(p, "incomum", ja));
        l.add(sortear(p, sorte.nextInt(6) == 0 && p.getAsJsonArray("mitica").size() > 0 ? "mitica" : "rara", ja));
        l.add(sorte.nextInt(10) < 3 && p.getAsJsonArray("terreno").size() > 0 ? sortear(p, "terreno", ja) : sortear(p, "comum", ja));   // 30%: terreno
        return l;
    }
    static Object loja(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        darBoasVindas(u);
        String acao = texto(c, "acao", 20, "saldo");
        Map<String, Object> r = new LinkedHashMap<>();
        switch (acao) {
            case "comprarBooster": {
                if (u.ouro < PRECO_BOOSTER) throw new IllegalArgumentException("Ouro insuficiente: o booster custa " + PRECO_BOOSTER + ".");
                u.ouro -= PRECO_BOOSTER; u.boosters++; sujo = true;
                break;
            }
            case "abrirBooster": {
                if (u.boosters <= 0) throw new IllegalArgumentException("Você não tem booster para abrir.");
                u.boosters--;
                List<JsonObject> cartas = gerarBooster("mundo1");
                JsonArray out = new JsonArray();
                for (JsonObject x : cartas) {
                    String k = chaveCarta(x); int q = u.colecao.merge(k, 1, Integer::sum);
                    JsonObject y = x.deepCopy(); y.addProperty("nova", q == 1); out.add(y);
                }
                sujo = true;
                r.put("cartas", out);
                break;
            }
            case "colecao": {
                pool("mundo1");
                List<Map<String, Object>> l = new ArrayList<>();
                for (Map.Entry<String, Integer> e : u.colecao.entrySet()) {
                    JsonObject x = cartaPorChave.get(e.getKey()); if (x == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String k : new String[]{"nome", "set", "num", "tipo", "cores", "faixa"}) m.put(k, str(x, k));
                    m.put("qtd", e.getValue()); l.add(m);
                }
                r.put("colecao", l);
                break;
            }
            default: break;
        }
        r.put("ouro", u.ouro); r.put("boosters", u.boosters); r.put("precoBooster", PRECO_BOOSTER);
        int total = 0; for (int q : u.colecao.values()) total += q;
        r.put("cartasNaColecao", total); r.put("cartasDiferentes", u.colecao.size());
        return r;
    }

    // ---------------- Campanha: escolha do deck inicial e progresso ----------------
    static java.nio.file.Path pastaCampanha() { return DADOS.toAbsolutePath().getParent().resolve("campanha"); }
    /** Lê um .dck: [comandante, cartas...] (básicos ficam de fora: são de graça). */
    static List<JsonObject> lerDck(java.nio.file.Path f) throws IOException {
        List<JsonObject> l = new ArrayList<>(); JsonObject cmd = null;
        java.util.regex.Pattern pt = java.util.regex.Pattern.compile("(SB: )?(\\d+) \\[(\\w+):(\\w+)\\] (.+)");
        for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            java.util.regex.Matcher m = pt.matcher(ln.trim()); if (!m.matches()) continue;
            String nome = m.group(5);
            if (Arrays.asList("Plains", "Island", "Swamp", "Mountain", "Forest").contains(nome)) continue;
            JsonObject c = new JsonObject(); c.addProperty("nome", nome); c.addProperty("set", m.group(3)); c.addProperty("num", m.group(4));
            if (m.group(1) != null) cmd = c; else l.add(c);
        }
        if (cmd != null) l.add(0, cmd);
        return l;
    }
    static boolean batalhaTripla(int n) { return n == 5 || n == 10 || n == 15; }   // 5 e 10: três bots; 15: o chefe do mundo (prêmio maior)
    static final String[][] BASICOS = {{"W", "Plains", "M15", "250"}, {"U", "Island", "M15", "254"}, {"B", "Swamp", "M15", "258"}, {"R", "Mountain", "M15", "262"}, {"G", "Forest", "M15", "266"}};
    /** O deck da campanha do jogador em .dck: comandante + cartas + básicos da cor até dar 100. */
    static String dckCampanha(JsonObject cp) {
        StringBuilder sb = new StringBuilder();
        JsonObject cmd = cp.getAsJsonObject("comandante");
        sb.append("SB: 1 [").append(str(cmd, "set")).append(":").append(str(cmd, "num")).append("] ").append(str(cmd, "nome")).append("\n");
        JsonArray d = cp.getAsJsonArray("deck");
        for (JsonElement e : d) { JsonObject x = e.getAsJsonObject(); sb.append("1 [").append(str(x, "set")).append(":").append(str(x, "num")).append("] ").append(str(x, "nome")).append("\n"); }
        int falta = 99 - d.size(); String cor = str(cp, "cor");
        List<String[]> bs = new ArrayList<>(); for (String[] b : BASICOS) if (cor.contains(b[0])) bs.add(b);
        if (bs.isEmpty()) bs.add(BASICOS[0]);
        for (int i = 0; i < bs.size() && falta > 0; i++) {   // divide os básicos entre as cores (a sobra vai para as primeiras)
            int q = falta / bs.size() + (i < falta % bs.size() ? 1 : 0); String[] b = bs.get(i);
            if (q > 0) sb.append(q).append(" [").append(b[2]).append(":").append(b[3]).append("] ").append(b[1]).append("\n");
        }
        return sb.toString();
    }
    static Object campanha(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        darBoasVindas(u);
        String acao = texto(c, "acao", 20, "estado");
        if (acao.equals("escolher")) {
            if (u.campanha != null) throw new IllegalArgumentException("Você já escolheu o seu deck inicial.");
            String cor = texto(c, "cor", 1, "");
            if (!"WUBRG".contains(cor) || cor.isEmpty()) throw new IllegalArgumentException("cor inválida");
            List<JsonObject> d = lerDck(pastaCampanha().resolve("mundo1").resolve("inicial-" + cor + ".dck"));
            JsonObject cp = new JsonObject(); cp.addProperty("cor", cor); cp.addProperty("mundo", 1); cp.addProperty("batalha", 1);
            cp.add("comandante", d.get(0)); cp.add("comandanteInicial", d.get(0)); cp.addProperty("corInicial", cor);
            JsonArray deck = new JsonArray();
            for (int i = 1; i < d.size(); i++) { deck.add(d.get(i)); u.colecao.merge(chaveCarta(d.get(i)), 1, Integer::sum); }
            cp.add("deck", deck); cp.addProperty("desde", System.currentTimeMillis());
            u.campanha = cp; sujo = true;
        }
        if (acao.equals("editor")) {   // tudo para o editor: coleção (com dados das cartas), deck atual, comandante
            if (u.campanha == null) throw new IllegalArgumentException("Escolha o seu deck inicial primeiro.");
            pool("mundo1");
            List<Map<String, Object>> col = new ArrayList<>();
            for (Map.Entry<String, Integer> e : u.colecao.entrySet()) {
                JsonObject x = cartaPorChave.get(e.getKey()); if (x == null) continue;
                String tipo = str(x, "tipo");
                if (tipo.contains("Basic") && tipo.contains("Land")) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : new String[]{"nome", "set", "num", "tipo", "faixa", "cores", "identidade", "lendaria"}) m.put(k, str(x, k));
                m.put("cmc", x.has("cmc") ? x.get("cmc").getAsDouble() : 0);
                m.put("qtd", e.getValue()); col.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("colecao", col); r.put("campanha", u.campanha);
            return r;
        }
        if (acao.equals("salvarDeck")) {   // valida: só cartas da coleção, uma de cada, nas cores do comandante, até 99
            if (u.campanha == null) throw new IllegalArgumentException("Escolha o seu deck inicial primeiro.");
            pool("mundo1");
            JsonArray d = c.getAsJsonArray("deck");
            if (d == null || d.size() > 99) throw new IllegalArgumentException("O deck pode ter no máximo 99 cartas além do comandante.");
            String cor = str(u.campanha, "cor");
            String nomeCmd = str(u.campanha.getAsJsonObject("comandante"), "nome");
            Set<String> nomes = new HashSet<>(); JsonArray novo = new JsonArray();
            for (JsonElement e : d) {
                JsonObject x = e.getAsJsonObject(); String k = chaveCarta(x);
                if (u.colecao.getOrDefault(k, 0) < 1) throw new IllegalArgumentException("Você não tem a carta " + str(x, "nome") + ".");
                JsonObject info = cartaPorChave.get(k);
                if (info == null) throw new IllegalArgumentException("Carta desconhecida: " + str(x, "nome"));
                if (!nomes.add(str(info, "nome"))) throw new IllegalArgumentException("Só pode uma cópia de " + str(info, "nome") + ".");
                if (str(info, "nome").equals(nomeCmd)) throw new IllegalArgumentException(nomeCmd + " já é o seu comandante.");
                for (char ch : str(info, "identidade").toCharArray()) if (cor.indexOf(ch) < 0) throw new IllegalArgumentException(str(info, "nome") + " não é das cores do seu comandante.");
                JsonObject y = new JsonObject(); y.addProperty("nome", str(info, "nome")); y.addProperty("set", str(info, "set")); y.addProperty("num", str(info, "num")); novo.add(y);
            }
            u.campanha.add("deck", novo); sujo = true;
        }
        if (acao.equals("trocarComandante")) {   // nova lendária da coleção (ou volta ao inicial); cartas fora das cores saem do deck
            if (u.campanha == null) throw new IllegalArgumentException("Escolha o seu deck inicial primeiro.");
            pool("mundo1");
            JsonObject cp = u.campanha;
            if (!cp.has("comandanteInicial")) { cp.add("comandanteInicial", cp.getAsJsonObject("comandante").deepCopy()); cp.addProperty("corInicial", str(cp, "cor")); }
            JsonObject ini = cp.getAsJsonObject("comandanteInicial");
            String k = texto(c, "nome", 200, "") + "|" + texto(c, "set", 10, "") + "|" + texto(c, "num", 10, "");
            JsonObject novo; String corNova;
            if (k.equals(chaveCarta(ini))) { novo = ini.deepCopy(); corNova = str(cp, "corInicial"); }
            else {
                JsonObject x = cartaPorChave.get(k);
                if (x == null || u.colecao.getOrDefault(k, 0) < 1) throw new IllegalArgumentException("Você não tem essa carta.");
                if (str(x, "lendaria").isEmpty() || !str(x, "tipo").contains("Creature")) throw new IllegalArgumentException("Só criaturas lendárias podem ser comandante.");
                corNova = str(x, "identidade");
                if (corNova.isEmpty()) throw new IllegalArgumentException("Comandantes incolores ainda não são aceitos na campanha.");
                novo = new JsonObject(); novo.addProperty("nome", str(x, "nome")); novo.addProperty("set", str(x, "set")); novo.addProperty("num", str(x, "num"));
            }
            // o deck que estava na tela (com mudanças não salvas) ou o salvo
            JsonArray base = c.has("deck") ? c.getAsJsonArray("deck") : cp.getAsJsonArray("deck");
            JsonArray fica = new JsonArray(); int saiu = 0; Set<String> vistos = new HashSet<>();
            for (JsonElement e : base) {
                JsonObject y = e.getAsJsonObject(); String ky = chaveCarta(y); JsonObject info = cartaPorChave.get(ky);
                boolean ok = info != null && u.colecao.getOrDefault(ky, 0) > 0 && !str(info, "nome").equals(str(novo, "nome")) && vistos.add(str(info, "nome"));
                if (ok) for (char ch : str(info, "identidade").toCharArray()) if (corNova.indexOf(ch) < 0) ok = false;
                if (ok) fica.add(y); else saiu++;
            }
            cp.add("comandante", novo); cp.addProperty("cor", corNova); cp.add("deck", fica); sujo = true;
            Map<String, Object> r = new LinkedHashMap<>(); r.put("campanha", cp); r.put("sairam", saiu); return r;
        }
        if (acao.equals("batalha")) {   // os decks para jogar a batalha n (só as já liberadas)
            if (u.campanha == null) throw new IllegalArgumentException("Escolha o seu deck inicial primeiro.");
            int n = c.has("n") ? c.get("n").getAsInt() : 0;
            if (n < 1 || n > 15 || n > u.campanha.get("batalha").getAsInt()) throw new IllegalArgumentException("Essa batalha ainda está bloqueada.");
            java.nio.file.Path m1 = pastaCampanha().resolve("mundo1");
            JsonObject ind = JsonParser.parseString(new String(Files.readAllBytes(pastaCampanha().resolve("mundo1.json")), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray bots = new JsonArray();
            for (JsonElement e : ind.getAsJsonArray("batalhas")) {
                JsonObject b = e.getAsJsonObject(); if (b.get("numero").getAsInt() != n) continue;
                for (JsonElement x : b.getAsJsonArray("bots")) {
                    JsonObject bo = x.getAsJsonObject(), o = new JsonObject();
                    o.addProperty("nome", str(bo, "nome"));
                    o.addProperty("deck", new String(Files.readAllBytes(m1.resolve(str(bo, "arquivo"))), StandardCharsets.UTF_8));
                    bots.add(o);
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("n", n); r.put("jogador", dckCampanha(u.campanha)); r.put("bots", bots);
            r.put("nivel", n == 15 ? 8 : n <= 7 ? 4 : 5);   // chefe do mundo: IA bem mais forte (o 10 do Chefe do dia é lento demais)
            r.put("chefe", n == 15);
            return r;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("campanha", u.campanha);
        return r;
    }

    // ---------------- Trilha de níveis: prêmio a cada nível, marcos nos níveis 10, 20, 30, 40 e 50 ----------------
    static final int NIVEL_MAX_TRILHA = 50;
    /** O que o nível n dá. Aplica em u e devolve a descrição. */
    static Map<String, Object> darPremioNivel(Usuario u, int n) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("nivel", n);
        List<String> itens = new ArrayList<>();
        switch (n) {
            case 10: u.ouro += 300; u.desbloqueios.add("pm:brasas"); u.desbloqueios.add("moldura:10");
                itens.add("Playmat Brasas de Akros"); itens.add("Moldura de Bronze"); itens.add("300 de Ouro"); break;
            case 20: u.desbloqueios.add("costas"); u.desbloqueios.add("moldura:20");
                itens.add("Costas de carta personalizadas"); itens.add("Moldura de Prata"); break;
            case 30: u.escolhaRara++; u.desbloqueios.add("moldura:30");
                itens.add("Uma rara à sua escolha"); itens.add("Moldura de Ouro"); break;
            case 40: u.desbloqueios.add("moldura:40"); u.desbloqueios.add("entrada:chamas"); u.boosters++;
                itens.add("Moldura em Chamas"); itens.add("Entrada de comandante em chamas"); itens.add("1 booster"); break;
            case 50: u.escolhaMitica++; u.desbloqueios.add("moldura:50"); u.desbloqueios.add("titulo:lenda"); u.titulo = "Lenda da Mesa";
                itens.add("Uma mítica à sua escolha"); itens.add("Título Lenda da Mesa"); itens.add("Moldura Lendária"); break;
            default:
                if (n % 3 == 0) { u.boosters++; itens.add("1 booster"); } else { u.ouro += 150; itens.add("150 de Ouro"); }
        }
        m.put("itens", itens); m.put("marco", n % 10 == 0);
        return m;
    }
    /** Entrega os prêmios de todos os níveis alcançados que ainda não foram entregues. */
    static List<Map<String, Object>> premiarNiveis(Usuario u) {
        if (u.desbloqueios == null) u.desbloqueios = new LinkedHashSet<>();
        if (u.moldura == null) u.moldura = ""; if (u.titulo == null) u.titulo = "";
        int nivel = Math.min(NIVEL_MAX_TRILHA, (Integer) nivelInfo(u).get("nivel"));
        List<Map<String, Object>> l = new ArrayList<>();
        if (u.nivelPremiado < 1) u.nivelPremiado = 1;   // o nível 1 são os 500 de Ouro das boas-vindas
        while (u.nivelPremiado < nivel) { u.nivelPremiado++; l.add(darPremioNivel(u, u.nivelPremiado)); }
        if (!l.isEmpty()) sujo = true;
        return l;
    }
    static Object nivel(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        darBoasVindas(u);
        List<Map<String, Object>> premios = premiarNiveis(u);
        String acao = texto(c, "acao", 20, "trilha");
        if (acao.equals("moldura")) {   // equipar uma moldura desbloqueada ("" = sem moldura)
            String k = texto(c, "moldura", 4, "");
            if (!k.isEmpty() && !u.desbloqueios.contains("moldura:" + k)) throw new IllegalArgumentException("Moldura ainda bloqueada.");
            u.moldura = k; sujo = true;
        }
        if (acao.equals("escolher")) {   // a rara (nível 30) ou mítica (nível 50) à escolha
            String faixa = texto(c, "faixa", 10, "");
            if (faixa.equals("rara") ? u.escolhaRara < 1 : faixa.equals("mitica") ? u.escolhaMitica < 1 : true) throw new IllegalArgumentException("Você não tem essa escolha disponível.");
            pool("mundo1");
            JsonObject x = cartaPorChave.get(texto(c, "nome", 200, "") + "|" + texto(c, "set", 10, "") + "|" + texto(c, "num", 10, ""));
            if (x == null || !faixa.equals(str(x, "faixa"))) throw new IllegalArgumentException("Escolha uma carta " + (faixa.equals("rara") ? "rara" : "mítica") + " do mundo.");
            if (faixa.equals("rara")) u.escolhaRara--; else u.escolhaMitica--;
            u.colecao.merge(chaveCarta(x), 1, Integer::sum); sujo = true;
        }
        if (acao.equals("cartas")) {   // as cartas que podem ser escolhidas (raras ou míticas do mundo)
            String faixa = texto(c, "faixa", 10, "rara");
            pool("mundo1");
            List<Map<String, Object>> l = new ArrayList<>();
            for (JsonObject x : cartaPorChave.values()) if (faixa.equals(str(x, "faixa")) && !str(x, "tipo").contains("Land")) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : new String[]{"nome", "set", "num", "cores", "tipo"}) m.put(k, str(x, k));
                m.put("tenho", u.colecao.getOrDefault(chaveCarta(x), 0)); l.add(m);
            }
            l.sort(Comparator.comparing(m -> (String) m.get("nome")));
            Map<String, Object> r = new LinkedHashMap<>(); r.put("cartas", l); return r;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nivel", nivelInfo(u)); r.put("nivelPremiado", u.nivelPremiado);
        r.put("desbloqueios", u.desbloqueios); r.put("moldura", u.moldura); r.put("titulo", u.titulo);
        r.put("escolhaRara", u.escolhaRara); r.put("escolhaMitica", u.escolhaMitica);
        r.put("ouro", u.ouro); r.put("boosters", u.boosters);
        if (!premios.isEmpty()) r.put("premiosNivel", premios);
        return r;
    }

    // ---------------- O Bazar: 6 cartas por dia (cada jogador vê as suas), lista de desejos e desmanchar repetidas ----------------
    static final Map<String, Integer> PRECO_BAZAR = new HashMap<>(), VALOR_DESMANCHE = new HashMap<>();
    static {
        PRECO_BAZAR.put("comum", 60); PRECO_BAZAR.put("incomum", 150); PRECO_BAZAR.put("rara", 700); PRECO_BAZAR.put("mitica", 1600);
        VALOR_DESMANCHE.put("comum", 10); VALOR_DESMANCHE.put("incomum", 25); VALOR_DESMANCHE.put("rara", 120); VALOR_DESMANCHE.put("mitica", 300);
    }
    static final int PRECO_RENOVAR = 100;
    static JsonObject sortearCom(Random rnd, JsonObject pool, String faixa, Set<String> ja) {
        JsonArray a = pool.getAsJsonArray(faixa);
        for (int t = 0; t < 40; t++) { JsonObject c = a.get(rnd.nextInt(a.size())).getAsJsonObject(); if (!str(c, "tipo").contains("Land") && ja.add(str(c, "nome"))) return c; }
        return a.get(rnd.nextInt(a.size())).getAsJsonObject();
    }
    /** Monta a mercadoria: 3 comuns, 2 incomuns, 1 rara (1 em 6 vira mítica). */
    static void montarBazar(Usuario u, long semente) throws IOException {
        JsonObject p = pool("mundo1"); Random rnd = new Random(semente); Set<String> ja = new HashSet<>();
        List<JsonObject> l = new ArrayList<>();
        for (int i = 0; i < 3; i++) l.add(sortearCom(rnd, p, "comum", ja));
        for (int i = 0; i < 2; i++) l.add(sortearCom(rnd, p, "incomum", ja));
        l.add(sortearCom(rnd, p, rnd.nextInt(6) == 0 ? "mitica" : "rara", ja));
        u.bazarCartas = new ArrayList<>();
        for (JsonObject c : l) { JsonObject y = new JsonObject(); for (String k : new String[]{"nome", "set", "num", "faixa"}) y.addProperty(k, str(c, k)); u.bazarCartas.add(y); }
        u.bazarVendidas = new HashSet<>();
    }
    static Object bazar(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        darBoasVindas(u);
        if (u.desejos == null) u.desejos = new LinkedHashSet<>();
        if (u.bazarVendidas == null) u.bazarVendidas = new HashSet<>();
        if (!hoje().equals(u.bazarDia) || u.bazarCartas == null || u.bazarCartas.isEmpty()) {   // virou o dia: mercadoria nova
            u.bazarDia = hoje(); u.bazarRenovou = 0; montarBazar(u, (u.codigo + "|" + hoje()).hashCode()); sujo = true;
        }
        pool("mundo1");
        String acao = texto(c, "acao", 20, "estado");
        Map<String, Object> r = new LinkedHashMap<>();
        switch (acao) {
            case "comprar": {
                int i = c.get("i").getAsInt();
                if (i < 0 || i >= u.bazarCartas.size() || u.bazarVendidas.contains(i)) throw new IllegalArgumentException("Essa carta já foi vendida.");
                JsonObject x = u.bazarCartas.get(i); int preco = PRECO_BAZAR.getOrDefault(str(x, "faixa"), 9999);
                if (u.ouro < preco) throw new IllegalArgumentException("Ouro insuficiente.");
                u.ouro -= preco; u.bazarVendidas.add(i); u.colecao.merge(chaveCarta(x), 1, Integer::sum); sujo = true;
                r.put("comprou", str(x, "nome"));
                break;
            }
            case "renovar": {
                if (u.ouro < PRECO_RENOVAR) throw new IllegalArgumentException("Ouro insuficiente.");
                u.ouro -= PRECO_RENOVAR; u.bazarRenovou++; montarBazar(u, System.nanoTime()); sujo = true;
                break;
            }
            case "desejo": {   // marca ou desmarca uma carta na lista de desejos
                String nome = texto(c, "nome", 200, "");
                if (!u.desejos.remove(nome)) { if (u.desejos.size() >= 60) throw new IllegalArgumentException("A lista de desejos já tem 60 cartas."); u.desejos.add(nome); }
                sujo = true;
                break;
            }
            case "desmanchar": {   // [{nome,set,num,qtd}]: vende cópias extras (sempre fica pelo menos uma)
                int ganho = 0;
                for (JsonElement e : c.getAsJsonArray("cartas")) {
                    JsonObject y = e.getAsJsonObject(); String k = chaveCarta(y); JsonObject info = cartaPorChave.get(k);
                    int tem = u.colecao.getOrDefault(k, 0), q = Math.min(y.get("qtd").getAsInt(), tem - 1);
                    if (info == null || q <= 0) continue;
                    u.colecao.put(k, tem - q); ganho += q * VALOR_DESMANCHE.getOrDefault(str(info, "faixa"), 0);
                }
                u.ouro += ganho; sujo = true; r.put("ganhou", ganho);
                break;
            }
            default: break;
        }
        List<Map<String, Object>> cartas = new ArrayList<>();
        for (int i = 0; i < u.bazarCartas.size(); i++) {
            JsonObject x = u.bazarCartas.get(i); Map<String, Object> m = new LinkedHashMap<>();
            for (String k : new String[]{"nome", "set", "num", "faixa"}) m.put(k, str(x, k));
            m.put("preco", PRECO_BAZAR.get(str(x, "faixa"))); m.put("vendida", u.bazarVendidas.contains(i)); m.put("tenho", u.colecao.getOrDefault(chaveCarta(x), 0));
            cartas.add(m);
        }
        List<Map<String, Object>> rep = new ArrayList<>();   // cópias que sobram para desmanchar
        for (Map.Entry<String, Integer> e : u.colecao.entrySet()) {
            JsonObject x = cartaPorChave.get(e.getKey()); if (x == null || e.getValue() < 2) continue;
            Map<String, Object> m = new LinkedHashMap<>(); for (String k : new String[]{"nome", "set", "num", "faixa"}) m.put(k, str(x, k));
            m.put("extra", e.getValue() - 1); m.put("valor", VALOR_DESMANCHE.get(str(x, "faixa"))); rep.add(m);
        }
        java.time.LocalDateTime agora = java.time.LocalDateTime.now();
        r.put("cartas", cartas); r.put("repetidas", rep); r.put("desejos", u.desejos); r.put("ouro", u.ouro); r.put("boosters", u.boosters);
        r.put("precoRenovar", PRECO_RENOVAR); r.put("segundosParaVirar", java.time.Duration.between(agora, agora.toLocalDate().plusDays(1).atStartOfDay()).getSeconds());
        r.put("valores", VALOR_DESMANCHE);
        return r;
    }

    // ---------------- Trocas entre amigos: sessão no servidor; as duas Mesas consultam o estado a cada segundo ----------------
    static class Troca {
        String id, a, b, estado = "convite", motivo = "";   // a = quem propôs; estado: convite, aberta, concluida, cancelada
        List<JsonObject> cartasA = new ArrayList<>(), cartasB = new ArrayList<>();
        int ouroA = 0, ouroB = 0; boolean aceitoA = false, aceitoB = false;
        long criada = System.currentTimeMillis(), mexida = System.currentTimeMillis(); int versao = 0;
        String pedido = "", pedidoDe = ""; long pedidoEm = 0;
        List<JsonObject> recebeuA = new ArrayList<>(), recebeuB = new ArrayList<>();
    }
    static final Map<String, Troca> trocas = new LinkedHashMap<>();
    static final long CONVITE_TROCA_MS = 3 * 60_000L, TROCA_PARADA_MS = 15 * 60_000L;
    static void limparTrocas() {
        long agora = System.currentTimeMillis();
        for (Troca t : trocas.values()) {
            if (t.estado.equals("convite") && agora - t.criada > CONVITE_TROCA_MS) { t.estado = "cancelada"; t.motivo = "O convite expirou."; }
            if (t.estado.equals("aberta") && agora - t.mexida > TROCA_PARADA_MS) { t.estado = "cancelada"; t.motivo = "A troca ficou parada tempo demais."; }
        }
        trocas.values().removeIf(t -> !t.estado.equals("convite") && !t.estado.equals("aberta") && agora - t.mexida > 10 * 60_000L);
    }
    static List<Map<String, Object>> trocasDe(Usuario u) {
        limparTrocas();
        List<Map<String, Object>> l = new ArrayList<>();
        for (Troca t : trocas.values()) {
            if (!(t.a.equals(u.codigo) || t.b.equals(u.codigo)) || !(t.estado.equals("convite") || t.estado.equals("aberta"))) continue;
            Usuario o = porCodigo(t.a.equals(u.codigo) ? t.b : t.a); if (o == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(); m.put("id", t.id); m.put("estado", t.estado); m.put("propus", t.a.equals(u.codigo)); m.put("com", resumo(o));
            l.add(m);
        }
        return l;
    }
    /** Nomes que não podem sair da coleção: cartas do deck da campanha e o comandante. */
    static Set<String> presasNoDeck(Usuario u) {
        Set<String> s = new HashSet<>();
        if (u.campanha != null) {
            if (u.campanha.has("deck")) for (JsonElement e : u.campanha.getAsJsonArray("deck")) s.add(str(e.getAsJsonObject(), "nome"));
            if (u.campanha.has("comandante")) s.add(str(u.campanha.getAsJsonObject("comandante"), "nome"));
        }
        return s;
    }
    /** Confere se u tem todas as cartas (com repetição) e o Ouro da oferta. */
    static void conferirOferta(Usuario u, List<JsonObject> cartas, int ouro) {
        if (ouro < 0 || ouro > u.ouro) throw new IllegalArgumentException(u.nome + " não tem esse Ouro.");
        Set<String> presas = presasNoDeck(u); Map<String, Integer> conta = new HashMap<>();
        for (JsonObject c : cartas) {
            String k = chaveCarta(c); int q = conta.merge(k, 1, Integer::sum);
            if (u.colecao.getOrDefault(k, 0) < q) throw new IllegalArgumentException(u.nome + " não tem mais " + str(c, "nome") + ".");
            if (presas.contains(str(c, "nome")) && u.colecao.getOrDefault(k, 0) - q < 1) throw new IllegalArgumentException(str(c, "nome") + " está no deck de " + u.nome + ".");
        }
    }
    static Map<String, Object> cartaVista(JsonObject x, Usuario dono) {
        Map<String, Object> m = new LinkedHashMap<>(); for (String k : new String[]{"nome", "set", "num", "faixa"}) m.put(k, str(x, k));
        if (dono != null) m.put("qtd", dono.colecao.getOrDefault(chaveCarta(x), 0));
        return m;
    }
    static List<Map<String, Object>> colecaoVista(Usuario u) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : u.colecao.entrySet()) { JsonObject x = cartaPorChave.get(e.getKey()); if (x == null || e.getValue() < 1) continue; l.add(cartaVista(x, u)); }
        l.sort(Comparator.comparing(m -> (String) m.get("nome")));
        return l;
    }
    static Object troca(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        darBoasVindas(u); pool("mundo1"); limparTrocas();
        String acao = texto(c, "acao", 20, "estado");
        if (acao.equals("propor")) {
            Usuario o = porCodigo(texto(c, "codigo", 10, ""));
            if (o == null || o == u) throw new IllegalArgumentException("Não achei esse jogador.");
            if (!b.amizades.contains(par(u, o))) throw new IllegalArgumentException("Só dá para trocar com amigos.");
            if (System.currentTimeMillis() - o.visto > ONLINE_MS) throw new IllegalArgumentException(o.nome + " não está online.");
            for (Troca t : trocas.values())   // já existe uma entre os dois: reaproveita
                if ((t.estado.equals("convite") || t.estado.equals("aberta")) && ((t.a.equals(u.codigo) && t.b.equals(o.codigo)) || (t.a.equals(o.codigo) && t.b.equals(u.codigo)))) return vistaTroca(t, u);
            Troca t = new Troca(); t.id = Long.toString(System.nanoTime(), 36); t.a = u.codigo; t.b = o.codigo;
            trocas.put(t.id, t);
            return vistaTroca(t, u);
        }
        Troca t = trocas.get(texto(c, "trocaId", 40, ""));   // "id" é o da Mesa; o da troca vem como trocaId
        if (t == null || !(t.a.equals(u.codigo) || t.b.equals(u.codigo))) throw new IllegalArgumentException("Essa troca não existe mais.");
        boolean souA = t.a.equals(u.codigo);
        Usuario o = porCodigo(souA ? t.b : t.a);
        switch (acao) {
            case "responder":
                if (souA || !t.estado.equals("convite")) break;
                if (c.has("aceitar") && c.get("aceitar").getAsBoolean()) t.estado = "aberta"; else { t.estado = "cancelada"; t.motivo = u.nome + " recusou o convite."; }
                t.mexida = System.currentTimeMillis(); t.versao++;
                break;
            case "oferecer": {
                if (!t.estado.equals("aberta")) throw new IllegalArgumentException("A troca não está aberta.");
                List<JsonObject> cartas = new ArrayList<>();
                for (JsonElement e : c.getAsJsonArray("cartas")) {
                    JsonObject y = e.getAsJsonObject(); JsonObject info = cartaPorChave.get(chaveCarta(y));
                    if (info == null) throw new IllegalArgumentException("Carta desconhecida.");
                    JsonObject z = new JsonObject(); for (String k : new String[]{"nome", "set", "num", "faixa"}) z.addProperty(k, str(info, k)); cartas.add(z);
                }
                if (cartas.size() > 30) throw new IllegalArgumentException("No máximo 30 cartas por troca.");
                int ouro = c.has("ouro") ? c.get("ouro").getAsInt() : 0;
                conferirOferta(u, cartas, ouro);
                if (souA) { t.cartasA = cartas; t.ouroA = ouro; } else { t.cartasB = cartas; t.ouroB = ouro; }
                t.aceitoA = t.aceitoB = false;   // qualquer mudança desfaz os aceites
                t.mexida = System.currentTimeMillis(); t.versao++;
                break;
            }
            case "pedir":   // pedir uma carta da coleção do outro (aparece como aviso para ele)
                t.pedido = texto(c, "nome", 200, ""); t.pedidoDe = u.codigo; t.pedidoEm = System.currentTimeMillis(); t.versao++;
                break;
            case "aceitar": {
                if (!t.estado.equals("aberta")) throw new IllegalArgumentException("A troca não está aberta.");
                boolean sim = !c.has("sim") || c.get("sim").getAsBoolean();
                if (t.cartasA.isEmpty() && t.cartasB.isEmpty() && t.ouroA == 0 && t.ouroB == 0) throw new IllegalArgumentException("A mesa está vazia.");
                if (souA) t.aceitoA = sim; else t.aceitoB = sim;
                t.mexida = System.currentTimeMillis(); t.versao++;
                if (t.aceitoA && t.aceitoB) executarTroca(t);
                break;
            }
            case "cancelar":
                if (t.estado.equals("convite") || t.estado.equals("aberta")) { t.estado = "cancelada"; t.motivo = u.nome + " saiu da troca."; t.mexida = System.currentTimeMillis(); t.versao++; }
                break;
            default: break;
        }
        return vistaTroca(t, u);
    }
    /** Os dois aceitaram: confere tudo de novo e troca de uma vez (cartas e Ouro). */
    static void executarTroca(Troca t) {
        Usuario ua = porCodigo(t.a), ub = porCodigo(t.b);
        try { conferirOferta(ua, t.cartasA, t.ouroA); conferirOferta(ub, t.cartasB, t.ouroB); }
        catch (IllegalArgumentException e) { t.aceitoA = t.aceitoB = false; t.motivo = e.getMessage(); return; }
        for (JsonObject x : t.cartasA) { String k = chaveCarta(x); ua.colecao.merge(k, -1, Integer::sum); ub.colecao.merge(k, 1, Integer::sum); }
        for (JsonObject x : t.cartasB) { String k = chaveCarta(x); ub.colecao.merge(k, -1, Integer::sum); ua.colecao.merge(k, 1, Integer::sum); }
        ua.colecao.values().removeIf(q -> q <= 0); ub.colecao.values().removeIf(q -> q <= 0);
        ua.ouro += t.ouroB - t.ouroA; ub.ouro += t.ouroA - t.ouroB;
        t.recebeuA = t.cartasB; t.recebeuB = t.cartasA;
        t.estado = "concluida"; t.mexida = System.currentTimeMillis(); t.versao++; sujo = true;
    }
    static Map<String, Object> vistaTroca(Troca t, Usuario u) {
        boolean souA = t.a.equals(u.codigo); Usuario o = porCodigo(souA ? t.b : t.a);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", t.id); r.put("estado", t.estado); r.put("motivo", t.motivo); r.put("versao", t.versao); r.put("propus", souA);
        r.put("eu", resumo(u)); r.put("outro", o == null ? null : resumo(o));
        r.put("minhas", souA ? t.cartasA : t.cartasB); r.put("dele", souA ? t.cartasB : t.cartasA);
        r.put("meuOuro", souA ? t.ouroA : t.ouroB); r.put("ouroDele", souA ? t.ouroB : t.ouroA);
        r.put("aceiteiEu", souA ? t.aceitoA : t.aceitoB); r.put("aceitouEle", souA ? t.aceitoB : t.aceitoA);
        r.put("saldo", u.ouro);
        if (t.estado.equals("concluida")) { r.put("recebi", souA ? t.recebeuA : t.recebeuB); r.put("ouroRecebido", souA ? t.ouroB : t.ouroA); }
        if (!t.pedido.isEmpty() && !t.pedidoDe.equals(u.codigo) && System.currentTimeMillis() - t.pedidoEm < 20_000) r.put("pedido", t.pedido);
        if (t.estado.equals("aberta") || t.estado.equals("convite")) {
            r.put("colecao", colecaoVista(u)); r.put("presas", presasNoDeck(u)); r.put("desejos", u.desejos == null ? new HashSet<String>() : u.desejos);
            if (o != null) r.put("colecaoDele", colecaoVista(o));
        }
        return r;
    }

    // ---------------- Playmat: cada jogador escolhe um modelo ou sobe a própria imagem ----------------
    static java.nio.file.Path pastaPlaymats() { return DADOS.toAbsolutePath().getParent().resolve("playmats"); }
    static Object playmat(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        if (c.has("imagem")) {   // JPEG em base64 (a Mesa já reduz antes de mandar)
            byte[] img = Base64.getDecoder().decode(texto(c, "imagem", 4_000_000, ""));
            if (img.length < 1000 || img.length > 2_500_000 || (img[0] & 0xff) != 0xFF || (img[1] & 0xff) != 0xD8) throw new IllegalArgumentException("imagem inválida");
            Files.createDirectories(pastaPlaymats());
            Files.write(pastaPlaymats().resolve(u.codigo + ".jpg"), img);
            u.playmat = "foto"; u.playmatVer = System.currentTimeMillis(); sujo = true;
        } else if (c.has("preset")) {
            String pr = texto(c, "preset", 30, "");
            if (!pr.matches("[a-z0-9-]*")) throw new IllegalArgumentException("modelo inválido");
            if (pr.equals("brasas") && (u.desbloqueios == null || !u.desbloqueios.contains("pm:brasas"))) throw new IllegalArgumentException("Esse playmat é o prêmio do nível 10.");
            u.playmat = pr; u.playmatVer = System.currentTimeMillis(); sujo = true;
        }
        if (c.has("jogador")) u.xmage = texto(c, "jogador", 64, u.xmage);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("playmat", u.playmat); r.put("versao", u.playmatVer); r.put("codigo", u.codigo);
        return r;
    }
    /** Playmat de cada jogador da partida, pelo nome do XMage. */
    static Object playmats(JsonObject c) {
        usuario(c);
        Map<String, Object> r = new LinkedHashMap<>();
        if (!c.has("jogadores")) return r;
        for (JsonElement e : c.getAsJsonArray("jogadores")) {
            String n = e.getAsString();
            for (Usuario o : b.porId.values()) if (n.equals(o.xmage) && !o.playmat.isEmpty()) {
                Map<String, Object> x = new LinkedHashMap<>(); x.put("playmat", o.playmat); x.put("versao", o.playmatVer); x.put("codigo", o.codigo);
                r.put(n, x); break;
            }
        }
        return r;
    }
    static void playmatImg(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getRawQuery(); String cod = "";
            if (q != null) for (String par : q.split("&")) if (par.startsWith("c=")) cod = par.substring(2);
            java.nio.file.Path f = pastaPlaymats().resolve(cod + ".jpg");
            if (!cod.matches("[A-Za-z0-9]{3,12}") || !Files.exists(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] d = Files.readAllBytes(f);
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, d.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(d); }
        } catch (Exception e) { try { ex.sendResponseHeaders(500, -1); } catch (Exception ignored) { } ex.close(); }
    }

    // ---------------- 2v2 online: times de cada mesa (o XMage lê o arquivo ao começar, para alternar os lugares) ----------------
    static final Map<String, JsonObject> timesMesa = new HashMap<>();   // mesa -> {timeA:[nomes], timeB:[nomes], quando}
    static Object times(JsonObject c) throws IOException {
        usuario(c);
        String mesa = texto(c, "mesa", 64, "");
        if (!mesa.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("mesa inválida");
        synchronized (timesMesa) {
            long agora = System.currentTimeMillis();
            timesMesa.values().removeIf(x -> agora - x.get("quando").getAsLong() > 6 * 3600_000L);
            if (c.has("timeA") && c.has("timeB")) {
                JsonObject t = new JsonObject();
                t.add("timeA", c.getAsJsonArray("timeA")); t.add("timeB", c.getAsJsonArray("timeB")); t.addProperty("quando", agora);
                timesMesa.put(mesa, t);
                java.nio.file.Path d = java.nio.file.Paths.get(Optional.ofNullable(System.getenv("TIMES")).orElse("/times"));
                if (Files.isDirectory(d)) Files.write(d.resolve(mesa + ".json"), GSON.toJson(t).getBytes(StandardCharsets.UTF_8));
            }
            JsonObject t = timesMesa.get(mesa);
            if (t == null) {   // o serviço reiniciou: os times continuam no arquivo
                java.nio.file.Path f = java.nio.file.Paths.get(Optional.ofNullable(System.getenv("TIMES")).orElse("/times")).resolve(mesa + ".json");
                if (Files.exists(f)) { t = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject(); timesMesa.put(mesa, t); }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            if (t != null) { r.put("timeA", t.get("timeA")); r.put("timeB", t.get("timeB")); }
            return r;
        }
    }

    // ---------------- 2v2: cada jogador vê a mão do parceiro (só na memória; some sozinho) ----------------
    static final Map<String, Map<String, Object[]>> maos = new HashMap<>();   // jogo -> código -> {nome, cartas, quando}
    static Object mao(JsonObject c) {
        Usuario u = usuario(c);
        String jogo = texto(c, "jogo", 64, "");
        if (jogo.isEmpty()) throw new IllegalArgumentException("sem jogo");
        long agora = System.currentTimeMillis();
        List<Map<String, Object>> outros = new ArrayList<>();
        synchronized (maos) {
            maos.values().forEach(m -> m.values().removeIf(x -> agora - (Long) x[2] > 120_000));
            maos.values().removeIf(Map::isEmpty);
            Map<String, Object[]> m = maos.computeIfAbsent(jogo, k -> new HashMap<>());
            if (c.has("cartas") && c.get("cartas").isJsonArray() && c.getAsJsonArray("cartas").size() <= 40)
                m.put(u.codigo, new Object[]{u.nome, c.getAsJsonArray("cartas"), agora, texto(c, "jogador", 64, "")});
            for (Map.Entry<String, Object[]> e : m.entrySet()) {
                if (e.getKey().equals(u.codigo)) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nome", e.getValue()[0]); x.put("cartas", e.getValue()[1]); x.put("quando", e.getValue()[2]);
                if (e.getValue().length > 3) x.put("jogador", e.getValue()[3]);
                outros.add(x);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>(); r.put("parceiros", outros); return r;
    }

    // ---------------- Deck do dia: um deck variado para jogar contra um bot e conhecer ----------------
    static Object deckDia(JsonObject c) throws IOException {
        Usuario u = usuario(c);
        JsonObject dd = deckDoCiclo();
        Map<String, Object> r = new LinkedHashMap<>();
        if (dd == null) { r.put("vazio", true); return r; }
        String ciclo = cicloAtual();
        r.put("ciclo", ciclo);
        r.put("fim", inicioCiclo().plusDays(1).toInstant().toEpochMilli());
        r.put("nome", str(dd, "nome"));
        r.put("comandante", dd.get("comandante"));
        if (dd.has("descricao")) r.put("descricao", str(dd, "descricao"));
        if (dd.has("estrategia")) r.put("estrategia", str(dd, "estrategia"));
        r.put("deck", str(dd, "deck"));
        List<String> v = b.venceramDeckDia.getOrDefault(ciclo, Collections.emptyList());
        r.put("euVenci", v.contains(u.codigo));
        List<Map<String, Object>> quem = new ArrayList<>();
        for (String cod : v) { Usuario o = porCodigo(cod); if (o != null) quem.add(resumo(o)); }
        r.put("venceram", quem);
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
        if (u.conquistas == null) u.conquistas = new LinkedHashMap<>();
        if (conferirConquistas(u).size() > 0) sujo = true;   // quem já tinha os recordes antes das conquistas existirem
        r.put("conquistas", conquistasJson(u));
        darBoasVindas(u);
        List<Map<String, Object>> premios = premiarNiveis(u);   // quem subiu antes da trilha existir recebe o que ficou para trás
        if (!premios.isEmpty()) r.put("premiosNivel", premios);
        r.put("ouro", u.ouro); r.put("boosters", u.boosters);
        r.put("moldura", u.moldura); r.put("titulo", u.titulo);
        r.put("trocas", trocasDe(u));
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
        m.put("moldura", o.moldura == null ? "" : o.moldura); m.put("titulo", o.titulo == null ? "" : o.titulo);
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
