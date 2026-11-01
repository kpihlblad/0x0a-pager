package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> NO_FIELDS = Map.of();

    static Stream<Arguments> cases() {
        return Stream.of(
                // structured: ECS, OTel, bunyan, Serilog, Kubernetes, journald, logstash, CloudWatch, Windows
                Arguments.of("ECS nested host, process and thread", NO_FIELDS, """
                        {"@timestamp":"2024-01-01T10:00:00Z","message":"started","host":{"name":"web-1"},
                         "process":{"pid":4242,"name":"node","thread":{"id":7,"name":"main"}}}""",
                        "started", new Activity("web-1", "node[4242]", "main", null, null)),
                Arguments.of("OTel flattened attributes", NO_FIELDS, """
                        {"host.name":"h1","service.instance.id":"i-1","service.name":"checkout","thread.id":3}""",
                        "x", new Activity("h1", "checkout[i-1]", "3", null, null)),
                Arguments.of("bunyan/pino hostname and pid", NO_FIELDS, """
                        {"v":0,"name":"myapp","hostname":"h","pid":123,"level":30,"msg":"x"}""",
                        "x", new Activity("h", "123", null, null, null)),
                Arguments.of("Serilog nested Properties use the last segment", NO_FIELDS,
                        """
                                {"@t":"2024-01-01T10:00:00Z","@mt":"x","Properties":{"MachineName":"M","ProcessId":5,"ThreadId":9}}""",
                        "x", new Activity("M", "5", "9", null, null)),
                Arguments.of("Kubernetes nested fields: pod is the machine, container the process", NO_FIELDS, """
                        {"kubernetes":{"pod_name":"api-7f9","pod_node_name":"node-3","container_name":"api",
                         "namespace_name":"prod"},"serviceName":"api","threadID":"t1","log":"hi"}""",
                        "hi", new Activity("api-7f9", "api", "t1", null, null)),
                Arguments.of("Kubernetes flattened keys", NO_FIELDS,
                        """
                                {"kubernetes_pod_name":"api-7f9","kubernetes_pod_node_name":"node-3","kubernetes_container_name":"api"}""",
                        "x", new Activity("api-7f9", "api", null, null, null)),
                Arguments.of("Kubernetes.Pod.Name in Splunk fields is the machine",
                        Map.of("Kubernetes.Pod.Name", "p-1"), null, "x", new Activity("p-1", null, null, null, null)),
                Arguments.of("journald Splunk fields", Map.of("_HOSTNAME", "box", "_PID", "99",
                        "SYSLOG_IDENTIFIER", "sshd", "_COMM", "sshd"), null, "Accepted publickey",
                        new Activity("box", "sshd[99]", null, null, null)),
                Arguments.of("logstash-logback thread_name and HOSTNAME", NO_FIELDS, """
                        {"@timestamp":"2024-01-01T10:00:00Z","@version":"1","message":"x","logger_name":"c.x.Y",
                         "thread_name":"main","level":"INFO","HOSTNAME":"app-1"}""",
                        "x", new Activity("app-1", null, "main", null, null)),
                Arguments.of("CloudWatch @logStream", NO_FIELDS, """
                        {"@logStream":"stream-1","message":"x"}""",
                        "x", new Activity(null, "stream-1", null, null, null)),
                Arguments.of("Windows Event Log fields", Map.of("Computer", "WIN01", "ProcessID", "4444",
                        "ThreadID", "7"), null, "An account was logged on",
                        new Activity("WIN01", "4444", "7", null, null)),
                Arguments.of("JSON thread string", NO_FIELDS, """
                        {"thread":"worker-3","level":"info"}""", "x", new Activity(null, null, "worker-3", null, null)),
                Arguments.of("trace id is the request", Map.of("trace_id", "4bf92f35"), null, "x",
                        new Activity(null, null, null, "4bf92f35", null)),
                Arguments.of("ECS trace.id is the request", NO_FIELDS, """
                        {"trace":{"id":"4bf92f35"},"thread":{"name":"main"}}""", "x",
                        new Activity(null, null, "main", "4bf92f35", null)),
                Arguments.of("OTel traceId is the request", NO_FIELDS, """
                        {"traceId":"4bf92f35","thread_name":"main"}""", "x",
                        new Activity(null, null, "main", "4bf92f35", null)),
                Arguments.of("thread and trace id are set independently", Map.of("thread_name", "main", "trace_id",
                        "abc"), null, "x", new Activity(null, null, "main", "abc", null)),
                Arguments.of("JSON array values are ignored", NO_FIELDS, """
                        {"tags":["a"],"thread":["x"]}""", "x", Activity.NONE),

                // syslog
                Arguments.of("RFC 3164 sshd with pam module", NO_FIELDS, null,
                        "Feb 28 04:48:54 combo sshd(pam_unix)[6741]: session opened for user root",
                        new Activity("combo", "sshd(pam_unix)[6741]", null, null, null)),
                Arguments.of("RFC 3164 kernel with pid 0", NO_FIELDS, null,
                        "Jul  8 08:52:05 calvisitor-10 kernel[0]: x",
                        new Activity("calvisitor-10", "kernel[0]", null, null, null)),
                Arguments.of("RFC 3164 with PRI and ISO timestamp", NO_FIELDS, null,
                        "<38>2024-03-01T10:00:00.123+02:00 db-2 postgres[812]: LOG: ready",
                        new Activity("db-2", "postgres[812]", null, null, null)),
                Arguments.of("RFC 5424 with procid", NO_FIELDS, null,
                        "<34>1 2003-10-11T22:14:15.003Z mymachine.example.com su 77 ID47 - msg",
                        new Activity("mymachine.example.com", "su[77]", null, null, null)),
                Arguments.of("RFC 5424 nil procid", NO_FIELDS, null,
                        "<34>1 2003-10-11T22:14:15.003Z mymachine.example.com su - ID47 - msg",
                        new Activity("mymachine.example.com", "su", null, null, null)),

                // application text
                Arguments.of("Spring Boot 3 with app name and thread", NO_FIELDS, null,
                        "2024-01-01T10:00:00.000Z  INFO 12345 --- [myapp] [nio-8080-exec-1] o.s.web : msg",
                        new Activity(null, "12345", "nio-8080-exec-1", null, null)),
                Arguments.of("Spring Boot 2 with padded thread", NO_FIELDS, null,
                        "2024-01-01 10:00:00.000  INFO 12345 --- [           main] o.s.b.StartupInfoLogger : Starting",
                        new Activity(null, "12345", "main", null, null)),
                Arguments.of("log4j thread with brackets and location", NO_FIELDS, null,
                        "2015-08-25 11:26:28,159 - INFO  [QuorumPeer[myid=2]/0:0:0:0:0:0:0:0:2181:FileTxnSnapLog@240] - Snapshotting",
                        new Activity(null, null, "QuorumPeer[myid=2]/0:0:0:0:0:0:0:0:2181", null, null)),
                Arguments.of("log4j thread with :: before the location", NO_FIELDS, null,
                        "2015-07-29 19:04:12,394 - INFO  [ProcessThread(sid:2 cport:-1)::PrepRequestProcessor@645] - x",
                        new Activity(null, null, "ProcessThread(sid:2 cport:-1)", null, null)),
                Arguments.of("logback thread before level", NO_FIELDS, null,
                        "12:00:00.123 [pool-1-thread-3] INFO c.x.Y - msg",
                        new Activity(null, null, "pool-1-thread-3", null, null)),
                Arguments.of("log4j thread after time and level", NO_FIELDS, null,
                        "2024-01-01 12:00:00,123 [main] ERROR Foo - boom",
                        new Activity(null, null, "main", null, null)),
                Arguments.of("Python/oslo pid and request id", NO_FIELDS, null,
                        "2017-05-17 12:02:35.320 2931 INFO nova.virt.libvirt.imagecache "
                                + "[req-addc1839-2ed5-4778-b57e-5854eb7b8b09 - - - - -] msg",
                        new Activity(null, "2931", null, "req-addc1839-2ed5-4778-b57e-5854eb7b8b09", null)),
                Arguments.of("Python/oslo with a leading file token", NO_FIELDS, null,
                        "nova-compute.log.2017-05-17_12:02:35 2017-05-17 12:02:35.320 2931 INFO nova.virt "
                                + "[req-addc1839-2ed5-4778-b57e-5854eb7b8b09 - - - - -] msg",
                        new Activity(null, "2931", null, "req-addc1839-2ed5-4778-b57e-5854eb7b8b09", null)),
                Arguments.of("glog thread id", NO_FIELDS, null,
                        "I0525 10:12:13.123456   12345 server.go:123] msg",
                        new Activity(null, null, "12345", null, null)),
                Arguments.of("logcat threadtime", NO_FIELDS, null,
                        "03-17 16:13:38.811  1702  2395 D WindowManager: msg",
                        new Activity(null, "1702", "2395", null, null)),
                Arguments.of("nginx error with connection", NO_FIELDS, null,
                        "2024/01/01 12:00:00 [error] 1234#5678: *99 open() failed",
                        new Activity(null, "1234", "5678", "*99", null)),
                Arguments.of("nginx error without connection", NO_FIELDS, null,
                        "2024/01/01 12:00:00 [error] 1234#5678: open() failed",
                        new Activity(null, "1234", "5678", null, null)),
                Arguments.of("Apache 2.4 pid, tid and client", NO_FIELDS, null,
                        "[Wed Oct 11 14:32:52.123 2000] [core:error] [pid 35708:tid 4328636416] "
                                + "[client 72.15.99.187:5678] msg",
                        new Activity(null, "35708", "4328636416", "72.15.99.187", null)),
                Arguments.of("Apache 2.2 client as request", NO_FIELDS, null,
                        "[Tue Feb 28 03:49:01 2006] [error] [client 218.22.153.242] Directory index forbidden",
                        new Activity(null, null, null, "218.22.153.242", null)),
                Arguments.of("pipe header pid", NO_FIELDS, null,
                        "201813-9:59:0:95|Step_LSC|30002312|processHandle called",
                        new Activity(null, "30002312", null, null, null)),
                Arguments.of("Erlang pid", NO_FIELDS, null, "<0.123.0>: connection established",
                        new Activity(null, null, "<0.123.0>", null, null)),
                Arguments.of("Go goroutine", NO_FIELDS, null, "goroutine 42 [running]:",
                        new Activity(null, null, "42", null, null)),
                Arguments.of("logfmt pid and tid", NO_FIELDS, null, "level=info pid=12 tid=7 msg=\"x\"",
                        new Activity(null, "12", "7", null, null)),
                Arguments.of("logfmt trace_id is the request", NO_FIELDS, null,
                        "level=info tid=7 trace_id=abc msg=\"x\"", new Activity(null, null, "7", "abc", null)),

                // negatives and placeholders
                Arguments.of("prose with a colon gives nothing", NO_FIELDS, null, "connecting to host: db1",
                        Activity.NONE),
                Arguments.of("logfmt host is not a machine", NO_FIELDS, null, "host=db1 level=info msg=started",
                        Activity.NONE),
                Arguments.of("bracketed user is not a thread", NO_FIELDS, null, "User logged in [admin]",
                        Activity.NONE),
                Arguments.of("bracketed level is not a thread", NO_FIELDS, null, "[INFO] starting", Activity.NONE),
                Arguments.of("lower-case level word in prose is not a thread", NO_FIELDS, null,
                        "request failed with error [timeout] after 3 retries", Activity.NONE),
                Arguments.of("placeholders are ignored", Map.of("_HOSTNAME", "-", "_PID", "null",
                        "SYSLOG_IDENTIFIER", "none", "thread_name", "N/A"), null, "x", Activity.NONE),
                Arguments.of("Splunk internal fields are ignored", fields("_cd", "1:2", "_bkt", "main~1",
                        "_serial", "0", "_indextime", "1", "index", "main", "splunk_server", "idx1", "linecount", "1",
                        "punct", "-", "timestartpos", "0", "timeendpos", "9", "date_hour", "12", "_time", "2017",
                        "_sourcetype", "syslog"), null, "x", Activity.NONE),

                // Splunk defaults and precedence
                Arguments.of("Splunk host is a weak machine", Map.of("host", "splunkhost", "sourcetype", "syslog",
                        "source", "/var/log/messages"), null, "hello",
                        new Activity("splunkhost", null, null, null, null)),
                Arguments.of("origin only when nothing else is known", Map.of("sourcetype", "app:json",
                        "source", "/var/log/app.log"), null, "hello",
                        new Activity(null, null, null, null, "app:json:/var/log/app.log")),
                Arguments.of("JSON host beats Splunk host", Map.of("host", "splunk-host"), """
                        {"hostname":"json-host"}""", "x", new Activity("json-host", null, null, null, null)),
                Arguments.of("k8s pod beats the Splunk host as machine", Map.of("host", "collector-1"), """
                        {"kubernetes":{"pod_name":"api-7f9"}}""", "x",
                        new Activity("api-7f9", null, null, null, null)),
                Arguments.of("k8s pod beats a JSON host field", NO_FIELDS, """
                        {"host":"web-1","kubernetes":{"pod_name":"p-9"}}""", "x",
                        new Activity("p-9", null, null, null, null)),
                Arguments.of("structured pid beats sniffed pid, sniffed name fills in", NO_FIELDS, """
                        {"pid":5}""", "Feb 28 04:48:54 combo sshd[6741]: x",
                        new Activity("combo", "sshd[5]", null, null, null)),
                Arguments.of("long values are capped at 200 characters", Map.of("hostname", "a".repeat(250)), null,
                        "x", new Activity("a".repeat(200), null, null, null, null)));
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void extractsActivity(String name, Map<String, String> splunkFields, String json, String text, Activity expected)
            throws Exception {
        JsonNode node = json == null ? null : MAPPER.readTree(json);

        assertThat(withoutProcessTerm(ActivityExtractor.extract(splunkFields, node, text))).isEqualTo(expected);
    }

    /** The cases above state the five identity parts; the process term has its own tests below. */
    private static Activity withoutProcessTerm(Activity activity) {
        return new Activity(activity.machine(), activity.process(), activity.thread(), activity.request(),
                activity.origin());
    }

    private static Activity extractWithRaw(Map<String, String> splunkFields, String json, String text, String raw)
            throws Exception {
        return ActivityExtractor.extract(splunkFields, json == null ? null : MAPPER.readTree(json), text, raw);
    }

    @Test
    void theProcessTermOfAKubernetesEventIsTheContainerName() throws Exception {
        String raw = """
                {"kubernetes":{"pod_name":"api-7f9","pod_node_name":"node-3","container_name":"api"},"log":"hi"}""";

        Activity activity = extractWithRaw(NO_FIELDS, raw, "hi", raw);

        assertThat(activity.machine()).isEqualTo("api-7f9");
        assertThat(activity.process()).isEqualTo("api");
        assertThat(activity.processTerm()).as("the container name occurs literally in _raw").isEqualTo("api");
    }

    @Test
    void theProcessTermOfASyslogEventIsTheCompositeKeyWhenItIsLiterallyThere() throws Exception {
        String raw = "Feb 28 04:48:54 combo sshd(pam_unix)[6741]: session opened for user root";

        Activity activity = extractWithRaw(NO_FIELDS, null, raw, raw);

        assertThat(activity.process()).isEqualTo("sshd(pam_unix)[6741]");
        assertThat(activity.processTerm()).isEqualTo("sshd(pam_unix)[6741]");
        assertThat(activity.narrowTerms(Activity.Level.PROCESS)).containsExactly("sshd(pam_unix)[6741]");
    }

    @Test
    void theProcessTermFallsBackToTheMachineAndThenTheName() throws Exception {
        Map<String, String> fields = Map.of("kubernetes.pod_name", "api-7f9", "app", "checkout");

        assertThat(extractWithRaw(fields, null, "x", "pod api-7f9 says hi").processTerm()).as("the pod (machine) is")
                .isEqualTo("api-7f9");
        assertThat(extractWithRaw(fields, null, "x", "the checkout service").processTerm()).isEqualTo("checkout");
    }

    @Test
    void theProcessTermPrefersTheCompositeKeyOverTheId() throws Exception {
        Map<String, String> fields = Map.of("container_id", "c-1", "app", "checkout");

        assertThat(extractWithRaw(fields, null, "x", "checkout[c-1] up").processTerm()).as("composite first")
                .isEqualTo("checkout[c-1]");
        assertThat(extractWithRaw(fields, null, "x", "pod c-1 says hi").processTerm()).as("then the id")
                .isEqualTo("c-1");
    }

    @Test
    void theProcessTermIsNullWhenNothingOccursInTheRaw() throws Exception {
        Map<String, String> fields = Map.of("kubernetes.pod_name", "api-7f9", "app", "checkout");

        assertThat(extractWithRaw(fields, null, "x", "nothing to see").processTerm()).isNull();
        assertThat(extractWithRaw(fields, null, "x", null).processTerm()).isNull();
        assertThat(extractWithRaw(fields, null, "x", "CHECKOUT API-7F9").processTerm()).as("case-sensitive").isNull();
        assertThat(extractWithRaw(NO_FIELDS, null, "x", "x").processTerm()).isNull();
    }

    @Test
    void theThreeArgumentFormTakesTheTextAsTheRaw() {
        assertThat(ActivityExtractor.extract(Map.of("app", "checkout"), null, "checkout up").processTerm())
                .isEqualTo("checkout");
    }

    @Test
    void nullInputsGiveNone() {
        assertThat(ActivityExtractor.extract(null, null, null)).isEqualTo(Activity.NONE);
        assertThat(ActivityExtractor.extract(NO_FIELDS, null, "")).isEqualTo(Activity.NONE);
        assertThat(ActivityExtractor.extract(null, null, "   ")).isEqualTo(Activity.NONE);
    }

    @Test
    void sniffsOnlyTheFirstLine() {
        assertThat(ActivityExtractor.extract(NO_FIELDS, null, "plain\n[pid 1:tid 2]"))
                .isEqualTo(Activity.NONE);
    }

    @Test
    void sourceFieldsNameTheStructuredFieldEachPartCameFrom() throws Exception {
        JsonNode json = MAPPER.readTree("""
                {"kubernetes":{"pod_name":"api-7f9","container_name":"api"},"thread_name":"main","traceId":"4bf9",
                 "log":"hi"}""");

        ActivityFields fields = ActivityExtractor.sourceFields(NO_FIELDS, json);

        assertThat(fields.machine()).isEqualTo(new ActivityFields.SourceField("kubernetes.pod_name", "api-7f9"));
        assertThat(fields.processName()).isEqualTo(new ActivityFields.SourceField("kubernetes.container_name", "api"));
        assertThat(fields.processId()).isNull();
        assertThat(fields.thread()).isEqualTo(new ActivityFields.SourceField("thread_name", "main"));
        assertThat(fields.request()).isEqualTo(new ActivityFields.SourceField("traceId", "4bf9"));
    }

    @Test
    void sourceFieldsAreNullForPartsSniffedFromTheText() {
        String text = "<30>1 2024-01-01T10:00:00Z web-1 checkout 4242 - - started";
        assertThat(ActivityExtractor.extract(NO_FIELDS, null, text).machine()).isEqualTo("web-1");

        assertThat(ActivityExtractor.sourceFields(NO_FIELDS, null)).isEqualTo(new ActivityFields(null, null, null,
                null, null));
    }

    @Test
    void theSplunkHostIsTheMachineFieldWhenNothingStructuredNamesOne() {
        ActivityFields fields = ActivityExtractor.sourceFields(Map.of("host", "web-2", "app", "checkout"), null);

        assertThat(fields.machine()).isEqualTo(new ActivityFields.SourceField("host", "web-2"));
        assertThat(fields.processName()).isEqualTo(new ActivityFields.SourceField("app", "checkout"));
    }
}
