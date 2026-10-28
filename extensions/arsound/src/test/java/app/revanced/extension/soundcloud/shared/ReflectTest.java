package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

public class ReflectTest {
    @SuppressWarnings("unused")
    private static class Sample {
        private static final Sample SHARED = new Sample();
        private final String a = "first";
        private final StringBuilder b = new StringBuilder("second");
        private final StringBuffer c = new StringBuffer("third");
        private final String d = "fourth";

        private int secret(String text) {
            return text.length();
        }

        private String nothing() {
            return "none";
        }
    }

    @Test
    public void fieldIsFoundByTypeAndAccessible() throws Exception {
        Field field = Reflect.field(Sample.class, Reflect.typeIs(StringBuilder.class));
        assertNotNull(field);
        assertEquals("second", field.get(new Sample()).toString());
    }

    @Test
    public void firstMatchWinsInDeclarationOrder() throws Exception {
        Field field = Reflect.field(Sample.class, Reflect.typeIs(String.class));
        assertEquals("first", field.get(new Sample()));
    }

    @Test
    public void missingFieldIsNull() {
        assertNull(Reflect.field(Sample.class, Reflect.typeIs(Integer.class)));
    }

    @Test
    public void typeIsLooksForTheExactClassOnly() {
        assertNull(Reflect.field(Sample.class, Reflect.typeIs(CharSequence.class)));
    }

    @Test
    public void typeNamePredicates() throws Exception {
        Field byName = Reflect.field(Sample.class, Reflect.typeNamed("java.lang.StringBuffer"));
        assertEquals("third", byName.get(new Sample()).toString());
        Field bySuffix = Reflect.field(Sample.class, Reflect.typeNameEndsWith(".StringBuilder"));
        assertEquals("second", bySuffix.get(new Sample()).toString());
        assertNull(Reflect.field(Sample.class, Reflect.typeNamed("StringBuffer")));
    }

    @Test
    public void staticFieldsAreToldApart() {
        Field shared = Reflect.field(Sample.class, field -> Reflect.isStatic(field) && field.getType() == Sample.class);
        assertNotNull(shared);
        assertTrue(Reflect.isStatic(shared));
        assertNull(Reflect.field(Sample.class, field -> !Reflect.isStatic(field) && field.getType() == Sample.class));
    }

    @Test
    public void instanceFieldValuesSkipStaticFieldsAndKeepDeclarationOrder() throws Exception {
        java.util.List<Object> values = Reflect.instanceFieldValues(new Sample());
        assertEquals(4, values.size());
        assertEquals("first", values.get(0));
        assertEquals("second", values.get(1).toString());
        assertEquals("fourth", values.get(3));
    }

    @Test
    public void methodIsFoundByShapeAndInvoked() throws Exception {
        Method method = Reflect.method(Sample.class,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class);
        assertNotNull(method);
        assertEquals(5, method.invoke(new Sample(), "hello"));
        Method none = Reflect.method(Sample.class, m -> m.getParameterTypes().length == 0 && m.getReturnType() == String.class);
        assertEquals("none", none.invoke(new Sample()));
        assertNull(Reflect.method(Sample.class, m -> m.getParameterTypes().length == 3));
    }
}
