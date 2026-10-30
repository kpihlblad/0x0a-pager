package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;

/** Pure identity/offset anchors; no Swing state, I/O or millisecond rounding. */
final class Reanchor {
    private Reanchor() {}

    record Anchor(SearchHit hit, int offset) {}

    record View(Anchor top, int topScreenRow, Anchor cursor, Anchor mark, Anchor highlight, int oldPageStart) {}

    record Placement(int pageStart, Integer cursor, Integer mark, Integer highlight) {}

    static Anchor anchor(EventListProvider provider, Integer row) {
        if (row == null || provider.rows().rowCount() == 0) return null;
        int valid = Math.clamp(row, 0, provider.rows().rowCount() - 1);
        return new Anchor(provider.hitAt(valid), provider.offsetWithin(valid));
    }

    static View capture(EventListProvider provider, CursorManager.ViewState state) {
        int top = Math.clamp(state.pageStart(), 0, Math.max(0, provider.rows().rowCount() - 1));
        return new View(anchor(provider, top), top - state.pageStart(), anchor(provider, state.cursorLine()),
                anchor(provider, state.markLine()), anchor(provider, state.navHighlightLine()), state.pageStart());
    }

    static Integer resolve(EventListProvider provider, Anchor anchor) {
        if (anchor == null || provider.rows().rowCount() == 0) return null;
        int first = provider.rowOfHit(anchor.hit());
        if (first < 0) first = provider.jump(anchor.hit().splunkTime());
        int event = provider.rows().eventAt(first);
        return provider.rows().rowOf(event, anchor.offset());
    }

    static Placement place(EventListProvider provider, View view) {
        if (provider.rows().rowCount() == 0) return new Placement(0, null, null, null);
        Integer top = resolve(provider, view.top());
        return new Placement(top == null ? view.oldPageStart() : top - view.topScreenRow(),
                resolve(provider, view.cursor()), resolve(provider, view.mark()), resolve(provider, view.highlight()));
    }
}
