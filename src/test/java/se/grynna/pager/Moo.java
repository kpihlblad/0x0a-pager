package se.grynna.pager;

import com.formdev.flatlaf.FlatDarkLaf;
import lombok.extern.slf4j.Slf4j;
import raven.toast.Notifications;
import se.grynna.pager.exceptions.ExceptionMessageFormatter;
import se.grynna.pager.ui.autocomplete.AutoCompletion2;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

@Slf4j
public class Moo extends JPanel {

    private JPanel filterPanelContainer;
    private JScrollPane filterScrollPane;

    public Moo() {}

    public void initComponents() {

        setLayout(new GridBagLayout());
        setFocusable(true);
        requestFocusInWindow();
        // center, grow
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.weighty = 1.0;

        // new full-width scroll panel for filter rows
        filterPanelContainer = new JPanel();

        filterPanelContainer.removeAll();

        filterPanelContainer.setLayout(new BoxLayout(filterPanelContainer, BoxLayout.Y_AXIS));
        filterPanelContainer.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        filterScrollPane = new JScrollPane(filterPanelContainer);

        filterScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        filterScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        add(filterScrollPane, c);

        log.debug("drawing shit");
        // update the fields when pressing "f"
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent evt) {
                log.debug("key pressed sothing");
                if (evt.getKeyChar() == 'f') {
                    log.debug("key pressed");
                    updateFieldSummaryAction("index");
                }
            }
        });
    }

    private void updateFieldSummaryAction(String index) {

        log.debug("Updating field summary for index {}", index);

        SwingWorker<List<String>, Void> fetchDataWorker = new SwingWorker<>() {
            @Override
            protected List<String> doInBackground() {
                try {
                    return List.of("not a real field summary", "another field summary",
                            "really really long field summary with some stats",
                            "another long field summary with some stats", "a small fox jumps over the lazy dog",
                            "This library is free software; you can redistribute it and/or",
                            "modify it under the terms of the GNU Lesser General Public",
                            "License as published by the Free Software Foundation; either",
                            "Yes this is a really long field summary",
                            "This library is distributed in the hope that it will be useful,",
                            "but WITHOUT ANY WARRANTY; without even the implied warranty of",
                            "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU",
                            "Lesser General Public License for more details.",
                            "You should have received a copy of the GNU Lesser General Public",
                            "License along with this library; if not, see",
                            "Also add information on how to contact you by electronic and paper mail.",
                            "another long field summary with some stats", "a small fox jumps over the lazy dog",
                            "This library is free software; you can redistribute it and/or",
                            "modify it under the terms of the GNU Lesser General Public",
                            "License as published by the Free Software Foundation; either", "this is it");

                } catch (Throwable ex) {
                    log.error("Updating Fields failed", ex);
                    Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER,
                            ExceptionMessageFormatter.formatException(ex));
                    return null;
                }
            }

            @Override
            protected void done() {
                try {

                    var fieldSummaries = get();
                    if (fieldSummaries == null) return;

                    int preferredH = 0;

                    filterPanelContainer.removeAll();
                    filterPanelContainer.setLayout(new GridBagLayout());
                    GridBagConstraints gbc = new GridBagConstraints();
                    gbc.insets = new Insets(2, 2, 2, 2);
                    gbc.anchor = GridBagConstraints.WEST;
                    gbc.fill = GridBagConstraints.HORIZONTAL;
                    gbc.weightx = 0;
                    gbc.weighty = 0;
                    int row = 0;
                    log.debug("Field summaries: {}", fieldSummaries);
                    for (String fs : fieldSummaries) {

                        // name + stats (excluding values)
                        JLabel info = new JLabel(fs);
                        gbc.gridx = 0;
                        gbc.gridy = row;
                        filterPanelContainer.add(info, gbc);

                        // editable combobox for value selection
                        JComboBox<String> cb = new JComboBox<>(new String[]{null, "reallly long value is thise",
                                "longer value, random text, trumpet clock",
                                "no no even logner value, random text, snail trampoline",
                                "another long value, random text, trumpet clock", "a small fox jumps over the lazy dog",
                                "This library is free software; you can redistribute it and/or",
                                "modify it under the terms of the GNU Lesser General Public",
                                "License as published by the Free Software Foundation; either",
                                "version 2.1 of the License, or (at your option) any later version.",
                                "This library is distributed in the hope that it will be useful,",
                                "but WITHOUT ANY WARRANTY; without even the implied warranty of",
                                "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU",
                                "Lesser General Public License for more details.",
                                "another long value, random text, trumpet clock", "a small fox jumps over the lazy dog",
                                "This library is free software; you can redistribute it and/or",
                                "modify it under the terms of the GNU Lesser General Public",
                                "License as published by the Free Software Foundation; either",
                                "version 2.1 of the License, or (at your option) any later version.",
                                "This library is distributed in the hope that it will be useful,",
                                "but WITHOUT ANY WARRANTY; without even the implied warranty of",
                                "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU",
                                "Lesser General Public License for more details.",
                                "another long value, random text, trumpet clock", "a small fox jumps over the lazy dog",
                                "This library is free software; you can redistribute it and/or",
                                "modify it under the terms of the GNU Lesser General Public",
                                "License as published by the Free Software Foundation; either",
                                "version 2.1 of the License, or (at your option) any later version.",
                                "This library is distributed in the hope that it will be useful,",
                                "but WITHOUT ANY WARRANTY; without even the implied warranty of",
                                "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU",
                                "Lesser General Public License for more details.",
                                "You should have received a copy of the GNU Lesser General Public",
                                "License along with this library; if not, see",
                                "Also add information on how to contact you by electronic and paper mail."});

                        cb.setEditable(true);
                        AutoCompletion2.enable(cb);

                        gbc.gridx = 1;
                        gbc.gridy = row;
                        filterPanelContainer.add(cb, gbc);

                        // delete icon when value chosen
                        JLabel del = new JLabel("X");
                        del.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        del.addMouseListener(new MouseAdapter() {
                            @Override
                            public void mouseClicked(MouseEvent e) {
                                cb.setSelectedItem("");
                            }
                        });
                        gbc.gridx = 2;
                        gbc.gridy = row;
                        filterPanelContainer.add(del, gbc);

                        row++;
                    }
                    // Add vertical glue to push everything up
                    gbc.gridwidth = 2;
                    gbc.weighty = 1.0;
                    filterPanelContainer.add(Box.createVerticalGlue(), gbc);
                    var totalHeight = preferredH;

                    log.debug("filterPanelContainer - totalHeight: {}", totalHeight);
                    filterPanelContainer.revalidate();
                    filterPanelContainer.repaint();

                } catch (Throwable e) {
                    log.error("Failed updating fields", e);
                }
            }
        };
        fetchDataWorker.execute();
    }

    // init flatlaf and create the main frame
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } catch (UnsupportedLookAndFeelException e) {
                log.error("Failed to apply FlatDarkLaf look and feel", e);
            }
            JFrame frame = new JFrame("Moo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setPreferredSize(new Dimension(800, 600));
            Moo moo = new Moo();
            moo.initComponents();
            frame.add(moo);
            frame.pack();
            frame.setVisible(true);
        });
    }
}
