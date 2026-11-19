package app.revanced.extension.soundcloud.theme.effect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlobsSceneTest {
    @Test
    public void aBlobThatHasNotStartedIsNotThere() {
        assertEquals(-1f, BlobsScene.position(1f, 20f, 5f), 0f);
    }

    @Test
    public void theBlobGoesThereAndBackAgain() {
        assertEquals(.5f, BlobsScene.position(10f, 20f, 0f), 1e-6f);
        assertEquals(1f, BlobsScene.position(20f, 20f, 0f), 1e-6f);
        // The way back takes as long as the way there.
        assertEquals(.5f, BlobsScene.position(30f, 20f, 0f), 1e-6f);
        assertEquals(0f, BlobsScene.position(40f, 20f, 0f), 1e-6f);
    }

    @Test
    public void aNegativeDelayStartsPartWayIn() {
        assertEquals(.25f, BlobsScene.position(0f, 20f, -5f), 1e-6f);
    }

    @Test
    public void theDriftStartsAtRestAndEndsAtTheFarCorner() {
        float[] out = new float[3];
        BlobsScene.keyframe(0f, out);
        assertEquals(0f, out[0], 1e-4f);
        assertEquals(0f, out[1], 1e-4f);
        assertEquals(1f, out[2], 1e-4f);
        BlobsScene.keyframe(.5f, out);
        assertEquals(120f, out[0], 1e-3f);
        assertEquals(-70f, out[1], 1e-3f);
        assertEquals(1.18f, out[2], 1e-4f);
        BlobsScene.keyframe(1f, out);
        assertEquals(-80f, out[0], 1e-3f);
        assertEquals(60f, out[1], 1e-3f);
        assertEquals(.92f, out[2], 1e-4f);
    }

    @Test
    public void aHueSurvivesTheRoundTripThroughAColour() {
        for (double hue : new double[]{20, 90, 150, 220, 280, 340}) {
            int colour = BlobColours.argb(0.62, 0.12, hue, 255);
            assertEquals(hue, BlobColours.hueOf(colour), 4.0);
        }
    }

    @Test
    public void theAlphaIsKeptAndALightColourIsLighterThanADarkOne() {
        int dark = BlobColours.argb(0.62, 0.15, 200, 0x57);
        int light = BlobColours.argb(0.78, 0.15, 200, 0x6B);
        assertEquals(0x57, dark >>> 24);
        assertEquals(0x6B, light >>> 24);
        assertTrue(sum(light) > sum(dark));
    }

    @Test
    public void aGreyHasNoHueAndTurningPastACircleWraps() {
        assertEquals(0.0, BlobColours.hueOf(0xFF808080), 0.0);
        assertEquals(10.0, BlobColours.normalize(370), 1e-9);
        assertEquals(350.0, BlobColours.normalize(-10), 1e-9);
    }

    private static int sum(int colour) {
        return ((colour >> 16) & 0xFF) + ((colour >> 8) & 0xFF) + (colour & 0xFF);
    }
}
