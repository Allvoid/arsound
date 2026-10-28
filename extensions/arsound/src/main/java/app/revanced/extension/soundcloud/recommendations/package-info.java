/**
 * Recommendations: hide duplicate uploads, "Not for me" dislikes and the daily "For you" playlist.
 *
 * <p>User view: re-uploads of the same song and tracks marked "Not for me" disappear from the home screen and autoplay;
 * a private "For you" playlist is rebuilt every midnight from what the user likes and plays (artists and songs found
 * through Last.fm, names only, no account, matched back to SoundCloud tracks).
 *
 * <p>Entry points:
 * <ul>
 * <li>{@code DuplicateFilter.filterAutoplay(Iterable, Object)}, {@code filterHomeViews(ArrayList)},
 * {@code filterSectionEntities(List)} - called by {@code recommendations/DuplicateFilterPatch.kt}; the same class also
 * has {@code filterSystemPlaylist}, called by {@code local.LocalAdditions}.</li>
 * <li>{@code TrackDislikes.addTrackMenuRow(Dialog, Object trackUrn)} - the "Not for me" row; called from the
 * track-menu hook in {@code download/DownloadTrackPatch.java}. {@code isDisliked} is used by the filters.</li>
 * <li>{@code ForYouJob} - an Android {@code JobService}; the Kotlin patch {@code recommendations/ForYouPatch.kt} only
 * declares it in the manifest. {@code ForYou.onAppStart()} is called from {@code local.SavedPlaylist}.</li>
 * </ul>
 *
 * <p>Main classes: filters - {@code DuplicatePass} (one pass over a list, subclasses differ in reading a track),
 * {@code SongKeys}/{@code SongFingerprint}/{@code SongRef} (what makes two titles the same song); dislikes -
 * {@code DislikeStore}, {@code DislikeMenuRow}; For you - {@code ForYou} (facade for the settings screen),
 * {@code LibrarySeeds} (seed songs from the phone), {@code LastFm}, {@code ForYouCandidates},
 * {@code SoundCloudMatcher} (best candidates, at most two per artist), {@code ForYouPlaylist} (the private playlist),
 * {@code ForYouPrefs}, {@code MatchCache}.
 *
 * <p>Settings: {@code duplicate_filter} and {@code merge_edited_versions} ({@code settings.Settings}),
 * {@code dislikes_enabled} ({@code TrackDislikes.ENABLED}, default on), {@code for_you_enabled} ({@code ForYou.ENABLED}).
 * Preference files: {@code arsound_dislikes}, {@code arsound_dislike_songs}, {@code arsound_for_you}. Screen:
 * {@code settings.RecommendationsScreen}.
 *
 * <p>Gotchas: dislikes never touch likes, playlists or search. "For you" only reads names from the phone and sends only
 * names to Last.fm; all {@code LastFm} calls block, run them off the main thread. The "For you" playlist is empty on
 * the server; its tracks are added on this phone (see {@code local}).
 */
package app.revanced.extension.soundcloud.recommendations;
