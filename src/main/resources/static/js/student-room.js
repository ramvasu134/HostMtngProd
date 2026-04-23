/**
 * Student Meeting Room JavaScript
 */

// ===== Config =====
const meetingData = document.getElementById('meetingData');
const MEETING_CODE       = meetingData.dataset.meetingCode;
const USER_ID            = meetingData.dataset.userId;
const USER_NAME          = meetingData.dataset.userName;
const RECORDING_ENABLED  = meetingData.dataset.recordingEnabled === 'true';

const ICE_SERVERS = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
    ]
};

// ===== State =====
let localStream    = null;
let stompClient    = null;
let peerConnections = {};
let pendingCandidates = {};   // peerId → queued ICE candidates before remoteDesc is set
let isMicOn        = true;
let isSpeakerOn    = true;
let isChatOpen     = false;
let isSettingsOpen = false;
let isThemesOpen   = false;

let mediaRecorder  = null;
let recordedChunks = [];
let recordingStartTime = 0;

// Persistent audio mixer — created once, reused across recording sessions
let _recAudioCtx      = null;   // AudioContext (stays open for the meeting duration)
let _recDest          = null;   // MediaStreamDestination (the mixed output)
let _recLocalAdded    = false;  // whether student mic is already wired in
let _recTeacherAdded  = false;  // whether teacher track is already wired in

let _isTranscribingActive = false; // true while speech recognition should accumulate text
let _isMeetingActive      = false; // true for the whole meeting duration
let _isRecordingActive    = false; // true while MediaRecorder is running
let _redirectAfterUpload  = false; // redirect to /student/room after upload on meeting end
let _pendingTranscripts   = [];    // STOMP payloads queued while WebSocket handshakes

// Rolling-buffer constants — keep at most the last 60 seconds of audio
const _REC_CHUNK_MS   = 5000; // emit a chunk every 5 s
const _REC_MAX_CHUNKS = 12;   // 12 × 5 s = 60 s window
let   _rollChunks     = [];   // current rolling chunk buffer

// ===== Boot =====
function buildInitials(name) {
    if (!name) return 'U';
    const parts = name.trim().split(/\s+/);
    return parts.length >= 2
        ? (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
        : name.substring(0, 2).toUpperCase();
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('userInitials').textContent = buildInitials(USER_NAME);

    // Restore Auto Join preference
    const autoJoin = localStorage.getItem('sr_autoJoin') === 'true';
    document.getElementById('autoJoinToggle').checked = autoJoin;

    // Restore saved theme
    const savedTheme = localStorage.getItem('sr_theme') || 'matte-black';
    srApplyTheme(savedTheme, true);

    // Close settings/themes dropdown when clicking outside
    document.addEventListener('click', (e) => {
        const anchor = e.target.closest('.sr-settings-anchor');
        if (!anchor) {
            closeSettings();
            closeThemesPanel();
        }
    });

    // ===== Click ANYWHERE on center stage to toggle mic =====
    const stage = document.getElementById('srCenterStage');
    if (stage) {
        stage.addEventListener('click', (e) => {
            // Don't fire if clicking a real interactive element
            const isInteractive = e.target.closest(
                'button, a, input, select, textarea, .sr-chat-dialog, ' +
                '.sr-settings-dropdown, .sr-themes-panel, .sr-modal-overlay'
            );
            if (!isInteractive) {
                srToggleMic();
            }
        });
    }

    // CRITICAL: get mic permission BEFORE connecting WebSocket.
    initAudio().then(() => {
        _isMeetingActive = true;
        _initSpeechRecognition();

        // Student joins with mic ON by default — start recording and transcription now.
        if (isMicOn) {
            _isTranscribingActive = true;
            if (_speechRecognition) {
                try { _speechRecognition.start(); } catch(e) {
                    console.warn('[STT] initial start error:', e.message);
                }
            }
            _startRollingRecorder();
            _startSttWatchdog();   // keep STT alive for the whole meeting
        }
        connectWebSocket();
    }).catch(() => {
        _isMeetingActive = true;
        connectWebSocket();
    });
});

// ===== Audio init =====
async function initAudio() {
    try {
        localStream = await navigator.mediaDevices.getUserMedia({
            audio: { echoCancellation: true, noiseSuppression: true },
            video: false
        });
    } catch (err) {
        console.warn('Mic access denied:', err);
    }
}

// ── Persistent audio mixer (student mic + teacher remote audio) ───────────────
//
// The mixer is created ONCE when the student's mic is ready.
// The teacher's audio track is wired in dynamically via _addTeacherToMixer()
// which is called from playHostAudio() every time the WebRTC stream arrives.
// MediaRecorder reads from _recDest.stream so it always has both voices.

function _initRecordingMixer() {
    if (_recAudioCtx && _recAudioCtx.state !== 'closed') return; // already alive
    try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        _recAudioCtx     = new Ctx();
        _recDest         = _recAudioCtx.createMediaStreamDestination();
        _recLocalAdded   = false;
        _recTeacherAdded = false;
        console.log('[Mixer] AudioContext created');
    } catch (e) {
        console.warn('[Mixer] init failed – will record mic-only:', e);
        _recAudioCtx = null;
        _recDest     = null;
    }
}

function _addLocalMicToMixer() {
    if (!_recAudioCtx || !_recDest || _recLocalAdded) return;
    if (!localStream || localStream.getAudioTracks().length === 0) return;
    try {
        if (_recAudioCtx.state === 'suspended') _recAudioCtx.resume();
        _recAudioCtx.createMediaStreamSource(localStream).connect(_recDest);
        _recLocalAdded = true;
        console.log('[Mixer] Student mic connected');
    } catch (e) {
        console.warn('[Mixer] addLocalMic failed:', e);
    }
}

// Called from playHostAudio() whenever the teacher's WebRTC stream arrives/changes
function _addTeacherToMixer(stream) {
    if (!stream) return;
    _initRecordingMixer();           // create if not yet done
    _addLocalMicToMixer();           // ensure student mic is in the mix
    if (!_recAudioCtx || !_recDest) return;
    try {
        const tracks = (stream instanceof MediaStream)
            ? stream.getAudioTracks()
            : [];
        if (tracks.length === 0) return;
        if (_recAudioCtx.state === 'suspended') _recAudioCtx.resume();
        _recAudioCtx.createMediaStreamSource(new MediaStream(tracks)).connect(_recDest);
        _recTeacherAdded = true;
        console.log('[Mixer] Teacher audio connected – both voices in mix');
    } catch (e) {
        console.warn('[Mixer] addTeacher failed:', e);
    }
}

// ── Noise-word transcript filter ──────────────────────────────────────────────
// Returns true when the text is too short or is only noise/filler words.
const _NOISE_WORDS = new Set([
    'okay','ok','yes','no','yeah','yep','nope','hmm','um','uh','ah',
    'oh','right','sure','fine','good','great','thanks','thank you',
    'alright','alright then','got it','i see','understood'
]);
function _isNoisyTranscript(text) {
    if (!text) return true;
    const trimmed = text.trim().toLowerCase().replace(/[.!?,;]+$/,'');
    if (trimmed.length < 2) return true;           // only single chars get filtered
    if (_NOISE_WORDS.has(trimmed)) return true;    // exact noise phrase match
    // Filter ONLY if every word is a noise word (e.g. "okay yes" → noisy)
    const words = trimmed.split(/\s+/);
    if (words.length <= 2 && words.every(w => _NOISE_WORDS.has(w))) return true;
    return false;
}

// ── Rolling-window recorder ───────────────────────────────────────────────────
// Records in 5-second chunks, keeping only the last 60 seconds.
// Triggered on mic-unmute; saved to server on mic-mute (or meeting end).
// This captures exactly what matters: teacher's recent question + student's answer.

function _startRollingRecorder() {
    if (!RECORDING_ENABLED || !localStream) return;
    if (_isRecordingActive) return; // already running
    try {
        _initRecordingMixer();
        _addLocalMicToMixer();
        // Resume AudioContext — safe after user interaction (mic button click)
        if (_recAudioCtx && _recAudioCtx.state === 'suspended') {
            _recAudioCtx.resume().catch(() => {});
        }

        _rollChunks = [];
        const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
            ? 'audio/webm;codecs=opus'
            : MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '';
        const opts = mimeType ? { mimeType } : {};

        const stream = (_recDest && _recDest.stream && _recDest.stream.getTracks().length > 0)
            ? _recDest.stream : localStream;

        mediaRecorder = new MediaRecorder(stream, opts);
        mediaRecorder.ondataavailable = e => {
            if (e.data && e.data.size > 0) {
                _rollChunks.push(e.data);
                // Enforce rolling window — drop oldest chunk when full
                while (_rollChunks.length > _REC_MAX_CHUNKS) _rollChunks.shift();
            }
        };
        mediaRecorder.onstop = _saveSegment;
        recordingStartTime = Date.now();
        mediaRecorder.start(_REC_CHUNK_MS);
        _isRecordingActive    = true;
        _isTranscribingActive = true;
        console.log('[Recording] Rolling recorder started (max', _REC_MAX_CHUNKS * _REC_CHUNK_MS / 1000, 's window)');
    } catch (err) {
        console.error('[Recording] Start error:', err);
        _isRecordingActive = false;
    }
}

// Called when the rolling recorder stops — uploads the buffered window then
// either redirects (end-of-meeting) or restarts for the next interaction.
function _saveSegment() {
    const willRedirect = _redirectAfterUpload;
    _redirectAfterUpload  = false;
    _isRecordingActive    = false;
    _isTranscribingActive = false;

    const chunks = _rollChunks.slice(); // snapshot
    _rollChunks = [];

    if (chunks.length < 2) {
        console.log('[Recording] Segment too short, skipping upload');
        if (willRedirect) { window.location.href = '/student/room'; }
        else if (_isMeetingActive) { _startRollingRecorder(); } // keep capturing teacher audio
        return;
    }

    const mimeType      = (mediaRecorder && mediaRecorder.mimeType) ? mediaRecorder.mimeType : 'audio/webm';
    const durationSecs  = Math.max(1, Math.round(chunks.length * _REC_CHUNK_MS / 1000));
    const blob          = new Blob(chunks, { type: mimeType });

    // Grab speech-recognition text and reset for next segment
    _stopTranscriptionAsync();
    const transcript = _getTranscriptResult();
    console.log('[Recording] Uploading', durationSecs + 's,', 'transcript:', transcript || '(none)');

    uploadRecording(blob, durationSecs, transcript)
        .then(() => {
            console.log('[Recording] Upload OK');
            if (willRedirect) {
                window.location.href = '/student/room';
            } else {
                showSrNotifToast('Clip Saved', '✅ ' + durationSecs + 's clip saved.', 'MEETING_STARTED');
                // Always restart the recorder so teacher audio is captured even while
                // student is muted.  The student mic track is already disabled at the
                // WebRTC level so no student noise leaks in during mute.
                if (_isMeetingActive) {
                    _startRollingRecorder();
                }
            }
        })
        .catch(err => {
            console.error('[Recording] Upload failed:', err);
            if (willRedirect) {
                window.location.href = '/student/room';
            } else {
                showSrNotifToast('Upload Failed', '❌ ' + err.message, 'MEETING_STARTED');
                // Still restart so we don't lose teacher audio
                if (_isMeetingActive) {
                    _startRollingRecorder();
                }
            }
        });
}

// Keep for any legacy call-sites — delegates to rolling recorder
function _startRecordingIfReady() { _startRollingRecorder(); }

// ===== WebSocket =====
let _wsReconnectTimer = null;
let _wsConnecting = false;

function connectWebSocket() {
    // Prevent multiple simultaneous connection attempts
    if (_wsConnecting) return;
    // If already connected, skip
    if (stompClient && stompClient.connected) return;

    _wsConnecting = true;
    // Cleanly disconnect any stale client before creating a new one
    if (stompClient) {
        try { stompClient.disconnect(); } catch (e) {}
        stompClient = null;
    }

    const socket = new SockJS('/ws');
    stompClient  = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, () => {
        _wsConnecting = false;
        setConnectionStatus(true);

        stompClient.subscribe('/topic/signal/'      + MEETING_CODE, m => handleSignaling(JSON.parse(m.body)));
        stompClient.subscribe('/topic/chat/'        + MEETING_CODE, m => displayChatMessage(JSON.parse(m.body)));
        stompClient.subscribe('/topic/participant/' + MEETING_CODE, m => handleParticipantEvent(JSON.parse(m.body)));
        stompClient.subscribe('/topic/control/'     + MEETING_CODE, m => handleControlEvent(JSON.parse(m.body)));

        // Subscribe to user-specific notifications (schedule, reminders, etc.)
        if (USER_ID) {
            stompClient.subscribe('/topic/notifications/' + USER_ID, m => {
                const data = JSON.parse(m.body);
                if (data) showSrNotifToast(data.title, data.message, data.type);
            });
        }

        // Flush any transcripts that were recognized before WS was ready
        while (_pendingTranscripts.length > 0) {
            stompClient.send('/app/transcript/' + MEETING_CODE, {}, _pendingTranscripts.shift());
        }

        sendParticipantEvent('join');

    }, () => {
        _wsConnecting = false;
        setConnectionStatus(false);
        // Disconnect stale client so next attempt is clean
        if (stompClient) { try { stompClient.disconnect(); } catch (e) {} stompClient = null; }
        clearTimeout(_wsReconnectTimer);
        _wsReconnectTimer = setTimeout(connectWebSocket, 3000);
    });
}

function setConnectionStatus(online) {
    const dot  = document.getElementById('statusDot');
    const text = document.getElementById('statusText');
    dot.classList.toggle('online', online);
    text.textContent  = online ? 'Online'  : 'Offline';
    text.style.color  = online ? '#22c55e' : '#ef4444';
}

// ===== WebRTC signaling =====
function handleSignaling(data) {
    const sid = String(data.senderId);
    if (sid === String(USER_ID)) return;
    // Only process signals that are explicitly targeted at this student (or broadcast with no target)
    if (data.targetId && String(data.targetId) !== String(USER_ID)) return;
    if      (data.type === 'offer')         handleOffer(sid, data);
    else if (data.type === 'answer')        handleAnswer(sid, data);
    else if (data.type === 'ice-candidate') handleIce(sid, data);
    else if (data.type === 'request-offer') createOffer(sid);
}

async function createOffer(targetId) {
    // Idempotency guard: skip if a connection attempt is already in progress for this peer
    if (peerConnections[targetId]) return;
    const pc = getOrCreatePC(targetId);
    try {
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        send({ type: 'offer', sdp: offer.sdp, targetId });
    } catch (e) { console.error(e); }
}

async function handleOffer(sid, data) {
    const pc = getOrCreatePC(sid);
    try {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: data.sdp }));
        await drainCandidates(sid);          // flush any queued ICE candidates
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        send({ type: 'answer', sdp: answer.sdp, targetId: sid });
    } catch (e) { console.error(e); }
}

async function handleAnswer(sid, data) {
    const pc = peerConnections[sid];
    if (pc) {
        try {
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: data.sdp }));
            await drainCandidates(sid);      // flush any queued ICE candidates
        } catch(e) { console.error(e); }
    }
}

async function handleIce(sid, data) {
    const pc = peerConnections[sid];
    if (!pc || !data.candidate) return;
    // If remote description not set yet, queue the candidate to apply later
    if (pc.remoteDescription && pc.remoteDescription.type) {
        try { await pc.addIceCandidate(new RTCIceCandidate(data.candidate)); } catch(e) {}
    } else {
        if (!pendingCandidates[sid]) pendingCandidates[sid] = [];
        pendingCandidates[sid].push(data.candidate);
    }
}

// Drain all queued ICE candidates for a peer after remote description is set
async function drainCandidates(peerId) {
    const queue = pendingCandidates[peerId];
    if (!queue || !queue.length) return;
    delete pendingCandidates[peerId];
    const pc = peerConnections[peerId];
    if (!pc) return;
    for (const c of queue) {
        try { await pc.addIceCandidate(new RTCIceCandidate(c)); } catch(e) {}
    }
}

function getOrCreatePC(peerId) {
    if (peerConnections[peerId]) return peerConnections[peerId];
    const pc = new RTCPeerConnection(ICE_SERVERS);
    peerConnections[peerId] = pc;

    if (localStream) localStream.getTracks().forEach(t => pc.addTrack(t, localStream));

    pc.onicecandidate = e => { if (e.candidate) send({ type: 'ice-candidate', candidate: e.candidate, targetId: peerId }); };
    pc.ontrack = e  => playHostAudio(e.streams[0]);
    pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
            hideHostAudio();
            delete peerConnections[peerId];
        }
    };
    return pc;
}

function send(payload) {
    if (stompClient && stompClient.connected)
        stompClient.send('/app/signal/' + MEETING_CODE, {}, JSON.stringify(payload));
}

// ===== Host audio playback =====
function playHostAudio(stream) {
    let audio = document.getElementById('host-audio-el');
    if (!audio) {
        audio = document.createElement('audio');
        audio.id = 'host-audio-el';
        audio.autoplay = true;
        audio.playsInline = true;
        audio.style.display = 'none';
        document.body.appendChild(audio);
    }
    audio.srcObject = stream;

    // Wire teacher's audio into the recording mixer so both voices are captured
    _addTeacherToMixer(stream);

    const playPromise = audio.play();
    if (playPromise !== undefined) {
        playPromise.catch(() => {
            const btn = document.getElementById('unmuteBtn');
            if (btn) btn.style.display = 'inline-flex';
        });
    }

    // Update teacher-connected indicator
    _setTeacherConnected(true);
}

function resumeHostAudio() {
    const audio = document.getElementById('host-audio-el');
    if (!audio) return;
    audio.play().then(() => {
        const btn = document.getElementById('unmuteBtn');
        if (btn) btn.style.display = 'none';
    }).catch(() => {});
}

function hideHostAudio() {
    const audio = document.getElementById('host-audio-el');
    if (audio) { audio.srcObject = null; audio.remove(); }
    _setTeacherConnected(false);
    const btn = document.getElementById('unmuteBtn');
    if (btn) btn.style.display = 'none';
}

function _setTeacherConnected(connected) {
    const dot  = document.querySelector('.sr-conn-dot');
    const text = document.getElementById('srTeacherConnText');
    if (dot) {
        dot.classList.toggle('online', connected);
        dot.classList.toggle('offline', !connected);
    }
    if (text) text.textContent = connected ? 'Teacher Connected' : 'Waiting for teacher…';
}

// ===== Participant events =====
function sendParticipantEvent(type) {
    if (stompClient && stompClient.connected)
        stompClient.send('/app/participant/' + MEETING_CODE, {}, JSON.stringify({
            event: type, micEnabled: isMicOn, cameraEnabled: false, handRaised: false
        }));
}

function handleParticipantEvent(data) {
    if (String(data.userId) === String(USER_ID)) return;
    if (data.event === 'join') {
        // Only initiate a WebRTC connection to the HOST — not to other students
        if (data.userRole === 'HOST') {
            createOffer(String(data.userId));
        }
    } else if (data.event === 'leave') {
        const pc = peerConnections[String(data.userId)];
        if (pc) { pc.close(); delete peerConnections[String(data.userId)]; }
        if (Object.keys(peerConnections).length === 0) hideHostAudio();
    }
}

function handleControlEvent(data) {
    if (data.event === 'end-meeting') {
        showSrNotifToast('Meeting Ended', 'Saving your recording…', 'MEETING_STARTED');
        hideHostAudio();
        _isMeetingActive      = false;
        _isTranscribingActive = false;

        if (_speechRecognition) { try { _speechRecognition.stop(); } catch(e) {} }

        if (_isRecordingActive && mediaRecorder && mediaRecorder.state !== 'inactive') {
            // _saveSegment will upload and then redirect
            _redirectAfterUpload = true;
            try { mediaRecorder.stop(); } catch(e) { window.location.href = '/student/room'; }
        } else {
            // Nothing to upload — go straight to room list
            setTimeout(() => { window.location.href = '/student/room'; }, 1800);
        }
    } else if (data.event === 'mute-all') {
        srToggleMic(true);
    }
}

// ===== Controls =====
function srToggleMic(forceMute) {
    // Update mic state
    if (localStream) {
        const track = localStream.getAudioTracks()[0];
        if (track) {
            isMicOn = forceMute === true ? false : !isMicOn;
            track.enabled = isMicOn;
        }
    } else {
        isMicOn = forceMute === true ? false : !isMicOn;
    }
    _updateMicUI(isMicOn);

    if (isMicOn) {
        // ── UNMUTED ──────────────────────────────────────────────────────────
        _isTranscribingActive = true;
        if (!_speechRecognition) _initSpeechRecognition();
        if (_speechRecognition && !_sttRunning) {
            try { _speechRecognition.start(); } catch(e) {}
        }
        _startRollingRecorder();
        _startSttWatchdog();
    } else {
        // ── MUTED ────────────────────────────────────────────────────────────
        _isTranscribingActive = false;
        _stopSttWatchdog();
        if (_speechRecognition) { try { _speechRecognition.stop(); } catch(e) {} }

        if (_isRecordingActive && mediaRecorder && mediaRecorder.state !== 'inactive') {
            try { mediaRecorder.stop(); } catch(e) { _isRecordingActive = false; }
        }
    }

    sendParticipantEvent('mic-toggle');
}

// ── Update all mic-related UI ──
function _updateMicUI(micOn) {
    const btn      = document.getElementById('btnMic');
    const icon     = document.getElementById('srMicIcon') || (btn && btn.querySelector('i'));
    const stage    = document.getElementById('srMicStage') || btn?.closest('.sr-mic-stage');
    const stageWrap = document.querySelector('.sr-mic-stage');
    const hintText = document.getElementById('srMicHintText');

    if (micOn) {
        // Active: green
        if (btn)  { btn.classList.remove('muted'); }
        if (icon) { icon.className = 'fas fa-microphone'; }
        if (stageWrap) stageWrap.classList.add('sr-mic-active');
        if (hintText) hintText.textContent = '🎙️ Speaking — tap to mute';
    } else {
        // Muted: red
        if (btn)  { btn.classList.add('muted'); }
        if (icon) { icon.className = 'fas fa-microphone-slash'; }
        if (stageWrap) stageWrap.classList.remove('sr-mic-active');
        if (hintText) hintText.textContent = '🔇 Muted — tap anywhere to speak';
    }
}

// Legacy name — now delegates to _saveSegment
function _onRecordingStop() { _saveSegment(); }

// ===== Save Recording Dialog =====
let _pendingBlob     = null;
let _pendingDuration = 0;

function srShowSaveDialog(blob, durationSecs) {
    _pendingBlob     = blob;
    _pendingDuration = durationSecs;

    const info = document.getElementById('srSaveRecInfo');
    if (info) info.innerHTML = 'Your ' + durationSecs + 's clip was saved to server.<br/>Want a local copy too?';

    // Wire up download button
    const dlBtn = document.getElementById('srSaveRecDownloadBtn');
    if (dlBtn) dlBtn.onclick = () => { srDownloadBlob(_pendingBlob, _pendingDuration); srCloseSaveDialog(); };

    document.getElementById('srSaveRecOverlay').classList.add('active');
    document.getElementById('srSaveRecModal').classList.add('open');
}

function srCloseSaveDialog() {
    document.getElementById('srSaveRecOverlay').classList.remove('active');
    document.getElementById('srSaveRecModal').classList.remove('open');
    _pendingBlob     = null;
    _pendingDuration = 0;
}

function srDiscardRecording() {
    srCloseSaveDialog();
    showSrNotifToast('Recording Discarded', 'The audio clip was discarded.', 'MEETING_STARTED');
}

function srDownloadBlob(blob, durationSecs) {
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = 'recording-' + new Date().toISOString().replace(/[:.]/g, '-') + '.webm';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
}

function srSetDialogStatus(msg, isError) {
    const s = document.getElementById('srSaveRecStatus');
    if (!s) return;
    s.textContent  = msg;
    s.style.color  = isError ? '#f87171' : '#22c55e';
    s.style.display = 'block';
}

function srDisableDialogButtons(disable) {
    ['srSaveRecDownloadBtn','srSaveRecUploadBtn','srSaveRecBothBtn'].forEach(id => {
        const b = document.getElementById(id);
        if (b) { b.disabled = disable; b.style.opacity = disable ? '0.5' : '1'; }
    });
}

function srUploadOnly(blob, durationSecs) {
    srDisableDialogButtons(true);
    srSetDialogStatus('Uploading to server…', false);
    uploadRecording(blob, durationSecs)
        .then(() => {
            srSetDialogStatus('✅ Saved to server! Teacher can now play it.', false);
            setTimeout(srCloseSaveDialog, 2000);
        })
        .catch(err => {
            srSetDialogStatus('❌ Upload failed: ' + err.message + '. Try again.', true);
            srDisableDialogButtons(false);
        });
}

function srSaveBoth(blob, durationSecs) {
    // Download immediately
    srDownloadBlob(blob, durationSecs);
    // Then upload
    srDisableDialogButtons(true);
    srSetDialogStatus('Uploading to server…', false);
    uploadRecording(blob, durationSecs)
        .then(() => {
            srSetDialogStatus('✅ Saved to device & server!', false);
            setTimeout(srCloseSaveDialog, 2000);
        })
        .catch(err => {
            srSetDialogStatus('⚠️ Downloaded but upload failed: ' + err.message, true);
            srDisableDialogButtons(false);
        });
}

function uploadRecording(blob, duration, transcript) {
    return new Promise(function(resolve, reject) {
        const formData = new FormData();
        formData.append('file', blob, 'audio-clip.webm');
        formData.append('duration', duration);
        if (transcript) formData.append('transcript', transcript);

        fetch('/api/meeting/' + MEETING_CODE + '/recording/upload', {
            method: 'POST',
            body: formData
        })
        .then(function(r) {
            if (!r.ok) return r.json().then(d => Promise.reject(new Error(d.error || 'Server error')));
            return r.json();
        })
        .then(function(data) {
            if (!data.success) return reject(new Error(data.error || 'Upload failed'));
            const cleanTranscript = (transcript || '').trim();
            if (cleanTranscript && stompClient && stompClient.connected) {
                stompClient.send('/app/transcript/' + MEETING_CODE, {}, JSON.stringify({
                    text:        cleanTranscript,
                    speakerName: USER_NAME,
                    isTeacher:   false,          // always student from this page
                    recordingId: data.recordingId,
                    startTime:   0,
                    endTime:     duration
                }));
            }
            // Refresh recordings list if open
            const modal = document.getElementById('srRecordingsModal');
            if (modal && modal.classList.contains('open')) srLoadRecordings();
            resolve(data);
        })
        .catch(function(err) {
            reject(err);
        });
    });
}

function srToggleSpeaker() {
    isSpeakerOn = !isSpeakerOn;
    const audio = document.getElementById('host-audio-el');
    const btn   = document.getElementById('btnSpeaker');
    const icon  = btn ? btn.querySelector('i') : null;
    if (audio) audio.muted = !isSpeakerOn;
    if (isSpeakerOn) {
        if (btn)  btn.classList.remove('muted');
        if (icon) icon.className = 'fas fa-volume-up';
    } else {
        if (btn)  btn.classList.add('muted');
        if (icon) icon.className = 'fas fa-volume-mute';
    }
}

// ===== Chat dialog =====
function srToggleChat() {
    isChatOpen = !isChatOpen;
    document.getElementById('srChatDialog').classList.toggle('open', isChatOpen);
    document.getElementById('srOverlay').classList.toggle('active', isChatOpen);
    if (isChatOpen) setTimeout(() => document.getElementById('srChatInput').focus(), 100);
}

// ===== Settings dropdown =====
function srToggleSettings(e) {
    if (e) e.stopPropagation();
    isSettingsOpen = !isSettingsOpen;
    document.getElementById('srSettingsDropdown').classList.toggle('open', isSettingsOpen);
}

function closeSettings() {
    isSettingsOpen = false;
    document.getElementById('srSettingsDropdown').classList.remove('open');
}

function srCloseAll() {
    isChatOpen     = false;
    isSettingsOpen = false;
    document.getElementById('srChatDialog').classList.remove('open');
    document.getElementById('srOverlay').classList.remove('active');
    closeSettings();
    closeThemesPanel();
}

// ===== Settings actions =====
function srAutoJoinChanged(checkbox) {
    localStorage.setItem('sr_autoJoin', checkbox.checked);
}

// ===== RECORDINGS MODAL =====
function srOpenRecordings(e) {
    if (e) e.preventDefault();
    closeSettings();
    document.getElementById('srRecordingsOverlay').classList.add('active');
    document.getElementById('srRecordingsModal').classList.add('open');
    srLoadRecordings();
}

function srCloseRecordings() {
    document.getElementById('srRecordingsOverlay').classList.remove('active');
    document.getElementById('srRecordingsModal').classList.remove('open');
}

function srLoadRecordings() {
    fetch('/api/user/recordings')
        .then(r => r.json())
        .then(data => {
            const list  = document.getElementById('srRecordingsList');
            const empty = document.getElementById('srRecordingsEmpty');
            // Remove old items (keep empty state)
            list.querySelectorAll('.sr-rec-item').forEach(el => el.remove());

            if (!data || data.length === 0) {
                if (empty) empty.style.display = '';
                return;
            }
            if (empty) empty.style.display = 'none';

            let counter = 1;
            data.forEach(rec => {
                const secs = rec.durationSeconds || 0;
                const dur  = secs > 0 ? `${secs}s` : '0s';
                const item = document.createElement('div');
                item.className = 'sr-rec-item';
                const audioId = 'sr-audio-' + rec.id;
                item.innerHTML = `
                    <div style="display:flex; justify-content:space-between; width:100%; align-items:center; margin-bottom: 10px;">
                        <div style="font-size:13px; color:#94a3b8;">
                            <strong>#${counter++}</strong> &nbsp;
                            <i class="fas fa-clock" style="margin:0 4px;color:#6366f1;"></i>${dur} &nbsp;
                            <i class="fas fa-calendar-alt" style="margin:0 4px;color:#6366f1;"></i>${escHtml(rec.createdAt)}
                        </div>
                    </div>
                    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:8px;">
                        <button class="sr-rec-item-btn sr-rec-item-play" onclick="srTogglePlay('${audioId}', this)" title="Play">
                            <i class="fas fa-play"></i>
                        </button>
                        <a href="/api/meeting/recording/${rec.id}/download" class="sr-rec-item-btn sr-rec-item-dl" download title="Download">
                            <i class="fas fa-download"></i>
                        </a>
                        <button class="sr-rec-item-btn sr-rec-item-del" onclick="srDeleteRecording(${rec.id}, this)" title="Delete">
                            <i class="fas fa-trash"></i>
                        </button>
                    </div>
                    <audio id="${audioId}" src="/api/meeting/recording/${rec.id}/play" preload="none"
                           onended="srResetPlayBtn('${audioId}')"
                           style="display:none;width:100%;margin-top:8px;"></audio>
                    ${rec.transcriptContent ? '<div style="margin-top:6px;padding:8px 10px;background:rgba(99,102,241,0.1);border-left:3px solid #6366f1;border-radius:6px;font-size:12px;color:#d1d5db;"><i class="fas fa-file-alt" style="margin-right:6px;color:#FFB84D;"></i>' + escHtml(rec.transcriptContent) + '</div>' : ''}`;
                list.appendChild(item);
            });
        })
        .catch(() => {});
}

function srDeleteRecording(id, btn) {
    srShowConfirm('Delete Recording', 'Delete this recording?', function() {
        fetch(`/api/user/recordings/${id}`, { method: 'DELETE' })
            .then(r => r.json())
            .then(d => {
                if (d.success) {
                    btn.closest('.sr-rec-item').remove();
                    srCheckEmpty();
                    srToast('Recording deleted', 'success');
                } else {
                    srToast('Failed to delete', 'error');
                }
            })
            .catch(() => srToast('Error deleting recording', 'error'));
    });
}

function srCheckEmpty() {
    const items = document.querySelectorAll('#srRecordingsList .sr-rec-item');
    const empty = document.getElementById('srRecordingsEmpty');
    if (empty) empty.style.display = items.length === 0 ? '' : 'none';
}

function srSaveAllRecordings() {
    const links = document.querySelectorAll('#srRecordingsList .sr-rec-item-dl');
    if (links.length === 0) {
        srToast('No recordings to save', 'warning');
        return;
    }
    links.forEach((a, i) => {
        setTimeout(() => {
            const link = document.createElement('a');
            link.href = a.href;
            link.download = '';
            link.click();
        }, i * 400);
    });
    srToast('Downloading ' + links.length + ' recording(s)', 'success');
}

function srClearAllRecordings() {
    const btns = [...document.querySelectorAll('#srRecordingsList .sr-rec-item-del')];
    if (btns.length === 0) {
        srToast('No recordings to delete', 'warning');
        return;
    }
    srShowConfirm('Delete All', 'Delete ALL ' + btns.length + ' recordings? This cannot be undone.', function() {
        let count = 0;
        btns.forEach(btn => {
            const onclick = btn.getAttribute('onclick');
            const match = onclick.match(/\d+/);
            if (match) {
                const id = parseInt(match[0]);
                fetch(`/api/user/recordings/${id}`, { method: 'DELETE' })
                    .then(r => r.json())
                    .then(d => {
                        if (d.success) {
                            btn.closest('.sr-rec-item').remove();
                            count++;
                            if (count === btns.length) {
                                srCheckEmpty();
                                srToast('All recordings deleted', 'success');
                            }
                        }
                    });
            }
        });
    });
}

// ===== Simple Confirm Dialog =====
let _srConfirmCallback = null;

function srShowConfirm(title, msg, onYes) {
    document.getElementById('srConfirmTitle').textContent = title;
    document.getElementById('srConfirmMsg').textContent = msg;
    _srConfirmCallback = onYes;
    document.getElementById('srConfirmYes').onclick = function() {
        srCloseConfirm();
        if (_srConfirmCallback) _srConfirmCallback();
    };
    document.getElementById('srConfirmOverlay').style.display = 'block';
    document.getElementById('srConfirmModal').style.display = 'block';
}

function srCloseConfirm() {
    document.getElementById('srConfirmOverlay').style.display = 'none';
    document.getElementById('srConfirmModal').style.display = 'none';
    _srConfirmCallback = null;
}

// ===== Toast Notifications =====
function srToast(msg, type) {
    const container = document.getElementById('srToastContainer');
    if (!container) return;
    const colors = { success: '#22c55e', error: '#ef4444', warning: '#f59e0b', info: '#6366f1' };
    const icons = { success: 'fa-check-circle', error: 'fa-exclamation-circle', warning: 'fa-exclamation-triangle', info: 'fa-info-circle' };
    const toast = document.createElement('div');
    toast.style.cssText = 'display:flex;align-items:center;gap:10px;padding:12px 16px;background:#1e293b;border:1px solid #334155;border-radius:10px;color:#f1f5f9;font-size:13px;box-shadow:0 4px 20px rgba(0,0,0,0.3);animation:srToastIn 0.3s ease;';
    toast.innerHTML = '<i class="fas ' + (icons[type] || icons.info) + '" style="color:' + (colors[type] || colors.info) + ';"></i><span>' + msg + '</span>';
    container.appendChild(toast);
    setTimeout(() => { toast.style.opacity = '0'; toast.style.transition = 'opacity 0.3s'; setTimeout(() => toast.remove(), 300); }, 3000);
}

// ===== CHANGE PASSWORD MODAL =====
function srOpenChangePassword(e) {
    if (e) e.preventDefault();
    closeSettings();
    // Reset form
    ['pwdCurrent','pwdNew','pwdConfirm'].forEach(id => { document.getElementById(id).value = ''; });
    const fb = document.getElementById('pwdFeedback');
    fb.style.display = 'none';
    fb.className = 'sr-pwd-feedback';

    document.getElementById('srPasswordOverlay').classList.add('active');
    document.getElementById('srPasswordModal').classList.add('open');
}

function srCloseChangePassword() {
    document.getElementById('srPasswordOverlay').classList.remove('active');
    document.getElementById('srPasswordModal').classList.remove('open');
}

function srToggleEye(inputId, btn) {
    const input = document.getElementById(inputId);
    const icon  = btn.querySelector('i');
    if (input.type === 'password') {
        input.type   = 'text';
        icon.className = 'fas fa-eye-slash';
    } else {
        input.type   = 'password';
        icon.className = 'fas fa-eye';
    }
}

function srSubmitChangePassword() {
    const current  = document.getElementById('pwdCurrent').value.trim();
    const newPwd   = document.getElementById('pwdNew').value.trim();
    const confirm  = document.getElementById('pwdConfirm').value.trim();
    const fb       = document.getElementById('pwdFeedback');
    const submitBtn = document.querySelector('.sr-btn-submit');

    const showFb = (msg, isError) => {
        fb.textContent  = msg;
        fb.className    = 'sr-pwd-feedback ' + (isError ? 'error' : 'success');
        fb.style.display = 'block';
    };

    if (!current)          { showFb('Current password is required', true);  return; }
    if (newPwd.length < 6) { showFb('New password must be at least 6 characters', true); return; }
    if (newPwd !== confirm) { showFb('Passwords do not match', true); return; }

    submitBtn.disabled   = true;
    submitBtn.textContent = 'Changing...';

    fetch('/api/user/change-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword: current, newPassword: newPwd, confirmPassword: confirm })
    })
    .then(r => r.json())
    .then(d => {
        submitBtn.disabled   = false;
        submitBtn.textContent = 'Change Password';
        if (d.success) {
            showFb('Password changed successfully!', false);
            setTimeout(srCloseChangePassword, 1800);
        } else {
            showFb(d.message || 'Failed to change password', true);
        }
    })
    .catch(() => {
        submitBtn.disabled   = false;
        submitBtn.textContent = 'Change Password';
        showFb('Network error. Please try again.', true);
    });
}

// ===== THEMES =====
function srOpenThemes(e) {
    if (e) e.preventDefault();
    // Close the settings dropdown, open the themes panel
    document.getElementById('srSettingsDropdown').classList.remove('open');
    isSettingsOpen = false;
    isThemesOpen = !isThemesOpen;
    document.getElementById('srThemesPanel').classList.toggle('open', isThemesOpen);
}

function closeThemesPanel() {
    isThemesOpen = false;
    const p = document.getElementById('srThemesPanel');
    if (p) p.classList.remove('open');
}

function srApplyTheme(theme, silent) {
    document.body.setAttribute('data-theme', theme);
    if (!silent) localStorage.setItem('sr_theme', theme);

    // Update active state in themes panel
    document.querySelectorAll('.sr-theme-item').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.theme === theme);
    });

    if (!silent) closeThemesPanel();
}

// ===== Chat send / receive =====
function srSendChat() {
    const input = document.getElementById('srChatInput');
    const msg   = input.value.trim();
    if (!msg || !stompClient || !stompClient.connected) return;
    stompClient.send('/app/chat/' + MEETING_CODE, {}, JSON.stringify({ content: msg }));
    input.value = '';
}

function displayChatMessage(data) {
    const body = document.getElementById('srChatMessages');

    // Remove empty-state placeholder if present
    const empty = body.querySelector('.sr-chat-empty');
    if (empty) empty.remove();

    // Align messages to top when there are items
    body.style.justifyContent = 'flex-start';

    const time = data.timestamp || data.time || new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const div  = document.createElement('div');
    div.className = 'sr-chat-msg';
    div.innerHTML = `
        <div class="sr-chat-msg-header">
            <span class="sr-chat-sender">${escHtml(data.senderName || data.userName || 'Unknown')}</span>
            <span class="sr-chat-time">${time}</span>
        </div>
        <div class="sr-chat-msg-body">${escHtml(data.content || data.message || '')}</div>`;
    body.appendChild(div);
    body.scrollTop = body.scrollHeight;
}

function escHtml(t) {
    const d = document.createElement('div');
    d.textContent = t;
    return d.innerHTML;
}

// ===== Notification Toast (for schedule/reminder notifications in meeting) =====
function showSrNotifToast(title, message, type) {
    const typeColor = {
        'SCHEDULE_CREATED':  '#6366f1',
        'SCHEDULE_REMINDER': '#f59e0b',
        'MEETING_STARTED':   '#22c55e',
        'RECORDING_AVAILABLE': '#38bdf8',
        'TRANSCRIPT_READY':  '#a78bfa'
    }[type] || '#6366f1';

    // Add keyframe animation once
    if (!document.getElementById('srNotifToastKF')) {
        const s = document.createElement('style');
        s.id = 'srNotifToastKF';
        s.textContent = '@keyframes srSlideIn{from{transform:translateX(110%);opacity:0}to{transform:translateX(0);opacity:1}}';
        document.head.appendChild(s);
    }

    const toast = document.createElement('div');
    toast.style.cssText = `
        position:fixed;top:70px;right:18px;z-index:9999;
        background:#1e293b;border:1px solid ${typeColor};border-radius:12px;
        padding:13px 16px;max-width:300px;min-width:240px;
        box-shadow:0 8px 32px rgba(0,0,0,.5);
        animation:srSlideIn .3s ease;font-family:inherit;pointer-events:all;
    `;
    toast.innerHTML = `
        <div style="display:flex;gap:10px;align-items:flex-start;">
            <i class="fas fa-bell" style="color:${typeColor};margin-top:2px;flex-shrink:0;"></i>
            <div style="flex:1;">
                <div style="font-weight:700;font-size:13px;color:#f0f6fc;margin-bottom:3px;">${escHtml(title || 'Notification')}</div>
                <div style="font-size:12px;color:#8b949e;line-height:1.4;">${escHtml(message || '')}</div>
            </div>
            <button onclick="this.closest('div[style]').remove()" style="background:none;border:none;color:#8b949e;cursor:pointer;padding:0;font-size:14px;flex-shrink:0;">✕</button>
        </div>`;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.transition = 'opacity .4s';
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 400);
    }, 6000);
}

// ===== Inline audio play/pause =====
function srTogglePlay(audioId, btn) {
    const audio = document.getElementById(audioId);
    if (!audio) return;
    // Pause all other playing audios
    document.querySelectorAll('audio').forEach(a => {
        if (a.id !== audioId && !a.paused) { a.pause(); a.currentTime = 0; }
    });
    document.querySelectorAll('.sr-rec-item-play').forEach(b => {
        const icon = b.querySelector('i');
        if (icon) icon.className = 'fas fa-play';
        b.querySelector('i').nextSibling && (b.lastChild.textContent = b.lastChild.textContent.replace('Pause','Play'));
    });
    if (audio.paused) {
        audio.play();
        const icon = btn.querySelector('i');
        if (icon) icon.className = 'fas fa-pause';
    } else {
        audio.pause();
        const icon = btn.querySelector('i');
        if (icon) icon.className = 'fas fa-play';
    }
}
function srResetPlayBtn(audioId) {
    const audio = document.getElementById(audioId);
    if (audio) audio.currentTime = 0;
    // Find the play button for this audio
    const item = audio ? audio.closest('.sr-rec-item') : null;
    if (item) {
        const btn = item.querySelector('.sr-rec-item-play');
        if (btn) { const i = btn.querySelector('i'); if (i) i.className = 'fas fa-play'; }
    }
}

// ===== Speech-to-Text Transcript Generation (Browser Web Speech API) =====
let _speechRecognition  = null;
let _transcriptText     = '';
let _interimText        = '';
let _sttRunning         = false;   // true while recognition.start() is active
let _sttWatchdog        = null;    // setInterval handle — restarts STT if it dies unexpectedly

// ── Student-side live transcript indicator ───────────────────────────────────
// Shows a subtle pill at the bottom of the screen so the student (and tester)
// can see that speech recognition is actively capturing speech.

// ── STT watchdog: restart recognition if it silently dies ────────────────────
function _startSttWatchdog() {
    clearInterval(_sttWatchdog);
    _sttWatchdog = setInterval(() => {
        if (!_isMeetingActive || !isMicOn) { clearInterval(_sttWatchdog); return; }
        if (!_sttRunning && _speechRecognition) {
            console.log('[STT Watchdog] restarting dead recognition');
            _srUpdateSttStatus('Watchdog restart…', '#f59e0b');
            try { _speechRecognition.start(); } catch(e) {}
        }
    }, 5000);  // check every 5 s
}
function _stopSttWatchdog() {
    clearInterval(_sttWatchdog);
    _sttWatchdog = null;
}

function _srEnsureLiveBanner() {
    let b = document.getElementById('_srLiveBanner');
    if (b) return b;
    b = document.createElement('div');
    b.id = '_srLiveBanner';
    b.style.cssText =
        'position:fixed;bottom:16px;left:50%;transform:translateX(-50%);z-index:9000;' +
        'background:rgba(15,23,42,0.92);border:1px solid #334155;border-radius:24px;' +
        'padding:8px 18px;font-size:12px;color:#94a3b8;display:flex;align-items:center;gap:10px;' +
        'max-width:90vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;' +
        'box-shadow:0 4px 20px rgba(0,0,0,0.5);';
    b.innerHTML =
        '<span id="_srSttDot" style="width:8px;height:8px;border-radius:50%;background:#22c55e;flex-shrink:0;"></span>' +
        '<span id="_srSttStatus" style="flex-shrink:0;">STT</span>' +
        '<span style="color:#334155;">|</span>' +
        '<span id="_srLiveText" style="color:#e2e8f0;font-style:italic;max-width:55vw;overflow:hidden;text-overflow:ellipsis;">…</span>';
    document.body.appendChild(b);
    return b;
}
function _srUpdateSttStatus(msg, color) {
    _srEnsureLiveBanner();
    const dot  = document.getElementById('_srSttDot');
    const span = document.getElementById('_srSttStatus');
    if (dot)  dot.style.background  = color || '#22c55e';
    if (span) span.textContent = msg || '';
}
function _srShowInterim(text) {
    _srEnsureLiveBanner();
    const el = document.getElementById('_srLiveText');
    if (el) { el.style.opacity = '0.5'; el.textContent = text || '…'; }
}
function _srShowLiveCapture(text) {
    _srEnsureLiveBanner();
    const el = document.getElementById('_srLiveText');
    if (el) {
        el.style.opacity = '1';
        el.textContent   = '✔ ' + text;
        // Fade back after 4 s
        clearTimeout(el._fadeTimer);
        el._fadeTimer = setTimeout(() => { if (el) { el.style.opacity = '0.5'; el.textContent = '…'; } }, 4000);
    }
}

function _initSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
        console.warn('SpeechRecognition API not available in this browser');
        return;
    }
    _speechRecognition = new SpeechRecognition();
    _speechRecognition.continuous = true;
    _speechRecognition.interimResults = true;
    _speechRecognition.lang = 'en-US';
    _speechRecognition.maxAlternatives = 1;
    _speechRecognition.onresult = (event) => {
        _interimText = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
            if (event.results[i].isFinal) {
                const sentence = event.results[i][0].transcript.trim();
                if (!sentence) continue;
                _transcriptText += sentence + ' ';

                // Show a live preview on student's own screen
                _srShowLiveCapture(sentence);

                // Always send — isTeacher: false so server creates bundled card, not teacher card
                const payload = JSON.stringify({
                    text:        sentence,
                    speakerName: USER_NAME,
                    isTeacher:   false,
                    timestamp:   new Date().toISOString()
                });
                if (stompClient && stompClient.connected) {
                    stompClient.send('/app/transcript/' + MEETING_CODE, {}, payload);
                    console.log('[STT] Sent transcript:', sentence);
                } else {
                    _pendingTranscripts.push(payload);
                    console.log('[STT] Buffered transcript (WS not ready):', sentence);
                }
            } else {
                _interimText += event.results[i][0].transcript;
                // Show interim text as hint (greyed out) on student screen
                _srShowInterim(_interimText);
            }
        }
    };
    _speechRecognition.onerror = (e) => {
        console.warn('[STT Student] error:', e.error);
        _srUpdateSttStatus('error: ' + e.error, '#ef4444');
        _sttRunning = false; // onend will fire after this and restart
    };
    _speechRecognition.onstart = () => {
        _sttRunning = true;
        _srUpdateSttStatus('Listening…', '#22c55e');
        console.log('[STT] Recognition started');
    };
    _speechRecognition.onend = () => {
        _sttRunning = false;
        // Restart as long as meeting is active and mic is on
        if (_isMeetingActive && isMicOn && _speechRecognition) {
            _srUpdateSttStatus('Restarting…', '#f59e0b');
            try { _speechRecognition.start(); } catch(e) {
                console.warn('[STT] restart error:', e.message);
            }
        } else {
            _srUpdateSttStatus('Paused', '#6b7280');
        }
    };
}

function _startTranscription() {
    if (!_speechRecognition) _initSpeechRecognition();
    if (!_speechRecognition) return;
    _transcriptText = '';
    _interimText = '';
    try {
        _speechRecognition.start();
        console.log('Speech recognition started');
    } catch(e) {
        console.warn('Speech start error:', e);
        // If already started, that's OK
    }
}

// Stop recognition (async — results may still arrive after this call)
function _stopTranscriptionAsync() {
    if (_speechRecognition) {
        try { _speechRecognition.stop(); } catch(e) {}
    }
}

// Read the accumulated transcript text (call after a delay from stop)
function _getTranscriptResult() {
    const result = (_transcriptText + _interimText).trim();
    _transcriptText = '';
    _interimText = '';
    return result;
}

// Legacy sync version (kept for compatibility)
function _stopTranscription() {
    _stopTranscriptionAsync();
    return _getTranscriptResult();
}

// ===== Cleanup on page leave =====
window.addEventListener('beforeunload', function() {
    _isMeetingActive      = false;
    _isTranscribingActive = false;
    _isRecordingActive    = false;
    _stopSttWatchdog();
    clearTimeout(_wsReconnectTimer);
    if (_speechRecognition) { try { _speechRecognition.stop(); } catch(e) {} }
    if (mediaRecorder && mediaRecorder.state !== 'inactive') { try { mediaRecorder.stop(); } catch(e) {} }
    if (stompClient && stompClient.connected) {
        sendParticipantEvent('leave');
        stompClient.disconnect();
    }
    Object.values(peerConnections).forEach(function(pc) { try { pc.close(); } catch(e) {} });
    if (localStream) localStream.getTracks().forEach(function(t) { t.stop(); });
    if (_recAudioCtx) { try { _recAudioCtx.close(); } catch(e) {} _recAudioCtx = null; _recDest = null; }
});

