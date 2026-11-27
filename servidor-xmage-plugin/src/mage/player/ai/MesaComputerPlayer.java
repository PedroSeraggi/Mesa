package mage.player.ai;

import mage.abilities.ActivatedAbility;
import mage.abilities.mana.ManaAbility;
import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.util.List;

/**
 * Bot da Mesa: a mesma IA "mad" do XMage (ComputerPlayerControllableProxy / ComputerPlayer7), com duas mudanças
 * para não travar a partida do meio para o fim:
 *
 * 1. Passa na hora quando há algo na pilha e ele não tem nenhuma resposta possível (só habilidades de mana).
 *    A IA original simula todas as jogadas a cada prioridade, mesmo quando só pode passar. Com a mesa cheia,
 *    isso gastava o tempo máximo inteiro antes de cada efeito resolver.
 *
 * 2. Tempo máximo por decisão: no máximo LIMITE_SEGUNDOS (o XMage usa nível x 3: 30 s no nível 10).
 *    A profundidade da simulação continua sendo o nível, então o bot difícil continua difícil.
 *
 * Entra no lugar do "Computer - mad" no config.xml do servidor (a ponte da Mesa ajusta isso sozinha).
 */
public class MesaComputerPlayer extends ComputerPlayerControllableProxy {

    static final int LIMITE_SEGUNDOS = 10;

    public MesaComputerPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        setMaxThinkTimeSecs(Math.max(3, Math.min(skill * 3, LIMITE_SEGUNDOS)));
    }

    public MesaComputerPlayer(final MesaComputerPlayer player) {
        super(player);
    }

    @Override
    public MesaComputerPlayer copy() {
        return new MesaComputerPlayer(this);
    }

    @Override
    public boolean priority(Game game) {
        // só na partida de verdade, com o bot jogando por ele mesmo (não em simulação nem controlado por outro jogador)
        if (!game.isSimulation() && isGameUnderControl() && !game.getStack().isEmpty() && semResposta(game)) {
            game.getState().setPriorityPlayerId(playerId);
            game.firePriorityEvent(playerId);
            pass(game);
            return false;
        }
        return super.priority(game);
    }

    /** Nada para fazer agora além de gerar mana: não há o que simular. */
    private boolean semResposta(Game game) {
        try {
            List<ActivatedAbility> jogaveis = getPlayable(game, true);
            for (ActivatedAbility a : jogaveis) {
                if (!(a instanceof ManaAbility)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;   // na dúvida, deixa a IA original decidir
        }
    }
}
