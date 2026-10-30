package se.grynna.pager.ui.login;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import raven.toast.Notifications;
import se.grynna.pager.exceptions.ExceptionMessageFormatter;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.splunk.LoginReason;
import se.grynna.pager.splunk.SplunkCredentials;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.DomainGuesstimator;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collects and validates Splunk credentials from user.
 * Returns credentials via callback - does NOT create SearchManager.
 *
 * <h2>Error Message</h2>
 * Optional orange-colored error message displayed above form.
 * Set via setErrorMessage() to explain why re-login needed.
 *
 * <h2>Callbacks</h2>
 * <ul>
 *   <li>onLoginSuccess(SplunkCredentials) - validated credentials</li>
 *   <li>onCancel() - user cancelled</li>
 * </ul>
 */
@Slf4j
public class UserLoginPanel extends JPanel {

    private static final Border BORDER_HEADER = new EmptyBorder(14, 24, 10, 24);
    private static final Border BORDER_CONTENT = new EmptyBorder(4, 24, 8, 24);
    private static final Border BORDER_FOOTER = new EmptyBorder(8, 24, 8, 24);
    private static final Dimension ERROR_PANEL_SIZE = new Dimension(460, 56);
    private static final int LABEL_WIDTH = 96;
    private static final float SMALL_FONT_SIZE = 11f;

    private static final String CRUMB = "0x0a pager \u276F";
    private static final String TITLE = "login";
    private static final String RELOGIN_TITLE = "login again";
    private static final String SERVER_SECTION = "Splunk\u00AE server";
    private static final String CREDENTIALS_SECTION = "Credentials";
    private static final String DEFAULT_PORT_TEXT = "default (8089)";
    private static final String UNREACHABLE_TITLE = "Splunk unreachable";
    static final String LOGIN_BUTTON_TEXT = "login";
    static final String RETRY_BUTTON_TEXT = "retry";
    static final String CANCEL_BUTTON_TEXT = "cancel";
    private static final String ACTION_CANCEL = "cancel";

    private final Config config;

    private final LoginBox box;
    private final Consumer<SplunkCredentials> loginConsumer;

    private final LoginReason reason;

    // LOGIN FIELDS
    private JTextField hostNameField;
    private JTextField portField;
    private JCheckBox portCheckbox;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private JButton cancelButton;

    public UserLoginPanel(Config config, LoginReason reason, Consumer<SplunkCredentials> loginConsumer) {

        this.config = config;
        this.reason = reason;
        this.loginConsumer = loginConsumer;
        setBackground(DarculaColors.BACKGROUND);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;

        this.box = new LoginBox();
        box.updateErrorMessage(reason);
        add(box, gbc);
    }

    /**
     * Request focus on the appropriate field (username or password).
     * Called by parent after the panel is added to the component hierarchy.
     */
    public void focusDefaultField() {
        // Set default button
        JRootPane rootPane = SwingUtilities.getRootPane(this);
        if (rootPane != null) {
            rootPane.setDefaultButton(loginButton);
        }

        if (reason.isUnreachable()) {
            loginButton.requestFocusInWindow();
        } else if (passwordField != null) {
            passwordField.requestFocusInWindow();
        }
    }

    private String buttonText() {
        return reason.isUnreachable() ? RETRY_BUTTON_TEXT : LOGIN_BUTTON_TEXT;
    }

    class LoginBox extends JPanel {
        private Font retroFont;
        private Font boldFont;
        private Font smallFont;
        private JTextArea errorArea;
        private JPanel errorPanel; // Container panel for error area

        public LoginBox() {
            initComponents();
        }

        public void updateErrorMessage(LoginReason reason) {
            if (errorPanel != null && errorArea != null) {
                if (reason.hasError()) {
                    errorArea.setText(reason.message());
                    errorPanel.setVisible(true);
                } else {
                    errorPanel.setVisible(false);
                }
                // Revalidate to adjust layout
                revalidate();
                repaint();
            }
        }

        public void initComponents() {
            var splunkHost = config.getSplunkHost();

            var userName = config.getSplunkUser();
            var splunkPort = config.getSplunkPort();
            var password = System.getenv("PASSWORD"); // for tests

            retroFont = Fontalizer.getFont();
            boldFont = Fontalizer.getBoldFont();
            smallFont = retroFont.deriveFont(SMALL_FONT_SIZE);

            setLayout(new BorderLayout());
            setBackground(DarculaColors.BACKGROUND);
            setBorder(BorderFactory.createLineBorder(DarculaColors.G4));

            hostNameField = new JTextField(splunkHost, 24);
            styleField(hostNameField);

            portField = new JTextField(String.valueOf(splunkPort), 6);
            styleField(portField);
            portCheckbox = new JCheckBox(DEFAULT_PORT_TEXT);
            styleCheckbox(portCheckbox);

            JPanel portPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            portPanel.setOpaque(false);
            portPanel.add(portField);
            portPanel.add(Box.createRigidArea(new Dimension(12, 0)));
            portPanel.add(portCheckbox);

            if (splunkPort == 8089) {
                portCheckbox.setSelected(true);
                portField.setEnabled(false);
            }

            portCheckbox.addActionListener(e -> {
                boolean defaultPort = portCheckbox.isSelected();
                portField.setEnabled(!defaultPort);
                if (defaultPort) {
                    portField.setText("8089");
                }
            });

            usernameField = new JTextField(userName, 24);
            styleField(usernameField);
            passwordField = new JPasswordField(password, 24);
            styleField(passwordField);

            // If splunk host is blank, start async domain guessing
            if (StringUtils.isBlank(splunkHost)) {
                startAsyncDomainGuessing();
            }

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setBorder(BORDER_CONTENT);
            content.add(createErrorPanel());
            content.add(new Section(SERVER_SECTION, retroFont,
                    FormGrid.of(LABEL_WIDTH, new String[]{"host", "port"}, hostNameField, portPanel)));
            content.add(new Section(CREDENTIALS_SECTION, retroFont,
                    FormGrid.of(LABEL_WIDTH, new String[]{"user", "password"}, usernameField, passwordField)));

            cancelButton = new KeyButton("Esc", CANCEL_BUTTON_TEXT, retroFont, false);
            cancelButton.addActionListener(e -> handleCancel());

            loginButton = new KeyButton("Enter", buttonText(), retroFont, true);
            loginButton.addActionListener(this::loginAction);

            add(createHeader(), BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
            add(createFooter(), BorderLayout.SOUTH);

            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_CANCEL);
            getActionMap().put(ACTION_CANCEL, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (cancelButton.isEnabled()) {
                        handleCancel();
                    }
                }
            });

            // Set up focus traversal and defaults
            List<Component> focusOrder = Arrays.asList(hostNameField, usernameField, passwordField, loginButton,
                    cancelButton);
            setFocusTraversalPolicy(new CustomFocusTraversalPolicy(focusOrder));
            setFocusTraversalPolicyProvider(true);

            // Lock fields in relogin mode (only password editable)
            // Apply visual styling to make it clear these fields cannot be edited
            if (reason.isRelogin()) {
                lockField(hostNameField);
                lockField(portField);
                portCheckbox.setEnabled(false);
                portCheckbox.setForeground(DarculaColors.GREY);
                lockField(usernameField);
            }
            // Splunk unreachable: the session is kept, so no password; only retry or cancel
            if (reason.isUnreachable()) {
                passwordField.setText("");
                lockField(passwordField);
            }

            // Note: Focus is requested externally via focusDefaultField() after the panel
            // is added to the component hierarchy. This ensures proper focus handling
            // when the panel is shown from a keyboard shortcut.

            // Enable Enter key for login
            setupEnterKeyAction(loginButton, e -> loginButton.doClick());
            setupEnterKeyAction(usernameField, e -> passwordField.requestFocusInWindow());
            setupEnterKeyAction(passwordField, e -> loginButton.doClick());
        }

        /** Top strip like the scope selector's: a breadcrumb-style title. */
        private JPanel createHeader() {
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            header.setOpaque(false);
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G3), BORDER_HEADER));

            JLabel crumb = new JLabel(CRUMB);
            crumb.setFont(retroFont);
            crumb.setForeground(DarculaColors.G6);
            JLabel title = new JLabel(
                    reason.isUnreachable() ? UNREACHABLE_TITLE : reason.isRelogin() ? RELOGIN_TITLE : TITLE);
            title.setFont(boldFont);
            title.setForeground(DarculaColors.FOREGROUND);

            header.add(crumb);
            header.add(title);
            return header;
        }

        /** Why a login is needed, in amber above the form; hidden without an error. */
        private JPanel createErrorPanel() {
            errorArea = new JTextArea();
            errorArea.setFont(retroFont);
            errorArea.setForeground(DarculaColors.ORANGE);
            errorArea.setEditable(false);
            errorArea.setFocusable(false);
            errorArea.setLineWrap(true);
            errorArea.setWrapStyleWord(true);
            errorArea.setOpaque(false);
            errorArea.setBorder(new EmptyBorder(8, 14, 4, 0));

            errorPanel = new JPanel(new BorderLayout());
            errorPanel.setOpaque(false);
            errorPanel.add(errorArea, BorderLayout.CENTER);
            errorPanel.setPreferredSize(ERROR_PANEL_SIZE);
            errorPanel.setVisible(false);
            return errorPanel;
        }

        /** Bottom strip like the scope selector's: the key buttons at the right. */
        private JPanel createFooter() {
            JPanel footer = new JPanel(new BorderLayout());
            footer.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            footer.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3), BORDER_FOOTER));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(cancelButton);
            buttons.add(loginButton);
            footer.add(buttons, BorderLayout.EAST);
            return footer;
        }

        private void styleField(JTextField textField) {
            textField.setFont(retroFont);
        }

        private void styleCheckbox(JCheckBox portCheckbox) {
            portCheckbox.setFont(smallFont);
            portCheckbox.setForeground(DarculaColors.G7);
            portCheckbox.setOpaque(false);
        }

        /**
         * Locks a text field for relogin mode with clear visual indication.
         * Uses dimmed colors to show the field is read-only.
         */
        private void lockField(JTextField field) {
            field.setEditable(false);
            field.setFocusable(false);
            // Visual styling: darker background and dimmed foreground
            field.setBackground(DarculaColors.BACKGROUND.darker());
            field.setForeground(DarculaColors.GREY);
        }

        private void startSpinner(JButton button) {
            startSpinnerAnimation(button, button.getText().length());
        }

        private void startSpinner(JTextField textField) {
            // Store original text and styling
            textField.putClientProperty("originalText", textField.getText());
            textField.putClientProperty("originalEditable", textField.isEditable());
            textField.putClientProperty("originalBackground", textField.getBackground());

            // Make field look disabled/busy
            textField.setEditable(false);
            textField.setBackground(textField.getBackground().darker());

            startSpinnerAnimation(textField, 1);
        }

        private void startSpinnerAnimation(JComponent component, int length) {
            // Dynamically generate loading states based on length
            String[] loadingStates = generateLoadingStates(length);

            component.setEnabled(false);

            // Set initial loading state
            if (component instanceof JButton button) {
                button.setText(loadingStates[0]);
            } else if (component instanceof JTextField textField) {
                textField.setText(loadingStates[0]);
            }

            Timer animationTimer = new Timer(66, null);
            final int[] currentState = {0};

            animationTimer.addActionListener(e -> {
                // Check if we should still be animating
                Timer timer = (Timer) component.getClientProperty("loadingTimer");
                if (timer != null && timer.isRunning()) {
                    currentState[0] = (currentState[0] + 1) % loadingStates.length;

                    if (component instanceof JButton button) {
                        button.setText(loadingStates[currentState[0]]);
                    } else if (component instanceof JTextField textField) {
                        textField.setText(loadingStates[currentState[0]]);
                    }
                } else {
                    animationTimer.stop();
                }
            });

            component.putClientProperty("loadingTimer", animationTimer);
            animationTimer.start();
        }

        private String[] generateLoadingStates(int length) {
            if (length <= 0) {
                return new String[]{"●"};
            }

            // Create a fancy centered spinner
            String[] spinnerChars = {"◐", "◓", "◑", "◒" // Rotating quarter circles
            };

            String[] states = new String[spinnerChars.length];

            for (int i = 0; i < spinnerChars.length; i++) {
                // Center the spinner character in the field
                int padding = (length - 1) / 2;
                StringBuilder state = new StringBuilder();

                // Add left padding
                state.append(" ".repeat(padding));

                // Add spinner character
                state.append(spinnerChars[i]);

                // Add right padding to fill the length
                int remainingSpaces = length - state.length();
                if (remainingSpaces > 0) {
                    state.append(" ".repeat(remainingSpaces));
                }

                states[i] = state.toString();
            }

            return states;
        }

        private void stopSpinner(JButton button) {
            Timer timer = (Timer) button.getClientProperty("loadingTimer");
            if (timer != null) {
                timer.stop();
                button.putClientProperty("loadingTimer", null);
            }

            button.setText(buttonText());
            button.setEnabled(true);
        }

        private void stopSpinner(JTextField textField) {
            Timer timer = (Timer) textField.getClientProperty("loadingTimer");
            if (timer != null) {
                timer.stop();
                textField.putClientProperty("loadingTimer", null);
            }

            // Restore original state
            String originalText = (String) textField.getClientProperty("originalText");
            Boolean originalEditable = (Boolean) textField.getClientProperty("originalEditable");
            Color originalBackground = (Color) textField.getClientProperty("originalBackground");

            textField.setText(originalText != null ? originalText : "");
            textField.setEditable(originalEditable != null ? originalEditable : true);
            textField.setBackground(
                    originalBackground != null ? originalBackground : UIManager.getColor("TextField.background"));
            textField.setEnabled(true);

            // Clean up client properties
            textField.putClientProperty("originalText", null);
            textField.putClientProperty("originalEditable", null);
            textField.putClientProperty("originalBackground", null);
        }

        private void loginFieldsEnabled(boolean enabled) {
            hostNameField.setEnabled(enabled);
            portField.setEnabled(enabled && !portCheckbox.isSelected());
            portCheckbox.setEnabled(enabled);
            usernameField.setEnabled(enabled);
            passwordField.setEnabled(enabled);
            loginButton.setEnabled(enabled);
            cancelButton.setEnabled(enabled);
        }

        SwingWorker<SplunkCredentials, Void> fetchDataWorker;

        private void loginAction(ActionEvent actionEvent) {

            if (fetchDataWorker != null && !fetchDataWorker.isDone()) {
                return;
            }

            int parsedPort = 8089;
            if (!portCheckbox.isSelected()) {
                try {
                    parsedPort = Integer.parseInt(portField.getText());
                } catch (NumberFormatException ex) {
                    Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER,
                            "Invalid port number.");
                    return;
                }
            }

            loginFieldsEnabled(false);
            startSpinner(loginButton);

            int port = parsedPort;
            String host = hostNameField.getText();
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();

            fetchDataWorker = new SwingWorker<>() {

                @Override
                protected SplunkCredentials doInBackground() throws SearchException {
                    var credentials = new SplunkCredentials(host, port, username, password);
                    loginConsumer.accept(credentials);
                    return credentials;
                }

                @Override
                protected void done() {
                    try {
                        SplunkCredentials credentials = get();

                        config.setSplunkHost(credentials.host());
                        config.setSplunkPort(credentials.port());
                        config.setSplunkUser(credentials.username());
                        config.flush();

                    } catch (Exception e) {
                        log.error("Error during login", e);
                        Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER,
                                ExceptionMessageFormatter.formatException(e));
                    } finally {
                        stopSpinner(loginButton);
                        loginFieldsEnabled(true);
                    }
                }
            };
            fetchDataWorker.execute();
        }

        private void startAsyncDomainGuessing() {
            // Disable the hostname field and show spinner
            startSpinner(hostNameField);

            SwingWorker<String, Void> worker = new SwingWorker<>() {
                @Override
                protected String doInBackground() {
                    return DomainGuesstimator.guessSplunkHost();
                }

                @Override
                protected void done() {
                    try {
                        String guessedHost = get();
                        stopSpinner(hostNameField);
                        hostNameField.setText(guessedHost);
                    } catch (Exception e) {
                        log.error("Error during domain guessing", e);
                        stopSpinner(hostNameField);
                        Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER,
                                "Failed to guess Splunk host: " + ExceptionMessageFormatter.formatException(e));
                    }
                }
            };
            worker.execute();
        }

        // Custom FocusTraversalPolicy
        private static class CustomFocusTraversalPolicy extends FocusTraversalPolicy {
            private final List<Component> order;

            CustomFocusTraversalPolicy(List<Component> order) {
                this.order = order;
            }

            @Override
            public Component getComponentAfter(Container aContainer, Component aComponent) {
                int index = order.indexOf(aComponent);
                if (index == -1) {
                    // Handle subcomponents (e.g., JComboBox editor)
                    for (int i = 0; i < order.size(); i++) {
                        if (isParentOf(order.get(i), aComponent)) {
                            index = i;
                            break;
                        }
                    }
                }
                return order.get((index + 1) % order.size());
            }

            @Override
            public Component getComponentBefore(Container aContainer, Component aComponent) {
                int index = order.indexOf(aComponent);
                if (index == -1) {
                    for (int i = 0; i < order.size(); i++) {
                        if (isParentOf(order.get(i), aComponent)) {
                            index = i;
                            break;
                        }
                    }
                }
                return order.get((index - 1 + order.size()) % order.size());
            }

            @Override
            public Component getFirstComponent(Container aContainer) {
                return order.isEmpty() ? null : order.getFirst();
            }

            @Override
            public Component getLastComponent(Container aContainer) {
                return order.isEmpty() ? null : order.getLast();
            }

            @Override
            public Component getDefaultComponent(Container aContainer) {
                return order.getFirst();
            }

            // Identity comparison is intentional: walking the Swing parent
            // chain must match the exact component instance.
            @SuppressWarnings("ReferenceEquality")
            private boolean isParentOf(Component parent, Component child) {
                while (child != null) {
                    if (child == parent) return true;
                    child = child.getParent();
                }
                return false;
            }
        }

        // Helper to bind Enter key to button actions
        private void setupEnterKeyAction(JComponent component, ActionListener action) {
            InputMap inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap actionMap = component.getActionMap();
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter");
            actionMap.put("enter", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.actionPerformed(e);
                }
            });
        }

        private void handleCancel() {
            // Signal cancellation by passing null to the consumer
            loginConsumer.accept(null);
        }
    }
}
