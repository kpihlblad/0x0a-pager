package se.grynna.pager.ui.debug;

import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.clip.Copier;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

public class CopierDebugWindow extends JFrame {
    private final JTextArea textArea;
    private final JButton submitButton;

    public CopierDebugWindow() {
        setTitle("Text Input Window");
        setSize(640, 480); // Approx 80×50 chars (assuming ~8px/char width, ~9.6px/char height)
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);

        // Create layout
        setLayout(new BorderLayout());

        // Text area
        textArea = new JTextArea(50, 80);
        textArea.setFont(Fontalizer.getFont());
        JScrollPane scrollPane = new JScrollPane(textArea);
        add(scrollPane, BorderLayout.CENTER);

        // Submit button
        submitButton = new JButton("Submit");
        submitButton.addActionListener(e -> submit());
        add(submitButton, BorderLayout.SOUTH);

        setVisible(true);
    }

    public void submit() {
        String text = textArea.getText();
        Copier.copy(Arrays.asList(text.split("\\r?\\n")));
    }
}
