/**
 * Network layer: custom DNS, the region guard, the network status icon, the bot-protection prompt and shared covers.
 *
 * <p>User view: DNS-over-HTTPS or plain DNS for the whole app; requests to SoundCloud are stopped while the app is
 * online from an IP of a chosen country (Russia by default); a small crossed-out globe above the player says "no
 * network" or "SoundCloud is switched off on this IP"; DataDome's device check asks before it opens; a cover that two
 * image loaders ask for at once is downloaded once.
 *
 * <p>Entry points (Kotlin side: {@code network/NetworkPatch.kt}):
 * <ul>
 * <li>{@code NetworkPatch.onBuild(Object builder)} - called at the end of {@code OkHttpClient.Builder.build()}, so it
 * reaches every OkHttp client of the app. It adds one named interceptor ({@code RequestGate}: developer delay, then
 * {@code RegionGuard.throwIfBlocked}, then {@code power.PowerSavingPatch.isBackgroundReportBlocked}, then
 * {@code SharedArtwork}) and wraps the {@code Dns} with {@code CustomDns.lookup}. The wrappers are
 * {@code ReflectiveProxy} objects with a name, so a client copied with {@code newBuilder()} is not wrapped twice.</li>
 * <li>{@code RegionGuard.isConnected(boolean)} - every {@code return} of SoundCloud's "is the network connected" check
 * passes through it, so the app also sees "offline" while the guard blocks.</li>
 * <li>{@code DataDomePrompt.start(Context, Intent)} - replaces the start of the DataDome check screen.</li>
 * </ul>
 *
 * <p>Main classes: {@code CustomDns} with {@code DohResolver} and {@code PlainDnsResolver} (both extend
 * {@code DnsResolver}; {@code DnsMessage} and {@code DnsLookup} are the wire format); {@code RegionGuard} with
 * {@code RegionState}, {@code CountryTrace} (Cloudflare trace), {@code NetworkWatcher}, {@code IpCountry}, and the
 * messages {@code BlockNotice}; {@code NetworkBanner} with {@code GlobeDrawable}.
 *
 * <p>Settings (in {@code settings.Settings}, screens {@code NetworkScreen}, {@code DnsRows}, {@code RegionGuardRows}):
 * {@code custom_dns}, {@code dns_mode}, {@code dns_preset}, {@code custom_doh_url}, {@code custom_dns_servers},
 * {@code region_guard}, {@code region_guard_countries}, {@code region_recheck_on_change},
 * {@code region_recheck_periodic}, {@code network_banner}, {@code datadome_prompt} and the developer option
 * {@code developer_network_delay_seconds}.
 *
 * <p>Gotchas:
 * <ul>
 * <li>OkHttp classes are not on the compile path; everything goes through reflection and {@code Proxy}. An OkHttp
 * interceptor may throw only {@code IOException} or unchecked exceptions; {@code RequestGate.intercept} unwraps
 * everything else, otherwise the app crashes on a cancelled download.</li>
 * <li>While the country check runs, requests wait; they must never go out before the answer.</li>
 * <li>The custom resolver never breaks the network: if the chosen server fails, the system resolver answers.</li>
 * <li>{@code NetworkBanner.onActivityResumed/onActivityPaused} are wired in {@code update.UpdateChecker}'s screen
 * hooks, not by a patch.</li>
 * </ul>
 */
package app.revanced.extension.soundcloud.network;
