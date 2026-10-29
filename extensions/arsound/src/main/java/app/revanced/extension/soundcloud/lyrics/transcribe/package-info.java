/**
 * Neural recognition of lyrics on the phone, for songs LRCLIB does not know: the voice is taken out of the music
 * (UVR-MDX-NET Voc_FT through ONNX Runtime), then GigaAM v3 (Russian) or Whisper writes the words (through sherpa-onnx).
 *
 * <p>User view: after the user agrees (off by default), the lyrics page of a song without lyrics offers to listen to
 * the song and write its words with timings. 64-bit ARM phones (and x86-64); the native libraries and the models
 * are downloaded on demand, not shipped in the APK. The result is stored and shown as lyrics "heard by the network".
 *
 * <p>Entry points: none from Kotlin patches. Everything public is in {@code TranscriptionManager}, called on the main
 * thread by {@code lyrics.LyricsAi} and by the settings screens: {@code isSupportedDevice}, {@code isEnabled},
 * {@code canOffer}, {@code stored}, {@code advise}, {@code start(urn, durationMs, Listener)}, {@code attach}/
 * {@code detach}, {@code cancel}, {@code startInstall}, {@code deleteModel}, {@code deleteAllFiles},
 * {@code clearTranscripts}.
 *
 * <p>Pipeline ({@code TranscriptionJob}, one background thread): {@code AsrInstaller} (the {@code NativeArchive}
 * libraries, the {@code AsrModel} and the {@code VoiceModel}, with {@code ResumableDownload} and SHA-256 checks) ->
 * {@code TrackAudio} (imported file, own download, or a temporary stream fetched by {@code HlsFetch}/{@code HlsPlaylist}/
 * {@code HttpBytes}) -> {@code AudioDecoder} into a {@code PcmTarget}: {@code MonoTarget} (the whole song) or
 * {@code VocalsTarget} ({@code VocalsStream} pieces through {@code MdxSeparator} with {@code MdxStft}/{@code RealFft},
 * ONNX Runtime loaded by {@code OrtLibs}; on a Snapdragon's neural processor through {@code Npu} where there is one),
 * both ending in mono 16 kHz via {@code StreamResampler} -> {@code Chunker}
 * (30 s windows) -> {@code SongListener} (per window: a {@code Recognizer}, {@code GigaAmRunner} or
 * {@code WhisperRunner}, the only classes that touch sherpa-onnx; the language by {@code LanguagePolicy}; a window that
 * looped is heard again in halves) -> {@code TextCleaner} (with {@code Loops}) and {@code LineTimer} ->
 * {@code Transcript}, stored by {@code TranscriptStore}/{@code TranscriptCodec}. {@code Guards} with {@code DeviceState} decide whether the phone
 * may work now (charge, heat, memory, metered network); {@code Advice} and {@code Failure} are what the page tells.
 *
 * <p>Settings: {@code lyrics_ai_enabled}, {@code lyrics_ai_separate} in {@code settings.Settings}. No model or language
 * is set for all songs: the user names the language of each song ({@code SongLanguage}), which picks the model; the
 * answer is remembered per artist ({@code arsound_song_languages}). GigaAM's lines are cut at the singer's pauses and
 * timed by its words ({@code HeardWord}, {@code PhraseLines}); Whisper's are spread over the window
 * ({@code LineTimer}).
 * Files live in {@code files/arsound/asr} (library per version, models) and the transcripts folder of the app.
 *
 * <p>Gotchas:
 * <ul>
 * <li>The Java classes of sherpa-onnx are vendored in {@code com.k2fsa.sherpa.onnx} and those of ONNX Runtime in
 * {@code ai.onnxruntime} (same tags as the native libraries; ONNX Runtime's loader reads the library path the app sets,
 * the one change); nothing may use them before {@code NativeLibs}/{@code OrtLibs} have loaded the checked libraries.
 * The ProGuard rules keep them.</li>
 * <li>{@code Npu} compiles the separation model for the neural processor once and keeps the result beside the model;
 * a phone where that fails is remembered ({@code npu-refused-<version>} in {@code files/arsound/asr}) and stays on the
 * cores. Only the generations listed there are used: fp16 on the processor was checked against the cores on real
 * songs (the same words).</li>
 * <li>The SHA-256 of every file and the URLs are written in {@code NativeArchive}, {@code AsrModel} and
 * {@code VoiceModel}, never taken from the server; only HTTPS and no foreign host after a redirect.</li>
 * <li>Never listen to DRM: {@code download.TranscriptionAudio} refuses previews, subscription-only tracks and every
 * protected stream (FairPlay, Widevine, SAMPLE-AES) before a byte is fetched. Keep that check in front of any new audio
 * source. Every request first asks {@code network.RegionGuard}.</li>
 * </ul>
 */
package app.revanced.extension.soundcloud.lyrics.transcribe;
