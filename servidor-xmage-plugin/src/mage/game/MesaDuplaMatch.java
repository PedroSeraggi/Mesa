package mage.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.util.RandomUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Commander 2v2 da Mesa: igual ao Commander Free For All, mas em vez de embaralhar a ordem
 * dos jogadores livremente, sorteia alternando os dois times (A, B, A, B).
 * - Contra bots: os humanos são um time e os bots o outro.
 * - Online (só humanos): os times vêm do arquivo times/<mesa>.json, que o servidor de amigos
 *   grava quando o dono começa a partida.
 * Com alcance de influência 1, cada jogador só alcança os adversários vizinhos.
 */
public class MesaDuplaMatch extends CommanderFreeForAllMatch {

    public MesaDuplaMatch(MatchOptions options) {
        super(options);
    }

    @Override
    protected void shufflePlayers() {
        List<MatchPlayer> a = new ArrayList<>(), b = new ArrayList<>();
        List<MatchPlayer> humanos = new ArrayList<>(), bots = new ArrayList<>();
        for (MatchPlayer mp : players) (mp.getPlayer().isHuman() ? humanos : bots).add(mp);
        if (!bots.isEmpty()) { a.addAll(humanos); b.addAll(bots); }
        else {
            Set<String> timeA = lerTimeA();
            if (timeA == null) { super.shufflePlayers(); return; }
            for (MatchPlayer mp : players) (timeA.contains(mp.getName()) ? a : b).add(mp);
        }
        if (a.size() != b.size() || a.isEmpty()) { super.shufflePlayers(); return; }   // times desiguais: comportamento normal
        Collections.shuffle(a, RandomUtil.getRandom());
        Collections.shuffle(b, RandomUtil.getRandom());
        boolean aPrimeiro = RandomUtil.nextBoolean();
        players.clear();
        for (int i = 0; i < a.size(); i++) {
            players.add(aPrimeiro ? a.get(i) : b.get(i));
            players.add(aPrimeiro ? b.get(i) : a.get(i));
        }
    }

    /** Nomes do time A desta mesa (null se não houver arquivo). */
    private Set<String> lerTimeA() {
        try {
            if (tableId == null) return null;
            Path f = Paths.get("times", tableId + ".json");
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("timeA");
            Set<String> s = new HashSet<>();
            for (JsonElement e : arr) s.add(e.getAsString());
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
