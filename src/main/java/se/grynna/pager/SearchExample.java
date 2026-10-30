package se.grynna.pager;

import com.splunk.*;
import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.utils.JobFormatter;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
public class SearchExample {

    static Service service = null;

    @SuppressWarnings("BusyWait")
    public static void main(String[] args) throws IOException {

        String userName = System.getenv("USERNAME");
        String password = System.getenv("PASSWORD");

        // validate
        if (isBlank(userName) || isBlank(password)) {
            log.error("Please set USER and PASSWORD environment variables");
            System.exit(1);
        }

        ServiceArgs loginArgs = new ServiceArgs();
        loginArgs.setPort(8089);
        loginArgs.setHost("splunk.example.com");
        loginArgs.setScheme("https");
        loginArgs.setUsername(userName); // Use your username
        loginArgs.setPassword(password); // Use your password

        // Initialize the SDK client
        service = Service.connect(loginArgs);

        // now, rounded to seconds.

        // Search bounds are instants, so use UTC explicitly rather than an implicit local zone.
        Instant stop = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toInstant();
        Instant start = stop.minus(10, ChronoUnit.MINUTES);

        // formated as ISO 8601
        String startStr = start.toString();
        String stopStr = stop.toString();

        log.info("Start: {}", startStr);
        log.info("Stop: {}", stopStr);

        // Run a simple search by first creating the search job
        JobArgs jobArgs = new JobArgs();
        jobArgs.setEarliestTime(start.toString());
        jobArgs.setLatestTime(stop.toString());

        var q = "search index=openshift_store-dev_dev_app "
        // + "_cd=681:515502";
        // + "_bkt=openshift_store-dev_dev_app~672~1B6AB815-DA3C-4F0E-A251-7B14AA72D951, _cd=672:586325,
        // _serial=116"
        ;

        Job job = service.getJobs().create(q, jobArgs);

        // Waiting for search results to be ready
        while (!job.isDone()) {
            try {
                log.debug(".");
                Thread.sleep(500); // 500 ms
            } catch (Exception e) {
                // Handle exception here.
            }
        }

        log.debug(".");
        log.info("Search job completed: {}", JobFormatter.formatJob(job));

        var eventsArgs = new JobEventsArgs();
        eventsArgs.setCount(0);
        eventsArgs.setOutputMode(JobEventsArgs.OutputMode.JSON);
        eventsArgs.setOffset(0);

        // Read results
        BufferedInputStream eventsStream = null;
        try {
            eventsStream = new BufferedInputStream(job.getEvents(eventsArgs));
            eventsStream.mark(/*1gb*/ 1024 * 1024 * 1024);

            ResultsReader reader = new ResultsReaderJson(eventsStream);

            // Iterate over events and print _raw field
            reader.forEach(s -> log.info("{}", s));

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            // dump stream
            if (eventsStream != null) {
                try {
                    eventsStream.reset();
                    // string from stream
                    String s = new String(eventsStream.readAllBytes(), StandardCharsets.UTF_8);
                    log.error("Error: {}", s);
                } catch (Exception ex) {
                    log.error("Failed to dump events stream", ex);
                }
            } else {
                log.error("No events stream available to dump");
            }
        }
    }
}
