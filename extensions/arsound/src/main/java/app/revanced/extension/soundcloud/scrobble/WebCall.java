package app.revanced.extension.soundcloud.scrobble;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.revanced.extension.soundcloud.network.StreamText;

/** One blocking HTTP call for the scrobbling services. Never on the main thread. */
final class WebCall {
    static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status / 100 == 2;
        }
    }

    private WebCall() {
    }

    /**
     * The status the services' classification should see. A 401 or 403 means "the credential is refused" only when the
     * service itself said so (its own JSON error): a block page of a filter, a captive portal or a firewall answers the
     * same codes, and must not make the app forget the queue and ask to connect again. Such an answer is as good as no
     * answer: 0, asked again later.
     *
     * @param serviceBody Whether the body is one the service writes (a JSON error).
     */
    static int serviceStatus(int status, boolean serviceBody) {
        return (status == 401 || status == 403) && !serviceBody ? 0 : status;
    }

    /**
     * @param body The request body, or null for a GET.
     * @return The answer, whatever its status.
     * @throws ScrobbleException with code 0 when the network failed.
     */
    static Answer send(String url, Map<String, String> headers, String body) throws ScrobbleException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream input = stream) {
                    text = StreamText.readUtf8(input);
                }
            }
            return new Answer(status, text);
        } catch (IOException ex) {
            throw new ScrobbleException(0, "No connection: " + ex.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            // A header the connection refuses (a token with a character that cannot go in a header): its text may carry the
            // header's value, so only the kind of failure is kept.
            throw new ScrobbleException(0, "The request could not be made: " + ex.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
