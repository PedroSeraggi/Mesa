package mage.game;

import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.mulligan.Mulligan;

/** Igual ao Commander Free For All, mas o jogo é um MesaChefeJogo (chefe com emblema de regras). */
public class MesaChefeMatch extends MatchImpl {

    public MesaChefeMatch(MatchOptions options) {
        super(options);
    }

    @Override
    public void startGame() throws GameException {
        int vida = 40;
        Mulligan mulligan = options.getMulliganType().getMulligan(options.getFreeMulligans());
        if (options.isCustomStartLifeEnabled()) vida = options.getCustomStartLife();
        int mao = options.isCustomStartHandSizeEnabled() ? options.getCustomStartHandSize() : 7;
        MesaChefeJogo game = new MesaChefeJogo(options.getAttackOption(), options.getRange(), mulligan, vida, mao);
        game.setStartMessage(this.createGameStartMessage());
        initGame(game);
        games.add(game);
    }
}
