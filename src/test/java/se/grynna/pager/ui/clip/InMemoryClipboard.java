package se.grynna.pager.ui.clip;

import java.awt.datatransfer.Transferable;

/** A {@link ClipboardAccess} that keeps the contents in memory, so copy and paste run headless. */
public final class InMemoryClipboard implements ClipboardAccess {

    private Transferable contents;

    @Override
    public void setContents(Transferable contents) {
        this.contents = contents;
    }

    @Override
    public Transferable contents() {
        return contents;
    }
}
