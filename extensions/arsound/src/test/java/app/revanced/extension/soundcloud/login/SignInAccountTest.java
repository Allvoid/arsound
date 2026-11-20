package app.revanced.extension.soundcloud.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignInAccountTest {
    @Test
    public void numbersTheRetriesFromTwo() {
        assertEquals("chelovechik-747996811 (2)", SignInAccount.alternateName("chelovechik-747996811", 1));
        assertEquals("chelovechik-747996811 (4)", SignInAccount.alternateName("chelovechik-747996811", 3));
    }

    @Test
    public void staysWithinAndroidsLimit() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 250; i++) longName.append('a');
        String name = SignInAccount.alternateName(longName.toString(), 1);
        assertEquals(SignInAccount.MAX_NAME_LENGTH, name.length());
        assertTrue(name.endsWith(" (2)"));
    }

    @Test
    public void differsFromTheUsualName() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < SignInAccount.MAX_NAME_LENGTH; i++) full.append('b');
        String usual = full.toString();
        for (int attempt = 1; attempt <= SignInAccount.ALTERNATE_NAMES; attempt++) {
            String name = SignInAccount.alternateName(usual, attempt);
            assertTrue(!usual.equals(name) && name.length() <= SignInAccount.MAX_NAME_LENGTH);
        }
    }

    @Test
    public void nothingToNumber() {
        assertNull(SignInAccount.alternateName(null, 1));
        assertNull(SignInAccount.alternateName("", 1));
        assertNull(SignInAccount.alternateName("name", 0));
    }
}
