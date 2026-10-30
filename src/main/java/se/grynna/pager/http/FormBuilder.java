package se.grynna.pager.http;

import lombok.SneakyThrows;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.stream.Collectors;

public class FormBuilder {

    private final SequencedMap<String, String> data = new LinkedHashMap<>();

    private FormBuilder() {}

    public static FormBuilder newBuilder() {
        return new FormBuilder();
    }

    public FormBuilder with(String key, String value) {
        // Add a key-value pair to the form
        this.data.put(key, value);
        return this;
    }

    public HttpRequest.BodyPublisher build() {

        return HttpRequest.BodyPublishers.ofString(data.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + encode(entry.getValue())).collect(Collectors.joining("&")));
    }

    @SneakyThrows
    private static final String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
