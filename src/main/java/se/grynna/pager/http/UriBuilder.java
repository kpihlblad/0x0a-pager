package se.grynna.pager.http;

import com.uwyn.urlencoder.UrlEncoder;

import java.net.URI;

public class UriBuilder {

    final StringBuilder path;
    private boolean firstQueryParam;

    private UriBuilder(StringBuilder sb) {
        path = sb;
    }

    // start with a "base url" with a trailing slash, include https:// etc

    public static UriBuilder fromBase(String baseUrl) {
        // validate baseUrl - require https:// etc

        if (!baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Base URL must start with http or https");
        }

        // remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return new UriBuilder(new StringBuilder(baseUrl));
    }

    // add a pathParam to the base url
    public UriBuilder pathParam(String segment) {

        var s = UrlEncoder.encode(segment);
        path.append("/");
        path.append(s);
        return this;
    }

    // add a (sub) path to the base url
    public UriBuilder path(String segment) {

        if (!segment.startsWith("/")) {
            path.append("/");
        }

        path.append(segment);

        return this;
    }

    public UriBuilder queryParam(String param, String value) {
        if (firstQueryParam) {
            path.append("?");
            firstQueryParam = false;
        } else {
            path.append("&");
        }

        path.append(param);
        path.append("=");
        path.append(UrlEncoder.encode(value));
        return this;
    }

    public URI build() {
        return URI.create(path.toString());
    }
}
