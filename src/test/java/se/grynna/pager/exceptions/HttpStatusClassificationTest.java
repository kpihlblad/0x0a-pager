package se.grynna.pager.exceptions;

import com.splunk.HttpException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStatusClassificationTest {

    private static HttpException http(int status, String detail) throws ReflectiveOperationException {
        Constructor<HttpException> ctor = HttpException.class.getDeclaredConstructor(int.class, String.class,
                String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(status, "HTTP " + status + " -- " + detail, detail);
    }

    @Test
    void aBadRequestIsNotASessionError() throws Exception {
        var info = ExceptionMessageFormatter
                .classifyException(http(400, "Error in 'TsidxStats': WHERE clause is not an exact query"));

        assertThat(info.isClientError()).isFalse();
    }

    @Test
    void aWrappedBadRequestIsNotASessionError() throws Exception {
        var info = ExceptionMessageFormatter.classifyException(new SearchException("Error during loadFieldSummary",
                http(400, "Error in 'TsidxStats': WHERE clause is not an exact query")));

        assertThat(info.isClientError()).isFalse();
    }

    @Test
    void unauthorizedAndForbiddenAreSessionErrors() throws Exception {
        assertThat(ExceptionMessageFormatter.classifyException(http(401, "Unauthorized")).isClientError()).isTrue();
        assertThat(ExceptionMessageFormatter.classifyException(http(403, "Forbidden")).isClientError()).isTrue();
    }

    @Test
    void aWrappedUnauthorizedIsASessionError() throws Exception {
        var info = ExceptionMessageFormatter
                .classifyException(new SearchException("Error during loadIndexes", http(401, "Unauthorized")));

        assertThat(info.isClientError()).isTrue();
    }

    @Test
    void wrappedTransportFailuresAreNetworkErrorsNotSessionErrors() {
        for (Exception transport : List.of(new java.net.NoRouteToHostException("No route to host"),
                new java.net.ConnectException("Connection refused"), new java.net.UnknownHostException("splunk"),
                new java.net.SocketTimeoutException("Read timed out"))) {
            var info = ExceptionMessageFormatter.classifyException(
                    new SearchException("Error during queryEvents", new RuntimeException(transport)));

            assertThat(info.category()).as(transport.toString())
                    .isEqualTo(ExceptionMessageFormatter.ErrorCategory.NETWORK_ERROR);
        }
    }

    @Test
    void otherStatusesAreNotSessionErrors() throws Exception {
        assertThat(ExceptionMessageFormatter.classifyException(http(404, "Not found")).isClientError()).isFalse();
        assertThat(ExceptionMessageFormatter.classifyException(http(500, "Server error")).isClientError()).isFalse();
    }
}
