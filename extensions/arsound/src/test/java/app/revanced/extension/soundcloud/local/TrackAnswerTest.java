package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrackAnswerTest {
    @Test
    public void notFoundMeansDeleted() {
        assertEquals(TrackAnswer.DELETED, TrackAnswer.of("404"));
    }

    @Test
    public void successAndForbiddenMeanTheTrackExists() {
        assertEquals(TrackAnswer.EXISTS, TrackAnswer.of("200"));
        assertEquals(TrackAnswer.EXISTS, TrackAnswer.of("206"));
        assertEquals(TrackAnswer.EXISTS, TrackAnswer.of("403"));
    }

    @Test
    public void failuresOfTheApiSayNothingAboutTheTrack() {
        assertEquals(TrackAnswer.API_FAILED, TrackAnswer.of("-1"));
        assertEquals(TrackAnswer.API_FAILED, TrackAnswer.of("401"));
        assertEquals(TrackAnswer.API_FAILED, TrackAnswer.of("429"));
        assertEquals(TrackAnswer.API_FAILED, TrackAnswer.of("503"));
    }

    @Test
    public void otherCodesAreUnclear() {
        assertEquals(TrackAnswer.UNCLEAR, TrackAnswer.of("400"));
        assertEquals(TrackAnswer.UNCLEAR, TrackAnswer.of("302"));
    }
}
