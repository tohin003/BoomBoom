// BoomBoom's script inside the hidden YouTube Music tab. It runs at document start, in the page's own world, and:
//   1. removes the ad instructions from the player's answers, so no ad is ever fetched or played;
//   2. keeps the page silent (the app plays the audio itself);
//   3. narrows the formats to the best audio (never a low-bitrate fallback) and the smallest picture;
//   4. copies the audio the player appends to the browser's media pipeline to the app, for the song the app asked for;
//   5. takes commands from the app: load a song, move the fetch position, stop.
// It relies on web standards (Media Source Extensions, fetch) and on one long-lived player call (loadVideoById),
// not on the page's internals.
(function () {
  'use strict';
  if (window.__boom) return;
  const bridge = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.boom;
  if (!bridge || window.top !== window) return;

  const post = (message) => { try { bridge.postMessage(message); } catch (error) { /* the app has gone */ } };
  const num = (x) => (typeof x === 'number' && isFinite(x) ? x : null);
  const report = (where, error) => post({ t: 'error', where, message: String(error && error.message || error).slice(0, 300) });
  const base64 = (bytes) => {
    let text = '';
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) text += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    return btoa(text);
  };

  const boom = (window.__boom = {});
  // The song the app wants now: { token, videoId }. Audio is passed on only for this song.
  let job = null;
  // The video the player's latest answer was about.
  let answeredVideoId = null;
  // Every source buffer seen: { id, buffer, mime, isAudio, videoId, token, held: [] }.
  const records = [];
  const recordOf = new WeakMap();
  let nextId = 1;
  // Audio appended before the app claimed the song is held here, up to this many bytes.
  const HELD_LIMIT = 4 * 1024 * 1024;

  // ---- 1. Ads --------------------------------------------------------------------------------------------------
  const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  const nativeParse = JSON.parse;
  function pruneAds(value) {
    if (!value || typeof value !== 'object') return value;
    const strip = (object) => {
      if (!object || typeof object !== 'object') return;
      for (const key of AD_KEYS) if (key in object) delete object[key];
    };
    strip(value);
    strip(value.playerResponse);
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item && typeof item === 'object') { strip(item); strip(item.playerResponse); }
      }
    }
    return value;
  }
  JSON.parse = new Proxy(nativeParse, {
    apply(target, self, args) { return pruneAds(Reflect.apply(target, self, args)); },
  });
  Response.prototype.json = new Proxy(Response.prototype.json, {
    apply(target, self, args) { return Reflect.apply(target, self, args).then(pruneAds); },
  });
  let initialPlayerResponse;
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get() { return initialPlayerResponse; },
      set(value) { initialPlayerResponse = pruneAds(value); },
    });
  } catch (error) { report('initial response trap', error); }

  // ---- Formats: always the best audio, never more video than needed ----------------------------------------------
  // The player chooses among the formats its answer lists, and on its own it may drop to a low-bitrate audio format
  // (a slow moment on the network, a small picture). The app plays what is captured, so the choice is made here:
  // the answer is cut down to the single best stereo audio format (256 kbps with Premium, otherwise about 130-150
  // kbps Opus) and the smallest picture the browser can decode.
  const settings = { video: 'lowest' };
  const AUDIO_RANK = { AUDIO_QUALITY_ULTRALOW: 0, AUDIO_QUALITY_LOW: 1, AUDIO_QUALITY_MEDIUM: 2, AUDIO_QUALITY_HIGH: 3 };
  const typeSupport = (window.ManagedMediaSource || window.MediaSource || {}).isTypeSupported;
  const canDecode = (mime) => {
    try { return typeSupport ? !!typeSupport.call(window.ManagedMediaSource || window.MediaSource, mime) : true; } catch (error) { return true; }
  };
  function shapeFormats(answer) {
    const streaming = answer && answer.streamingData;
    if (!streaming || !Array.isArray(streaming.adaptiveFormats)) return null;
    try {
      lastOffer = {
        formats: streaming.adaptiveFormats.map((format) => [
          format.itag, String(format.mimeType || '').replace(/\s/g, ''), format.bitrate, format.audioQuality || format.qualityLabel || '',
          format.isDrc ? 'drc' : '', format.drmFamilies ? 'drm:' + format.drmFamilies.join('+') : '', format.xtags ? 'xtags' : '',
          format.audioTrack ? 'track:' + (format.audioTrack.audioIsDefault ? 'default' : 'other') : '',
        ].filter((part) => part !== '').join(' ')),
        sabr: !!streaming.serverAbrStreamingUrl,
        drm: !!(streaming.licenseInfos || streaming.drmParams),
        status: answer.playabilityStatus && answer.playabilityStatus.status,
      };
    } catch (error) { report('offer', error); }
    const isAudio = (format) => /^audio\//i.test(String(format.mimeType || ''));
    const audio = streaming.adaptiveFormats.filter(isAudio);
    const video = streaming.adaptiveFormats.filter((format) => !isAudio(format));
    if (!audio.length) return null;
    // The original mix: not a dubbed track, not surround, not the "stable volume" (compressed) variant.
    const narrow = (list, keep) => { const kept = list.filter(keep); return kept.length ? kept : list; };
    let choice = narrow(audio, (format) => canDecode(String(format.mimeType)));
    choice = narrow(choice, (format) => !format.audioTrack || format.audioTrack.audioIsDefault);
    choice = narrow(choice, (format) => !(format.audioChannels > 2));
    choice = narrow(choice, (format) => !format.isDrc);
    const rank = (format) => (format.audioQuality in AUDIO_RANK ? AUDIO_RANK[format.audioQuality] : 2);
    const top = Math.max.apply(null, choice.map(rank));
    choice = choice.filter((format) => rank(format) === top);
    // Within the top class Opus is preferred: it is the higher bitrate there and needs no resampling at 48 kHz.
    const opus = choice.filter((format) => /opus/i.test(String(format.mimeType)));
    const pool = opus.length ? opus : choice;
    const best = pool.reduce((a, b) => ((a.bitrate || 0) >= (b.bitrate || 0) ? a : b));

    let kept = video;
    if (settings.video === 'none') {
      kept = [];
    } else if (settings.video === 'lowest' && video.length) {
      const playable = narrow(video, (format) => canDecode(String(format.mimeType)));
      kept = [playable.reduce((a, b) => ((a.bitrate || 1e12) <= (b.bitrate || 1e12) ? a : b))];
    }
    streaming.adaptiveFormats = kept.concat([best]);
    return { itag: best.itag, mime: String(best.mimeType), bitrate: best.bitrate || 0, quality: best.audioQuality || null, video: kept.length ? kept[0].itag : null };
  }

  // ---- The player's answers -------------------------------------------------------------------------------------
  const isPlayerRequest = (url) => /\/youtubei\/v1\/(player|get_watch)\b/.test(url);
  function describePlayer(answer, chosen) {
    try {
      const details = answer.videoDetails || {};
      const playability = answer.playabilityStatus || {};
      const streaming = answer.streamingData || {};
      const audio = (streaming.adaptiveFormats || [])
        .filter((format) => String(format.mimeType || '').indexOf('audio/') === 0)
        .map((format) => ({ itag: format.itag, mime: String(format.mimeType), bitrate: format.bitrate, quality: format.audioQuality }));
      const config = answer.playerConfig && answer.playerConfig.audioConfig;
      if (details.videoId) answeredVideoId = details.videoId;
      post({
        t: 'player', token: job && job.videoId === details.videoId ? job.token : null,
        videoId: details.videoId || null, status: playability.status || null, reason: playability.reason || null,
        seconds: num(Number(details.lengthSeconds)), isLive: !!details.isLive, loudnessDb: config ? num(config.loudnessDb) : null,
        audio, chosen: chosen || null,
      });
    } catch (error) { report('player answer', error); }
  }
  // How the player's requests for media are faring, for `boom.report`.
  const isMediaRequest = (url) => /videoplayback|googlevideo\.com/.test(url);
  const media = { requests: 0, answers: 0, failures: 0, lastStatus: null, lastFailure: null, tier: null, parameters: null, host: null };
  let lastOffer = null;
  const playerErrors = [];
  const nativeFetch = window.fetch;
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    const pending = nativeFetch.apply(this, arguments);
    if (isMediaRequest(url)) {
      media.requests += 1;
      // Which parameters the request carries (names only) and its "content tier", for the diagnostics.
      try {
        const query = new URL(url, location.href).searchParams;
        media.tier = query.get('ctier');
        media.parameters = Array.from(new Set(Array.from(query.keys()))).sort().join(',').slice(0, 400);
        media.host = new URL(url, location.href).hostname.replace(/^[^.]*/, '*');
      } catch (error) { /* not a URL to take apart */ }
      pending.then(
        (response) => { media.answers += 1; media.lastStatus = response.status; },
        (error) => { media.failures += 1; media.lastFailure = String(error).slice(0, 120); });
    }
    if (!isPlayerRequest(url)) return pending;
    return pending.then(async (response) => {
      try {
        const answer = nativeParse(await response.clone().text());
        pruneAds(answer);
        describePlayer(answer, shapeFormats(answer));
        const clean = new Response(JSON.stringify(answer), {
          status: response.status, statusText: response.statusText, headers: response.headers,
        });
        Object.defineProperties(clean, {
          url: { value: response.url }, redirected: { value: response.redirected }, type: { value: response.type },
        });
        return clean;
      } catch (error) {
        report('player rewrite', error);
        return response;
      }
    });
  };
  const nativeOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (isPlayerRequest(String(url))) {
      this.addEventListener('load', () => {
        try { describePlayer(nativeParse(this.responseText)); } catch (error) { /* not JSON */ }
      });
    }
    return nativeOpen.apply(this, arguments);
  };

  // ---- 2. Silence -----------------------------------------------------------------------------------------------
  // The page's player must never be able to sound, not for an instant. On an iPhone a playing media element that
  // is not muted makes the browser engine claim the phone's audio, and the app's own playback is then interrupted
  // exactly as by a phone call (muting it again a moment later, on "volumechange", is too late). So an element's
  // real `muted` can only ever be true. The page is shown the value it last set, so its player sees an ordinary
  // browser and does not keep trying.
  const mutedProperty = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
  const believedMuted = new WeakMap();
  function silence(element) {
    try { if (mutedProperty.get.call(element) !== true) mutedProperty.set.call(element, true); } catch (error) { /* not a media element */ }
  }
  Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
    configurable: true,
    enumerable: mutedProperty.enumerable,
    get() { return believedMuted.has(this) ? believedMuted.get(this) : this.defaultMuted; },
    set(value) {
      believedMuted.set(this, !!value);
      silence(this);
    },
  });
  // Elements are silenced as they are made, before they can load anything.
  const nativeCreateElement = Document.prototype.createElement;
  Document.prototype.createElement = function () {
    const element = nativeCreateElement.apply(this, arguments);
    if (element instanceof HTMLMediaElement) silence(element);
    return element;
  };
  const nativePlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    silence(this);
    // Nothing asked for: the page must not play on its own (a song it opened onto, or the next in its own queue).
    // It is refused the way a browser refuses autoplay, which the player takes in its stride.
    if (!job) return Promise.reject(new DOMException('BoomBoom plays the audio itself', 'NotAllowedError'));
    return nativePlay.apply(this, arguments);
  };
  // A last net for an element that came about some other way (written into the page as markup, say).
  for (const type of ['loadstart', 'play', 'volumechange']) {
    document.addEventListener(type, (event) => {
      if (event.target instanceof HTMLMediaElement) silence(event.target);
    }, true);
  }

  // ---- 3. The media pipeline ------------------------------------------------------------------------------------
  function passOn(record, bytes) {
    post({ t: 'append', token: record.token, id: record.id, d: base64(bytes) });
  }
  function claim(record) {
    // The song the app asked for: announce the buffer, then pass on what was held back.
    record.token = job.token;
    post({ t: 'buffer', token: record.token, id: record.id, mime: record.mime, videoId: record.videoId });
    for (const bytes of record.held) passOn(record, bytes);
    record.held = [];
    record.heldBytes = 0;
  }
  function wanted(record) {
    return record.isAudio && job && record.videoId === job.videoId;
  }
  function hookMediaSource(Constructor) {
    if (!Constructor || Constructor.prototype.__boomHooked) return;
    Constructor.prototype.__boomHooked = true;
    const nativeAdd = Constructor.prototype.addSourceBuffer;
    Constructor.prototype.addSourceBuffer = function (mime) {
      const buffer = nativeAdd.apply(this, arguments);
      try {
        const record = {
          id: nextId++, buffer, mime: String(mime), isAudio: /^audio\//i.test(String(mime)), source: this,
          videoId: answeredVideoId, token: null, held: [], heldBytes: 0,
        };
        records.push(record);
        if (records.length > 24) records.shift();
        recordOf.set(buffer, record);
        if (wanted(record)) claim(record);
      } catch (error) { report('addSourceBuffer', error); }
      return buffer;
    };
    const nativeEnd = Constructor.prototype.endOfStream;
    Constructor.prototype.endOfStream = function () {
      try {
        for (const record of records) {
          if (record.source === this && record.token) post({ t: 'end', token: record.token, id: record.id, seconds: num(this.duration) });
        }
      } catch (error) { report('endOfStream', error); }
      return nativeEnd.apply(this, arguments);
    };
  }
  hookMediaSource(window.MediaSource);
  hookMediaSource(window.ManagedMediaSource);

  // On iPhone the page gets a "managed" media source: the browser tells the page when to stop fetching
  // ("endstreaming", once enough is buffered) and when to carry on ("startstreaming"), and it can hold back the
  // go-ahead from a player that is not playing, to save battery. The page's player here is never playing (the app
  // plays the audio), so it would be left with the first minute of each song. The page is therefore never told to
  // stop: it always sees "streaming", and "endstreaming" never reaches it.
  const Managed = window.ManagedMediaSource;
  if (Managed) {
    try {
      Object.defineProperty(Managed.prototype, 'streaming', { configurable: true, get() { return true; } });
      Object.defineProperty(Managed.prototype, 'onendstreaming', { configurable: true, get() { return null; }, set() {} });
      const nativeListen = EventTarget.prototype.addEventListener;
      Managed.prototype.addEventListener = function (type) {
        if (type === 'endstreaming') return undefined;
        return nativeListen.apply(this, arguments);
      };
    } catch (error) { report('managed media source', error); }
  }

  // On iPhone only ManagedSourceBuffer is a global; appendBuffer lives on a prototype further up its chain.
  function owner(start, method) {
    let prototype = start;
    while (prototype && !Object.prototype.hasOwnProperty.call(prototype, method)) prototype = Object.getPrototypeOf(prototype);
    return prototype;
  }
  const BufferConstructor = window.ManagedSourceBuffer || window.SourceBuffer;
  const bufferPrototype = BufferConstructor && owner(BufferConstructor.prototype, 'appendBuffer');
  if (bufferPrototype) {
    const nativeAppend = bufferPrototype.appendBuffer;
    bufferPrototype.appendBuffer = function (data) {
      try {
        const record = recordOf.get(this);
        if (record) {
          record.appends = (record.appends || 0) + 1;
          record.bytes = (record.bytes || 0) + (data ? data.byteLength || 0 : 0);
        }
        if (record && record.isAudio) {
          const bytes = data instanceof ArrayBuffer
            ? new Uint8Array(data)
            : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
          if (record.token && job && record.token === job.token) {
            passOn(record, bytes);
          } else if (!record.token && record.heldBytes + bytes.length <= HELD_LIMIT) {
            // The app may claim this song a moment from now (a page that opened straight onto it).
            record.held.push(bytes.slice());
            record.heldBytes += bytes.length;
          }
        }
      } catch (error) { report('appendBuffer', error); }
      return nativeAppend.apply(this, arguments);
    };
    const nativeAbort = bufferPrototype.abort;
    bufferPrototype.abort = function () {
      try {
        const record = recordOf.get(this);
        if (record && record.isAudio) {
          if (record.token) post({ t: 'restart', token: record.token, id: record.id });
          // Held pieces before a restart are a cut-off segment, except a complete stream header.
          else if (record.held.length > 1) { record.held = record.held.slice(0, 1); record.heldBytes = record.held[0].length; }
        }
      } catch (error) { report('abort', error); }
      return nativeAbort.apply(this, arguments);
    };
  } else {
    post({ t: 'unsupported', reason: 'no media source buffers in this browser' });
  }

  // ---- 4. Commands from the app ----------------------------------------------------------------------------------
  const player = () => {
    const element = document.getElementById('movie_player');
    return element && typeof element.loadVideoById === 'function' ? element : null;
  };
  const video = () => document.querySelector('video');

  /// Starts fetching a song. Returns 'ok', or 'no-player' when the page has no player yet (the app then opens the
  /// song's page instead and calls `adopt`).
  boom.load = function (token, videoId) {
    job = { token, videoId };
    // Audio held back for a song nobody claimed is of no use now.
    for (const record of records) {
      if (!record.token) { record.held = []; record.heldBytes = 0; }
    }
    const element = player();
    if (!element) return 'no-player';
    element.loadVideoById(videoId);
    return 'ok';
  };

  /// Claims a song the page is already loading (it was opened straight onto that song).
  boom.adopt = function (token, videoId) {
    job = { token, videoId };
    for (const record of records) {
      if (!record.token && wanted(record)) claim(record);
    }
    // If the page tried to start before the song was claimed, it was refused: start it now.
    const api = player();
    try { api && api.playVideo && api.playVideo(); } catch (error) { /* it starts on its own */ }
    return answeredVideoId;
  };

  /// Keeps the player paused with its playhead at `seconds`, so that it fetches the audio from there on.
  /// Returns where the page's own buffer of that audio ends, for the app's log.
  boom.pump = function (token, seconds) {
    if (!job || job.token !== token) return { stale: true };
    const element = video();
    if (!element) return { missing: true };
    if (!element.paused) element.pause();
    if (isFinite(element.duration) && element.duration > 0) {
      const target = Math.max(0, Math.min(seconds, element.duration - 0.25));
      if (Math.abs(element.currentTime - target) > 0.2) element.currentTime = target;
    }
    // How the page's player is doing, for the app's trace when audio stops arriving.
    const api = player();
    let audioEnd = null;
    for (const record of records) {
      if (record.token !== token) continue;
      try {
        const ranges = record.buffer.buffered;
        for (let i = 0; i < ranges.length; i++) {
          if (ranges.start(i) <= element.currentTime + 0.5 && ranges.end(i) >= element.currentTime) audioEnd = ranges.end(i);
        }
      } catch (error) { /* the buffer was removed */ }
    }
    return {
      at: num(element.currentTime), duration: num(element.duration), ready: element.readyState,
      network: element.networkState, paused: element.paused, audioEnd: num(audioEnd),
      mediaError: element.error ? element.error.code : null,
      playerState: api && api.getPlayerState ? num(api.getPlayerState()) : null,
    };
  };

  /// Stops fetching for a song.
  boom.cancel = function (token) {
    if (!job || job.token !== token) return;
    job = null;
    const element = video();
    if (element && !element.paused) element.pause();
    const api = player();
    try { api && api.stopVideo && api.stopVideo(); } catch (error) { /* nothing to stop */ }
  };

  /// 'lowest' (the smallest picture), 'none' (audio only) or 'all' (the player's own choice).
  boom.setVideo = function (mode) { settings.video = mode; return settings.video; };

  /// What the app needs to talk to YouTube Music's data API as this page does.
  boom.session = function () {
    const config = window.ytcfg && typeof window.ytcfg.get === 'function' ? window.ytcfg : null;
    const get = (key) => { try { return config ? config.get(key) : null; } catch (error) { return null; } };
    const context = get('INNERTUBE_CONTEXT') || {};
    const client = context.client || {};
    return {
      clientVersion: get('INNERTUBE_CLIENT_VERSION') || client.clientVersion || null,
      visitorData: get('VISITOR_DATA') || client.visitorData || null,
      language: get('HL') || client.hl || null,
      region: get('GL') || client.gl || null,
      isSignedIn: !!get('LOGGED_IN'),
      authUser: get('SESSION_INDEX') != null ? String(get('SESSION_INDEX')) : null,
      onBehalfOfUser: get('DELEGATED_SESSION_ID') || null,
      hasPlayer: !!player(),
    };
  };

  /// Keeps this page's process awake while the app waits on the answer (used while the app is in the background).
  let release = null;
  boom.hold = function (milliseconds) {
    return new Promise((resolve) => {
      const timer = setTimeout(() => { release = null; resolve('time'); }, milliseconds);
      release = () => { clearTimeout(timer); release = null; resolve('released'); };
    });
  };
  boom.release = function () { if (release) release(); };

  // Tell the app when the player is there to take commands, and when it reports an error.
  let announced = null;
  window.__boomPlayerError = function (code) {
    playerErrors.push(String(code));
    if (playerErrors.length > 8) playerErrors.shift();
    post({ t: 'error', where: 'player', message: 'the page player reported error ' + String(code) });
  };

  /// What the page's player is doing, for the app's diagnostics: no personal data, only states and formats.
  boom.report = function () {
    const out = { job: job ? job.videoId : null, answered: answeredVideoId, media, offer: lastOffer, playerErrors };
    try {
      const element = video();
      out.video = element ? {
        ready: element.readyState, network: element.networkState, paused: element.paused, at: num(element.currentTime),
        duration: num(element.duration), muted: mutedProperty.get.call(element),
        error: element.error ? element.error.code + ' ' + String(element.error.message || '') : null,
        src: String(element.currentSrc || element.src || '').slice(0, 12), keys: !!element.mediaKeys,
      } : null;
    } catch (error) { out.video = 'threw ' + error; }
    try {
      out.buffers = records.slice(-6).map((record) => {
        let ranges = [];
        try {
          const buffered = record.buffer.buffered;
          for (let i = 0; i < buffered.length; i++) ranges.push([num(buffered.start(i)), num(buffered.end(i))]);
        } catch (error) { ranges = 'gone'; }
        let updating = null;
        try { updating = record.buffer.updating; } catch (error) { /* removed */ }
        return {
          id: record.id, mime: record.mime, videoId: record.videoId, claimed: !!record.token,
          appends: record.appends || 0, bytes: record.bytes || 0, updating, source: record.source ? record.source.readyState : null, ranges,
        };
      });
    } catch (error) { out.buffers = 'threw ' + error; }
    try {
      const api = player();
      if (api) {
        const data = api.getVideoData ? api.getVideoData() : {};
        out.player = {
          state: api.getPlayerState ? api.getPlayerState() : null, videoId: data && data.video_id,
          quality: api.getPlaybackQuality ? api.getPlaybackQuality() : null,
          loaded: api.getVideoLoadedFraction ? num(api.getVideoLoadedFraction()) : null,
        };
        const answer = api.getPlayerResponse ? api.getPlayerResponse() : null;
        if (answer) {
          out.kept = ((answer.streamingData || {}).adaptiveFormats || []).map((format) => format.itag);
          out.playability = (answer.playabilityStatus || {}).status + ' ' + String((answer.playabilityStatus || {}).reason || '');
        }
        try {
          const nerds = api.getStatsForNerds ? api.getStatsForNerds() : null;
          if (nerds) out.nerds = { codecs: nerds.codecs, fmt: nerds.fmt, afmt: nerds.afmt, bandwidth: nerds.bandwidth_kbps, debug: String(nerds.debug_info || '').slice(0, 200), protected: nerds.drm_style || null };
        } catch (error) { out.nerds = 'threw ' + error; }
      } else {
        out.player = null;
      }
    } catch (error) { out.player = 'threw ' + error; }
    return out;
  };
  const watch = setInterval(() => {
    const element = player();
    if (!element || announced === element) return;
    announced = element;
    try { element.addEventListener('onError', '__boomPlayerError'); } catch (error) { /* older players */ }
    post({ t: 'ready', href: location.href });
  }, 150);
  window.addEventListener('pagehide', () => clearInterval(watch));

  post({ t: 'hello', href: location.href, mediaSource: typeof window.ManagedMediaSource !== 'undefined' ? 'managed' : typeof window.MediaSource });
})();
