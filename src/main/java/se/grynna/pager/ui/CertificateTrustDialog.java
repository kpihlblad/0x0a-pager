package se.grynna.pager.ui;

import se.grynna.pager.splunk.cert.CertificateChallenge;
import se.grynna.pager.splunk.cert.ServerCertificate;
import se.grynna.pager.splunk.cert.TrustDecision;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modal dialog shown when a Splunk server presents a certificate that the system does
 * not trust - most commonly a self-signed certificate from a local test instance.
 *
 * <p>The dialog explains why the certificate is untrusted, shows its fingerprint and
 * other identifying details, and lets the user either trust it (remembered for next
 * time) or refuse the connection. When a previously trusted certificate has changed,
 * the change is highlighted so it can be recognised as a possible interception.
 */
public class CertificateTrustDialog extends JDialog {

    private static final Font FONT = Fontalizer.getFont();
    private static final Font BOLD = Fontalizer.getBoldFont();
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final CertificateChallenge challenge;
    private Consumer<TrustDecision> onDecision;
    private boolean decided;

    public CertificateTrustDialog(Component parent, CertificateChallenge challenge) {
        super(getParentFrame(parent), titleFor(challenge), true);
        this.challenge = challenge;
        initialize();
    }

    /**
     * Show the dialog and report the user's decision to {@code onDecision}.
     * Must be called on the Swing Event Dispatch Thread.
     */
    public static void show(Component parent, CertificateChallenge challenge, Consumer<TrustDecision> onDecision) {
        CertificateTrustDialog dialog = new CertificateTrustDialog(parent, challenge);
        dialog.setOnDecision(onDecision);
        dialog.setVisible(true);
    }

    public void setOnDecision(Consumer<TrustDecision> onDecision) {
        this.onDecision = onDecision;
    }

    private static Frame getParentFrame(Component parent) {
        if (parent instanceof Frame f) {
            return f;
        }
        return (Frame) SwingUtilities.getAncestorOfClass(Frame.class, parent);
    }

    private static String titleFor(CertificateChallenge challenge) {
        return switch (challenge.reason()) {
            case CHANGED -> "Certificate Changed";
            case EXPIRED -> "Certificate Expired";
            case UNTRUSTED -> challenge.certificate().classification() == ServerCertificate.Classification.SELF_SIGNED
                    ? "Self-Signed Certificate"
                    : "Untrusted Certificate";
        };
    }

    private void initialize() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                decide(TrustDecision.REJECT);
            }
        });

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBackground(DarculaColors.BACKGROUND);
        content.setBorder(new EmptyBorder(18, 22, 16, 22));

        content.add(createHeader(), BorderLayout.NORTH);
        content.add(createBody(), BorderLayout.CENTER);
        content.add(createButtons(), BorderLayout.SOUTH);

        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> decide(TrustDecision.REJECT),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(getParent());
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setBackground(DarculaColors.BACKGROUND);

        JLabel title = new JLabel(titleFor(challenge));
        title.setFont(BOLD.deriveFont(17f));
        title.setForeground(headerColor());
        header.add(title, BorderLayout.NORTH);

        JLabel host = new JLabel(challenge.certificate().host() + ":" + challenge.certificate().port());
        host.setFont(BOLD);
        host.setForeground(DarculaColors.CYAN);
        header.add(host, BorderLayout.CENTER);

        header.setBorder(new EmptyBorder(0, 0, 14, 0));
        return header;
    }

    private Color headerColor() {
        return switch (challenge.reason()) {
            case CHANGED -> DarculaColors.RED;
            case EXPIRED -> DarculaColors.ORANGE;
            case UNTRUSTED -> challenge.certificate().classification() == ServerCertificate.Classification.SELF_SIGNED
                    ? DarculaColors.ORANGE
                    : DarculaColors.RED;
        };
    }

    private JComponent createBody() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(DarculaColors.BACKGROUND);

        body.add(explanationPanel());
        body.add(Box.createVerticalStrut(12));

        if (challenge.isChanged()) {
            body.add(changedPanel());
            body.add(Box.createVerticalStrut(12));
        }

        body.add(detailsPanel());
        return body;
    }

    private JComponent explanationPanel() {
        String explanation = switch (challenge.reason()) {
            case CHANGED -> "The certificate presented by this server is different from the one you "
                    + "previously trusted. This happens after a reinstall or certificate rotation, but can "
                    + "also indicate that the connection is being intercepted. Compare the fingerprints below.";
            case EXPIRED -> "The certificate matches the one you trusted, but it is no longer within its "
                    + "validity period. Only continue if you understand why it has expired.";
            case UNTRUSTED -> challenge.certificate().classification() == ServerCertificate.Classification.SELF_SIGNED
                    ? "The server presented a self-signed certificate. This is typical for local or test "
                            + "installations (for example a Splunk test image), but it cannot be verified against a "
                            + "trusted certificate authority. Only continue if you recognise this server."
                    : "The server's certificate was issued by a certificate authority this system does not "
                            + "trust. This can be a local test setup, but may also indicate that someone is "
                            + "intercepting the connection. Only continue if you are sure this is expected.";
        };

        JTextArea area = new JTextArea(explanation);
        area.setFont(FONT);
        area.setForeground(DarculaColors.FOREGROUND);
        area.setBackground(DarculaColors.BACKGROUND);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setBorder(null);
        area.setColumns(68);
        area.setRows(4);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    private JComponent changedPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(DarculaColors.BACKGROUND_WARNING);
        panel.setBorder(new EmptyBorder(10, 12, 10, 12));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel warning = new JLabel("\u26A0  Fingerprint changed");
        warning.setFont(BOLD);
        warning.setForeground(DarculaColors.RED);
        panel.add(warning, BorderLayout.NORTH);

        JPanel fingerprints = new JPanel(new GridLayout(0, 1, 0, 4));
        fingerprints.setOpaque(false);
        fingerprints.add(fingerprintRow("Previously trusted", challenge.previouslyTrustedFingerprint()));
        fingerprints.add(fingerprintRow("Presented now", challenge.certificate().sha256Fingerprint()));
        panel.add(fingerprints, BorderLayout.CENTER);
        return panel;
    }

    private JComponent fingerprintRow(String label, String fingerprint) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel name = new JLabel(label + ":");
        name.setFont(FONT);
        name.setForeground(DarculaColors.GREY);
        name.setPreferredSize(new Dimension(130, 18));
        row.add(name, BorderLayout.WEST);

        JLabel value = new JLabel(fingerprint == null ? "-" : fingerprint);
        value.setFont(MONO);
        value.setForeground(DarculaColors.FOREGROUND);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private JComponent detailsPanel() {
        ServerCertificate cert = challenge.certificate();
        X509Certificate leaf = cert.leaf();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(DarculaColors.BACKGROUND_PLUS);
        panel.setBorder(new EmptyBorder(12, 14, 12, 14));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 14);

        addDetail(panel, c, "Subject", leaf.getSubjectX500Principal().getName());
        addDetail(panel, c, "Issuer", leaf.getIssuerX500Principal().getName());
        addDetail(panel, c, "Valid from", DATE.format(cert.notBefore()));
        addDetail(panel, c, "Valid until", DATE.format(cert.notAfter()) + (cert.isValidNow() ? "" : "  (expired)"));
        addDetail(panel, c, "Signature", leaf.getSigAlgName());
        addDetail(panel, c, "Covers host",
                cert.coversHost() ? cert.host() + "  \u2713" : cert.host() + "  \u2717  (name mismatch)");
        List<String> sans = cert.subjectAlternativeNames();
        if (!sans.isEmpty()) {
            addDetail(panel, c, "Alternative names", String.join(", ", sans));
        }
        addDetail(panel, c, "Chain length", String.valueOf(cert.chain().size()));
        addDetail(panel, c, "Type",
                cert.classification() == ServerCertificate.Classification.SELF_SIGNED
                        ? "Self-signed / locally generated"
                        : "Unknown certificate authority");
        addDetail(panel, c, "SHA-256", cert.sha256Fingerprint(), MONO);
        addDetail(panel, c, "SHA-256 (key)", cert.sha256SpkiFingerprint(), MONO);

        return panel;
    }

    private void addDetail(JPanel panel, GridBagConstraints c, String label, String value) {
        addDetail(panel, c, label, value, FONT);
    }

    private void addDetail(JPanel panel, GridBagConstraints c, String label, String value, Font valueFont) {
        JLabel name = new JLabel(label);
        name.setFont(BOLD);
        name.setForeground(DarculaColors.COMMENT);
        c.gridx = 0;
        c.weightx = 0;
        panel.add(name, c);

        JLabel val = new JLabel(value);
        val.setFont(valueFont);
        val.setForeground(DarculaColors.FOREGROUND);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(val, c);

        c.gridy++;
    }

    private JComponent createButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setBackground(DarculaColors.BACKGROUND);
        buttons.setBorder(new EmptyBorder(18, 0, 0, 0));

        JButton cancel = button("Cancel", false);
        cancel.addActionListener(e -> decide(TrustDecision.REJECT));
        buttons.add(cancel);

        JButton trustOnce = button("Trust Once", false);
        trustOnce.addActionListener(e -> decide(TrustDecision.TRUST_ONCE));
        buttons.add(trustOnce);

        JButton trust = button("Trust & Remember", true);
        trust.addActionListener(e -> decide(TrustDecision.TRUST));
        buttons.add(trust);

        getRootPane().setDefaultButton(cancel);
        return buttons;
    }

    private JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFont(BOLD);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(8, 18, 8, 18));
        button.setBackground(primary ? DarculaColors.BLUE : DarculaColors.CURRENT_LINE);
        button.setForeground(DarculaColors.FOREGROUND);
        return button;
    }

    private void decide(TrustDecision decision) {
        if (decided) {
            return;
        }
        decided = true;
        if (onDecision != null) {
            onDecision.accept(decision);
        }
        dispose();
    }
}
