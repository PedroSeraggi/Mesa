import com.google.gson.GsonBuilder;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.Rarity;
import mage.constants.SuperType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Gera o "pool" de cartas de um mundo da campanha (cartas que saem nos boosters), a partir do banco do XMage.
 * Uso: java GerarPool <saida.json> <SET1,SET2,...>   (rodar na pasta do mage-client, onde fica o db/)
 * Saída: {comum:[...], incomum:[...], rara:[...], mitica:[...], terreno:[...]} com {nome,set,num,tipo,cores}
 */
public class GerarPool {
    public static void main(String[] a) throws Exception {
        String[] sets = a[1].split(",");
        Map<String, List<Map<String, String>>> pool = new LinkedHashMap<>();
        for (String k : new String[]{"comum", "incomum", "rara", "mitica", "terreno"}) pool.put(k, new ArrayList<>());
        Set<String> vistos = new HashSet<>();
        for (CardInfo c : CardRepository.instance.findCards(new CardCriteria().setCodes(sets))) {
            if (c.getTypes().isEmpty()) continue;
            String nome = c.getName().split(" // ")[0];
            if (c.isNightCard() || nome.matches("(Snow-Covered )?(Plains|Island|Swamp|Mountain|Forest|Wastes)")) continue;
            if (!vistos.add(nome)) continue;   // uma impressão por nome
            boolean terreno = c.getTypes().contains(CardType.LAND);
            boolean lendaria = c.getSupertypes().contains(SuperType.LEGENDARY) && c.getTypes().contains(CardType.CREATURE);
            Map<String, String> x = new LinkedHashMap<>();
            x.put("nome", nome); x.put("set", c.getSetCode()); x.put("num", c.getCardNumber());
            x.put("tipo", String.valueOf(c.getTypes())); x.put("raridade", String.valueOf(c.getRarity()));
            x.put("subtipos", String.valueOf(c.getSubTypes())); x.put("cmc", String.valueOf(c.getManaValue()));
            StringBuilder cores = new StringBuilder();
            if (c.getColor().isWhite()) cores.append('W'); if (c.getColor().isBlue()) cores.append('U'); if (c.getColor().isBlack()) cores.append('B');
            if (c.getColor().isRed()) cores.append('R'); if (c.getColor().isGreen()) cores.append('G');
            x.put("cores", cores.toString());
            x.put("texto", String.join(" | ", c.getRules()).replaceAll("<[^>]+>", ""));
            // identidade de cor (Commander): inclui os símbolos de mana do texto das habilidades
            try {
                mage.filter.FilterMana id = mage.util.ManaUtil.getColorIdentity(c.createCard());
                StringBuilder ident = new StringBuilder();
                if (id.isWhite()) ident.append('W'); if (id.isBlue()) ident.append('U'); if (id.isBlack()) ident.append('B'); if (id.isRed()) ident.append('R'); if (id.isGreen()) ident.append('G');
                x.put("identidade", ident.toString());
            } catch (Throwable e) { x.put("identidade", cores.toString()); }
            Rarity r = c.getRarity();
            x.put("lendaria", lendaria ? "1" : "");
            if (terreno) pool.get("terreno").add(x);   // terrenos não básicos: espaço próprio no booster
            else if (r == Rarity.COMMON) pool.get("comum").add(x);
            else if (r == Rarity.UNCOMMON) pool.get("incomum").add(x);
            else if (r == Rarity.RARE) pool.get("rara").add(x);
            else if (r == Rarity.MYTHIC) pool.get("mitica").add(x);
        }
        Files.write(Paths.get(a[0]), new GsonBuilder().setPrettyPrinting().create().toJson(pool).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, List<Map<String, String>>> e : pool.entrySet()) System.out.println(e.getKey() + ": " + e.getValue().size());
        System.exit(0);
    }
}
