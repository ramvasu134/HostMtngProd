// ===== Host Dashboard Meeting — WebRTC + WebSocket =====
// Called from teacher-dashboard.html when a meeting is started/ended.
//
// Public API:
//   initDashboardMeeting(meetingCode)  — call when meeting starts
//   cleanupDashboardMeeting()          — call when meeting ends
//   dashToggleMic()                    — mic button handler
//   dashToggleSpeaker()                — speaker button handler
//   sendDashChatMessage()              — chat send button handler

// ── Configuration ─────────────────────────────────────────────────────────────
// Starts as STUN-only; upgraded to include a free TURN relay (if the server has
// one configured) by _dm_loadIceServers() before any peer connection is created.
// TURN is required for reliable audio when a student is behind a symmetric/
// restrictive NAT (common on school Wi-Fi / mobile data) — STUN alone silently
// fails in that case with no error, which is why some students had no audio.
let _DM_ICE = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun3.l.google.com:19302' },
        { urls: 'stun:stun4.l.google.com:19302' }
    ]
};

function _dm_loadIceServers() {
    return fetch('/api/ice-servers')
        .then(r => r.ok ? r.json() : null)
        .then(servers => {
            if (Array.isArray(servers) && servers.length > 0) {
                _DM_ICE = { iceServers: servers };
                console.log('[DashMeeting] ICE servers loaded (' + servers.length + ' entries, TURN included if configured)');
            }
        })
        .catch(e => console.warn('[DashMeeting] Could not load ICE servers, using STUN-only fallback:', e));
}

// Read teacher identity injected by Thymeleaf
const _dmEl = document.getElementById('dashMeetingData');
const DASH_USER_ID   = _dmEl ? _dmEl.dataset.userId   : '';
const DASH_USER_NAME = _dmEl ? _dmEl.dataset.userName : '';

// ── Runtime state ─────────────────────────────────────────────────────────────
let _dm_code        = '';
let _dm_stomp       = null;
let _dm_local       = null;      // MediaStream (mic)
let _dm_peers       = {};        // peerId → RTCPeerConnection
let _dm_pending     = {};        // peerId → queued ICE candidates
let _dm_micOn       = true;
let _dm_spkMuted    = false;
let _dm_wsTimer     = null;
let _dm_wsConnecting = false;
let _dm_transcriptSeq = 0;
let _dm_liveTranscriptByCard = {};
let _dm_voiceAnalyzers = {};
let _dm_lastSpeechAt = {};
// Dais (speaking spotlight) state — keyed per speaker so that two students
// speaking at the same time each get their own card + independent silence
// timer (edge case: concurrent speakers). Key is the participant id when
// known, otherwise a name-derived fallback key for unmatched speaker events.
let _dm_daisSpeakers = {};   // key → { timer, pid }

// ── Conversation Bundler state ────────────────────────────────────────────────
const _dm_convMap = {};           // studentKey → conv object
let _dm_pendingTeacher = [];      // teacher lines not yet attached to any student card
const _DM_CONV_TIMEOUT    = 300000; // 5 min inactivity → close (student may be listening quietly)
const _DM_TEACHER_MAX_AGE = 120000; // 2 min — keep teacher lines available for next student turn

// Teacher's own live-speech singleton card
let _dm_teacherCardEl = null;

// Transcripts captured before the WebSocket handshake completes
let _dm_pendingTranscripts = [];

// ── Teacher Speech Recognition state ─────────────────────────────────────────
let _dm_speechRec    = null;
let _dm_srActive     = false;
let _dm_sttRunning   = false;
let _dm_sttWatchdog  = null;

// Noise-word filter — same logic as student side
const _DM_NOISE_WORDS = new Set([
    'okay','ok','yes','no','yeah','yep','nope','hmm','um','uh','ah',
    'oh','right','sure','fine','good','great','thanks','thank you',
    'alright','alright then','got it','i see','understood'
]);
function _dm_isNoisyText(text) {
    if (!text) return true;
    const t = text.trim().toLowerCase().replace(/[.!?,;]+$/, '');
    if (t.length < 2) return true;
    if (_DM_NOISE_WORDS.has(t)) return true;
    const words = t.split(/\s+/);
    return words.length <= 2 && words.every(w => _DM_NOISE_WORDS.has(w));
}

// Start teacher's continuous Speech Recognition
function _dm_startSpeechRec() {
    const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRec) return;                        // not supported in this browser

    if (!_dm_speechRec) {
        _dm_speechRec = new SpeechRec();
        _dm_speechRec.continuous      = true;
        _dm_speechRec.interimResults  = true;   // get interim so Chrome commits results faster
        _dm_speechRec.lang            = 'en-US';
        _dm_speechRec.maxAlternatives = 1;

        _dm_speechRec.onresult = function(event) {
            if (!_dm_srActive || !_dm_code) return;
            for (let i = event.resultIndex; i < event.results.length; i++) {
                if (event.results[i].isFinal) {
                    const text = event.results[i][0].transcript.trim();
                    if (text) {
                        const payload = JSON.stringify({
                            text:        text,
                            speakerName: DASH_USER_NAME,
                            isTeacher:   true,
                            timestamp:   new Date().toISOString()
                        });
                        if (_dm_stomp && _dm_stomp.connected) {
                            _dm_stomp.send('/app/transcript/' + _dm_code, {}, payload);
                        } else {
                            // WebSocket still connecting — buffer for flush on connect
                            _dm_pendingTranscripts.push(payload);
                        }
                        console.log('[Teacher SpeechRec]', text);
                    }
                }
            }
        };

        _dm_speechRec.onstart = function() {
            _dm_sttRunning = true;
            console.log('[Teacher STT] started');
        };
        _dm_speechRec.onerror = function(e) {
            _dm_sttRunning = false;
            console.warn('[Teacher SpeechRec] error:', e.error);
        };
        _dm_speechRec.onend = function() {
            _dm_sttRunning = false;
            if (_dm_srActive && _dm_micOn && _dm_code) {
                try { _dm_speechRec.start(); } catch(e) {}
            }
        };
    }

    _dm_srActive = true;
    if (!_dm_sttRunning) {
        try { _dm_speechRec.start(); } catch(e) { console.warn('[Teacher STT] start error:', e.message); }
    }
    // Watchdog: restart teacher STT every 5 s if it silently dies
    clearInterval(_dm_sttWatchdog);
    _dm_sttWatchdog = setInterval(function() {
        if (!_dm_srActive || !_dm_micOn || !_dm_code) { clearInterval(_dm_sttWatchdog); return; }
        if (!_dm_sttRunning && _dm_speechRec) {
            console.log('[Teacher STT Watchdog] restarting');
            try { _dm_speechRec.start(); } catch(e) {}
        }
    }, 5000);
}

// Stop teacher's Speech Recognition
function _dm_stopSpeechRec() {
    _dm_srActive   = false;
    _dm_sttRunning = false;
    clearInterval(_dm_sttWatchdog);
    _dm_sttWatchdog = null;
    if (_dm_speechRec) {
        try { _dm_speechRec.stop(); } catch(e) {}
        _dm_speechRec = null;
    }
}

// ── Public: start meeting ────────────────────────────────────────────────────

function initDashboardMeeting(meetingCode) {
    _dm_code = meetingCode;
    _showDashChat();    // switch chat tab to real UI

    // Load ICE servers (STUN + optional free TURN) in parallel with the mic
    // permission prompt so it's ready before any peer connection is created.
    _dm_loadIceServers();

    // Get mic first, then connect WebSocket
    navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
        video: false
    })
    .then(function (stream) {
        _dm_local = stream;
        _dm_micOn = true;
        _dm_updateMicBtn(true);
        _dm_setupViz(stream);
        _dm_connectWS();
        _dm_startSpeechRec(); // begin capturing teacher's speech in real-time
    })
    .catch(function (err) {
        console.warn('[DashMeeting] Mic denied:', err);
        _dm_micOn = false;
        _dm_updateMicBtn(false);
        _dm_connectWS();   // still connect for chat / control
    });
}

// ── Public: end meeting ──────────────────────────────────────────────────────

function cleanupDashboardMeeting() {
    clearTimeout(_dm_wsTimer);
    if (_dm_stomp && _dm_stomp.connected) {
        try {
            _dm_stomp.send('/app/participant/' + _dm_code, {},
                JSON.stringify({ event: 'leave', micEnabled: false, cameraEnabled: false }));
            _dm_stomp.send('/app/control/' + _dm_code, {},
                JSON.stringify({ event: 'end-meeting' }));
            _dm_stomp.disconnect();
        } catch (e) {}
    }
    _dm_stomp       = null;
    _dm_wsConnecting = false;

    Object.values(_dm_peers).forEach(pc => pc.close());
    _dm_peers   = {};
    _dm_pending = {};
    Object.keys(_dm_voiceAnalyzers).forEach(_dm_stopVoiceAnalyzer);
    _dm_voiceAnalyzers = {};
    _dm_lastSpeechAt = {};
    _dm_studentMicMuted = {};
    _dm_studentSndMuted = {};

    // Remove any leftover student audio elements + the audio-unlock prompt
    document.querySelectorAll('audio[id^="dash-sr-"]').forEach(el => el.remove());
    const unlockBar = document.getElementById('dmAudioUnlockBar');
    if (unlockBar) unlockBar.remove();
    _dm_audioUnlockShown = false;

    _dm_stopSpeechRec(); // stop teacher's speech recognition
    _dm_pendingTranscripts = [];

    if (_dm_local) { _dm_local.getTracks().forEach(t => t.stop()); _dm_local = null; }
    _dm_code = '';

    _hideDashChat();
    _dm_clearParticipants();
    _dm_updateMicBtn(false);
}

// ── Public: mic toggle ───────────────────────────────────────────────────────

function dashToggleMic() {
    if (!_dm_local) return;
    _dm_micOn = !_dm_micOn;
    _dm_local.getAudioTracks().forEach(t => { t.enabled = _dm_micOn; });
    _dm_updateMicBtn(_dm_micOn);
    _dm_sendParticipant('mic-toggle');
    // Pause recognition when muted so mute-noise isn't transcribed
    if (_dm_micOn) {
        _dm_srActive = true;
        try { if (_dm_speechRec) _dm_speechRec.start(); } catch(e) {}
    } else {
        _dm_srActive = false;
        try { if (_dm_speechRec) _dm_speechRec.stop(); } catch(e) {}
    }
}

// ── Public: speaker toggle ───────────────────────────────────────────────────

function dashToggleSpeaker() {
    _dm_spkMuted = !_dm_spkMuted;
    document.querySelectorAll('audio[id^="dash-sr-"]').forEach(el => {
        const pid = el.id.replace('dash-sr-', '');
        el.muted = _dm_spkMuted || !!_dm_studentSndMuted[pid];
    });
    const btn  = document.getElementById('speakerBtn');
    const icon = btn ? btn.querySelector('i') : null;
    if (icon) icon.className = _dm_spkMuted ? 'fas fa-volume-mute' : 'fas fa-volume-up';
}

// ── Public: send chat ────────────────────────────────────────────────────────

function sendDashChatMessage() {
    const input = document.getElementById('dashChatInput');
    if (!input) return;
    const text = input.value.trim();
    if (!text || !_dm_stomp || !_dm_stomp.connected) return;
    _dm_stomp.send('/app/chat/' + _dm_code, {}, JSON.stringify({ content: text }));
    input.value = '';
}

// ── WebSocket ─────────────────────────────────────────────────────────────────

function _dm_connectWS() {
    if (_dm_wsConnecting) return;
    if (_dm_stomp && _dm_stomp.connected) return;
    _dm_wsConnecting = true;
    if (_dm_stomp) { try { _dm_stomp.disconnect(); } catch (e) {} _dm_stomp = null; }

    const socket = new SockJS('/ws');
    _dm_stomp    = Stomp.over(socket);
    _dm_stomp.debug = null;

    _dm_stomp.connect({}, function () {
        _dm_wsConnecting = false;
        console.log('[DashMeeting] WebSocket connected. Meeting:', _dm_code);

        _dm_stomp.subscribe('/topic/signal/'      + _dm_code, m => _dm_onSignal(JSON.parse(m.body)));
        _dm_stomp.subscribe('/topic/participant/' + _dm_code, m => _dm_onParticipant(JSON.parse(m.body)));
        _dm_stomp.subscribe('/topic/chat/'        + _dm_code, m => _dm_onChat(JSON.parse(m.body)));
        _dm_stomp.subscribe('/topic/control/'     + _dm_code, m => { /* future */ });
        _dm_stomp.subscribe('/topic/recording/'   + _dm_code, m => _dm_onRecording(JSON.parse(m.body)));
        _dm_stomp.subscribe('/topic/transcript/'  + _dm_code, m => _dm_onTranscript(JSON.parse(m.body)));

        // Flush any teacher transcripts captured before WS was ready
        while (_dm_pendingTranscripts.length > 0) {
            _dm_stomp.send('/app/transcript/' + _dm_code, {}, _dm_pendingTranscripts.shift());
        }

        // Announce host presence so any waiting students will create an offer
        _dm_sendParticipant('join');

        // Seed cards for participants already in the meeting (race-condition fix)
        _dm_seedExistingParticipants();

    }, function (err) {
        _dm_wsConnecting = false;
        console.error('[DashMeeting] WS error, retrying…', err);
        if (_dm_stomp) { try { _dm_stomp.disconnect(); } catch (e) {} _dm_stomp = null; }
        if (_dm_code) {
            clearTimeout(_dm_wsTimer);
            _dm_wsTimer = setTimeout(_dm_connectWS, 5000);
        }
    });
}

function _dm_sendParticipant(type) {
    if (_dm_stomp && _dm_stomp.connected) {
        _dm_stomp.send('/app/participant/' + _dm_code, {},
            JSON.stringify({ event: type, micEnabled: _dm_micOn, cameraEnabled: false }));
    }
}

function _dm_sendSignal(payload) {
    if (_dm_stomp && _dm_stomp.connected) {
        _dm_stomp.send('/app/signal/' + _dm_code, {}, JSON.stringify(payload));
    }
}

// ── WebRTC ────────────────────────────────────────────────────────────────────

function _dm_getPC(peerId) {
    if (_dm_peers[peerId]) return _dm_peers[peerId];
    const pc = new RTCPeerConnection(_DM_ICE);
    _dm_peers[peerId] = pc;

    // Add teacher's mic track so this student can hear the teacher
    if (_dm_local) {
        _dm_local.getTracks().forEach(t => pc.addTrack(t, _dm_local));
    }

    pc.onicecandidate = e => {
        if (e.candidate) {
            _dm_sendSignal({ type: 'ice-candidate', candidate: e.candidate, targetId: peerId });
        }
    };

    // Receive student audio and play it
    pc.ontrack = e => _dm_playAudio(peerId, e.streams[0]);

    pc.onconnectionstatechange = () => {
        console.log('[DashMeeting] Peer', peerId, 'connection state:', pc.connectionState);
        if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
            const audio = document.getElementById('dash-sr-' + peerId);
            if (audio) audio.remove();
            delete _dm_peers[peerId];
        }
    };
    pc.oniceconnectionstatechange = () => {
        console.log('[DashMeeting] Peer', peerId, 'ICE state:', pc.iceConnectionState);
    };
    return pc;
}

function _dm_playAudio(peerId, stream) {
    let audio = document.getElementById('dash-sr-' + peerId);
    if (!audio) {
        audio = document.createElement('audio');
        audio.id         = 'dash-sr-' + peerId;
        audio.autoplay   = true;
        audio.playsInline = true;
        audio.muted      = _dm_spkMuted || !!_dm_studentSndMuted[String(peerId)];
        document.body.appendChild(audio);
    }
    audio.srcObject = stream;
    _dm_startVoiceAnalyzer(peerId, stream);

    // The `autoplay` attribute alone is not enough — some browsers silently
    // block unmuted autoplay for audio elements created outside a direct
    // click handler, with NO error and NO visual cue. Explicitly call play()
    // and surface a one-click fix if it's blocked, otherwise the teacher can
    // end up never hearing any student with no indication why.
    const playPromise = audio.play();
    if (playPromise !== undefined) {
        playPromise.catch(() => _dm_showAudioUnlockPrompt());
    }
}

let _dm_audioUnlockShown = false;

function _dm_showAudioUnlockPrompt() {
    if (_dm_audioUnlockShown || document.getElementById('dmAudioUnlockBar')) return;
    _dm_audioUnlockShown = true;
    const bar = document.createElement('div');
    bar.id = 'dmAudioUnlockBar';
    bar.style.cssText =
        'position:fixed;top:70px;left:50%;transform:translateX(-50%);' +
        'background:#f59e0b;color:#111827;padding:10px 18px;border-radius:10px;' +
        'z-index:9999;font-size:13px;font-weight:600;box-shadow:0 4px 16px rgba(0,0,0,0.35);' +
        'display:flex;align-items:center;gap:10px;cursor:pointer;';
    bar.innerHTML = '<i class="fas fa-volume-mute"></i> Student audio is blocked by your browser — click to enable';
    bar.onclick = _dm_unlockAllStudentAudio;
    document.body.appendChild(bar);
}

function _dm_unlockAllStudentAudio() {
    document.querySelectorAll('audio[id^="dash-sr-"]').forEach(el => {
        el.play().catch(() => {});
    });
    const bar = document.getElementById('dmAudioUnlockBar');
    if (bar) bar.remove();
    _dm_audioUnlockShown = false;
}

// Browsers unblock audio after ANY user gesture on the page, so retry
// silently on the very next click too (covers teachers who click a button
// elsewhere instead of the banner itself).
document.addEventListener('click', function () {
    if (_dm_audioUnlockShown) _dm_unlockAllStudentAudio();
});

function _dm_onSignal(data) {
    const sid = String(data.senderId);
    if (sid === String(DASH_USER_ID)) return;                               // ignore own
    if (data.targetId && String(data.targetId) !== String(DASH_USER_ID)) return; // not for me
    if      (data.type === 'offer')         _dm_doOffer(sid, data);
    else if (data.type === 'answer')        _dm_doAnswer(sid, data);
    else if (data.type === 'ice-candidate') _dm_doIce(sid, data);
}

async function _dm_doOffer(sid, data) {
    const pc = _dm_getPC(sid);
    try {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: data.sdp }));
        await _dm_drainICE(sid);
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        _dm_sendSignal({ type: 'answer', sdp: answer.sdp, targetId: sid });
    } catch (e) { console.error('[DashMeeting] offer error:', e); }
}

async function _dm_doAnswer(sid, data) {
    const pc = _dm_peers[sid];
    if (!pc) return;
    try {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: data.sdp }));
        await _dm_drainICE(sid);
    } catch (e) { console.error('[DashMeeting] answer error:', e); }
}

async function _dm_doIce(sid, data) {
    const pc = _dm_peers[sid];
    if (!pc || !data.candidate) return;
    if (pc.remoteDescription && pc.remoteDescription.type) {
        try { await pc.addIceCandidate(new RTCIceCandidate(data.candidate)); } catch (e) {}
    } else {
        if (!_dm_pending[sid]) _dm_pending[sid] = [];
        _dm_pending[sid].push(data.candidate);
    }
}

async function _dm_drainICE(peerId) {
    const queue = _dm_pending[peerId];
    if (!queue || !queue.length) return;
    delete _dm_pending[peerId];
    const pc = _dm_peers[peerId];
    if (!pc) return;
    for (const c of queue) {
        try { await pc.addIceCandidate(new RTCIceCandidate(c)); } catch (e) {}
    }
}

// ── Seed existing participants on WS connect (race-condition fix) ─────────────

function _dm_seedExistingParticipants() {
    if (!_dm_code) return;
    fetch('/api/meeting/' + _dm_code + '/participants')
        .then(function(r) { return r.json(); })
        .then(function(list) {
            list.forEach(function(p) {
                // Only show students (not the host themselves)
                if (String(p.id) === String(DASH_USER_ID)) return;
                if (p.role === 'HOST') return;
                _dm_addCard(p.id, p.displayName || 'Student');
                _dm_updateCount();
                // Ask the student to send us an offer so WebRTC audio connects
                _dm_sendSignal({ type: 'request-offer', targetId: String(p.id) });
            });
        })
        .catch(function(e) { console.warn('[DashMeeting] Could not seed participants:', e); });
}

// ── Participant events ────────────────────────────────────────────────────────

function _dm_onParticipant(data) {
    if (String(data.userId) === String(DASH_USER_ID)) return;

    if (data.event === 'join') {
        _dm_addCard(data.userId, data.userName);
        _dm_updateCount();
        // Tell this student to create an offer directed at the host
        _dm_sendSignal({ type: 'request-offer', targetId: String(data.userId) });

    } else if (data.event === 'leave') {
        _dm_stopVoiceAnalyzer(String(data.userId));
        delete _dm_lastSpeechAt[String(data.userId)];
        delete _dm_studentMicMuted[String(data.userId)];
        delete _dm_studentSndMuted[String(data.userId)];
        _dm_removeCard(data.userId);
        _dm_updateCount();
        const pc = _dm_peers[String(data.userId)];
        if (pc) { pc.close(); delete _dm_peers[String(data.userId)]; }
        const audio = document.getElementById('dash-sr-' + data.userId);
        if (audio) audio.remove();

    } else if (data.event === 'mic-toggle') {
        _dm_updateCardMic(data.userId, data.micEnabled);
        // Keep the card's mic action button in sync with the student's actual state
        _dm_studentMicMuted[String(data.userId)] = data.micEnabled === false;
        _dm_updatePactMic(String(data.userId), data.micEnabled !== false);
    }
}

// ── Chat ──────────────────────────────────────────────────────────────────────

function _dm_onChat(data) {
    const box = document.getElementById('dashChatMessages');
    if (!box) return;
    const isOwn  = String(data.senderId) === String(DASH_USER_ID);
    const sender = _dmEsc(data.senderName || data.userName || 'Unknown');
    const text   = _dmEsc(data.content   || data.message  || '');
    const time   = data.timestamp || new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const div = document.createElement('div');
    div.className = 'dm-msg' + (isOwn ? ' dm-own' : '');
    div.innerHTML = `<div class="dm-sender">${sender}</div>
        <div class="dm-bubble">${text}</div>
        <div class="dm-time">${time}</div>`;
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function _showDashChat() {
    const empty = document.getElementById('dashChatEmpty');
    const chat  = document.getElementById('dashChatContainer');
    if (empty) empty.style.display = 'none';
    if (chat)  chat.style.display  = 'flex';
}

function _hideDashChat() {
    const empty = document.getElementById('dashChatEmpty');
    const chat  = document.getElementById('dashChatContainer');
    if (empty) empty.style.display  = '';
    if (chat)  {
        chat.style.display = 'none';
        const msgs = document.getElementById('dashChatMessages');
        if (msgs) msgs.innerHTML = '';
    }
}

function _dm_updateMicBtn(enabled) {
    const btn  = document.getElementById('micBtn');
    const icon = btn ? btn.querySelector('i') : null;
    if (!icon) return;
    if (enabled) {
        icon.className = 'fas fa-microphone';
        if (btn) btn.style.background = '';
    } else {
        icon.className = 'fas fa-microphone-slash';
        if (btn) btn.style.background = 'rgba(239,68,68,0.3)';
    }
}

function _dm_addCard(userId, userName) {
    const area = document.getElementById('participantsCards') || document.getElementById('participantsContent');
    if (!area) return;
    // Hide placeholder
    const placeholder = area.querySelector('.no-participants');
    if (placeholder) placeholder.style.display = 'none';
    // Avoid duplicates
    if (area.querySelector('[data-pid="' + userId + '"]')) return;
    const pid      = String(userId);
    const initials = _dmInitials(userName);
    const card = document.createElement('div');
    card.className   = 'dm-pcard';
    card.dataset.pid = userId;
    card.innerHTML =
        '<div class="dm-avatar">' + initials + '</div>' +
        '<div class="dm-pname">' + _dmEsc(userName) + '</div>' +
        '<div class="dm-mic-dots" id="dm-mic-' + userId + '">' +
            '<div class="dm-dot muted"></div>' +
            '<div class="dm-dot" style="background:rgba(6,182,212,0.5);"></div>' +
            '<div class="dm-dot" style="background:rgba(255,255,255,0.2);"></div>' +
        '</div>';

    // Per-student action buttons: mic (remote mute), sound (mute for me), chat, exit (kick)
    const actions = document.createElement('div');
    actions.className = 'dm-pcard-actions';
    const mkBtn = function (id, cls, icon, title, handler) {
        const b = document.createElement('button');
        b.id        = id;
        b.className = 'dm-pact ' + cls;
        b.title     = title;
        b.innerHTML = '<i class="fas ' + icon + '"></i>';
        b.addEventListener('click', function (ev) { ev.stopPropagation(); handler(); });
        return b;
    };
    actions.appendChild(mkBtn('dm-pact-mic-' + pid, 'dm-pact-mic', 'fa-microphone-slash', 'Mute / unmute student',
        function () { dashStudentMicToggle(pid); }));
    actions.appendChild(mkBtn('dm-pact-sound-' + pid, 'dm-pact-sound', 'fa-volume-up', 'Mute student audio for me',
        function () { dashStudentSoundToggle(pid); }));
    actions.appendChild(mkBtn('dm-pact-chat-' + pid, 'dm-pact-chat', 'fa-comment', 'Chat with student',
        function () { dashStudentChat(pid, userName); }));
    actions.appendChild(mkBtn('dm-pact-exit-' + pid, 'dm-pact-exit', 'fa-sign-out-alt', 'Remove from meeting',
        function () { dashStudentKick(pid, userName); }));
    card.appendChild(actions);

    area.appendChild(card);
}

// ── Per-student card actions ─────────────────────────────────────────────────

let _dm_studentMicMuted = {};   // host-enforced mic mute per student
let _dm_studentSndMuted = {};   // local "sound off for me" per student

function dashStudentMicToggle(userId) {
    const pid      = String(userId);
    const nowMuted = !_dm_studentMicMuted[pid];
    _dm_studentMicMuted[pid] = nowMuted;
    if (_dm_stomp && _dm_stomp.connected) {
        _dm_stomp.send('/app/control/' + _dm_code, {}, JSON.stringify({
            event: nowMuted ? 'mute' : 'unmute',
            targetUserId: pid
        }));
    }
    _dm_updatePactMic(pid, !nowMuted);
}

function _dm_updatePactMic(userId, micOn) {
    const btn = document.getElementById('dm-pact-mic-' + userId);
    if (!btn) return;
    const icon = btn.querySelector('i');
    if (icon) icon.className = 'fas ' + (micOn ? 'fa-microphone' : 'fa-microphone-slash');
    btn.classList.toggle('off', !micOn);
}

function dashStudentSoundToggle(userId) {
    const pid   = String(userId);
    const muted = !_dm_studentSndMuted[pid];
    _dm_studentSndMuted[pid] = muted;
    const audio = document.getElementById('dash-sr-' + pid);
    if (audio) audio.muted = muted || _dm_spkMuted;
    const btn = document.getElementById('dm-pact-sound-' + pid);
    if (btn) {
        const icon = btn.querySelector('i');
        if (icon) icon.className = 'fas ' + (muted ? 'fa-volume-mute' : 'fa-volume-up');
        btn.classList.toggle('off', muted);
    }
}

function dashStudentChat(userId, userName) {
    if (typeof switchTab === 'function') switchTab('chat');
    const input = document.getElementById('dashChatInput');
    if (input) {
        const tag = '@' + (userName || 'Student') + ' ';
        if (!input.value.startsWith(tag)) input.value = tag + input.value;
        input.focus();
    }
}

function dashStudentKick(userId, userName) {
    if (!confirm('Remove ' + (userName || 'this student') + ' from the meeting?')) return;
    if (_dm_stomp && _dm_stomp.connected) {
        _dm_stomp.send('/app/control/' + _dm_code, {}, JSON.stringify({
            event: 'kick-student',
            targetUserId: String(userId)
        }));
    }
}

function _dm_removeCard(userId) {
    const card = document.querySelector('[data-pid="' + userId + '"]');
    if (card) card.remove();

    // If this student was on the Dais when they left, clear their speaking
    // card/timer too (there's no card left to requeue).
    const pid = String(userId);
    const key = Object.keys(_dm_daisSpeakers).find(k => _dm_daisSpeakers[k].pid === pid);
    if (key) {
        clearTimeout(_dm_daisSpeakers[key].timer);
        delete _dm_daisSpeakers[key];
        const stage = document.getElementById('dashDaisStage');
        const daisCard = stage ? stage.querySelector('.dm-dais-card[data-dais-key="' + key.replace(/"/g, '') + '"]') : null;
        if (daisCard) daisCard.remove();
        _dm_daisRestoreEmptyIfIdle(stage, document.getElementById('dashDaisEmpty'), document.getElementById('dashDaisWave'));
    }

    const area = document.getElementById('participantsCards') || document.getElementById('participantsContent');
    if (!area) return;
    const remaining = area.querySelectorAll('[data-pid]');
    if (remaining.length === 0) {
        const placeholder = area.querySelector('.no-participants');
        if (placeholder) placeholder.style.display = '';
    }
}

function _dm_updateCardMic(userId, micOn) {
    const el = document.getElementById('dm-mic-' + userId);
    if (!el) return;
    const dot = el.querySelector('.dm-dot');
    if (dot) {
        dot.className = 'dm-dot ' + (micOn ? 'speaking' : 'muted');
    }
    if (micOn) _dm_flashSpeakAlert(userId);
}

function _dm_flashSpeakAlert(userId, userName) {
    // Dais is student-only. Never put the teacher on the speaking stage —
    // teacher speech belongs in the live-transcript card, not the queue.
    if (String(userId || '') === String(DASH_USER_ID)) return;

    const stage = document.getElementById('dashDaisStage');
    const empty = document.getElementById('dashDaisEmpty');
    const wave  = document.getElementById('dashDaisWave');
    if (!stage) return;

    const pid   = String(userId || '');
    const label = (userName || _dm_getParticipantName(pid) || 'Student').trim();
    // Guard by name too — unmatched teacher transcripts can arrive with an
    // empty userId but the teacher's display name.
    if (label && DASH_USER_NAME && label.toLowerCase() === String(DASH_USER_NAME).trim().toLowerCase()) return;
    // When the participant id isn't known (e.g. a speaker event matched only
    // by name), fall back to a name-derived key so distinct unmatched
    // speakers still get their own card instead of clobbering each other.
    const key = pid || ('name:' + label.toLowerCase());

    if (empty) empty.style.display = 'none';
    if (wave) wave.classList.add('active');

    // Reset only THIS speaker's own silence timer — other students who are
    // simultaneously speaking keep their independent cards + timers running.
    const existing = _dm_daisSpeakers[key];
    if (existing && existing.timer) clearTimeout(existing.timer);

    // Reuse or create this speaker's own Dais card (multiple cards can
    // coexist side by side when two+ students speak at the same time).
    let card = stage.querySelector('.dm-dais-card[data-dais-key="' + key.replace(/"/g, '') + '"]');
    if (!card) {
        card = document.createElement('div');
        card.className = 'dm-dais-card';
        card.dataset.daisKey = key;
        if (pid) card.dataset.daisPid = pid;
        card.innerHTML =
            '<div class="dm-dais-avatar">' + _dmEsc(_dmInitials(label)) + '</div>' +
            '<div class="dm-dais-name">' + _dmEsc(label) + '</div>' +
            '<div class="dm-dais-status">' +
                '<div class="dm-dais-live-dot"></div>' +
                '<span>Speaking</span>' +
            '</div>' +
            '<div class="dm-dais-bars">' +
                '<div class="dm-dais-bar"></div>' +
                '<div class="dm-dais-bar"></div>' +
                '<div class="dm-dais-bar"></div>' +
                '<div class="dm-dais-bar"></div>' +
                '<div class="dm-dais-bar"></div>' +
            '</div>';
        stage.appendChild(card);
    }

    // After 3.5 s of silence from THIS student only, remove their card and
    // rotate them to the back of the participants queue (first-spoken,
    // last-in-line) so the next student is easy to spot/call on.
    const timer = setTimeout(() => {
        _dm_removeDaisCard(key, pid);
    }, 3500);
    _dm_daisSpeakers[key] = { timer, pid };
}

function _dm_removeDaisCard(key, pid) {
    const stage = document.getElementById('dashDaisStage');
    const empty = document.getElementById('dashDaisEmpty');
    const wave  = document.getElementById('dashDaisWave');
    delete _dm_daisSpeakers[key];

    const c = stage ? stage.querySelector('.dm-dais-card[data-dais-key="' + key.replace(/"/g, '') + '"]') : null;
    if (c) {
        c.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
        c.style.opacity    = '0';
        c.style.transform  = 'scale(0.85) translateY(8px)';
        setTimeout(() => { if (c.parentElement) c.remove(); _dm_daisRestoreEmptyIfIdle(stage, empty, wave); }, 420);
    } else {
        _dm_daisRestoreEmptyIfIdle(stage, empty, wave);
    }

    // Only known participants (real pid) can be repositioned in the queue.
    if (pid) _dm_moveParticipantToQueueEnd(pid);
}

function _dm_daisRestoreEmptyIfIdle(stage, empty, wave) {
    // Only show the empty placeholder / stop the wave once nobody else is
    // still speaking (handles the concurrent-speakers edge case correctly).
    if (stage && stage.querySelectorAll('.dm-dais-card').length > 0) return;
    if (empty) empty.style.display = '';
    if (wave) wave.classList.remove('active');
}

// Rotates a participant to the back of the participants list once they
// finish speaking — a simple, fair "spoke → back of the line" queue so the
// teacher can see at a glance who hasn't had a turn recently.
function _dm_moveParticipantToQueueEnd(pid) {
    const area = document.getElementById('participantsCards') || document.getElementById('participantsContent');
    if (!area) return;
    const card = area.querySelector('[data-pid="' + pid + '"]');
    if (!card) return;
    if (area.lastElementChild === card) return; // already at the back
    card.classList.add('dm-requeue');
    area.appendChild(card); // re-appending an existing node relocates it — no clone/recreate
    setTimeout(() => card.classList.remove('dm-requeue'), 600);
}

function _dm_flashSpeakBlipBySpeaker(data) {
    if (!data) return;
    // Teacher transcripts must never drive the Dais — students only.
    if (data.isTeacher === true) return;
    const speakerId = data.userId || data.senderId;
    if (speakerId && String(speakerId) === String(DASH_USER_ID)) return;
    if (speakerId) {
        _dm_flashSpeakAlert(speakerId, data.speakerName || data.userName);
        return;
    }
    const speakerName = (data.speakerName || data.userName || '').trim().toLowerCase();
    if (!speakerName) return;
    if (DASH_USER_NAME && speakerName === String(DASH_USER_NAME).trim().toLowerCase()) return;
    const cards = Array.from(document.querySelectorAll('#participantsCards [data-pid], #participantsContent [data-pid]'));
    const matched = cards.find(c => {
        const nameEl = c.querySelector('.dm-pname');
        return nameEl && nameEl.textContent && nameEl.textContent.trim().toLowerCase() === speakerName;
    });
    if (matched) {
        _dm_flashSpeakAlert(matched.dataset.pid, matched.querySelector('.dm-pname')?.textContent || data.speakerName || data.userName);
    } else {
        _dm_flashSpeakAlert('', data.speakerName || data.userName || 'Student');
    }
}

function _dm_startVoiceAnalyzer(peerId, stream) {
    const key = String(peerId);
    _dm_stopVoiceAnalyzer(key);
    if (!stream) return;
    try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        const ctx = new Ctx();
        // Clone the stream — using the SAME MediaStream object as the <audio>
        // element's srcObject in a createMediaStreamSource() can interfere
        // with playback in modern Chrome (the AudioContext "takes" the
        // stream, leaving the <audio> element silent with no error).
        const cloned = new MediaStream(stream.getAudioTracks().map(t => t.clone()));
        const src = ctx.createMediaStreamSource(cloned);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 256;
        analyser.smoothingTimeConstant = 0.65;
        src.connect(analyser);
        const buf = new Uint8Array(analyser.frequencyBinCount);
        const run = () => {
            if (!_dm_voiceAnalyzers[key]) return;
            analyser.getByteFrequencyData(buf);
            const avg = buf.reduce((a, b) => a + b, 0) / buf.length;
            const now = Date.now();
            if (avg > 12 && (!_dm_lastSpeechAt[key] || now - _dm_lastSpeechAt[key] > 750)) {
                _dm_lastSpeechAt[key] = now;
                _dm_flashSpeakAlert(key);
            }
            _dm_voiceAnalyzers[key].raf = requestAnimationFrame(run);
        };
        _dm_voiceAnalyzers[key] = { ctx, src, analyser, raf: requestAnimationFrame(run) };
    } catch (e) {
        // Ignore audio context errors on unsupported browsers/devices.
    }
}

function _dm_stopVoiceAnalyzer(peerId) {
    const key = String(peerId);
    const entry = _dm_voiceAnalyzers[key];
    if (!entry) return;
    try {
        if (entry.raf) cancelAnimationFrame(entry.raf);
        if (entry.src) entry.src.disconnect();
        if (entry.analyser) entry.analyser.disconnect();
        if (entry.ctx && typeof entry.ctx.close === 'function') entry.ctx.close();
    } catch (e) {}
    delete _dm_voiceAnalyzers[key];
}

function _dm_updateCount() {
    const count = document.querySelectorAll('#participantsCards [data-pid], #participantsContent [data-pid]').length;
    const el = document.getElementById('meetingOnline');
    if (el) el.textContent = count;
}

function _dm_clearParticipants() {
    const area = document.getElementById('participantsCards') || document.getElementById('participantsContent');
    if (!area) return;
    area.querySelectorAll('[data-pid]').forEach(el => el.remove());
    const placeholder = area.querySelector('.no-participants');
    if (placeholder) placeholder.style.display = '';
    const cnt = document.getElementById('meetingOnline');
    if (cnt) cnt.textContent = '0';

    // Clear Dais (all concurrent speakers)
    Object.values(_dm_daisSpeakers).forEach(entry => { if (entry.timer) clearTimeout(entry.timer); });
    _dm_daisSpeakers = {};
    const stage = document.getElementById('dashDaisStage');
    if (stage) stage.querySelectorAll('.dm-dais-card').forEach(el => el.remove());
    const daisEmpty = document.getElementById('dashDaisEmpty');
    if (daisEmpty) daisEmpty.style.display = '';
    const wave = document.getElementById('dashDaisWave');
    if (wave) wave.classList.remove('active');

    // Close all active conversations (cards stay visible for review)
    Object.keys(_dm_convMap).forEach(k => _dm_closeConv(k));
    _dm_pendingTeacher = [];
    _dm_clearTeacherCard();
}

function _dm_getParticipantName(userId) {
    const card = document.querySelector('[data-pid="' + String(userId) + '"]');
    if (!card) return '';
    const nameEl = card.querySelector('.dm-pname');
    return nameEl ? nameEl.textContent : '';
}

function _dm_setupViz(stream) {
    try {
        const ctx      = new (window.AudioContext || window.webkitAudioContext)();
        const analyser = ctx.createAnalyser();
        const cloned   = new MediaStream(stream.getAudioTracks().map(t => t.clone()));
        ctx.createMediaStreamSource(cloned).connect(analyser);
        analyser.fftSize = 256;
        const buf = new Uint8Array(analyser.frequencyBinCount);
        const btn = document.getElementById('micBtn');
        (function tick() {
            requestAnimationFrame(tick);
            analyser.getByteFrequencyData(buf);
            const avg = buf.reduce((a, b) => a + b, 0) / buf.length;
            if (btn && _dm_micOn && avg > 15) {
                btn.style.boxShadow = `0 0 ${6 + avg / 16}px rgba(99,102,241,0.7)`;
            } else if (btn) {
                btn.style.boxShadow = '';
            }
        })();
    } catch (e) { /* ignore if AudioContext unsupported */ }
}

// ── Utility ───────────────────────────────────────────────────────────────────

function _dmInitials(name) {
    const parts = (name || '').trim().split(/\s+/);
    return parts.length >= 2
        ? (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
        : (name || '??').substring(0, 2).toUpperCase();
}

function _dmEsc(text) {
    const d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
}

// ── Recording events ──────────────────────────────────────────────────────────

function _dm_onRecording(data) {
    if (data.event !== 'recording_saved') return;
    // Show badge on Recordings nav tab
    const recTab = document.querySelector('.nav-tab[data-tab="recordings"]');
    if (recTab) {
        let badge = recTab.querySelector('.rec-badge');
        if (!badge) {
            badge = document.createElement('span');
            badge.className = 'rec-badge';
            badge.style.cssText = 'display:inline-block;background:#ef4444;color:#fff;border-radius:50%;font-size:10px;min-width:16px;height:16px;line-height:16px;text-align:center;margin-left:4px;';
            recTab.appendChild(badge);
        }
        badge.textContent = (parseInt(badge.textContent || '0') + 1);
    }
    // Refresh recordings list via AJAX (no page reload)
    if (typeof refreshRecordingsAjax === 'function') {
        refreshRecordingsAjax();
    }
    // Toast notification
    const name = data.userName || 'A student';
    const dur  = data.duration ? ' (' + data.duration + 's)' : '';
    _dmShowToast('🎙️ New Clip', name + ' saved an audio clip' + dur, '#6366f1');
    if (data.whatsappSetupAlert && data.whatsappSetupMessage) {
        _dmShowToast('WhatsApp', data.whatsappSetupMessage, '#f59e0b');
    }
}

// ── Conversation bundler helpers ──────────────────────────────────────────────

function _dm_convKey(userId, fallbackName) {
    return userId ? ('u' + userId) : ('n' + (fallbackName || 'anon').toLowerCase().replace(/\W+/g, '_'));
}

function _dm_getOrCreateConv(userId, userName) {
    const key = _dm_convKey(userId, userName);
    if (_dm_convMap[key]) {
        clearTimeout(_dm_convMap[key].timer);
        _dm_convMap[key].timer = setTimeout(() => _dm_closeConv(key), _DM_CONV_TIMEOUT);
        return _dm_convMap[key];
    }
    const cardId = 'conv-' + key + '-' + Date.now();
    const conv = { key, studentName: userName, userId, lines: [], cardId, timer: null };
    _dm_convMap[key] = conv;
    _dm_buildConvCard(conv);
    conv.timer = setTimeout(() => _dm_closeConv(key), _DM_CONV_TIMEOUT);
    return conv;
}

function _dm_closeConv(key) {
    if (_dm_convMap[key]) {
        clearTimeout(_dm_convMap[key].timer);
        delete _dm_convMap[key];
    }
}

function _dm_buildConvCard(conv) {
    const list  = document.getElementById('dashLiveTranscriptList');
    const empty = document.getElementById('dashLiveTranscriptEmpty');
    if (!list) return;
    if (empty) empty.style.display = 'none';
    const card = document.createElement('div');
    card.id        = conv.cardId;
    card.className = 'conv-card';
    card.innerHTML =
        '<div class="conv-card-header">' +
            '<div class="conv-card-avatar">' + _dmEsc(_dmInitials(conv.studentName)) + '</div>' +
            '<div class="conv-card-title">' +
                '<div class="conv-card-name">' + _dmEsc(conv.studentName) + '</div>' +
                '<div class="conv-card-sub">Live conversation</div>' +
            '</div>' +
            '<button class="conv-ok-btn" onclick="_dm_archiveConvCard(\'' + conv.cardId + '\')">' +
                '<i class="fas fa-check"></i> Done' +
            '</button>' +
        '</div>' +
        '<div class="conv-lines" id="lines-' + conv.cardId + '"></div>';
    list.appendChild(card);
    _dm_scrollTranscripts();
}

function _dm_appendLine(conv, role, name, text) {
    conv.lines.push({ role, name, text });
    const linesEl = document.getElementById('lines-' + conv.cardId);
    if (!linesEl) return;
    const div = document.createElement('div');
    div.className = 'conv-line ' + (role === 'teacher' ? 'conv-line-teacher' : 'conv-line-student');
    div.innerHTML =
        '<span class="conv-line-icon"><i class="fas ' +
            (role === 'teacher' ? 'fa-chalkboard-teacher' : 'fa-user-graduate') + '"></i></span>' +
        '<span class="conv-line-name">' + _dmEsc(name) + '</span>' +
        '<span class="conv-line-text">' + _dmEsc(text) + '</span>';
    linesEl.appendChild(div);
    _dm_scrollTranscripts();
}

function _dm_scrollTranscripts() {
    const scroll = document.getElementById('mcpLiveScroll');
    if (scroll) scroll.scrollTop = scroll.scrollHeight;
}

function _dm_archiveConvCard(cardId) {
    // Find conv by cardId
    const key  = Object.keys(_dm_convMap).find(k => _dm_convMap[k].cardId === cardId);
    const conv = key ? _dm_convMap[key] : null;
    const card = document.getElementById(cardId);
    const liveList  = document.getElementById('dashLiveTranscriptList');
    const liveEmpty = document.getElementById('dashLiveTranscriptEmpty');

    if (card) card.remove();
    if (liveList && !liveList.querySelector('.conv-card, .mcp-card') && liveEmpty) {
        liveEmpty.style.display = '';
    }

    // Archive as bundled entry in allTranscripts for the Transcripts tab
    if (conv && conv.lines.length > 0) {
        const fullText = conv.lines.map(l => l.name + ': ' + l.text).join('\n');
        if (Array.isArray(window.allTranscripts)) {
            const exists = window.allTranscripts.some(t => t.convCardId === cardId);
            if (!exists) {
                window.allTranscripts.unshift({
                    id: Date.now(),
                    convCardId: cardId,
                    content: fullText,
                    speakerName: conv.studentName + ' ↔ Teacher',
                    studentName: conv.studentName,
                    createdAt: new Date().toISOString()
                });
            }
            if (typeof renderTranscripts === 'function') renderTranscripts(window.allTranscripts);
        }
        if (key) _dm_closeConv(key);
    }
    if (typeof loadTranscripts === 'function') loadTranscripts();
    if (typeof refreshRecordingsAjax === 'function') refreshRecordingsAjax();
    _dmShowToast('Saved', 'Conversation moved to Transcripts tab', '#22c55e');
}

// Kept for backward compat (flat mcp-cards from earlier in session)
function _dm_archiveTranscript(cardId) {
    _dm_archiveConvCard(cardId);
}

// ── Teacher live-speech card (always visible when teacher speaks) ─────────────

function _dm_ensureTeacherCard() {
    if (_dm_teacherCardEl && document.contains(_dm_teacherCardEl)) return;
    const list  = document.getElementById('dashLiveTranscriptList');
    const empty = document.getElementById('dashLiveTranscriptEmpty');
    if (!list) return;
    if (empty) empty.style.display = 'none';
    const card = document.createElement('div');
    card.id        = 'conv-teacher-live';
    card.className = 'conv-card conv-card-teacher-live';
    card.innerHTML =
        '<div class="conv-card-header">' +
            '<div class="conv-card-avatar" style="background:linear-gradient(135deg,#6366f1,#8b5cf6);">T</div>' +
            '<div class="conv-card-title">' +
                '<div class="conv-card-name">Your Live Speech</div>' +
                '<div class="conv-card-sub">Real-time teacher transcription</div>' +
            '</div>' +
            '<button class="conv-ok-btn" style="background:linear-gradient(135deg,#64748b,#475569);" ' +
                'onclick="_dm_clearTeacherCard()">' +
                '<i class="fas fa-trash-alt"></i> Clear' +
            '</button>' +
        '</div>' +
        '<div class="conv-lines" id="lines-teacher-live"></div>';
    list.insertBefore(card, list.firstChild);
    _dm_teacherCardEl = card;
}

function _dm_showTeacherLine(name, text) {
    _dm_ensureTeacherCard();
    const linesEl = document.getElementById('lines-teacher-live');
    if (!linesEl) return;
    const div = document.createElement('div');
    div.className = 'conv-line conv-line-teacher';
    div.innerHTML =
        '<span class="conv-line-icon"><i class="fas fa-chalkboard-teacher"></i></span>' +
        '<span class="conv-line-name">' + _dmEsc(name) + '</span>' +
        '<span class="conv-line-text">' + _dmEsc(text) + '</span>';
    linesEl.appendChild(div);
    // Cap the card at 12 most-recent lines to prevent overflow
    const all = linesEl.querySelectorAll('.conv-line');
    if (all.length > 12) all[0].remove();
    _dm_scrollTranscripts();
}

function _dm_clearTeacherCard() {
    if (_dm_teacherCardEl) { _dm_teacherCardEl.remove(); _dm_teacherCardEl = null; }
    const liveList  = document.getElementById('dashLiveTranscriptList');
    const liveEmpty = document.getElementById('dashLiveTranscriptEmpty');
    if (liveList && !liveList.querySelector('.conv-card, .mcp-card') && liveEmpty) {
        liveEmpty.style.display = '';
    }
}

// ── Main transcript handler ───────────────────────────────────────────────────

function _dm_onTranscript(data) {
    // Accept messages that have text, regardless of whether 'success' is set
    if (!data || !data.text) return;
    if (data.success === false) return; // explicit server error — skip

    const text        = data.text.trim();
    if (!text) return;
    const isTeacher   = data.isTeacher === true;
    const speakerName = (data.speakerName || data.userName || 'Unknown').trim();
    const userId      = String(data.userId || '');

    // Dais spotlight is for students only — teacher speech stays in the
    // live-transcript card / conversation bundler, never on the stage.
    if (!isTeacher) {
        _dm_flashSpeakBlipBySpeaker(data);
    }

    if (isTeacher) {
        // ── Teacher spoke ───────────────────────────────────────────────────
        // 1. Show immediately in the teacher's own live-speech card
        _dm_showTeacherLine(speakerName, text);

        // 2. Buffer so it can be prepended to a new student card
        _dm_pendingTeacher.push({ name: speakerName, text, at: Date.now() });
        if (_dm_pendingTeacher.length > 30) _dm_pendingTeacher.shift(); // keep last 30 teacher lines

        // 3. Also append live to ALL currently open student conversation cards
        Object.values(_dm_convMap).forEach(conv => _dm_appendLine(conv, 'teacher', speakerName, text));

    } else {
        // ── Student spoke ───────────────────────────────────────────────────
        const conv = _dm_getOrCreateConv(userId, speakerName);

        // For a brand-new card: prepend recent pending teacher lines (≤ 20 s old)
        if (conv.lines.length === 0) {
            const now    = Date.now();
            const recent = _dm_pendingTeacher.filter(l => now - l.at < _DM_TEACHER_MAX_AGE);
            recent.forEach(tl => _dm_appendLine(conv, 'teacher', tl.name, tl.text));
            _dm_pendingTeacher = []; // consumed — clear buffer
        }

        _dm_appendLine(conv, 'student', speakerName, text);
    }
}

function _dmShowToast(title, msg, color) {
    const t = document.createElement('div');
    t.style.cssText = 'position:fixed;bottom:24px;right:24px;background:' + (color||'#374151') + ';color:#fff;padding:12px 18px;border-radius:10px;z-index:9999;font-size:13px;box-shadow:0 4px 16px rgba(0,0,0,0.4);max-width:280px;';
    t.innerHTML = '<strong>' + title + '</strong><br>' + msg;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 4500);
}

// ── Cleanup on page close ─────────────────────────────────────────────────────

window.addEventListener('beforeunload', function () {
    if (_dm_code) cleanupDashboardMeeting();
});

