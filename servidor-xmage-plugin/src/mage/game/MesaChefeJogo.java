package mage.game;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.condition.Condition;
import mage.abilities.decorator.ConditionalContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.EndTurnEffect;
import mage.abilities.effects.common.continuous.BoostControlledEffect;
import mage.abilities.triggers.BeginningOfUpkeepTriggeredAbility;
import mage.constants.Duration;
import mage.constants.MultiplayerAttackOption;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.SubType;
import mage.constants.TargetController;
import mage.constants.Zone;
import mage.filter.common.FilterCreaturePermanent;
import mage.game.command.Emblem;
import mage.game.mulligan.Mulligan;
import mage.game.permanent.token.GoblinToken;
import mage.game.turn.TurnMod;
import mage.players.Player;

import java.util.UUID;

/**
 * Commander com chefe da campanha da Mesa. O jogador chamado "Exército de Krenko" recebe um emblema com:
 *  - no começo da manutenção dele, a cada 3 manutenções: cria duas fichas Goblin 1/1;
 *  - na primeira manutenção dele a partir do turno 5: cada oponente pula o próximo turno ("Guardas, segurem ele!");
 *  - enquanto ele tiver menos de 20 de vida: os Goblins dele ganham +1/+0.
 */
public class MesaChefeJogo extends CommanderFreeForAll {

    public static final String KRENKO = "Exército de Krenko";

    public MesaChefeJogo(MultiplayerAttackOption attackOption, RangeOfInfluence range, Mulligan mulligan, int startLife, int startHandSize) {
        super(attackOption, range, mulligan, startLife, startHandSize);
    }

    protected MesaChefeJogo(final MesaChefeJogo game) {
        super(game);
    }

    @Override
    protected void init(UUID choosingPlayerId) {
        // contra o Krenko o jogador sempre começa: assim o turno 5 é dele (é nele que os Guardas agem)
        boolean temChefe = false; UUID humano = null;
        for (Player p : getPlayers().values()) {
            if (p.getName() != null && p.getName().contains("Krenko")) temChefe = true;
            else if (p.isHuman() && humano == null) humano = p.getId();
        }
        if (temChefe && humano != null && startingPlayerId == null) startingPlayerId = humano;
        super.init(choosingPlayerId);
        for (Player p : getPlayers().values()) {
            if (p.getName() == null || !p.getName().contains("Krenko")) continue;
            MageObject fonte = null;
            for (UUID id : p.getCommandersIds()) { fonte = getCard(id); if (fonte != null) break; }
            if (fonte == null) fonte = p.getLibrary().getFromTop(this);
            if (fonte == null) { informPlayers("[Mesa] Chefe sem carta de origem para o emblema"); continue; }
            addEmblem(new KrenkoEmblema(), fonte, p.getId());
            informPlayers("Krenko recebe o Emblema do Chefe: Horda, Guardas e Fúria.");
        }
    }

    @Override
    public MesaChefeJogo copy() {
        return new MesaChefeJogo(this);
    }

    // ---------------- emblema ----------------
    public static class KrenkoEmblema extends Emblem {
        public KrenkoEmblema() {
            super("Emblema do Chefe: Krenko");
            this.getAbilities().add(new BeginningOfUpkeepTriggeredAbility(Zone.COMMAND, TargetController.OPPONENT, new KrenkoGuardas(), false));
            this.getAbilities().add(new BeginningOfUpkeepTriggeredAbility(Zone.COMMAND, TargetController.YOU, new KrenkoManutencao(), false));
            FilterCreaturePermanent goblins = new FilterCreaturePermanent(SubType.GOBLIN, "Goblins");
            this.getAbilities().add(new SimpleStaticAbility(Zone.COMMAND, new ConditionalContinuousEffect(
                    new BoostControlledEffect(1, 0, Duration.WhileOnBattlefield, goblins, false),
                    new VidaAbaixoDe20(),
                    "Fúria: enquanto você tiver menos de 20 de vida, os Goblins que você controla recebem +1/+0")));
        }

        /** O XMage procura a imagem do emblema na lista oficial de fichas; este é nosso, então usa a imagem da carta de origem. */
        @Override
        public void setSourceObjectAndInitImage(MageObject fonte) {
            this.sourceObject = fonte;
            if (fonte != null) {
                setExpansionSetCode(fonte.getExpansionSetCode());
                setCardNumber(fonte.getCardNumber());
            }
        }

        private KrenkoEmblema(final KrenkoEmblema e) {
            super(e);
        }

        @Override
        public KrenkoEmblema copy() {
            return new KrenkoEmblema(this);
        }
    }

    public static class VidaAbaixoDe20 implements Condition {
        @Override
        public boolean apply(Game game, Ability source) {
            Player p = game.getPlayer(source.getControllerId());
            return p != null && p.getLife() < 20;
        }

        @Override
        public String toString() {
            return "você tem menos de 20 de vida";
        }
    }

    /** Guardas: no primeiro turno de um oponente a partir do turno 5, o turno dele acaba na manutenção (ele perde o turno). */
    public static class KrenkoGuardas extends OneShotEffect {
        public KrenkoGuardas() {
            super(Outcome.Benefit);
            staticText = "Guardas: no primeiro turno de um oponente a partir do turno 5, esse oponente perde o turno";
        }

        private KrenkoGuardas(final KrenkoGuardas e) {
            super(e);
        }

        @Override
        public KrenkoGuardas copy() {
            return new KrenkoGuardas(this);
        }

        @Override
        public boolean apply(Game game, Ability source) {
            String chave = "mesaChefe:" + source.getControllerId() + ":guardas";
            if (game.getTurnNum() < 5 || game.getState().getValue(chave) != null) return false;
            game.getState().setValue(chave, Boolean.TRUE);
            game.informPlayers("Krenko: Guardas, segurem ele! (o turno de " + game.getPlayer(game.getActivePlayerId()).getName() + " acaba agora)");
            return new EndTurnEffect().apply(game, source);
        }
    }

    public static class KrenkoManutencao extends OneShotEffect {
        public KrenkoManutencao() {
            super(Outcome.Benefit);
            staticText = "Horda: a cada 3 manutenções suas, crie duas fichas Goblin 1/1 vermelhas";
        }

        private KrenkoManutencao(final KrenkoManutencao e) {
            super(e);
        }

        @Override
        public KrenkoManutencao copy() {
            return new KrenkoManutencao(this);
        }

        @Override
        public boolean apply(Game game, Ability source) {
            UUID eu = source.getControllerId();
            String chave = "mesaChefe:" + eu;
            Integer n = (Integer) game.getState().getValue(chave + ":manut");
            n = (n == null ? 0 : n) + 1;
            game.getState().setValue(chave + ":manut", n);
            if (n % 3 == 0) {
                new CreateTokenEffect(new GoblinToken(), 2).apply(game, source);
                game.informPlayers("Krenko: Mais goblins! Eu SEMPRE tenho mais!");
            }
            return true;
        }
    }
}
