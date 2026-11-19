package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BoundedCopyTest {
    @Test
    public void aFileUnderTheLimitIsCopiedWhole() throws IOException {
        byte[] data = "short invented picture".getBytes("UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long copied = BoundedCopy.copy(new ByteArrayInputStream(data), out, 1000);
        assertEquals(data.length, copied);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void aFileExactlyAtTheLimitIsKept() throws IOException {
        byte[] data = new byte[300];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(300, BoundedCopy.copy(new ByteArrayInputStream(data), out, 300));
        assertEquals(300, out.size());
    }

    @Test
    public void aFileOverTheLimitIsRefusedAndNotWritten() throws IOException {
        byte[] data = new byte[301];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(-1, BoundedCopy.copy(new ByteArrayInputStream(data), out, 300));
        // The chunk that goes over the limit is not written: nothing of this small file reached the output.
        assertEquals(0, out.size());
    }

    @Test
    public void anEmptyFileIsCopiedAsEmpty() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, BoundedCopy.copy(new ByteArrayInputStream(new byte[0]), out, 10));
        assertEquals(0, out.size());
    }
}
