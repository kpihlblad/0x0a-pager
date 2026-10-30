package se.grynna.pager.ui.login;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The multi-select index picker: selection key, pending edit and commit, chips and the popup filter. */
class IndexPickerTest {

    private IndexPicker picker;
    private final List<String> fired = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        onEdt(() -> {
            picker = new IndexPicker();
            picker.setIndexes(new TreeSet<>(List.of("web", "db", "app_logs", "k8s_prod")));
            picker.setOnChange(fired::add);
        });
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private String key() throws Exception {
        return onEdt(() -> picker.getSelectionKey());
    }

    @Test
    void startsEmptyWithTheKnownIndexesCounted() throws Exception {
        assertThat(key()).isEmpty();
        assertThat(onEdt(() -> picker.choiceCount())).isEqualTo(4);
        assertThat(onEdt(() -> picker.chipTexts())).isEmpty();
    }

    @Test
    void checkingAllUnchecksEveryIndex() throws Exception {
        onEdt(() -> {
            picker.openPending();
            picker.togglePending("web");
            picker.togglePending("db");
            picker.togglePending("*");
            picker.commitPending();
        });

        assertThat(key()).isEqualTo("*");
        assertThat(fired).containsExactly("*");
    }

    @Test
    void checkingAnIndexUnchecksAll() throws Exception {
        onEdt(() -> picker.setSelectionKey("*"));
        fired.clear();

        onEdt(() -> {
            picker.openPending();
            picker.togglePending("web");
            picker.commitPending();
        });

        assertThat(key()).isEqualTo("web");
        assertThat(fired).containsExactly("web");
    }

    @Test
    void uncheckingAllLeavesNothing() throws Exception {
        onEdt(() -> picker.setSelectionKey("*"));
        fired.clear();

        onEdt(() -> {
            picker.togglePending("*");
            picker.commitPending();
        });

        assertThat(key()).isEmpty();
        assertThat(fired).containsExactly("");
    }

    @Test
    void pendingToggleDoNotFireUntilCommitted() throws Exception {
        onEdt(() -> {
            picker.openPending();
            picker.togglePending("web");
            picker.togglePending("db");
            picker.togglePending("k8s_prod");
        });

        assertThat(key()).isEmpty();
        assertThat(fired).isEmpty();

        onEdt(() -> picker.commitPending());

        assertThat(key()).isEqualTo("db,k8s_prod,web");
        assertThat(fired).containsExactly("db,k8s_prod,web");
    }

    @Test
    void commitFiresOnceAndOnlyWhenTheKeyChanged() throws Exception {
        onEdt(() -> picker.setSelectionKey("web"));
        fired.clear();

        // Ticked and unticked again: the key is the same
        onEdt(() -> {
            picker.openPending();
            picker.togglePending("db");
            picker.togglePending("db");
            picker.commitPending();
        });
        assertThat(fired).isEmpty();

        // A second commit without a pending edit does nothing
        onEdt(() -> {
            picker.openPending();
            picker.togglePending("db");
            picker.commitPending();
            picker.commitPending();
        });
        assertThat(fired).containsExactly("db,web");
    }

    @Test
    void togglingWithoutAnOpenEditStartsOneFromTheSelection() throws Exception {
        onEdt(() -> picker.setSelectionKey("web"));
        fired.clear();

        onEdt(() -> {
            picker.togglePending("db");
            picker.commitPending();
        });

        assertThat(key()).isEqualTo("db,web");
    }

    @Test
    void theChipCrossCommitsAtOnce() throws Exception {
        onEdt(() -> picker.setSelectionKey("db,web"));
        fired.clear();

        onEdt(() -> picker.removeChip("db"));

        assertThat(key()).isEqualTo("web");
        assertThat(fired).containsExactly("web");

        onEdt(() -> picker.removeChip("web"));
        assertThat(key()).isEmpty();
        assertThat(fired).containsExactly("web", "");
    }

    @Test
    void theAllChipCrossClearsTheSelection() throws Exception {
        onEdt(() -> picker.setSelectionKey("*"));
        assertThat(onEdt(() -> picker.chipTexts())).containsExactly("all indexes \u00D7");
        fired.clear();

        onEdt(() -> picker.removeChip("*"));

        assertThat(key()).isEmpty();
        assertThat(fired).containsExactly("");
    }

    @Test
    void setIndexesKeepsTheSelectionAndFiresNothing() throws Exception {
        onEdt(() -> picker.setSelectionKey("db,web"));
        fired.clear();

        onEdt(() -> picker.setIndexes(new TreeSet<>(List.of("web", "other"))));

        assertThat(key()).isEqualTo("db,web");
        assertThat(fired).isEmpty();
        assertThat(onEdt(() -> picker.choiceCount())).isEqualTo(2);
        // db is no longer listed but still has a chip
        assertThat(onEdt(() -> picker.chipTexts())).containsExactly("db \u00D7", "web \u00D7");
    }

    @Test
    void aSelectedIndexMissingFromTheListStillHasAChip() throws Exception {
        onEdt(() -> picker.setSelectionKey("gone"));

        assertThat(onEdt(() -> picker.chipTexts())).containsExactly("gone \u00D7");
        assertThat(key()).isEqualTo("gone");
    }

    @Test
    void setSelectionKeyNormalizesAndFiresOnlyOnAChange() throws Exception {
        onEdt(() -> picker.setSelectionKey("web, db ,web"));
        assertThat(key()).isEqualTo("db,web");
        assertThat(fired).containsExactly("db,web");

        onEdt(() -> picker.setSelectionKey("web,db"));
        assertThat(fired).hasSize(1);

        onEdt(() -> picker.setSelectionKey(null));
        assertThat(key()).isEmpty();
        assertThat(fired).containsExactly("db,web", "");
    }

    @Test
    void chipsThatDoNotFitCollapseIntoPlusNMore() throws Exception {
        onEdt(() -> {
            picker.setIndexes(new TreeSet<>(List.of("index_number_one", "index_number_two", "index_number_three",
                    "index_number_four", "index_number_five", "index_number_six")));
            picker.setSelectionKey("index_number_one,index_number_two,index_number_three,index_number_four,"
                    + "index_number_five,index_number_six");
        });

        List<String> chips = onEdt(() -> picker.chipTexts());

        assertThat(chips).hasSizeBetween(2, 6);
        assertThat(chips.getLast()).matches("\\+\\d+ more");
        int hidden = Integer.parseInt(chips.getLast().replaceAll("\\D", ""));
        assertThat(chips.size() - 1 + hidden).isEqualTo(6);
    }

    @Test
    void anIndexListThatFitsShowsEveryChipWithoutOverflow() throws Exception {
        onEdt(() -> picker.setSelectionKey("db,web"));

        assertThat(onEdt(() -> picker.chipTexts())).containsExactly("db \u00D7", "web \u00D7");
    }

    @Test
    void visibleChoicesListsAllThenTheKnownIndexesSorted() throws Exception {
        assertThat(onEdt(() -> picker.visibleChoices(""))).containsExactly("*", "app_logs", "db", "k8s_prod", "web");
    }

    @Test
    void theFilterIsACaseInsensitiveSubstring() throws Exception {
        assertThat(onEdt(() -> picker.visibleChoices("K8S"))).containsExactly("k8s_prod");
        assertThat(onEdt(() -> picker.visibleChoices("_"))).containsExactly("app_logs", "k8s_prod");
        assertThat(onEdt(() -> picker.visibleChoices("zzz"))).isEmpty();
    }

    @Test
    void theAllEntryShowsForAnEmptyFilterAStarOrAPrefixOfAll() throws Exception {
        assertThat(onEdt(() -> picker.visibleChoices("*"))).containsExactly("*");
        assertThat(onEdt(() -> picker.visibleChoices("a"))).startsWith("*").contains("app_logs");
        assertThat(onEdt(() -> picker.visibleChoices("al"))).containsExactly("*");
        assertThat(onEdt(() -> picker.visibleChoices("ALL"))).containsExactly("*");
        assertThat(onEdt(() -> picker.visibleChoices("alls"))).isEmpty();
        assertThat(onEdt(() -> picker.visibleChoices("db"))).containsExactly("db");
    }

    @Test
    void aDisabledPickerKeepsItsSelection() throws Exception {
        onEdt(() -> {
            picker.setSelectionKey("web");
            picker.setEnabled(false);
        });

        assertThat(onEdt(() -> picker.isEnabled())).isFalse();
        assertThat(key()).isEqualTo("web");
    }
}
