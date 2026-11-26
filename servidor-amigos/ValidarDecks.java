import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;

import java.io.File;

/** Ferramenta: valida decks .dck como o servidor (formato Commander). Uso: java ValidarDecks <pasta> */
public class ValidarDecks {
    public static void main(String[] a) throws Exception {
        int ruins = 0;
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".dck"));
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            StringBuilder erros = new StringBuilder();
            DeckCardLists dl = DeckImporter.importDeckFromFile(f.getPath(), erros, false);
            Deck deck = Deck.load(dl, true, true);
            mage.deck.Commander v = new mage.deck.Commander();
            boolean ok = v.validate(deck);
            int total = deck.getCards().size() + deck.getSideboard().size();
            StringBuilder er = new StringBuilder();
            if (!ok) for (Object x : v.getErrorsListSorted()) {
                try { er.append(x.getClass().getMethod("getGroup").invoke(x)).append(": ").append(x.getClass().getMethod("getMessage").invoke(x)).append("; "); }
                catch (Exception e) { er.append(x).append("; "); }
            }
            if (!ok) ruins++;
            System.out.println((ok ? "OK    " : "FALHA ") + f.getName() + " (" + total + " cartas)" + (erros.length() > 0 ? " avisos: " + erros.toString().trim().replace('\n', ' ') : "") + (ok ? "" : " -> " + er));
        }
        System.out.println(ruins == 0 ? "TODOS VALIDOS" : ruins + " COM PROBLEMA");
        System.exit(0);
    }
}
