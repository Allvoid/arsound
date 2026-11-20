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
    public void anEleventhAccountIsRefusedAndNoneIsDropped() {
        List<SavedAccount> accounts = new ArrayList<>();
        for (int i = 0; i < AccountVault.MAX_ACCOUNTS; i++) {
            accounts = AccountVault.upsert(accounts, account("u-" + i, "" + i, "t", i));
            assertEquals(i + 1, accounts.size());
        }
        // RULE A: the vault itself never drops a usable entry to make room.
        assertNull(AccountVault.upsert(accounts, account("u-new", "99", "t", 99)));
        assertEquals(AccountVault.MAX_ACCOUNTS, accounts.size());
        // A copy of an account that is there is renewed in place, at the top.
        List<SavedAccount> renewed = AccountVault.upsert(accounts, account("u-3", "3", "fresh", 100));
        assertEquals(AccountVault.MAX_ACCOUNTS, renewed.size());
        assertEquals("u-3", renewed.get(0).name);
        assertEquals("fresh", renewed.get(0).accessToken);
    }

    @Test
    public void aListThatLosesEntriesIsKeptAsItWasBeforeAnythingRewritesIt() {
        // Damaged text, and a list with an entry that is no usable account: both are backed up, not lost silently.
        assertTrue(AccountVault.losesEntries("not json"));
        assertTrue(AccountVault.losesEntries("[{\"name\":\"a-1\"},{\"bad\":1}]"));
        assertFalse(AccountVault.losesEntries(null));
        assertFalse(AccountVault.losesEntries(AccountVault.serialize(java.util.Collections.singletonList(account("a-1", "1", "t", 1)))));
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
    public void aTruncatedListIsDamagedAndAnEmptyOrGoodOneIsNot() {
        assertTrue(AccountVault.damaged("[{\"name\":\"a-1\",\"accessToken\":\"secret-tok"));
        assertTrue(AccountVault.damaged("not json"));
        assertFalse(AccountVault.damaged(null));
        assertFalse(AccountVault.damaged(""));
        assertFalse(AccountVault.damaged("[]"));
        List<SavedAccount> accounts = new ArrayList<>();
        accounts.add(account("a-1", "1", "access", 1));
        assertFalse(AccountVault.damaged(AccountVault.serialize(accounts)));
    }

    @Test
    public void anAccountWithoutTokensOrUserIdIsNotKept() {
        assertFalse(new SavedAccount("a-1", "1", null, null, null, null, null, 0).usable());
        assertFalse(new SavedAccount("a-1", null, "t", null, null, null, null, 0).usable());
        assertTrue(new SavedAccount("a-1", "1", null, "r", null, null, null, 0).usable());
        assertTrue(AccountVault.parse("[{\"name\":\"a-1\",\"seenAt\":1}]").isEmpty());
        assertTrue(AccountVault.parse("").isEmpty());
    }

    @Test
    public void aSwitchOfThisRunIsNotAbandonedWhileItsWipeTakesMinutes() {
        // Followed by this run (the question about the wipe may wait on the screen): kept, or the end of the wipe would
        // look like SoundCloud's own sign-out and drop the saved copy of the account the user switched from.
        assertFalse(AccountVault.isStale(121_000, true));
        assertFalse(AccountVault.isStale(10 * 60_000, true));
        assertTrue(AccountVault.isStale(16 * 60_000, true));
        assertTrue(AccountVault.isStale(-1, true));
    }

    @Test
    public void aMarkLeftByAKilledRunIsNoSwitch() {
        // Its wipe died with it: an own sign-out of SoundCloud's in the next two minutes must not be taken for its end.
        assertTrue(AccountVault.isStale(1_000, false));
    }

    @Test
    public void theEndOfAWipeRightAfterTheNextAccountWentInChangesNothing() {
        // The mark is gone, the account left is already the new one: not an own sign-out that would delete its copy.
        assertEquals(AccountVault.AfterPurge.LEAVE, AccountVault.afterPurge(false, false, false, true));
        assertEquals(AccountVault.AfterPurge.OWN_SIGN_OUT, AccountVault.afterPurge(false, false, false, false));
    }

    @Test
    public void theEndOfTheWipeAfterAGivenUpSwitchChangesNothing() {
        // Switch given up (no screen to ask on) while the wipe still runs: its end is not SoundCloud's own sign-out
        // (that would drop the kept copy of the account left) and starts nothing in the background.
        assertEquals(AccountVault.AfterPurge.LEAVE, AccountVault.afterPurge(true, true, false, false));
        assertEquals(AccountVault.AfterPurge.LEAVE, AccountVault.afterPurge(true, true, true, false));
        // Even when its mark went stale meanwhile.
        assertEquals(AccountVault.AfterPurge.LEAVE, AccountVault.afterPurge(false, true, false, false));
    }

    @Test
    public void theEndOfTheWipeOfALiveSwitchOrOfAnOwnSignOut() {
        assertEquals(AccountVault.AfterPurge.OWN_SIGN_OUT, AccountVault.afterPurge(false, false, false, false));
        assertEquals(AccountVault.AfterPurge.FINISH, AccountVault.afterPurge(true, false, false, false));
        assertEquals(AccountVault.AfterPurge.ASK, AccountVault.afterPurge(true, false, true, false));
    }

    @Test
    public void aNewSwitchWithinTheGraceHearsTheEndOfItsOwnWipe() {
        AccountVault.newSwitch();
        AccountVault.markFinished();
        assertTrue(AccountVault.justFinished());
        // The next switch begins within the five seconds: the grace was the earlier switch's, not this one's.
        AccountVault.newSwitch();
        assertFalse(AccountVault.justFinished());
    }

    @Test
    public void signingInWaitsForARunningWipeAndWarnsAboutACutOne() {
        assertEquals(AccountVault.SignInGate.WAIT, AccountVault.signInGate(true, false, false));
        // SoundCloud's own sign-out runs a wipe too, with no mark of ours.
        assertEquals(AccountVault.SignInGate.WAIT, AccountVault.signInGate(true, false, true));
        assertEquals(AccountVault.SignInGate.WAIT, AccountVault.signInGate(false, true, false));
        assertEquals(AccountVault.SignInGate.WARN, AccountVault.signInGate(false, false, true));
        assertEquals(AccountVault.SignInGate.GO, AccountVault.signInGate(false, false, false));
    }

    @Test
    public void theSwitchCodeNeverDeletesAKeptAccountByItself() throws Exception {
        // RULE: a kept account goes only when the user removes it from the list (AccountSheet).
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/app/revanced/extension/soundcloud/login/AccountSwitch.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains("AccountVault.remove("));
    }

    @Test
    public void aListOverTheLimitStillRefreshesItsOwnCopiesButRefusesANewOne() {
        List<SavedAccount> accounts = new ArrayList<>();
        for (int i = 0; i < 12; i++) accounts.add(account("u-" + i, "" + i, "t", i));
        List<SavedAccount> renewed = AccountVault.upsert(accounts, account("u-5", "5", "fresh", 99));
        assertEquals(12, renewed.size());
        assertEquals("fresh", renewed.get(0).accessToken);
        assertNull(AccountVault.upsert(accounts, account("u-new", "99", "t", 99)));
    }

    @Test
    public void aSwitchNeedsAUsableCopyOfTheAccountItTakesOut() {
        assertTrue(AccountVault.canLeave(false, null));
        assertFalse(AccountVault.canLeave(true, null));
        // A copy with no tokens is no way back.
        assertFalse(AccountVault.canLeave(true, new SavedAccount("a-1", "1", null, null, null, null, null, 1)));
        assertTrue(AccountVault.canLeave(true, account("a-1", "1", "t", 1)));
    }

    @Test
    public void eachDifferentBadListIsKeptAndTheLastFewStay() throws Exception {
        String backups = null;
        for (int i = 1; i <= 5; i++) backups = AccountVault.addBackup(backups, "bad " + i);
        assertEquals(AccountVault.BACKUPS, new org.json.JSONArray(backups).length());
        assertTrue(backups.contains("bad 5"));
        assertFalse(backups.contains("bad 1"));
        // The same text again changes nothing.
        assertEquals(backups, AccountVault.addBackup(backups, "bad 5"));
    }

    @Test
    public void anotherAccountCanBeAddedOnlyBelowTheLimit() {
        assertTrue(AccountVault.canAddAnother(AccountVault.MAX_ACCOUNTS - 1));
        assertFalse(AccountVault.canAddAnother(AccountVault.MAX_ACCOUNTS));
    }

    @Test
    public void aListThatLosesAnEntryIsRewrittenOnlyAfterItsBackup() {
        String lossy = "[{\"name\":\"a-1\"}]";
        assertFalse(AccountVault.mayRewrite(lossy, () -> false));
        assertTrue(AccountVault.mayRewrite(lossy, () -> true));
        // A list that reads whole needs no backup: the backup is not even asked for.
        String whole = AccountVault.serialize(java.util.Collections.singletonList(account("a-1", "1", "t", 1)));
        assertTrue(AccountVault.mayRewrite(whole, () -> {
            throw new AssertionError("no backup needed");
        }));
    }

    @Test
    public void theLargestBadListIsNeverTheOneThatGoes() throws Exception {
        String big = "[{\"a\":1},{\"a\":2},{\"a\":3},{\"a\":4}]";
        String backups = AccountVault.addBackup(null, big);
        for (int i = 0; i < 6; i++) backups = AccountVault.addBackup(backups, "[{\"b\":" + i + "}]");
        assertTrue(backups.contains("{\\\"a\\\":4}"));
        assertEquals(AccountVault.BACKUPS, new org.json.JSONArray(backups).length());
        // The newest is in too.
        assertTrue(backups.contains("{\\\"b\\\":5}"));
    }

    @Test
    public void unreadableBackupsAreNotClearedByAddingOne() {
        assertNull(AccountVault.addBackup("not a list", "[{\"a\":1}]"));
    }

    @Test
    public void aBackupThatDoesNotParseIsSizedByTheAccountsItShowsAndNeverByZero() {
        String cut = "[{\"name\":\"a\",\"x\":1},{\"name\":\"b\"},{\"name\":\"c\"},{\"na";
        assertEquals(3, AccountVault.entriesOf(cut));
        assertEquals(1, AccountVault.entriesOf("garbage"));
    }

    @Test
    public void anUnreadableBackupWithTheMostAccountsIsNotTheFirstToGo() {
        String cut = "[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"},{\"name\":\"d\"},{\"na";
        String backups = AccountVault.addBackup(null, cut);
        for (int i = 0; i < 6; i++) backups = AccountVault.addBackup(backups, "[{\"name\":\"x" + i + "\"}]");
        assertTrue(backups.contains("{\\\"name\\\":\\\"d\\\"}"));
    }
}
