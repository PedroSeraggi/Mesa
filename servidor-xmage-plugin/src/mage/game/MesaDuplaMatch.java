package mage.game;

import mage.game.match.MatchOptions;
import mage.game.match.MatchPlayer;
import mage.util.RandomUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Commander 2v2 da Mesa: igual ao Commander Free For All, mas em vez de embaralhar a ordem
 * dos jogadores livremente, sorteia alternando humano e bot (humano, bot, humano, bot).
 * Com alcance de influência 1, cada jogador só alcança os bots vizinhos.
 */
public class MesaDuplaMatch extends CommanderFreeForAllMatch {

    public MesaDuplaMatch(MatchOptions options) {
        super(options);
    }

    @Override
    protected void shufflePlayers() {
        List<MatchPlayer> humanos = new ArrayList<>(), bots = new ArrayList<>();
        for (MatchPlayer mp : players) (mp.getPlayer().isHuman() ? humanos : bots).add(mp);
        Collections.shuffle(humanos, RandomUtil.getRandom());
        Collections.shuffle(bots, RandomUtil.getRandom());
        if (humanos.size() != bots.size()) { super.shufflePlayers(); return; }   // não é time contra time: comportamento normal
        boolean humanoPrimeiro = RandomUtil.nextBoolean();
        players.clear();
        for (int i = 0; i < humanos.size(); i++) {
            players.add(humanoPrimeiro ? humanos.get(i) : bots.get(i));
            players.add(humanoPrimeiro ? bots.get(i) : humanos.get(i));
        }
    }
}
