/**
 * WebCodecs H.264 Decoder
 * Decodes raw H.264 NAL units using hardware-accelerated VideoDecoder
 */
class H264Decoder {
    constructor(onFrame, onError) {
        this.onFrame = onFrame;
        this.onError = onError;
        this.decoder = null;
        this.configured = false;
        this.frameCount = 0;
        this.startTime = 0;
        this.codecString = null; // dynamically detected from SPS
    }

    static isSupported() {
        return typeof VideoDecoder !== 'undefined';
    }

    async init() {
        if (!H264Decoder.isSupported()) {
            throw new Error('WebCodecs VideoDecoder not available');
        }

        // Check H.264 support — try High Profile first (better compression),
        // then Baseline as fallback. Actual codec string will be updated from SPS.
        const codecs = ['avc1.640028', 'avc1.42001e'];
        let supportedCodec = null;
        for (const codec of codecs) {
            const support = await VideoDecoder.isConfigSupported({
                codec,
                optimizeForLatency: true,
                hardwareAcceleration: 'prefer-hardware'
            });
            if (support.supported) {
                supportedCodec = codec;
                break;
            }
        }

        if (!supportedCodec) {
            throw new Error('H.264 not supported');
        }

        this.codecString = supportedCodec;

        this.decoder = new VideoDecoder({
            output: (frame) => {
                this.frameCount++;
                this.onFrame(frame);
            },
            error: (e) => {
                console.error('[Decoder] Error:', e);
                this.onError(e);
            }
        });

        this.decoder.configure({
            codec: supportedCodec,
            optimizeForLatency: true,
            hardwareAcceleration: 'prefer-hardware'
        });

        this.configured = true;
        this.startTime = performance.now();
        console.log('[Decoder] Initialized with WebCodecs, codec:', supportedCodec);
    }

    /**
     * Decode a frame received from WebSocket
     * @param {ArrayBuffer} data - 8-byte header + H.264 NAL units
     *   header: [flags:u8][seqLo:u8][seqHi:u8][tsMs0:u8][tsMs1:u8][tsMs2:u8][tsMs3:u8][reserved:u8]
     *   flags: 0x00=delta, 0x01=keyframe, 0x02=codec config (SPS/PPS)
     */
    decode(data) {
        if (!this.configured || !this.decoder || this.decoder.state === 'closed') {
            return;
        }

        const view = new DataView(data);
        if (data.byteLength < 9) return;

        const flags = view.getUint8(0);
        const seqNum = view.getUint16(1, true);  // LE
        const serverTsMs = view.getUint32(3, true);  // LE

        // 0x02 = SPS/PPS config — cache and detect codec, don't decode
        if (flags === 0x02) {
            this._cachedSpsPps = data.slice(8);
            this._detectCodecFromSps(new Uint8Array(this._cachedSpsPps));
            return;
        }

        const isKeyFrame = flags === 0x01;
        const nalData = data.slice(8); // Remove 8-byte header

        // Detect frame drops via sequence gap
        if (this._lastSeqNum !== undefined) {
            const expected = (this._lastSeqNum + 1) & 0xFFFF;
            if (seqNum !== expected) {
                console.warn('[Decoder] Frame gap: expected', expected, 'got', seqNum);
                this.onError(new Error('frame gap'));
            }
        }
        this._lastSeqNum = seqNum;

        // On keyframes, prepend cached SPS/PPS for decoder
        let frameData = nalData;
        if (isKeyFrame && this._cachedSpsPps) {
            const spsPps = new Uint8Array(this._cachedSpsPps);
            const combined = new Uint8Array(spsPps.length + nalData.byteLength);
            combined.set(spsPps);
            combined.set(new Uint8Array(nalData), spsPps.length);
            frameData = combined.buffer;
        }

        try {
            const chunk = new EncodedVideoChunk({
                type: isKeyFrame ? 'key' : 'delta',
                timestamp: serverTsMs * 1000,  // server timestamp in microseconds
                data: frameData
            });

            if (this.decoder.decodeQueueSize > 3) {
                console.warn('[Decoder] Queue backing up:', this.decoder.decodeQueueSize);
                return;
            }

            this.decoder.decode(chunk);
        } catch (e) {
            console.error('[Decoder] Decode error:', e);
            this.onError(e);
        }
    }

    /**
     * Parse SPS NAL from keyframe to detect actual codec string (avc1.XXYYZZ).
     * Reconfigure decoder if profile changed (e.g. High ↔ Baseline fallback).
     */
    _detectCodecFromSps(nalData) {
        // Find SPS NAL unit (type 7) after start code 0x00000001
        for (let i = 0; i < nalData.length - 7; i++) {
            if (nalData[i] === 0 && nalData[i+1] === 0 && nalData[i+2] === 0 && nalData[i+3] === 1) {
                const nalType = nalData[i+4] & 0x1F;
                if (nalType === 7 && i + 7 < nalData.length) {
                    const profile = nalData[i+5];
                    const compat = nalData[i+6];
                    const level = nalData[i+7];
                    const newCodec = 'avc1.' +
                        profile.toString(16).padStart(2, '0') +
                        compat.toString(16).padStart(2, '0') +
                        level.toString(16).padStart(2, '0');
                    if (newCodec !== this.codecString) {
                        console.log('[Decoder] Codec changed:', this.codecString, '->', newCodec);
                        this.codecString = newCodec;
                        try {
                            this.decoder.configure({
                                codec: newCodec,
                                optimizeForLatency: true,
                                hardwareAcceleration: 'prefer-hardware'
                            });
                        } catch (e) {
                            console.warn('[Decoder] Reconfigure failed for', newCodec, e);
                        }
                    }
                    return;
                }
            }
        }
    }

    getFps() {
        const elapsed = (performance.now() - this.startTime) / 1000;
        if (elapsed < 1) return 0;
        return Math.round(this.frameCount / elapsed);
    }

    resetStats() {
        this.frameCount = 0;
        this.startTime = performance.now();
    }

    destroy() {
        if (this.decoder && this.decoder.state !== 'closed') {
            try {
                this.decoder.close();
            } catch (_) {}
        }
        this.decoder = null;
        this.configured = false;
    }
}
