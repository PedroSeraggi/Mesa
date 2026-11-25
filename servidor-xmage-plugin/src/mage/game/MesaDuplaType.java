package mage.game;

import mage.game.match.MatchType;

/** Tipo de jogo "Commander 2v2 Mesa" (ver MesaDuplaMatch). */
public class MesaDuplaType extends MatchType {

    public MesaDuplaType() {
        this.name = "Commander 2v2 Mesa";
        this.maxPlayers = 10;
        this.minPlayers = 3;
        this.numTeams = 0;
        this.useAttackOption = true;
        this.useRange = true;
        this.sideboardingAllowed = false;
    }

    protected MesaDuplaType(final MesaDuplaType mt) {
        super(mt);
    }

    @Override
    public MesaDuplaType copy() {
        return new MesaDuplaType(this);
    }
}
