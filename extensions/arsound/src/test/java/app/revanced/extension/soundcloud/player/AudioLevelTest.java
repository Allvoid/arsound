package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class AudioLevelTest {
    private static ByteBuffer shorts(int count, short value) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) buffer.putShort(value);
        buffer.flip();
        return buffer;
    }

    @Test
    public void silenceIsZeroAndAnEmptyBufferToo() {
        assertEquals(0f, AudioLevel.rms(shorts(1024, (short) 0), false), 0f);
        assertEquals(0f, AudioLevel.rms(ByteBuffer.allocateDirect(0), false), 0f);
    }

    @Test
    public void aConstantHalfScaleSignalHasHalfRms() {
        assertEquals(0.5f, AudioLevel.rms(shorts(2048, (short) 16384), false), 1e-3f);
    }

    @Test
    public void floatSamplesAreRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 64; i++) buffer.putFloat(-0.25f);
        buffer.flip();
        assertEquals(0.25f, AudioLevel.rms(buffer, true), 1e-4f);
    }

    @Test
    public void theBufferPositionIsLeftAlone() {
        ByteBuffer buffer = shorts(256, (short) 1000);
        AudioLevel.rms(buffer, false);
        assertEquals(0, buffer.position());
        assertEquals(512, buffer.remaining());
    }

    @Test
    public void scaleIsClampedToOne() {
        assertEquals(0f, AudioLevel.scale(0f), 0f);
        assertEquals(1f, AudioLevel.scale(0.9f), 0f);
        assertTrue(AudioLevel.scale(0.1f) > 0.2f && AudioLevel.scale(0.1f) < 0.5f);
    }
}
