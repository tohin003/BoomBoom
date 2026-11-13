import BoomModels
import Foundation

/// The running app's machinery, put together once at launch: the hidden tab, the capture broker, the audio engine,
/// and the stores the screens use.
@MainActor
public final class BoomRuntime {
    public let browser: HiddenBrowser
    public let engine: LiveEngine
    public let services: AppServices

    private let broker: StreamBroker
    private let cache: AudioCache
    private let downloads: DownloadLibrary
    #if canImport(MediaPlayer) && canImport(UIKit)
    private let nowPlaying: NowPlayingCenter
    #endif

    /// - Parameters:
    ///   - api: The data client. It should take its session from `browser.sessionSnapshot()`.
    ///   - browser: The hidden tab. Put its web view in a window (`attach(to:)`) and call `start()`.
    public init(api: any MusicAPI, browser: HiddenBrowser) {
        self.browser = browser
        cache = AudioCache()
        downloads = DownloadLibrary()
        broker = StreamBroker(browser: browser, cache: cache, downloads: downloads)
        engine = LiveEngine(broker: broker, system: SystemAudio())

        let player = PlayerStore(api: api, engine: engine)
        let equalizer = EqualizerStore()
        equalizer.attach(engine)
        services = AppServices(
            api: api, player: player, equalizer: equalizer,
            session: SessionStore(api: api, backend: browser),
            cache: CacheStore(backend: AudioCacheSettings(cache)),
            downloads: DownloadStore(backend: engine, indexURL: downloads.indexURL))
        #if canImport(MediaPlayer) && canImport(UIKit)
        nowPlaying = NowPlayingCenter(player: player)
        #endif
    }
}
