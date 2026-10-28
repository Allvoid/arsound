package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

public class AsrModelTest {
    private Locale saved;

    @Before
    public void russian() {
        saved = Locale.getDefault();
        Locale.setDefault(new Locale("ru"));
    }

    @After
    public void restore() {
        Locale.setDefault(saved);
    }

    @Test
    public void everyFileIsPinnedByAnHttpsAddressAndAChecksum() {
        for (AsrModel model : AsrModel.values()) {
            AsrFile[] files = model.files();
            assertEquals(3, files.length);
            for (AsrFile file : files) {
                assertTrue(file.url, file.url.startsWith("https://huggingface.co/csukuangfj/sherpa-onnx-whisper-" + model.id + "/resolve/"));
                assertTrue(file.url, file.url.endsWith("/" + file.name));
                assertTrue(file.name, file.sha256.matches("[0-9a-f]{64}"));
                assertTrue(file.size > 0);
            }
            // A fixed commit, not "main": the files cannot change under the checksums.
            assertTrue(files[0].url, files[0].url.matches(".*/resolve/[0-9a-f]{40}/.*"));
        }
    }

    @Test
    public void theNamesLineUp() {
        AsrModel model = AsrModel.SMALL;
        assertEquals("small-encoder.int8.onnx", model.encoderName());
        assertEquals("small-decoder.int8.onnx", model.decoderName());
        assertEquals("small-tokens.txt", model.tokensName());
        assertEquals("base-decoder.int8.onnx", AsrModel.BASE.decoderName());
    }

    @Test
    public void theSizesAddUp() {
        assertEquals(375_485_327L, AsrModel.SMALL.totalBytes());
        assertEquals(160_609_290L, AsrModel.BASE.totalBytes());
        assertEquals("375 МБ", Sizes.format(AsrModel.SMALL.totalBytes()));
        assertEquals("161 МБ", Sizes.format(AsrModel.BASE.totalBytes()));
    }

    @Test
    public void theSettingFallsBackToTheAccurateModel() {
        assertSame(AsrModel.SMALL, AsrModel.fromSetting(null));
        assertSame(AsrModel.SMALL, AsrModel.fromSetting("tiny"));
        assertSame(AsrModel.BASE, AsrModel.fromSetting("base"));
        assertNull(AsrModel.fromId("tiny"));
    }

    @Test
    public void theLighterModelNeedsLessMemory() {
        assertTrue(AsrModel.BASE.minAvailableBytes < AsrModel.SMALL.minAvailableBytes);
    }

    @Test
    public void sizesAreToldInTheUnitsOfADownload() {
        assertEquals("1,2 ГБ", Sizes.format(1_200_000_000L));
        assertEquals("меньше 1 МБ", Sizes.format(10_000));
        assertEquals("0 МБ", Sizes.format(0));
    }

    @Test
    public void theLibraryIsPinnedToo() {
        assertTrue(AsrLibrary.ARCHIVE_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + AsrLibrary.VERSION + "/"));
        assertTrue(AsrLibrary.ARCHIVE_SHA256.matches("[0-9a-f]{64}"));
        assertTrue(AsrLibrary.FILE_SHA256.matches("[0-9a-f]{64}"));
        assertTrue(AsrLibrary.FILE_SIZE > 0 && AsrLibrary.ARCHIVE_SIZE > 0);
    }
}
