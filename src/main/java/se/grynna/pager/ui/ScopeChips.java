package se.grynna.pager.ui;

import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The scope's filters in the status row: one chip per field filter and narrowing term
 * {@code +"term"} or {@code +field="value"}, and per excluded term {@code −"term"} or {@code −field="value"}, each
 * with a × that removes it. Hidden while the scope has no filters or terms. Long terms are elided and
 * only the first {@link #MAX_CHIPS} get chips; the rest collapse into {@code +N more}
 * whose tooltip lists them all. Chips are not focusable.
 */
public class ScopeChips extends JPanel {

    static final int MAX_CHIPS = 3;
    static final int ELIDE_AT = 24;
    private static final String NARROW_SIGN = "+";
    private static final String EXCLUDE_SIGN = "\u2212";

    private final Font font;
    private final Consumer<String> onRemove;

    /**
     * @param font     the status row font
     * @param onRemove called on the EDT with the term whose × was pressed
     */
    public ScopeChips(Font font, Consumer<String> onRemove) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        this.font = font;
        this.onRemove = onRemove;
        setOpaque(false);
        setVisible(false);
    }

    /** Rebuild the chips for the given narrowing and excluded terms. */
    public void setTerms(List<String> narrow, List<String> exclude) {
        removeAll();
        List<String> signs = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (String term : narrow) {
            signs.add(NARROW_SIGN);
            terms.add(term);
        }
        for (String term : exclude) {
            signs.add(EXCLUDE_SIGN);
            terms.add(term);
        }
        int shown = Math.min(MAX_CHIPS, terms.size());
        for (int i = 0; i < shown; i++) {
            add(chip(signs.get(i), terms.get(i), i < narrow.size()));
        }
        if (terms.size() > shown) {
            add(overflowLabel(terms.size() - shown, terms, signs));
        }
        setVisible(!terms.isEmpty());
        revalidate();
        repaint();
    }

    /**
     * Chip text for one term: {@code +"term"} or {@code −"term"}, the term elided when long; a field term
     * reads {@code +field="value"}.
     */
    static String chipText(String sign, String term) {
        return sign + FieldTerm.decode(term)
                .map(field -> field.field() + "=\"" + elide(field.value()) + "\"")
                .orElseGet(() -> "\"" + elide(term) + "\"");
    }

    static String elide(String term) {
        return term.length() <= ELIDE_AT ? term : term.substring(0, ELIDE_AT) + "…";
    }

    private JPanel chip(String sign, String term, boolean narrowing) {
        Color accent = narrowing ? DarculaColors.CYAN : DarculaColors.PINK;
        JLabel text = new JLabel(chipText(sign, term));
        text.setFont(font);
        text.setForeground(accent);
        text.setToolTipText(sign + " " + FieldTerm.display(term));

        JButton remove = new JButton("×");
        remove.setFont(font);
        remove.setForeground(accent);
        remove.setMargin(new Insets(0, 3, 0, 3));
        remove.setBorderPainted(false);
        remove.setContentAreaFilled(false);
        remove.setFocusable(false);
        remove.setToolTipText("Remove " + FieldTerm.display(term) + " from the scope");
        remove.addActionListener(e -> onRemove.accept(term));

        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        chip.setOpaque(false);
        chip.setBorder(BorderFactory.createLineBorder(accent, 1, true));
        chip.add(text);
        chip.add(remove);
        return chip;
    }

    private JLabel overflowLabel(int hidden, List<String> terms, List<String> signs) {
        StringBuilder all = new StringBuilder("<html>");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                all.append("<br>");
            }
            all.append(signs.get(i)).append(' ').append(escapeHtml(FieldTerm.display(terms.get(i))));
        }
        JLabel more = new JLabel("+%d more".formatted(hidden));
        more.setFont(font);
        more.setForeground(DarculaColors.GREY);
        more.setToolTipText(all.append("</html>").toString());
        return more;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
