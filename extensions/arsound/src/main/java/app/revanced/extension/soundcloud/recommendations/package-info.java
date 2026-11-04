/**
 * Recommendations: hide duplicate uploads, "Not for me" dislikes and the daily "For you" playlist.
 *
 * <p>User view: re-uploads of the same song and tracks marked "Not for me" disappear from the home screen and autoplay;
 * a "For you" playlist of this phone only is rebuilt every midnight from what the user likes and plays (artists and songs found
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
 * {@code SoundCloudMatcher} (best candidates, at most two per artist), {@code ForYouPlaylist} (the playlist, on this phone only),
 * {@code ForYouPrefs}, {@code MatchCache}; My Wave - {@code MyWave} (start, stop, refill when four tracks are left,
 * called by {@code player.PlaybackEvents}), {@code WaveBuilder} (taste, sources, matching to SoundCloud), {@code WaveMixer}
 * (order and the never-again rules), {@code WaveSource} (SoundCloud related / Last.fm / Deezer, ticked in the settings),
 * {@code DeezerApi}, {@code WaveMemory}/{@code WaveLedger} (skipped and heard tracks, fed by {@code ListeningStats}
 * also when the statistics are off; the hooks never wait for the disk: {@code WaveLearner} holds the track lengths and
 * the verdicts, a background thread loads and fills the ledger), {@code ListenVerdict} (under 15 s skipped, 45 s or
 * most of the track heard, judged by the real length of the track); the broken-heart button - {@code DislikeAction}.
 * The tab page is {@code WavePage} (a fragment of the bottom bar's "My Wave" tab, {@code navbar.WaveNavigationTarget}):
 * {@code WaveOrb} draws the moving wave with the button, {@code WaveChip} is a source chip, {@code WaveCovers} loads
 * the small covers, {@code WaveSession} keeps the last wave (its tracks and their titles) for after a restart. The
 * settings screen is {@code settings.WaveScreen} (the tab switch, sources, memory; behind the page's gear and in the
 * settings, {@code settings.WaveScreenLauncher}).
 *
 * <p>Settings: {@code duplicate_filter} and {@code merge_edited_versions} ({@code settings.Settings}),
 * {@code dislikes_enabled} ({@code TrackDislikes.ENABLED}, default on), {@code for_you_enabled} ({@code ForYou.ENABLED}).
 * {@code hide_tab_wave} ({@code settings.BoolSettings.HIDE_TAB_WAVE}), {@code wave_src_soundcloud/lastfm/deezer}. Preference files: {@code arsound_dislikes}, {@code arsound_dislike_songs}, {@code arsound_wave_session},
 * {@code arsound_for_you}, {@code arsound_wave}. Screen:
 * {@code settings.RecommendationsScreen}.
 *
 * <p>Gotchas: dislikes never touch likes, playlists or search. "For you" only reads names from the phone and sends only
 * names to Last.fm; all {@code LastFm} calls block, run them off the main thread. The "For you" playlist lives on
 * this phone only (see {@code local.LocalPlaylists}).
 */
package app.revanced.extension.soundcloud.recommendations;
