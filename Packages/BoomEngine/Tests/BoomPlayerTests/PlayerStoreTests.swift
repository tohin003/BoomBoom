import BoomModels
import Foundation
import Synchronization
import Testing
@testable import BoomPlayer

/// A data source with just enough behaviour for the queue: every song's radio is five numbered followers.
private final class StubAPI: MusicAPI, @unchecked Sendable {
    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue {
        let seed = endpoint.videoId ?? endpoint.playlistId ?? "x"
        let first = Song(videoId: seed, title: "Song \(seed)", duration: 200)
        let rest = (1...5).map { Song(videoId: "\(seed)-r\($0)", title: "Radio \($0)", duration: 180) }
        return WatchQueue(songs: [first] + rest, currentIndex: 0, continuation: "more-\(seed)",
                          lyricsBrowseId: "lyrics-\(seed)", relatedBrowseId: "related-\(seed)")
    }
    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation {
        SongsContinuation(songs: (1...3).map { Song(videoId: "\(token)-\($0)", title: "More \($0)", duration: 150) }, continuation: nil)
    }
    func lyrics(browseId: String) async throws -> Lyrics { Lyrics(lines: [.init(text: browseId)]) }
    func related(browseId: String) async throws -> [Shelf] { [] }
    func rate(videoId: String, _ status: LikeStatus) async throws {}

    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] { [] }
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults { SearchResults(query: query) }
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation { .init(items: [], continuation: nil) }
    func home(chip: Chip?) async throws -> BrowsePage { BrowsePage() }
    func explore() async throws -> BrowsePage { BrowsePage() }
    func charts(country: String?) async throws -> BrowsePage { BrowsePage() }
    func moodsAndGenres() async throws -> [ChipGroup] { [] }
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage { BrowsePage() }
    func browseContinuation(_ token: String) async throws -> BrowsePage { BrowsePage() }
    func album(id: String) async throws -> AlbumPage { AlbumPage(album: AlbumItem(id: id, title: id)) }
    func artist(id: String) async throws -> ArtistPage { ArtistPage(artist: ArtistItem(id: id, name: id)) }
    func playlist(id: String) async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: id, title: id)) }
    func playlistContinuation(_ token: String) async throws -> SongsContinuation { .init(songs: [], continuation: nil) }
    func account() async throws -> Account? { nil }
    func library(_ section: LibrarySection) async throws -> BrowsePage { BrowsePage() }
    func likedSongs() async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: "LM", title: "Liked")) }
    func history() async throws -> BrowsePage { BrowsePage() }
    func ratePlaylist(id: String, _ status: LikeStatus) async throws {}
    func subscribe(channelId: String, _ subscribed: Bool) async throws {}
    func sendLibraryFeedback(token: String) async throws {}
    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String]) async throws -> String { "PL" }
    func deletePlaylist(id: String) async throws {}
    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws {}
    func addToPlaylist(id: String, videoIds: [String]) async throws {}
    func removeFromPlaylist(id: String, songs: [Song]) async throws {}
}

@MainActor
private func makeStore() -> (PlayerStore, SimulatedEngine) {
    let engine = SimulatedEngine()
    engine.loadDelay = .milliseconds(5)
    let defaults = UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!
    return (PlayerStore(api: StubAPI(), engine: engine, defaults: defaults), engine)
}

/// Waits until `condition` holds, for at most `seconds`: the tests must not depend on how busy the machine is.
@MainActor
private func eventually(_ seconds: Double = 3, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

private func songs(_ count: Int) -> [Song] {
    (0..<count).map { Song(videoId: "v\($0)", title: "Track \($0)", duration: 100) }
}

@MainActor
@Suite struct PlayerStoreTests {
    @Test func playingASongFillsTheQueueWithItsRadio() async throws {
        let (store, _) = makeStore()
        store.play(Song(videoId: "abc", title: "Abc", duration: 200))
        #expect(store.current?.videoId == "abc")
        #expect(store.status == .loading)
        #expect(await eventually { store.status == .playing && store.queue.count == 6 })
        #expect(store.queue.map(\.song.videoId) == ["abc", "abc-r1", "abc-r2", "abc-r3", "abc-r4", "abc-r5"])
        #expect(store.source?.kind == .radio)
        #expect(try await store.lyrics()?.lines.first?.text == "lyrics-abc")
    }

    @Test func playingAListStartsAtTheChosenSong() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(5), startAt: 2, source: QueueSource(kind: .album, label: "Album", title: "A"))
        #expect(store.current?.videoId == "v2")
        #expect(store.hasPrevious && store.hasNext)
        store.next()
        #expect(store.current?.videoId == "v3")
        store.previous()
        #expect(store.current?.videoId == "v2")
        #expect(store.upNext.map(\.song.videoId) == ["v3", "v4"])
    }

    @Test func previousRestartsTheSongWhenWellIntoIt() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(3), startAt: 1)
        #expect(await eventually { store.status == .playing })
        store.seek(to: 50)
        store.previous()
        #expect(store.current?.videoId == "v1")
        #expect(store.clock.position == 0)
    }

    @Test func queueEdits() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(4), startAt: 0)
        store.playNext([Song(videoId: "n", title: "Next")])
        store.addToQueue([Song(videoId: "z", title: "Last")])
        #expect(store.queue.map(\.song.videoId) == ["v0", "n", "v1", "v2", "v3", "z"])
        store.moveQueue(fromOffsets: IndexSet(integer: 5), toOffset: 1)
        #expect(store.queue.map(\.song.videoId) == ["v0", "z", "n", "v1", "v2", "v3"])
        let removed = store.queue[2].id
        store.removeFromQueue([removed])
        #expect(store.queue.map(\.song.videoId) == ["v0", "z", "v1", "v2", "v3"])
        // Removing the current song moves on to the next.
        store.removeFromQueue([store.queue[0].id])
        #expect(store.current?.videoId == "z")
        store.clearUpNext()
        #expect(store.queue.map(\.song.videoId) == ["z"])
    }

    @Test func shuffleKeepsTheCurrentSongAndCanBeUndone() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(30), startAt: 3)
        let before = store.queue.map(\.song.videoId)
        store.toggleShuffle()
        #expect(store.isShuffled)
        #expect(store.current?.videoId == "v3")
        #expect(Array(store.queue.map(\.song.videoId).prefix(4)) == Array(before.prefix(4)))
        #expect(Set(store.queue.map(\.song.videoId)) == Set(before))
        #expect(store.queue.map(\.song.videoId) != before)
        store.toggleShuffle()
        #expect(!store.isShuffled)
        #expect(store.queue.map(\.song.videoId) == before)
    }

    @Test func songsFollowOnAndRepeatAllWraps() async throws {
        let (store, engine) = makeStore()
        let short = (0..<2).map { Song(videoId: "s\($0)", title: "S\($0)", duration: 0.25) }
        store.setRepeatMode(.all)
        store.play(songs: short, startAt: 0)
        #expect(await eventually { store.current?.videoId == "s1" })
        #expect(await eventually { store.current?.videoId == "s0" })
        _ = engine
    }

    @Test func aRadioCarriesOnPastTheEndOfAList() async throws {
        let (store, _) = makeStore()
        let short = [Song(videoId: "only", title: "Only", duration: 0.2)]
        store.play(songs: short, startAt: 0, source: QueueSource(kind: .album, label: "Album", title: "A"))
        #expect(await eventually { store.current?.videoId == "only-r1" })
        #expect(store.queue.count > 1)
    }

    @Test func likingTheCurrentSongStaysWithThatSong() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(3), startAt: 0)
        #expect(store.likeStatus == .indifferent)
        store.setLike(.like)
        #expect(store.likeStatus == .like)
        store.next()
        #expect(store.likeStatus == .indifferent)
        store.previous()
        #expect(store.current?.videoId == "v0")
        #expect(store.likeStatus == .like)
    }

    @Test func reorderingWhileShuffledKeepsTheListenersOrder() async throws {
        let (store, _) = makeStore()
        store.play(songs: songs(8), startAt: 0)
        store.toggleShuffle()
        store.moveQueue(fromOffsets: IndexSet(integer: 7), toOffset: 1)
        let chosen = store.queue.map(\.song.videoId)
        store.toggleShuffle()
        #expect(!store.isShuffled)
        #expect(store.queue.map(\.song.videoId) == chosen)
    }

    @Test func anUnplayableSongInTheQueueIsPassedOver() async throws {
        let (store, engine) = makeStore()
        engine.unplayable = ["v1"]
        let short = (0..<3).map { Song(videoId: "v\($0)", title: "T\($0)", duration: 0.2) }
        store.play(songs: short, startAt: 0)
        // v0 plays to its end, v1 fails and is passed over, v2 plays.
        #expect(await eventually(5) { store.current?.videoId == "v2" && store.status == .playing })
    }

    @Test func anUnplayableSongTheListenerPickedShowsItsFailure() async throws {
        let (store, engine) = makeStore()
        engine.unplayable = ["v1"]
        store.play(songs: songs(3), startAt: 1)
        #expect(await eventually { store.status.isFailure })
        try await Task.sleep(for: .milliseconds(1500))
        #expect(store.current?.videoId == "v1")
        #expect(store.status.isFailure)
        // Trying again once it can be played works.
        engine.unplayable = []
        store.retry()
        #expect(await eventually { store.status == .playing })
    }

    @Test func aRunOfUnplayableSongsStopsThePassingOver() async throws {
        let (store, engine) = makeStore()
        engine.unplayable = ["v1", "v2", "v3", "v4", "v5"]
        store.play(songs: songs(8), startAt: 0)
        store.next()
        // Passed over v1 and v2; on the third failure in a row it stays put (most likely the network is down).
        #expect(await eventually(6) { store.current?.videoId == "v3" && store.status.isFailure })
        try await Task.sleep(for: .milliseconds(1600))
        #expect(store.current?.videoId == "v3")
    }

    @Test func sleepTimerAtEndOfSongPauses() async throws {
        let (store, _) = makeStore()
        let short = (0..<2).map { Song(videoId: "s\($0)", title: "S\($0)", duration: 0.25) }
        store.play(songs: short, startAt: 0)
        store.setSleepTimer(.endOfSong)
        #expect(await eventually { store.status == .paused && store.sleepTimer == nil })
        #expect(store.sleepTimer == nil)
    }
}

@MainActor
@Suite struct EqualizerStoreTests {
    private func makeStore() -> EqualizerStore {
        EqualizerStore(defaults: UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!)
    }

    @Test func aBandBoostShowsInTheCurveAtItsFrequency() {
        let store = makeStore()
        store.setGain(6, band: 5)
        #expect(store.settings.isEnabled)
        let curve = store.response(at: [1000, 100, 10_000])
        #expect(abs(curve[0] - 6) < 0.05)
        #expect(abs(curve[1]) < 0.3)
        #expect(abs(curve[2]) < 0.3)
    }

    @Test func offMeansFlat() {
        let store = makeStore()
        store.select(EqualizerStore.builtInPresets[1])
        store.setEnabled(false)
        #expect(store.response(at: [50, 1000]).allSatisfy { $0 == 0 })
        #expect(store.settings.isTransparent)
    }

    @Test func shelvesReachTheirGainAtTheExtremes() {
        let store = makeStore()
        store.setEnabled(true)
        store.setBass(9)
        store.setTreble(4)
        let curve = store.response(at: [20, 20_000, 1000])
        #expect(abs(curve[0] - 9) < 0.5)
        #expect(abs(curve[1] - 4) < 0.5)
        #expect(abs(curve[2]) < 0.6)
        // Auto headroom, when asked for, follows the highest point of the whole curve.
        #expect(store.settings.headroom == 0)
        store.setAutoHeadroomEnabled(true)
        #expect(abs(store.settings.headroom - 9) < 0.15)
        #expect(store.settings.profile.headroom == store.settings.headroom)
    }

    @Test func tenBandsSetAlikeGiveALevelCurve() {
        let store = makeStore()
        for band in 0..<10 { store.setGain(6, band: band) }
        let centres = EqualizerStore.bands.map(\.frequency)
        #expect(store.response(at: centres).allSatisfy { abs($0 - 6) < 0.01 })
        let between = store.response(at: [45, 90, 177, 354, 707, 1414, 2828, 5657, 11_314])
        #expect(between.allSatisfy { abs($0 - 6) < 0.4 })
        store.setAutoHeadroomEnabled(true)
        #expect(store.settings.headroom >= 6 && store.settings.headroom < 6.3)
    }

    @Test func comparingPlaysTheSongAsMadeAndLeavesTheSettingsAlone() {
        let sink = RecordingSink()
        let store = EqualizerStore(sink: sink, defaults: UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!)
        store.setGain(6, band: 2)
        store.setBoost(4)
        #expect(sink.last?.isEnabled == true)
        store.setComparing(true)
        #expect(store.isComparing)
        #expect(sink.last?.isEnabled == false)
        #expect(store.settings.isEnabled && store.settings.boost == 4)
        store.setComparing(false)
        #expect(sink.last == store.settings)
        // Touching a control ends the comparison and is heard at once.
        store.setComparing(true)
        store.setBoost(5)
        #expect(!store.isComparing)
        #expect(sink.last?.isEnabled == true && sink.last?.boost == 5)
    }

    @Test func settingsSavedByAnOlderVersionStillLoad() throws {
        let old = #"{"preamp":2,"isLoudnessNormalizationEnabled":true,"gains":[1,0,0,0,0,0,0,0,0,3],"isAutoHeadroomEnabled":false,"boost":5,"bass":4,"treble":0,"isEnabled":true,"isLimiterEnabled":true}"#
        let settings = try JSONDecoder().decode(SoundSettings.self, from: Data(old.utf8))
        #expect(settings.boost == 5 && settings.gains[9] == 3 && settings.isLoudnessNormalizationEnabled)
        #expect(settings.balance == 0 && settings.width == 1)
    }

    @Test func resetCentresAndFlattensButKeepsTheSwitch() {
        let store = makeStore()
        store.setGain(5, band: 1)
        store.setBalance(-0.4)
        store.setWidth(1.6)
        store.setBoost(8)
        store.reset()
        #expect(store.settings.isEnabled)
        #expect(store.settings.gains.allSatisfy { $0 == 0 })
        #expect(store.settings.balance == 0 && store.settings.width == 1 && store.settings.boost == 0)
        #expect(store.settings.isTransparent)
    }

    @Test func metersFollowTheEngine() async {
        let sink = RecordingSink()
        sink.levels.peakLeft = 0.5
        sink.levels.peakRight = 0.25
        sink.levels.rmsLeft = 0.25
        sink.levels.gainReduction = 3
        let store = EqualizerStore(sink: sink, defaults: UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!)
        store.startMetering()
        #expect(await eventually { store.meter.left > -7 })
        #expect(abs(store.meter.left + 6.02) < 0.1)
        #expect(abs(store.meter.right + 12.04) < 0.1)
        #expect(abs(store.meter.rmsLeft + 12.04) < 0.1)
        #expect(store.meter.gainReduction > 2.5)
        #expect(store.meter.spectrum.count == LevelMeter.spectrumBands)
        #expect(store.meter.spectrum[10] == -30)
        store.stopMetering()
        #expect(store.meter.left == LevelMeter.floor && store.meter.gainReduction == 0)
    }

    @Test func presetsAreRememberedAndCustomOnesCanGo() {
        let suite = "boom.tests.\(UUID().uuidString)"
        let first = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        first.setGain(3, band: 0)
        let saved = first.saveCurrentAsPreset(named: "  Mine ")
        #expect(saved.name == "Mine")
        let second = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        #expect(second.settings.gains[0] == 3)
        #expect(second.presets.contains { $0.id == saved.id })
        #expect(second.selectedPresetID == saved.id)
        second.deletePreset(id: saved.id)
        second.deletePreset(id: "flat")
        #expect(!second.presets.contains { $0.id == saved.id })
        #expect(second.presets.contains { $0.id == "flat" })
    }

    @Test func aSavedPresetKeepsTheWholeSoundAndABuiltInOneOnlyTheBands() {
        let suite = "boom.tests.\(UUID().uuidString)"
        let store = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        store.setGain(4, band: 1)
        store.setBoost(7)
        store.setBass(3)
        store.setDeepBass(0.5)
        store.setWidth(1.4)
        store.setLimiterEnabled(true)
        let mine = store.saveCurrentAsPreset(named: "Car")
        #expect(mine.sound?.boost == 7 && mine.sound?.deepBass == 0.5)
        #expect(store.selectedPresetID == mine.id)

        // A built-in preset changes the bands and leaves the amplifier where it is.
        let rock = EqualizerStore.builtInPresets.first { $0.id == "rock" }!
        store.select(rock)
        #expect(store.settings.gains == rock.gains && store.settings.boost == 7 && store.settings.width == 1.4)
        #expect(store.selectedPresetID == "rock")
        // Moving the boost does not unselect a preset that says nothing about the boost...
        store.setBoost(2)
        #expect(store.selectedPresetID == "rock")
        // ...but moving a band does.
        store.setGain(-3, band: 4)
        #expect(store.selectedPresetID == nil)

        // The listener's own preset brings everything back, also after a restart.
        let later = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        let saved = later.presets.first { $0.id == mine.id }!
        later.select(saved)
        #expect(later.settings.gains[1] == 4 && later.settings.boost == 7 && later.settings.bass == 3)
        #expect(later.settings.deepBass == 0.5 && later.settings.width == 1.4)
        #expect(later.selectedPresetID == mine.id)
        // Here the boost is part of the preset, so changing it unselects.
        later.setBoost(9)
        #expect(later.selectedPresetID == nil)
        // Saving over it keeps the new sound under the same name.
        later.overwritePreset(id: mine.id)
        #expect(later.presets.first { $0.id == mine.id }?.sound?.boost == 9)
        #expect(later.selectedPresetID == mine.id)
    }

    @Test func favouritesAreRememberedAndGoWithADeletedPreset() {
        let suite = "boom.tests.\(UUID().uuidString)"
        let store = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        let mine = store.saveCurrentAsPreset(named: "Mine")
        store.setFavorite(true, presetID: "jazz")
        store.setFavorite(true, presetID: mine.id)
        store.setFavorite(true, presetID: "no-such-preset")
        #expect(store.favoritePresetIDs == ["jazz", mine.id])
        #expect(store.isFavorite("jazz") && !store.isFavorite("rock"))
        let later = EqualizerStore(defaults: UserDefaults(suiteName: suite)!)
        #expect(later.favoritePresets.map(\.id) == ["jazz", mine.id])
        later.deletePreset(id: mine.id)
        later.setFavorite(false, presetID: "jazz")
        #expect(later.favoritePresetIDs.isEmpty)
        #expect(EqualizerStore(defaults: UserDefaults(suiteName: suite)!).favoritePresetIDs.isEmpty)
    }

    @Test func aPresetSavedBeforeTheAmplifierWasKeptStillLoads() throws {
        let old = #"[{"id":"custom-1","name":"Old","gains":[1,2,3,4,5,6,7,8,9,10],"isBuiltIn":false}]"#
        let presets = try JSONDecoder().decode([EqualizerPreset].self, from: Data(old.utf8))
        #expect(presets[0].sound == nil && presets[0].gains[9] == 10)
    }

    @Test func settingsFromBeforeAutoHeadroomWasOptionalAreBroughtInLineOnce() {
        let suite = "boom.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let old = #"{"preamp":0,"gains":[0,0,3,0,0,0,0,0,0,0],"isAutoHeadroomEnabled":true,"boost":0,"bass":0,"treble":0,"isEnabled":true,"isLimiterEnabled":true}"#
        defaults.set(Data(old.utf8), forKey: "sound.settings")
        let first = EqualizerStore(defaults: defaults)
        #expect(!first.settings.isAutoHeadroomEnabled && first.settings.gains[2] == 3)
        // After that it is the listener's to decide, and the choice sticks.
        first.setAutoHeadroomEnabled(true)
        #expect(EqualizerStore(defaults: defaults).settings.isAutoHeadroomEnabled)
    }

    @Test func spatialIsPartOfTheSoundAndOfASavedPreset() {
        let sink = RecordingSink()
        let store = EqualizerStore(sink: sink, defaults: UserDefaults(suiteName: "boom.tests.\(UUID().uuidString)")!)
        #expect(!store.settings.wantsHeadTracking)
        store.setEnabled(true)
        store.setSpatialEnabled(true)
        #expect(sink.last?.profile.isSpatial == true)
        #expect(store.settings.wantsHeadTracking && !store.settings.isTransparent)
        let preset = store.saveCurrentAsPreset(named: "Headphones")
        store.setSpatialEnabled(false)
        #expect(store.selectedPresetID == nil)
        store.select(preset)
        #expect(store.settings.isSpatialEnabled)
        store.setHeadTrackingEnabled(false)
        #expect(!store.settings.wantsHeadTracking)
        // The headphones' ability is asked of the engine when a screen starts looking.
        sink.isHeadTrackingAvailable = true
        store.refreshHeadTrackingAvailability()
        #expect(store.isHeadTrackingAvailable)
    }

    @Test func theHeadTrackerKeepsLoudspeakersInPlaceAndLetsThemFollowSlowly() {
        let turns = Mutex<[Float]>([])
        let tracker = HeadTracker { turn in turns.withLock { $0.append(turn) } }
        tracker.take(yaw: 1.0, at: 100)                 // where the head points when it starts: straight ahead
        tracker.take(yaw: 1.5, at: 100.02)              // half a radian to the left, at once
        #expect(turns.withLock { $0 } [0] == 0)
        #expect(abs(turns.withLock { $0 } [1] + 0.5) < 0.01)   // so the loudspeakers are half a radian to the right of the nose
        // Kept there, the loudspeakers come round: after half a minute they are in front again.
        var time = 100.02
        for _ in 0..<1500 {
            time += 0.02
            tracker.take(yaw: 1.5, at: time)
        }
        #expect(abs(turns.withLock { $0 }.last ?? 1) < 0.02)
        // Across the join of the circle the short way is taken.
        let wrapped = Mutex<[Float]>([])
        let other = HeadTracker { turn in wrapped.withLock { $0.append(turn) } }
        other.take(yaw: 3.1, at: 5)
        other.take(yaw: -3.1, at: 5.02)                 // 0.083 radians further left, through pi
        #expect(abs((wrapped.withLock { $0 }.last ?? 9) + 0.083) < 0.01)
    }

    @Test func valuesAreKeptInRange() {
        let store = makeStore()
        store.setGain(40, band: 2)
        store.setBoost(-5)
        store.setPreamp(-99)
        store.setBass(-40)
        store.setBalance(3)
        store.setWidth(-1)
        #expect(store.settings.gains[2] == 12)
        #expect(store.settings.boost == 0)
        #expect(store.settings.preamp == -12)
        #expect(store.settings.bass == -12)
        #expect(store.settings.balance == 1)
        #expect(store.settings.width == 0)
    }
}

/// Stands in for the audio engine: remembers what it was told and reports the levels it is given.
@MainActor
private final class RecordingSink: SoundProcessing {
    var last: SoundSettings?
    var levels = SoundLevels()

    func apply(_ settings: SoundSettings) { last = settings }
    func takeLevels() -> SoundLevels { levels }
    func spectrum(bands: Int) -> [Float] { Array(repeating: -30, count: bands) }
    var isHeadTrackingAvailable = false
}
