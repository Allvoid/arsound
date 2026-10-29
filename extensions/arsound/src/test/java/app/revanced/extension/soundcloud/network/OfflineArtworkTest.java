package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OfflineArtworkTest {
    @Test
    public void theSizeIsTheLastPartOfTheName() {
        assertArrayEquals(new String[]{"artworks-knAiv41C2qWv-0", "t500x500.jpg"},
                OfflineArtwork.nameAndSize("/artworks-knAiv41C2qWv-0-t500x500.jpg"));
        assertArrayEquals(new String[]{"avatars-000123-abc", "large.jpg"},
                OfflineArtwork.nameAndSize("/avatars-000123-abc-large.jpg"));
    }

    @Test
    public void anAddressWithoutASizeIsNotKept() {
        assertNull(OfflineArtwork.nameAndSize("/artworks"));
        assertNull(OfflineArtwork.nameAndSize("/artworks-abc-"));
        assertNull(OfflineArtwork.nameAndSize("/artworks-abc-t120x120.jpg?x=1"));
        assertNull(OfflineArtwork.nameAndSize("/artworks-a@b-t120x120.jpg"));
    }
}
