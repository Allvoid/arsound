package app.revanced.extension.soundcloud.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class PlayerConfigTest {
    private static final String TABLE = "{\"schemaVersion\":1,\"players\":{"
            + "\"abcd1234\":{\"sig\":\"Tl(48,5831,INPUT)\",\"nClass\":\"Xy\",\"sts\":20400,\"aliases\":[\"11112222\",\"33334444\"]},"
            + "\"deadbeef\":{\"sig\":\"Zz(1,INPUT)\",\"nClass\":\"Qq\",\"sts\":20500}}}";

    @Test
    public void everyPlayerOfTheTableIsFound() throws Exception {
        Map<String, PlayerConfig> table = PlayerConfig.parseTable(TABLE);
        assertEquals(4, table.size());
        assertEquals("Tl(48,5831,INPUT)", table.get("abcd1234").sigJsExpression);
        assertEquals("Xy", table.get("abcd1234").nClass);
        assertEquals(20400, table.get("abcd1234").sts);
        assertEquals(20500, table.get("deadbeef").sts);
    }

    @Test
    public void aliasesShareTheConfigOfTheirPlayer() throws Exception {
        Map<String, PlayerConfig> table = PlayerConfig.parseTable(TABLE);
        assertSame(table.get("abcd1234"), table.get("11112222"));
        assertSame(table.get("abcd1234"), table.get("33334444"));
    }

    @Test
    public void aMalformedEntryIsSkipped() throws Exception {
        String json = "{\"schemaVersion\":1,\"players\":{"
                + "\"bad00001\":{\"sig\":\"x\",\"nClass\":\"y\"},"
                + "\"bad00002\":{\"sig\":5,\"nClass\":\"y\",\"sts\":1},"
                + "\"bad00003\":\"text\","
                + "\"good0004\":{\"sig\":\"s\",\"nClass\":\"n\",\"sts\":7}}}";
        Map<String, PlayerConfig> table = PlayerConfig.parseTable(json);
        assertEquals(1, table.size());
        assertEquals(7, table.get("good0004").sts);
    }

    @Test
    public void anotherSchemaVersionIsRefused() {
        try {
            PlayerConfig.parseTable("{\"schemaVersion\":2,\"players\":{}}");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("schemaVersion"));
        }
    }

    @Test
    public void aTableWithoutPlayersIsRefused() {
        try {
            PlayerConfig.parseTable("{\"schemaVersion\":1}");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("players"));
        }
    }

    @Test
    public void aRootThatIsNotAnObjectIsRefused() {
        try {
            PlayerConfig.parseTable("[1,2]");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }
    }

    @Test
    public void theNTransformInstantiatesTheNamedClass() {
        String expression = new PlayerConfig("s", "Xy", 1).nJsExpression();
        assertTrue(expression.contains("new g.Xy("));
        assertTrue(expression.endsWith("(INPUT)"));
    }

    @Test
    public void jsonValuesKeepTheirTypes() throws Exception {
        Object value = new SimpleJsonParser("{\"a\":[1,2.5,\"x\\n\\u0041\",true,false,null],\"b\":{}}").parse();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        List<?> list = (List<?>) map.get("a");
        assertEquals(1L, list.get(0));
        assertEquals(2.5, list.get(1));
        assertEquals("x\nA", list.get(2));
        assertEquals(Boolean.TRUE, list.get(3));
        assertEquals(Boolean.FALSE, list.get(4));
        assertNull(list.get(5));
        assertTrue(((Map<?, ?>) map.get("b")).isEmpty());
    }

    @Test
    public void brokenJsonIsAnIoError() {
        for (String json : new String[]{"{", "{\"a\":1", "{\"a\" 1}", "[1 2]", "{\"a\":\"x"}) {
            try {
                new SimpleJsonParser(json).parse();
                fail(json);
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }
}
