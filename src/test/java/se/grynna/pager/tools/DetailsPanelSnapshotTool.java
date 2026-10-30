package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;
import se.grynna.pager.ui.details.DetailsPanel;
import se.grynna.pager.ui.details.EventDetails;
import se.grynna.pager.ui.details.EventDetails.ScopeMark;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dev tool, not a test: renders the event details ({@link DetailsPanel}) headless with a canned Kubernetes-style JSON
 * event and writes it as a PNG, so its look can be reviewed without a display or a Splunk.
 *
 * <pre>
 * mvn test -Dtest=DetailsPanelSnapshotTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Dsnapshot.out=/tmp/opencode/detailspanel.png] [-Dsnapshot.width=1100] [-Dsnapshot.height=640] \
 *     [-Dsnapshot.field=kubernetes.pod_name] [-Dsnapshot.level=ERROR]
 * </pre>
 */
@Tag("tool")
class DetailsPanelSnapshotTool {

    private static final String RAW = """
            {"@timestamp":"2026-10-27T09:14:03.482917351Z","level":"ERROR","logger":"se.acme.orders.OrderService",\
            "thread":"http-nio-8080-exec-7","message":"Failed to reserve stock for order 8c1f-77d2: upstream returned 503 after 3 retries (timeout 2000 ms), giving up and releasing the reservation",\
            "trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","kubernetes":{"pod_name":"orders-api-7d9c5b6f4-x2kqp",\
            "namespace_name":"prod-orders","container_name":"orders-api","labels":{"app":"orders-api","version":"2.14.1"},\
            "host":"ip-10-12-4-77.eu-north-1.compute.internal"},"tags":["checkout","retry"],\
            "stack_trace":"java.lang.IllegalStateException: stock service unavailable\\n\\tat se.acme.orders.StockClient.reserve(StockClient.java:88)\\n\\tat se.acme.orders.OrderService.place(OrderService.java:142)\\n\\tat jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\\nCaused by: java.net.SocketTimeoutException: Read timed out"}""";

    @Test
    void render() throws Exception {
        String out = System.getProperty("snapshot.out", "/tmp/opencode/detailspanel.png");
        int width = Integer.getInteger("snapshot.width", 1100);
        int height = Integer.getInteger("snapshot.height", 640);
        String selectField = System.getProperty("snapshot.field", "kubernetes.pod_name");
        Level level = Level.valueOf(System.getProperty("snapshot.level", "ERROR"));

        SwingUtilities.invokeAndWait(PagerLaf::setup);
        DateTimeFormatterManagerTestUtil.initDTM();

        TreeMap<String, String> map = new TreeMap<>(Map.ofEntries(Map.entry("_bkt", "k8s_prod~12~A1B2C3D4"),
                Map.entry("_cd", "12:48213907"), Map.entry("_indextime", "1793092443"),
                Map.entry("_serial", "0"), Map.entry("_si", "idx-1\nk8s_prod"),
                Map.entry("_sourcetype", "_json"), Map.entry("host", "ip-10-12-4-77"),
                Map.entry("index", "k8s_prod"), Map.entry("linecount", "1"),
                Map.entry("source", "/var/log/containers/orders-api-7d9c5b6f4-x2kqp_prod-orders_orders-api.log"),
                Map.entry("sourcetype", "kube:container"), Map.entry("splunk_server", "idx-1"),
                Map.entry("level", "ERROR"), Map.entry("kubernetes.pod_name", "orders-api-7d9c5b6f4-x2kqp"),
                Map.entry("timestartpos", "0"), Map.entry("timeendpos", "30")));
        LoadedLogLine event = new LoadedLogLine(LoadedLogLine.MsgType.JSON, "seg", 12345, "cd", "bkt",
                Instant.parse("2026-10-27T09:14:03.482Z"), RAW, Instant.parse("2026-10-27T09:14:04.107Z"),
                Instant.parse("2026-10-27T09:14:03.482917351Z"),
                "Failed to reserve stock for order 8c1f-77d2: upstream returned 503 after 3 retries (timeout 2000 ms), giving up and releasing the reservation\n"
                        + "java.lang.IllegalStateException: stock service unavailable\n\tat se.acme.orders.StockClient.reserve(StockClient.java:88)",
                false, level);
        event.setEvent(map);

        EventDetails details = EventDetails.of(event, name -> name.equals("message") || name.equals("stack_trace"),
                name -> switch (name) {
                    case "kubernetes.namespace_name" -> ScopeMark.NARROW;
                    case "logger" -> ScopeMark.EXCLUDE;
                    default -> ScopeMark.NONE;
                });

        SwingUtilities.invokeAndWait(() -> {
            DetailsPanel panel = new DetailsPanel();
            panel.setSize(width, height);
            panel.setDetails(details, "match 3 of 212");
            panel.select(Math.max(0, details.indexOf(selectField)));
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.print(g);
            g.dispose();
            try {
                File file = new File(out);
                file.getParentFile().mkdirs();
                ImageIO.write(img, "png", file);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("Wrote " + out);
    }
}
