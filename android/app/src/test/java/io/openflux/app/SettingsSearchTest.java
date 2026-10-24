package io.openflux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class SettingsSearchTest {

    private static List<String> titles(String query) {
        List<String> out = new ArrayList<>();
        for (SettingsPage.SearchItem item : SettingsPage.search(query)) out.add(item.title);
        return out;
    }

    @Test
    public void findsByTitleCaseInsensitive() {
        assertEquals(Arrays.asList("Kill Switch"), titles("KILL"));
    }

    @Test
    public void yoAndYeAreTheSame() {
        assertTrue(titles("темная").contains("Тёмная тема"));
    }

    @Test
    public void findsByExtraWords() {
        assertTrue(titles("белый список").contains("Только выбранные"));
        assertTrue(titles("телеграм").contains("Telegram-чат"));
    }

    @Test
    public void sectionNameFindsItsRows() {
        List<String> network = titles("сеть");
        assertTrue(network.containsAll(Arrays.asList("DNS-сервер: по умолчанию", "Свой DNS", "MTU пакета", "Kill Switch")));
    }

    @Test
    public void everyWordMustMatch() {
        assertEquals(Arrays.asList("Свой DNS"), titles("свой dns"));
        assertTrue(titles("kill тема").isEmpty());
    }

    @Test
    public void emptyOrUnknownQueryFindsNothing() {
        assertTrue(titles("   ").isEmpty());
        assertTrue(titles("абракадабра").isEmpty());
    }

    // Results are told apart by title, and the highlight needs a target.
    @Test
    public void itemsHaveUniqueTitlesAndTargets() {
        Set<String> seen = new HashSet<>();
        for (SettingsPage.SearchItem item : SettingsPage.searchItems()) {
            assertTrue("duplicate title " + item.title, seen.add(item.title));
            assertFalse(item.title, item.target.isEmpty());
        }
    }
}
