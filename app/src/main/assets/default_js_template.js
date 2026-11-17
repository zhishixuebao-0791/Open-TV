(function () {
    if (window.wvt_javascriptInjected) return;
    window.wvt_javascriptInjected = true;
    var token = String(window.wvt_session);
    var selector = '%selector%';
    var enterFullscreenButton = '%enter_fullscreen_button%';
    var playingCheckEnabled = %playing_check_enabled%;
    var video = null, previousTime = -1, lastProgress = Date.now(), lastState = '';
    function report(state) {
        if (state === 'waiting' && lastState === state) return;
        lastState = state;
        window.main.reportPlayback(token, state, video ? video.videoWidth || 0 : 0, video ? video.videoHeight || 0 : 0);
    }
    function progress() {
        if (!video || video.paused || video.seeking) return;
        var position = video.currentTime || 0;
        if (Math.abs(position - previousTime) > 0.03) {
            previousTime = position;
            lastProgress = Date.now();
            // Playback telemetry remains active for adapters that disable legacy play checks.
            report('playing');
        }
    }
    function setup(element) {
        video = element;
        previousTime = video.currentTime || 0;
        lastProgress = Date.now();
        lastState = '';
        video.autoplay = true;
        video.style.objectFit = 'fill';
        video.addEventListener('timeupdate', progress);
        video.addEventListener('waiting', function () { report('waiting'); });
        video.addEventListener('stalled', function () { report('waiting'); });
        video.addEventListener('pause', function () { report('waiting'); });
        video.addEventListener('error', function () { report('error'); });
        window.main.reportPlayback(token, 'fullscreen', 0, 0);
    }
    window.wvt_fullscreenVideo = function () {
        if (!video || document.fullscreenElement) return;
        var button = null;
        try { button = document.querySelector(enterFullscreenButton); } catch (_) {}
        if (button) button.click();
        else if (video.requestFullscreen) video.requestFullscreen().catch(function () {});
    };
    document.addEventListener('keydown', function (event) { if (event.key === 'f') window.wvt_fullscreenVideo(); });
    function loop() {
        if (video && !document.documentElement.contains(video)) { video = null; report('waiting'); }
        if (!video) {
            var element = null;
            try { element = document.querySelector(selector); } catch (_) {}
            if (element && element.tagName === 'VIDEO') setup(element);
            else if (element) {
                var child = element.querySelector('video');
                if (child) setup(child);
            }
        }
        if (video) {
            progress();
            if (Date.now() - lastProgress >= 2500) report('waiting');
        }
        setTimeout(loop, 1000);
    }
    loop();
})();
