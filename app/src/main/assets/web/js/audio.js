/**
 * Castla - Audio Player
 * Receives AAC audio frames via WebSocket and plays through Web Audio API.
 * Uses AudioContext.decodeAudioData for AAC frame decoding.
 */
class AudioPlayer {
    constructor() {
        this.context = null;
        this.nextPlayTime = 0;
        this.isPlaying = false;
        this.frameDuration = 1024 / 44100; // AAC-LC frame duration (~23.2ms)
        this.bufferedFrames = 0;
        this.BUFFER_TARGET = 3; // buffer 3 frames before starting playback
    }

    init() {
        try {
            this.context = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: 44100,
                latencyHint: 'interactive'
            });
            this.nextPlayTime = 0;
            this.isPlaying = false;
            console.log('[Audio] AudioContext initialized, state:', this.context.state);
            return true;
        } catch (e) {
            console.error('[Audio] Failed to create AudioContext:', e);
            return false;
        }
    }

    async resume() {
        if (this.context && this.context.state === 'suspended') {
            await this.context.resume();
            console.log('[Audio] AudioContext resumed');
        }
    }

    /**
     * Feed raw AAC frame data received from WebSocket.
     * Wraps in ADTS header for decodeAudioData compatibility.
     */
    async feed(aacData) {
        if (!this.context || this.context.state === 'closed') return;

        try {
            // Wrap raw AAC in ADTS header so decodeAudioData can parse it
            const adtsFrame = this._wrapADTS(aacData);
            const audioBuffer = await this.context.decodeAudioData(adtsFrame.buffer);
            this._scheduleBuffer(audioBuffer);
        } catch (e) {
            // decodeAudioData may fail on partial/corrupt frames — just skip
            // console.warn('[Audio] Decode error (skipping frame):', e.message);
        }
    }

    _scheduleBuffer(audioBuffer) {
        const source = this.context.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(this.context.destination);

        const now = this.context.currentTime;

        if (!this.isPlaying) {
            this.bufferedFrames++;
            if (this.bufferedFrames >= this.BUFFER_TARGET) {
                this.nextPlayTime = now + 0.05; // 50ms initial buffer
                this.isPlaying = true;
            } else {
                return; // still buffering
            }
        }

        // If we've fallen behind, jump ahead
        if (this.nextPlayTime < now) {
            this.nextPlayTime = now + 0.01;
        }

        source.start(this.nextPlayTime);
        this.nextPlayTime += audioBuffer.duration;
    }

    /**
     * Wrap raw AAC-LC frame in ADTS header (7 bytes).
     * Required for decodeAudioData which expects a complete audio container.
     */
    _wrapADTS(rawAAC) {
        const frameLength = rawAAC.length + 7;
        const adts = new Uint8Array(frameLength);

        // ADTS fixed header
        adts[0] = 0xFF; // sync word
        adts[1] = 0xF1; // sync word + MPEG-4, Layer 0, no CRC
        // Profile (AAC-LC=1, so objectType-1=1), sampling freq index (44100=4), private=0, channel config=2
        adts[2] = ((1 << 6) | (4 << 2) | (0 << 1) | ((2 >> 2) & 0x01));
        adts[3] = ((2 & 0x03) << 6) | ((frameLength >> 11) & 0x03);
        adts[4] = (frameLength >> 3) & 0xFF;
        adts[5] = ((frameLength & 0x07) << 5) | 0x1F;
        adts[6] = 0xFC; // buffer fullness VBR + 0 AAC frames in ADTS - 1

        adts.set(rawAAC instanceof Uint8Array ? rawAAC : new Uint8Array(rawAAC), 7);
        return adts;
    }

    stop() {
        if (this.context && this.context.state !== 'closed') {
            this.context.close().catch(() => {});
        }
        this.context = null;
        this.isPlaying = false;
        this.bufferedFrames = 0;
        this.nextPlayTime = 0;
        console.log('[Audio] Stopped');
    }

    static isSupported() {
        return !!(window.AudioContext || window.webkitAudioContext);
    }
}
