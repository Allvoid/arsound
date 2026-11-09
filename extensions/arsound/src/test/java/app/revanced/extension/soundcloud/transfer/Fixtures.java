package app.revanced.extension.soundcloud.transfer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The JSON answers kept in {@code src/test/resources/transfer}. */
final class Fixtures {
    private Fixtures() {
    }

    static JSONObject json(String name) throws Exception {
        try (InputStream in = Fixtures.class.getResourceAsStream("/transfer/" + name)) {
            if (in == null) throw new IllegalStateException("No fixture " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
