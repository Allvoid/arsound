/**
 * Neural recognition of lyrics on the phone (Whisper through sherpa-onnx), for songs LRCLIB does not know.
 *
 * <p>User view: after the user agrees (off by default), the lyrics page of a song without lyrics offers to listen to
 * the song and write its words with timings. Only 64-bit ARM phones; the native library and the model (small or base)
 * are downloaded on demand, not shipped in the APK. The result is stored and shown as lyrics "heard by the network".
 *
 * <p>Entry points: none from Kotlin patches. Everything public is in {@code TranscriptionManager}, called on the main
 * thread by {@code lyrics.LyricsAi} and by the settings screens: {@code isSupportedDevice}, {@code isEnabled},
 * {@code canOffer}, {@code stored}, {@code advise}, {@code start(urn, durationMs, Listener)}, {@code attach}/
 * {@code detach}, {@code cancel}, {@code startInstall}, {@code deleteModel}, {@code deleteAllFiles},
 * {@code clearTranscripts}.
 *
 * <p>Pipeline ({@code TranscriptionJob}, one background thread): {@code AsrInstaller} (library + model, with
 * {@code ResumableDownload} and SHA-256 checks) -> {@code TrackAudio} (imported file, own download, or a temporary
 * stream fetched by {@code HlsFetch}/{@code HlsPlaylist}/{@code HttpBytes}) -> {@code AudioDecoder} and
 * {@code PcmConverter}/{@code StreamResampler} (mono 16 kHz) -> {@code Chunker} (30 s windows) -> {@code SongListener}
 * (per window: {@code WhisperRunner}, the only class that touches sherpa-onnx; the language by {@code LanguagePolicy};
 * a window that looped is heard again in halves) -> {@code TextCleaner} (with {@code Loops}) and {@code LineTimer} ->
 * {@code Transcript}, stored by {@code TranscriptStore}/{@code TranscriptCodec}. {@code Guards} with {@code DeviceState} decide whether the phone
 * may work now (charge, heat, memory, metered network); {@code Advice} and {@code Failure} are what the page tells.
 *
 * <p>Settings: {@code lyrics_ai_enabled}, {@code lyrics_ai_model}, {@code lyrics_ai_language} in {@code settings.Settings}.
 * Files live in {@code files/arsound/asr} (library per version, models) and the transcripts folder of the app.
 *
 * <p>Gotchas:
 * <ul>
 * <li>The Java classes of sherpa-onnx are vendored in {@code com.k2fsa.sherpa.onnx} (same tag as the native library);
 * nothing may use them before {@code NativeLibs} has loaded the checked library. The ProGuard rule keeps them.</li>
 * <li>The SHA-256 of every file and the URLs are written in {@code AsrLibrary} and {@code AsrModel}, never taken from
 * the server; only HTTPS and no foreign host after a redirect.</li>
 * <li>Never listen to DRM: {@code download.TranscriptionAudio} refuses previews, subscription-only tracks and every
 * protected stream (FairPlay, Widevine, SAMPLE-AES) before a byte is fetched. Keep that check in front of any new audio
 * source. Every request first asks {@code network.RegionGuard}.</li>
 * </ul>
 */
package app.revanced.extension.soundcloud.lyrics.transcribe;
