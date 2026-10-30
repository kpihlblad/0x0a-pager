package se.grynna.pager.splunk;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeFoo {

    public static void main(String[] args) {

        ZoneId zoneId = ZoneId.of("Europe/Stockholm");
        System.out.println("Zone ID: " + zoneId);

        Instant base = Instant.parse("2024-03-30T14:33:33Z");

        ZonedDateTime zdt = base.atZone(zoneId);
        System.out.println("ZonedDateTime: " + zdt);
    }
}
