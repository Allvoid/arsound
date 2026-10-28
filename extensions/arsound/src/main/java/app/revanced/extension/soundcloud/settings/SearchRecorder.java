package app.revanced.extension.soundcloud.settings;

import app.revanced.extension.soundcloud.settings.search.SearchEntry;
import app.revanced.extension.soundcloud.settings.search.SearchIndex;

/**
 * Collects the rows of the settings screens for the search while the screens are built off screen. When nothing
 * collects, every method does nothing, so a screen built to be shown costs nothing extra.
 */
final class SearchRecorder {
    private SearchIndex collector;
    /** The section being built for the index, null for the main screen. */
    private SectionScreen section;
    private boolean skipRecord;
    private boolean indexing;
    /** The entry of the section row that is being built; extra words of that row go to it. */
    private SearchEntry sectionEntry;

    /** Starts collecting into {@code index}; the main screen is built first. */
    void start(SearchIndex index) {
        collector = index;
        section = null;
        indexing = true;
    }

    void building(SectionScreen section) {
        this.section = section;
    }

    void stop() {
        collector = null;
        section = null;
        indexing = false;
    }

    /** True while the screens are only read for the search. */
    boolean isIndexing() {
        return indexing;
    }

    /** Adds a row. Returns its entry so the row can hand over more words later, or null if nothing is collected. */
    SearchEntry record(String title, String description) {
        if (collector == null || title == null || title.isEmpty()) return null;
        if (skipRecord) return sectionEntry;
        String sectionTitle = section == null ? null : section.title();
        SearchEntry entry = new SearchEntry(section == null ? null : section.id(), sectionTitle, title, description, false);
        collector.add(entry);
        return entry;
    }

    /**
     * On the main screen the row that opens a section is a section of its own in the search; elsewhere it is a row
     * like the others. Returns true if it was taken as a section: the row about to be made must then not be recorded
     * again, which {@link #endSectionRow} lifts.
     */
    boolean beginSectionRow(String screenId, String title, String description) {
        boolean sectionRow = collector != null && section == null;
        if (sectionRow) {
            sectionEntry = new SearchEntry(screenId, title, title, description, true);
            collector.add(sectionEntry);
        }
        skipRecord = sectionRow;
        return sectionRow;
    }

    void endSectionRow() {
        skipRecord = false;
        sectionEntry = null;
    }
}
