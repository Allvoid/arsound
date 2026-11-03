/**
 * Recommendations: hide duplicate uploads, "Not for me" dislikes and the daily "For you" playlist.
 *
 * <p>User view: re-uploads of the same song and tracks marked "Not for me" disappear from the home screen and autoplay;
 * a private "For you" playlist is rebuilt every midnight from what the user likes and plays (artists and songs found
 * through Last.fm, names only, no account, matched back to SoundCloud tracks).
 *
 * <p>Entry points:
 * <ul>
 * <li>{@code AutoplayQueue.setQueueManager(Object)} and {@code removeUpcomingRecommendations(Object)} - the queue
 * manager's constructor and its refill of recommendations; called by {@code recommendations/DuplicateFilterPatch.kt}.</li>
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
 * {@code ForYouPrefs}, {@code MatchCache}; My Wave - {@code MyWave} (start, stop, refill when four tracks are left,
 * called by {@code player.PlaybackEvents}), {@code WaveBuilder} (taste, sources, matching to SoundCloud), {@code WaveMixer}
 * (order and the never-again rules), {@code WaveSource} (SoundCloud related / Last.fm / Deezer, ticked in the settings),
 * {@code DeezerApi}, {@code WaveMemory}/{@code WaveLedger} (skipped and heard tracks, fed by {@code ListeningStats}),
 * {@code ListenVerdict} (under 15 s skipped, 45 s or most of the track heard); the broken-heart button - {@code DislikeAction}.
 * The screen is {@code settings.WaveScreen} (opened by the wave icon that {@code navbar.LibrarySearchButton} adds to the Library top bar,
 * {@code settings.WaveScreenLauncher}); the big card is {@code theme.WaveCard}.
 *
 * <p>Settings: {@code duplicate_filter} and {@code merge_edited_versions} ({@code settings.Settings}),
 * {@code dislikes_enabled} ({@code TrackDislikes.ENABLED}, default on), {@code for_you_enabled} ({@code ForYou.ENABLED}).
 * {@code hide_wave_button}, {@code wave_src_soundcloud/lastfm/deezer}. Preference files: {@code arsound_dislikes}, {@code arsound_dislike_songs},
 * {@code arsound_for_you}, {@code arsound_wave}. Screen:
 * {@code settings.RecommendationsScreen}.
 *
 * <p>Gotchas: dislikes never touch likes, playlists or search. "For you" only reads names from the phone and sends only
 * names to Last.fm; all {@code LastFm} calls block, run them off the main thread. The "For you" playlist is empty on
 * the server; its tracks are added on this phone (see {@code local}).
 */
package app.revanced.extension.soundcloud.recommendations;
