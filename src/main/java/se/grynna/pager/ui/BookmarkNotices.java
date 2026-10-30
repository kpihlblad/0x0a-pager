package se.grynna.pager.ui;

/** The wording of the bookmark prompts and notices that scope changes, copy and quit share. */
public final class BookmarkNotices {

    private BookmarkNotices() {}

    public static String count(int n) {
        return n + (n == 1 ? " bookmark" : " bookmarks");
    }

    /** The status-bar Y/N question before a scope change drops bookmarks. */
    public static String outsidePrompt(int n) {
        return "%s outside the new scope and will be removed. Continue?"
                .formatted(n == 1 ? "1 bookmark is" : n + " bookmarks are");
    }

    /** The hint that follows the prompt: how to keep the bookmarks. */
    public static String keepHint() {
        return "N, then ☰ Copy bookmarks or Export… keeps them";
    }

    /** The confirm dialog text inside the index overlay. */
    public static String outsideDialog(int n) {
        return outsidePrompt(n).replace(" Continue?", "")
                + "\nCancel, then use ☰ Copy bookmarks or Export… to keep them."
                + "\n\nContinue?";
    }

    public static String removed(int n) {
        return count(n) + " removed";
    }

    public static String copied(int n) {
        return "Copied " + count(n);
    }

    /** The quit/logout dialog text. */
    public static String unsaved(int n) {
        return "You have " + count(n) + " that " + (n == 1 ? "is" : "are") + " not exported or copied.";
    }
}
