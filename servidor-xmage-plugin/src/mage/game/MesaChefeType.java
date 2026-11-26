package mage.game;

import mage.game.match.MatchType;

/** Tipo de jogo "Commander Chefe Mesa": batalha de chefe da campanha (ver MesaChefeMatch). */
public class MesaChefeType extends MatchType {

    public MesaChefeType() {
        this.name = "Commander Chefe Mesa";
        this.maxPlayers = 10;
        this.minPlayers = 2;
        this.numTeams = 0;
        this.useAttackOption = true;
        this.useRange = true;
        this.sideboardingAllowed = false;
    }

    protected MesaChefeType(final MesaChefeType mt) {
        super(mt);
    }

    @Override
    public MesaChefeType copy() {
        return new MesaChefeType(this);
    }
}
