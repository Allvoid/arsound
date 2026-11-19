/**
 * Recommendations: hide duplicate uploads, "Not for me" dislikes and My Wave.
 *
 * <p>User view: re-uploads of the same song and tracks marked "Not for me" disappear from the home screen and autoplay;
 * the bottom bar's "My Wave" tab plays an endless wave built from what the user likes and plays (SoundCloud related,
 * Last.fm, Deezer; names only, no account, matched back to SoundCloud tracks). There is no "For you" playlist any more:
 * {@code local.LegacyForYou} takes what an earlier version left (the nightly job, the playlist, its memory) off the phone once.
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
 * <li>{@code MyWave.addTrackMenuRow(Dialog, Object trackUrn)} - the "My Wave by this track" row ({@code WaveTrackRow}) of the
 * same track-menu hook; a tap calls {@code MyWave.startFromTrack}, which plays the track and then what
 * {@code WaveBuilder.buildStation} finds for it (SoundCloud related and their neighbours, Deezer, Last.fm).</li>
 * <li>{@code StreamReposts.hideReposts(Object)} - a page of the Feed about to be stored; called by
 * {@code recommendations/StreamRepostsPatch.kt}. Switch {@code hide_feed_reposts}, default off.</li>
 * </ul>
 *
 * <p>Main classes: filters - {@code DuplicatePass} (one pass over a list, subclasses differ in reading a track),
 * {@code SongKeys}/{@code SongFingerprint}/{@code SongRef} (what makes two titles the same song), {@code WaveDedupe} (one copy
 * of a song in a wave batch, against the queue and the batch itself); dislikes -
 * {@code DislikeStore}, {@code DislikeMenuRow}; names - {@code SongNames}; {@code LastFm}; My Wave - {@code MyWave} (start, stop, refill when four tracks are left,
 * called by {@code player.PlaybackEvents}), {@code WaveBuilder} (taste, sources, matching to SoundCloud), {@code WaveMixer}
 * (order and the never-again rules), {@code WaveSource} (SoundCloud related / Last.fm / Deezer, ticked in the settings),
 * {@code DeezerApi}, {@code WaveMemory}/{@code WaveLedger} (skipped and heard tracks, fed by {@code ListeningStats}
 * also when the statistics are off; the hooks never wait for the disk: {@code WaveLearner} holds the track lengths and
 * the verdicts, a background thread loads and fills the ledger), {@code ListenVerdict} (under 15 s skipped, 45 s or
 * most of the track heard, judged by the real length of the track); the broken-heart button - {@code DislikeAction}.
 * The tab page is {@code WavePage} (a fragment of the bottom bar's "My Wave" tab, {@code navbar.WaveNavigationTarget}):
 * {@code WaveLines} draws the moving lines with the button, {@code WaveChip} is a source chip, {@code WaveCovers} loads
 * the small covers, {@code WaveSession} keeps the last wave (its tracks and their titles) for after a restart. The
 * settings screen is {@code settings.WaveScreen} (the tab switch, sources, memory; behind the page's gear and in the
 * settings, {@code settings.WaveScreenLauncher}).
 *
 * <p>Settings: {@code duplicate_filter}, {@code merge_edited_versions} and {@code hide_feed_reposts} ({@code settings.Settings}),
 * {@code dislikes_enabled} ({@code TrackDislikes.ENABLED}, default on).
 * {@code hide_tab_wave} ({@code settings.BoolSettings.HIDE_TAB_WAVE}), {@code wave_src_soundcloud/lastfm/deezer}. Preference files: {@code arsound_dislikes}, {@code arsound_dislike_songs}, {@code arsound_wave_session},
 * {@code arsound_wave}. Screen:
 * {@code settings.RecommendationsScreen}.
 *
 * <p>Gotchas: dislikes never touch likes, playlists or search. My Wave sends only names to Last.fm; all {@code LastFm}
 * calls block, run them off the main thread.
 */
package app.revanced.extension.soundcloud.recommendations;
