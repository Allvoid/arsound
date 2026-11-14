package app.revanced.extension.soundcloud.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AccountVaultTest {
    private static SavedAccount account(String name, String userId, String access, long seenAt) {
        return new SavedAccount(name, userId, access, "refresh-" + userId, "*", "0", null, seenAt);
    }

    @Test
    public void theNameShownDropsTheUserIdSoundCloudAdds() {
        assertEquals("allvoid", SavedAccount.shownName("allvoid-1234567"));
        assertEquals("dj-shadow", SavedAccount.shownName("dj-shadow-42"));
        assertEquals("someone", SavedAccount.shownName("someone@mail.ru"));
        assertEquals("plain", SavedAccount.shownName("plain"));
        // A name that is only digits after a dash keeps something to show.
        assertEquals("-123", SavedAccount.shownName("-123"));
    }

    @Test
    public void aSavedAccountGoesToTheTopAndReplacesItsOlderCopy() {
        List<SavedAccount> accounts = new ArrayList<>();
        accounts = AccountVault.upsert(accounts, account("a-1", "1", "old", 1));
        accounts = AccountVault.upsert(accounts, account("b-2", "2", "b", 2));
        accounts = AccountVault.upsert(accounts, account("a-1", "1", "new", 3));
        assertEquals(2, accounts.size());
        assertEquals("a-1", accounts.get(0).name);
        assertEquals("new", accounts.get(0).accessToken);
        assertEquals("b-2", accounts.get(1).name);
    }

    @Test
    public void theListKeepsAtMostTenAccounts() {
        List<SavedAccount> accounts = new ArrayList<>();
        for (int i = 0; i < 15; i++) accounts = AccountVault.upsert(accounts, account("u-" + i, "" + i, "t", i));
        assertEquals(AccountVault.MAX_ACCOUNTS, accounts.size());
        assertEquals("u-14", accounts.get(0).name);
    }

    @Test
    public void theListComesBackAsItWasWritten() {
        List<SavedAccount> accounts = new ArrayList<>();
        accounts.add(new SavedAccount("a-1", "1", "access", null, "*", "3600", "a@b.c", 5));
        accounts.add(new SavedAccount("b-2", "2", null, "refresh", null, null, null, 6));
        List<SavedAccount> back = AccountVault.parse(AccountVault.serialize(accounts));
        assertEquals(2, back.size());
        assertEquals("access", back.get(0).accessToken);
        assertNull(back.get(0).refreshToken);
        assertEquals("a@b.c", back.get(0).email);
        assertEquals("3600", back.get(0).expiresIn);
        assertEquals(5, back.get(0).seenAt);
        assertNull(back.get(1).accessToken);
        assertEquals("refresh", back.get(1).refreshToken);
        assertNull(back.get(1).email);
    }

    @Test
    public void anAccountWithoutTokensOrUserIdIsNotKept() {
        assertFalse(new SavedAccount("a-1", "1", null, null, null, null, null, 0).usable());
        assertFalse(new SavedAccount("a-1", null, "t", null, null, null, null, 0).usable());
        assertTrue(new SavedAccount("a-1", "1", null, "r", null, null, null, 0).usable());
        assertTrue(AccountVault.parse("[{\"name\":\"a-1\",\"seenAt\":1}]").isEmpty());
        assertTrue(AccountVault.parse("").isEmpty());
    }
}
