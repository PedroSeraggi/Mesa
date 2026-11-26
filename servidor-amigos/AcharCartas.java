import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;

import java.util.List;

/** Ferramenta: mostra edição, número e tipo de cartas pelo nome (uso: java AcharCartas "Nome 1" "Nome 2" ...). */
public class AcharCartas {
    public static void main(String[] a) {
        for (String n : a) {
            List<CardInfo> l = CardRepository.instance.findCards(n);
            if (l.isEmpty()) { System.out.println("NAO ACHEI: " + n); continue; }
            CardInfo c = l.get(0);
            System.out.println(n + " | " + c.getSetCode() + " | " + c.getCardNumber() + " | " + c.getTypes() + " " + c.getSupertypes() + " | " + c.getRarity() + " | cor " + c.getColor());
        }
        System.exit(0);
    }
}
