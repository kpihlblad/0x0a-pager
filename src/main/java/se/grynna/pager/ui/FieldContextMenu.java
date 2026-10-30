package se.grynna.pager.ui;

import se.grynna.pager.ui.details.EventDetails.Field;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * The right-click menu of a field in the event details: narrow or
 * exclude by the field's value, add it to or remove it from the message fields, copy. Every item calls the handler
 * of its key; the ones that do not apply to the field are disabled. Built apart from showing it, so it runs
 * headless.
 */
final class FieldContextMenu {

    /**
     * What the menu depends on.
     *
     * @param field         the selected field
     * @param canBeMessage  whether the field may be a message field (a field of a JSON event)
     * @param customMessage whether the index's message fields differ from the default (offers the reset)
     * @param keyMap        where the key hints come from
     */
    record Model(Field field, boolean canBeMessage, boolean customMessage, KeyMap keyMap) {}

    /** The handlers the items call. */
    interface Actions {
        void narrow();

        void exclude();

        void toggleMessage();

        void resetMessage();

        void copyValue();

        void copyTerm();
    }

    private FieldContextMenu() {}

    static JPopupMenu build(Model model, Actions actions) {
        KeyMap keys = model.keyMap();
        Field field = model.field();
        String term = DetailsFields.shortTermText(field);
        JPopupMenu menu = new JPopupMenu();

        menu.add(item(RowContextMenu.hinted("Narrow to " + term, keys.label(UiAction.DETAILS_NARROW_FIELD)),
                field.filterable(), actions::narrow));
        menu.add(item(RowContextMenu.hinted("Exclude " + term, keys.label(UiAction.DETAILS_EXCLUDE_FIELD)),
                field.filterable(), actions::exclude));
        menu.addSeparator();

        menu.add(item(RowContextMenu.hinted(field.inMessage() ? "Remove from message" : "Add to message",
                keys.label(UiAction.DETAILS_MESSAGE_FIELD)), model.canBeMessage(), actions::toggleMessage));
        if (model.customMessage()) {
            menu.add(item("Reset message fields", true, actions::resetMessage));
        }
        menu.addSeparator();

        menu.add(item(RowContextMenu.hinted("Copy value", keys.label(UiAction.COPY)), true, actions::copyValue));
        menu.add(item("Copy " + term, true, actions::copyTerm));
        return menu;
    }

    private static JMenuItem item(String text, boolean enabled, Runnable action) {
        JMenuItem item = RowContextMenu.item(text, action);
        item.setEnabled(enabled);
        return item;
    }
}
