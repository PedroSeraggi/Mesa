import com.google.gson.*;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Fábrica de chefes: completa a fila de chefes (pasta chefes/) até ter META decks esperando.
 * Busca decks bracket 4 de Commander no Archidekt (os mais vistos), pula combo/cEDH/stax,
 * exige ~25 criaturas, nunca repete comandante, converte para o XMage e valida como o servidor.
 * Mistura dois tipos (uns 4 por 3): "brutal" (agressivo, que a IA joga bem: muitas criaturas,
 * curva baixa, voar/atropelar/ímpeto/ameaçar) e "estrategico" (variado, para a coleção ter de tudo).
 *
 * Uso: java Fabrica <pastaChefes> <estado.json> <meta>   (rodar numa pasta que tenha db/cards.h2.db)
 */
public class Fabrica {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String[] PROIBIDO = {"combo", "cedh", "stax", "turbo", "infinite", "infinito", "storm", "lock"};
    static final int MIN_CRIATURAS = 25, MAX_PECAS_COMBO = 6, MAX_FALTANDO = 3;
    static final String[] ESTRATEGICAS = {"group hug", "politics", "control", "spellslinger", "aristocrat", "voltron", "stax", "pillowfort"};

    public static void main(String[] a) throws Exception {
        Path pasta = Paths.get(a[0]), estadoArq = Paths.get(a[1]);
        int meta = Integer.parseInt(a[2]);
        Files.createDirectories(pasta.resolve("usados"));
        JsonObject est = Files.exists(estadoArq) ? JsonParser.parseString(new String(Files.readAllBytes(estadoArq), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        if (!est.has("pagina")) est.addProperty("pagina", 1);
        if (!est.has("tentados")) est.add("tentados", new JsonArray());
        Set<String> tentados = new HashSet<>(); for (JsonElement e : est.getAsJsonArray("tentados")) tentados.add(e.getAsString());
        // comandantes que já foram (ou estão na fila para ser) chefes: nunca repetir
        Set<String> comandantes = new HashSet<>();
        for (Path d : new Path[]{pasta, pasta.resolve("usados")})
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.json")) {
                for (Path f : ds) {
                    JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
                    comandantes.add(o.getAsJsonObject("comandante").get("nome").getAsString().toLowerCase());
                    if (o.has("origem")) tentados.add(o.get("origem").getAsString().replaceAll("\\D", ""));
                }
            }
        int naFila = contarFila(pasta);
        int[] tipos = contarTipos(pasta);   // [brutais, estrategicos] na fila
        log("fila tem " + naFila + " (" + tipos[0] + " brutais, " + tipos[1] + " estratégicos), meta " + meta);
        int novos = 0, paginasSemNada = 0;
        while (naFila < meta && paginasSemNada < 6) {
            int pagina = est.get("pagina").getAsInt();
            JsonObject lista = obter("https://archidekt.com/api/decks/v3/?formats=3&edhBracket=4&orderBy=-viewCount&pageSize=50&page=" + pagina);
            if (lista == null) { log("Archidekt não respondeu a lista; para por hoje"); break; }
            JsonArray res = lista.getAsJsonArray("results");
            if (res.size() == 0) { log("acabou a lista do Archidekt"); break; }
            boolean aproveitou = false;
            for (JsonElement el : res) {
                if (naFila >= meta) break;
                JsonObject r = el.getAsJsonObject();
                String id = r.get("id").getAsString();
                if (tentados.contains(id)) continue;
                String texto = (r.get("name").getAsString() + " " + (r.has("description") && !r.get("description").isJsonNull() ? r.get("description").toString() : "")).toLowerCase();
                if (r.has("tags") && r.get("tags").isJsonArray()) texto += " " + r.get("tags").toString().toLowerCase();
                String proib = Arrays.stream(PROIBIDO).filter(texto::contains).findFirst().orElse(null);
                if (proib != null) { tentados.add(id); log(id + " pulado (" + proib + ")"); continue; }
                // qual tipo a fila está precisando (uns 4 brutais para 3 estratégicos)
                String precisa = tipos[0] * 3 <= tipos[1] * 4 ? "brutal" : "estrategico";
                try {
                    JsonObject chefe = montar(id, comandantes, texto);
                    if (chefe == null) { tentados.add(id); continue; }
                    String tipo = chefe.get("tipo").getAsString();
                    if (!tipo.equals(precisa) && tipos[tipo.equals("brutal") ? 0 : 1] >= (tipo.equals("brutal") ? 5 : 4)) {
                        log(id + " guardado para depois (é " + tipo + ", a fila precisa de " + precisa + ")"); continue;   // não marca: volta numa próxima busca
                    }
                    tentados.add(id);
                    tipos[tipo.equals("brutal") ? 0 : 1]++;
                    String nomeArq = String.format("%s-%s.json", new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date()), id);
                    Files.write(pasta.resolve(nomeArq), GSON.toJson(chefe).getBytes(StandardCharsets.UTF_8));
                    comandantes.add(chefe.getAsJsonObject("comandante").get("nome").getAsString().toLowerCase());
                    naFila++; novos++; aproveitou = true;
                    log("NOVO CHEFE (" + tipo + "): " + chefe.get("nome").getAsString() + " (" + id + ")");
                } catch (Exception e) { log(id + " erro: " + e); }
                salvarEstado(estadoArq, est, tentados);
            }
            if (naFila < meta) { est.addProperty("pagina", pagina + 1); paginasSemNada = aproveitou ? 0 : paginasSemNada + 1; }
            salvarEstado(estadoArq, est, tentados);
        }
        salvarEstado(estadoArq, est, tentados);
        log("fim: " + novos + " novo(s), fila com " + naFila);
        System.out.println("RESULTADO fila=" + naFila + " novos=" + novos);
        System.exit(0);
    }

    static String describe(Object erro) {
        try { Object g = erro.getClass().getMethod("getGroup").invoke(erro), m = erro.getClass().getMethod("getMessage").invoke(erro); return g + ": " + m; }
        catch (Exception e) { return String.valueOf(erro); }
    }

    static int[] contarTipos(Path pasta) throws IOException {
        int[] t = new int[2];
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pasta, "*.json")) {
            for (Path f : ds) {
                JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("tipo")) t["brutal".equals(o.get("tipo").getAsString()) ? 0 : 1]++;
            }
        }
        return t;
    }

    static int contarFila(Path pasta) throws IOException {
        int n = 0; try (DirectoryStream<Path> ds = Files.newDirectoryStream(pasta, "*.json")) { for (Path p : ds) n++; } return n;
    }

    /** Baixa o deck, filtra, converte para o XMage (100 cartas) e valida. null = não serve. */
    static JsonObject montar(String id, Set<String> comandantesUsados, String textoLista) throws Exception {
        JsonObject d = obter("https://archidekt.com/api/decks/" + id + "/");
        if (d == null) throw new IOException("sem resposta");
        Set<String> fora = new HashSet<>();
        for (JsonElement c : d.getAsJsonArray("categories")) { JsonObject o = c.getAsJsonObject(); if (o.has("includedInDeck") && !o.get("includedInDeck").getAsBoolean()) fora.add(o.get("name").getAsString()); }
        int criaturas = 0, pecasCombo = 0, evasivas = 0, naoTerrenos = 0; double somaCusto = 0;
        List<String[]> cartas = new ArrayList<>(); List<String> cmds = new ArrayList<>();
        for (JsonElement el : d.getAsJsonArray("cards")) {
            JsonObject c = el.getAsJsonObject();
            Set<String> cats = new HashSet<>(); if (c.has("categories") && c.get("categories").isJsonArray()) for (JsonElement x : c.getAsJsonArray("categories")) cats.add(x.getAsString());
            if (!Collections.disjoint(cats, fora)) continue;
            JsonObject card = c.getAsJsonObject("card"), or = card.getAsJsonObject("oracleCard");
            int q = c.get("quantity").getAsInt();
            String nome = or.get("name").getAsString().split(" // ")[0];
            boolean ehCmd = cats.contains("Commander");
            if (or.has("types") && or.get("types").toString().contains("Creature")) {
                criaturas += q;
                String kw = or.has("keywords") ? or.get("keywords").toString() : "";
                if (kw.contains("Flying") || kw.contains("Trample") || kw.contains("Haste") || kw.contains("Menace")) evasivas += q;
            }
            if (!(or.has("types") && or.get("types").toString().contains("Land"))) { naoTerrenos += q; if (or.has("cmc")) somaCusto += or.get("cmc").getAsDouble() * q; }
            if (or.has("twoCardComboIds") && or.get("twoCardComboIds").isJsonArray() && or.getAsJsonArray("twoCardComboIds").size() > 0) pecasCombo++;
            String set = card.has("edition") && card.get("edition").isJsonObject() ? card.getAsJsonObject("edition").get("editioncode").getAsString().toUpperCase() : "";
            String num = card.has("collectorNumber") && !card.get("collectorNumber").isJsonNull() ? card.get("collectorNumber").getAsString() : "";
            cartas.add(new String[]{String.valueOf(q), nome, set, num, ehCmd ? "1" : "0"});
            if (ehCmd) cmds.add(nome);
        }
        if (cmds.isEmpty() || cmds.size() > 2) { log(id + " pulado (comandante: " + cmds + ")"); return null; }
        if (cmds.stream().anyMatch(n -> comandantesUsados.contains(n.toLowerCase()))) { log(id + " pulado (comandante repetido: " + cmds + ")"); return null; }
        if (criaturas < MIN_CRIATURAS) { log(id + " pulado (" + criaturas + " criaturas)"); return null; }
        if (pecasCombo > MAX_PECAS_COMBO) { log(id + " pulado (" + pecasCombo + " peças de combo)"); return null; }
        // converte para o XMage
        StringBuilder dck = new StringBuilder(); List<String> faltam = new ArrayList<>(); Map<String, Integer> basicos = new HashMap<>();
        int total = 0; JsonObject cmdJson = null;
        for (String[] c : cartas) {
            int q = Integer.parseInt(c[0]); String nome = c[1];
            CardInfo ci = (!c[2].isEmpty() && !c[3].isEmpty()) ? CardRepository.instance.findCard(c[2], c[3]) : null;
            if (ci == null || !ci.getName().split(" // ")[0].equalsIgnoreCase(nome)) { List<CardInfo> o = CardRepository.instance.findCards(nome); ci = o.isEmpty() ? null : o.get(0); }
            if (ci == null) { faltam.add(nome); continue; }
            String n = ci.getName().split(" // ")[0];
            if (n.matches("Plains|Island|Swamp|Mountain|Forest|Wastes")) basicos.merge(n, q, Integer::sum);
            if ("1".equals(c[4]) && cmdJson == null) { cmdJson = new JsonObject(); cmdJson.addProperty("nome", n); cmdJson.addProperty("set", c[2].isEmpty() ? ci.getSetCode() : c[2]); cmdJson.addProperty("num", c[3].isEmpty() ? ci.getCardNumber() : c[3]); }
            dck.append("1".equals(c[4]) ? "SB: " : "").append(q).append(" [").append(ci.getSetCode()).append(":").append(ci.getCardNumber()).append("] ").append(n).append('\n');
            total += q;
        }
        if (faltam.stream().anyMatch(f -> cmds.contains(f))) { log(id + " pulado (comandante não existe no XMage)"); return null; }
        if (faltam.size() > MAX_FALTANDO) { log(id + " pulado (" + faltam.size() + " cartas fora do XMage)"); return null; }
        String basico = basicos.isEmpty() ? null : Collections.max(basicos.entrySet(), Map.Entry.comparingByValue()).getKey();
        String txt = dck.toString();
        if (total < 100) {
            if (basico == null) { log(id + " pulado (faltam cartas e não há básico para completar)"); return null; }
            CardInfo b = CardRepository.instance.findCards(basico).get(0);
            txt += (100 - total) + " [" + b.getSetCode() + ":" + b.getCardNumber() + "] " + basico + "\n";
        } else if (total > 100) {
            if (basico == null || basicos.get(basico) < total - 100) { log(id + " pulado (" + total + " cartas)"); return null; }
            int tirar = total - 100; StringBuilder nb = new StringBuilder();
            for (String ln : txt.split("\n")) {
                if (tirar > 0 && !ln.startsWith("SB:") && ln.endsWith("] " + basico)) { int q = Integer.parseInt(ln.split(" ")[0]); int t = Math.min(q, tirar); tirar -= t; q -= t; if (q > 0) nb.append(q).append(ln.substring(ln.indexOf(' '))).append('\n'); }
                else nb.append(ln).append('\n');
            }
            txt = nb.toString();
        }
        // valida como o servidor (Commander)
        Path tmp = Files.createTempFile("chefe", ".dck");
        Files.write(tmp, txt.getBytes(StandardCharsets.UTF_8));
        StringBuilder erros = new StringBuilder();
        DeckCardLists dl = DeckImporter.importDeckFromFile(tmp.toString(), erros, false);
        Files.deleteIfExists(tmp);
        Deck deck = Deck.load(dl, true, true);
        mage.deck.Commander v = new mage.deck.Commander();
        if (!v.validate(deck)) { StringBuilder er = new StringBuilder(); for (Object x : v.getErrorsListSorted()) er.append(x.toString().replaceAll("^.*@\\w+$", "")).append(describe(x)).append("; ");
            log(id + " pulado (inválido no XMage: " + er + ")"); return null; }
        double custoMedio = naoTerrenos == 0 ? 9 : somaCusto / naoTerrenos;
        String textoTudo = (textoLista + " " + (d.has("deckTags") ? d.get("deckTags").toString().toLowerCase() : "")).toLowerCase();
        boolean estrategico = Arrays.stream(ESTRATEGICAS).anyMatch(textoTudo::contains);
        boolean brutal = !estrategico && criaturas >= 30 && custoMedio <= 3.5 && evasivas >= 8;
        JsonObject chefe = new JsonObject();
        chefe.addProperty("tipo", brutal ? "brutal" : "estrategico");
        String estrategia = textoDescricao(d);
        if (!estrategia.isEmpty()) chefe.addProperty("estrategia", estrategia);
        chefe.addProperty("nome", String.join(" & ", cmds));
        chefe.addProperty("descricao", "Deck \"" + d.get("name").getAsString() + "\" do Archidekt · " + criaturas + " criaturas · custo médio " + String.format(Locale.ROOT, "%.1f", custoMedio));
        chefe.addProperty("origem", "archidekt.com/decks/" + id);
        chefe.add("comandante", cmdJson);
        chefe.addProperty("deck", txt);
        if (!faltam.isEmpty()) chefe.addProperty("trocadas", String.join(", ", faltam));
        return chefe;
    }

    /** Texto da descrição do deck (o Archidekt guarda em formato de editor: {"ops":[{"insert":"..."}]}), resumido. */
    static String textoDescricao(JsonObject d) {
        try {
            if (!d.has("description") || d.get("description").isJsonNull()) return "";
            String bruto = d.get("description").getAsString(), t = bruto;
            if (bruto.trim().startsWith("{")) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement e : JsonParser.parseString(bruto).getAsJsonObject().getAsJsonArray("ops")) {
                    JsonElement ins = e.getAsJsonObject().get("insert");
                    if (ins != null && ins.isJsonPrimitive()) sb.append(ins.getAsString());
                }
                t = sb.toString();
            }
            t = t.replaceAll("\\s+", " ").trim();
            return t.length() > 600 ? t.substring(0, 600).replaceAll("\\s\\S*$", "") + "..." : t;
        } catch (Exception e) { return ""; }
    }

    /** GET com calma: 6 s entre pedidos; se o site limitar, espera e tenta de novo. */
    static long ultimo = 0;
    static JsonObject obter(String url) throws InterruptedException {
        for (int t = 0; t < 6; t++) {
            long espera = 6000 - (System.currentTimeMillis() - ultimo); if (espera > 0) Thread.sleep(espera);
            ultimo = System.currentTimeMillis();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Mesa; fabrica de chefes)");
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (c.getResponseCode() == 200) {
                    String txt = new String(ler(c.getInputStream()), StandardCharsets.UTF_8);
                    if (txt.trim().startsWith("{")) return JsonParser.parseString(txt).getAsJsonObject();
                }
            } catch (Exception ignored) { }
            Thread.sleep(45000L * (t + 1));
        }
        return null;
    }
    static byte[] ler(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); }
    static void salvarEstado(Path arq, JsonObject est, Set<String> tentados) throws IOException {
        JsonArray t = new JsonArray(); for (String s : tentados) t.add(s); est.add("tentados", t);
        Files.write(arq, GSON.toJson(est).getBytes(StandardCharsets.UTF_8));
    }
    static void log(String s) { System.out.println(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + s); }
}
