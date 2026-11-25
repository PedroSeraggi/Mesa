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
 *   POST /perfil {id, codigo} -> perfil público de alguém (foto, banner, frase, estatísticas, decks)
 *
 * Variáveis: SENHA (opcional), PORTA (padrão 17190), DADOS (padrão ./dados.json).
 */
public class Amigos {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final long ONLINE_MS = 35_000, CONVITE_MS = 3 * 3600_000L;
    static String SENHA;
    static Path DADOS;

    // ---- dados (tudo em memória; gravado no arquivo quando muda) ----
    static class Usuario { String id, codigo, nome = "?", avatar = "", status = ""; JsonObject sala, perfil; long visto; }
    static class Convite { String id, de, para; JsonObject sala; long quando; }
    static class Banco {
        Map<String, Usuario> porId = new LinkedHashMap<>();
        Set<String> amizades = new LinkedHashSet<>();     // "AAAAAA|BBBBBB" (ordem alfabética)
        Set<String> pedidos = new LinkedHashSet<>();      // "DE>PARA"
        List<Convite> convites = new ArrayList<>();
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
        srv.createContext("/saude", ex -> responder(ex, 200, "{\"ok\":true}"));
        srv.setExecutor(Executors.newFixedThreadPool(8));
        srv.start();
        // grava no arquivo a cada 20 s se algo mudou ("visto por último" muda o tempo todo; não precisa gravar a cada ping)
        new Timer(true).scheduleAtFixedRate(new TimerTask() { public void run() { gravar(); } }, 20_000, 20_000);
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
        return r;
    }

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
        m.put("codigo", o.codigo); m.put("nome", o.nome); m.put("avatar", o.avatar);
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
