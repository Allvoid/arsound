/**
 * Everything around playback: equalizer, sleep timer, play mode button, listening statistics, stream cache, mixing with
 * other apps, notification cover, smooth player sheet, the vivo control-centre player, the smooth transition between
 * tracks, loading the next track ahead and hiding the comments that pop up in the player.
 *
 * <p>User view: an equalizer with presets and preamp; a sleep timer in the player's menu ("in 30 minutes" or "at 23:30");
 * a repeat/shuffle button in the media notification; listening statistics kept on the phone; a stream cache with a size
 * chosen by the user; "mix with other apps" (play without audio focus); the right cover in the notification; a player
 * sheet that does not stutter when pulled down; Arsound in the music card of vivo OriginOS; a track that ends fades out
 * and the next fades in; the next streamed track is fetched whole while the current one plays; no pop-up comments.
 *
 * <p>Entry points (Kotlin: {@code player/*Patch.kt}; {@code SleepTimer} is hooked from {@code download/DownloadTrackPatch.kt}):
 * <ul>
 * <li>{@code SoftEqualizer.withProcessor} puts {@code SoftEqualizerProcessor} first in the audio chain of the player: the
 * equalizer and the preamp are shaped in software (filters in {@code player/dsp}), {@code SoftEqualizer.publish} hands it
 * new settings; {@code AudioEqualizer.onAudioSession(int)} is kept empty; {@code ListeningStats.onPlaybackItem/onPlayerState};
 * {@code MixWithOtherApps.skipAudioFocus(Object)}; {@code StreamCache.beforeOpen/customSize/hasCustomSize}.</li>
 * <li>{@code PlayModeButton.build/onCustomAction/remember/setNotificationManager/setQueueManager}; the manifest receivers
 * {@code PlayModeReceiver} and {@code VivoMusicCardReceiver} (names written by the patches).</li>
 * <li>{@code NotificationArtwork.imageProviderPackage/style/remember}; {@code SleepTimer.onTrackMenu/setMenuConsumer};
 * {@code PlayerSheet.onSheetState}; {@code PlayerArtworkLayers.created/round/skipSlide/square}.</li>
 * <li>{@code PlaybackEvents.onPlay/onProgress} (player/TrackTransitionsPatch.kt), handed on to {@code Crossfade} and
 * {@code NextTrackPreload}; {@code PlayerComments.setPlayerSettings/showPopups} (player/PlayerCommentsPatch.kt).</li>
 * <li>Screen hook (no patch): {@code VivoMusicCard.onActivityResumed}.</li>
 * <li>Versions of a track: {@code TrackVersions} (the choice: {@code VersionSpeed}, reverb and bass as {@code VersionStrength}),
 * {@code SlowedPlayback} sets the speed (pitch follows) on the Media3 player, {@code SoftEqualizerProcessor} (in the audio chain of the player, filters in {@code player/dsp}) shapes the equalizer, the
 * preamp, the bass boost and the reverb in software ({@code AudioEqualizer.onVersionsChanged}, {@code SoftEqualizer.publish}), {@code VersionsButton} is
 * the round buttons of the top row of the full player (versions, "Not for me") and {@code VersionsPopup} the small window.
 * "Not for me" also stands in the media notification and the lock screen as a custom action of {@code PlayModeButton}
 * ({@code DISLIKE_ACTION}, {@code PlayModeReceiver} for Android 12L and older) and in the 2 x 2 widget; the work is
 * {@code recommendations.DislikeAction}.</li>
 * </ul>
 *
 * <p>Main classes: sound - {@code AudioEqualizer}, {@code SoftEqualizer}, {@code SoftEqualizerProcessor}, {@code GraphicCurve}, {@code UserPresetStore},
 * {@code EqualizerChoice}; timer - {@code SleepTimer}, {@code SleepTimerScheduler}, {@code SleepTimerDialog} and the
 * {@code TimerMode} implementations {@code InTimeMode}/{@code AtClockMode}; queue - {@code PlayQueueApi} (the one place
 * that knows the obfuscated queue methods), {@code PlayMode}; stats - {@code ListeningStats}, {@code ListenSession},
 * {@code ListenRecord}, {@code HistoryFile}, {@code TrackTitleResolver}; focus - {@code MixWithOtherApps},
 * {@code CallWatcher}; vivo - {@code VivoMusicCard}, {@code VivoPlayerList}, {@code VivoHintScheduler}/{@code VivoHintScreen};
 * transitions - {@code PlaybackEvents}, {@code Crossfade}, {@code NextTrackPreload} (the copies themselves are
 * {@code download.PrefetchedTracks}); comments - {@code PlayerComments}.
 *
 * <p>Settings: keys live next to the feature - {@code equalizer_enabled/preset/levels/preamp/choice/user_presets}
 * ({@code AudioEqualizer}), {@code listening_stats} ({@code ListeningStats}), {@code arsound_player_track_colors}
 * ({@code NotificationArtwork}), and in {@code settings.Settings}: {@code mix_with_other_apps}, {@code stream_cache_size},
 * {@code stream_cache_days}, {@code smooth_player_collapse}, {@code crossfade}, {@code crossfade_seconds},
 * {@code fx_speed}, {@code fx_reverb}, {@code fx_bass}, {@code slowed_percent}, {@code fx_speedup_percent},
 * {@code preload_next}, {@code hide_player_comments}. Own preference files: {@code arsound_listening_stats},
 * {@code arsound_sleep_timer}, {@code arsound_vivo_music_card}.
 *
 * <p>Gotchas: SoundCloud's queue manager and player classes are reached by reflection under their short obfuscated
 * names, so a SoundCloud update can break {@code PlayQueueApi}; fix it there only. With "mix with other apps" on,
 * {@code CallWatcher} must pause the music for phone calls. {@code VivoMusicCard} needs WRITE_SECURE_SETTINGS (granted
 * once over adb) and only touches the vivo player's own whitelist.
 */
package app.revanced.extension.soundcloud.player;
