package se.grynna.pager;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.extern.slf4j.Slf4j;
import raven.toast.Notifications;
import raven.toast.ToastClientProperties;
import se.grynna.pager.ui.AppClose;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.LogViewer;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

@Slf4j
public class Main {

    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) {
        // macOS menu bar name; read when the toolkit starts, so it must be set first.
        System.setProperty("apple.awt.application.name", "0x0A Pager");
        FlatDarculaLaf.setup(); // Apply Darcula theme

        SwingUtilities.invokeLater(() -> {
            Theme theme = new Config().getTheme();
            DarculaColors.use(theme);
            PagerLaf.setup(theme);

            JFrame frame = new JFrame("0x0A Pager");

            List<Image> icons = windowIcons();
            frame.setIconImages(icons);
            // The macOS Dock shows this icon; other platforms use the window icons.
            if (!icons.isEmpty() && Taskbar.isTaskbarSupported()
                    && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(icons.getLast());
            }

            UIManager.put(ToastClientProperties.TOAST_INFO_ICON, new FlatSVGIcon("raven/toast/svg/info.svg"));
            UIManager.put(ToastClientProperties.TOAST_SUCCESS_ICON, new FlatSVGIcon("raven/toast/svg/success.svg"));
            UIManager.put(ToastClientProperties.TOAST_WARNING_ICON, new FlatSVGIcon("raven/toast/svg/warning.svg"));
            UIManager.put(ToastClientProperties.TOAST_ERROR_ICON, new FlatSVGIcon("raven/toast/svg/error.svg"));
            UIManager.put(ToastClientProperties.TOAST_CLOSE_ICON, new FlatSVGIcon("raven/toast/svg/close.svg"));
            UIManager.put(ToastClientProperties.TOAST_ICON, new FlatSVGIcon("raven/toast/svg/info.svg"));

            // Setup notifications
            Notifications.getInstance().setJFrame(frame);

            // frame.setSize(640, 480);
            // frame.setExtendedState(JFrame.MAXIMIZED_BOTH);

            LogViewer viewer = new LogViewer(Clock.system(ZoneId.systemDefault()));

            // The close button asks first when bookmarks were never exported or copied.
            AppClose.install(frame, viewer, () -> System.exit(0));

            frame.add(viewer);
            frame.pack();
            frame.setVisible(true);
        });
    }

    /** The window icon in every bundled size; the window manager or taskbar picks the closest one. */
    private static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            String path = "/icons/0x0a-pager-" + size + ".png";
            try (InputStream in = requireNonNull(Main.class.getResourceAsStream(path), path)) {
                icons.add(ImageIO.read(in));
            } catch (IOException | NullPointerException e) {
                log.error("Failed to load window icon {}, continuing without it", path, e);
            }
        }
        return icons;
    }
}
