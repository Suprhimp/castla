/**
 * MSE H.264 Decoder
 * Uses Media Source Extensions to play H.264 via the browser's built-in decoder.
 * Works over HTTP (unlike WebCodecs which requires secure context).
 * Wraps raw H.264 NAL units in fMP4 (fragmented MP4) segments.
 */
class MseDecoder {
    constructor(videoEl, onError) {
        this.video = videoEl;
        this.onError = onError;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this.appending = false;
        this.initSegmentSent = false;
        this.frameCount = 0;
        this.startTime = 0;
        this.sequenceNumber = 0;
        this.baseDecodeTime = 0;
        this.frameDuration = 3000; // ~33ms in 90kHz timescale
        this.sps = null;
        this.pps = null;
        this.width = 0;
        this.height = 0;
        this.codecString = 'avc1.42001e'; // default, updated from SPS
        this.ready = false; // true after sourceopen + init segment appended successfully
    }

    static isSupported() {
        return typeof MediaSource !== 'undefined' &&
            MediaSource.isTypeSupported('video/mp4; codecs="avc1.42001e"');
    }

    init() {
        return new Promise((resolve, reject) => {
            this.mediaSource = new MediaSource();

            // Listen for video element errors
            this.video.addEventListener('error', (e) => {
                const err = this.video.error;
                console.error('[MSE] Video element error:', err ? `code=${err.code} message=${err.message}` : e);
            });

            this.mediaSource.addEventListener('sourceopen', () => {
                try {
                    this.startTime = performance.now();
                    console.log('[MSE] MediaSource opened, waiting for SPS/PPS');
                    resolve();
                } catch (e) {
                    reject(e);
                }
            });

            this.mediaSource.addEventListener('sourceclose', () => {
                console.log('[MSE] MediaSource closed');
                this.ready = false;
                // Don't access this.mediaSource here — it may have been nulled by destroy()
            });

            this.mediaSource.addEventListener('sourceended', () => {
                console.log('[MSE] MediaSource ended');
            });

            // Set src AFTER event listeners are attached
            this.video.src = URL.createObjectURL(this.mediaSource);

            // Auto-play with low latency
            this.video.muted = true;
            this.video.autoplay = true;
            this.video.playsInline = true;
        });
    }

    /**
     * Decode a frame from the WebSocket.
     * @param {ArrayBuffer} data - 1-byte header (0x01=key, 0x00=delta) + H.264 NAL units
     */
    decode(data) {
        const view = new Uint8Array(data);
        if (view.length < 2) return;

        const isKeyFrame = view[0] === 0x01;
        const nalData = new Uint8Array(data, 1);

        if (isKeyFrame) {
            const oldWidth = this.width;
            const oldHeight = this.height;
            this._extractSpsPps(nalData);

            // Detect resolution change from SPS — reinit everything
            if (this.initSegmentSent && this.sps && this.pps &&
                (this.width !== oldWidth || this.height !== oldHeight)) {
                console.log('[MSE] Resolution changed: ' + oldWidth + 'x' + oldHeight +
                    ' -> ' + this.width + 'x' + this.height + ', reinitializing');
                this._reinit(data);
                return;
            }
        }

        if (!this.mediaSource || this.mediaSource.readyState !== 'open') {
            // Queue frame if we're in the middle of reinit
            if (this._pendingReinit && isKeyFrame) {
                this._pendingKeyframe = data;
            }
            return;
        }

        // Create SourceBuffer and send init segment once we have SPS/PPS
        if (!this.initSegmentSent && this.sps && this.pps) {
            if (!this._createSourceBuffer()) return;
            this._sendInitSegment();
        }

        if (!this.initSegmentSent) return;

        // Wrap NAL units in moof+mdat
        const segment = this._createMediaSegment(nalData, isKeyFrame);
        if (segment.length > 0) {
            this._appendBuffer(segment);
            this.frameCount++;
        }

        // Keep playback near live edge
        this._maintainLiveEdge();
    }

    /**
     * Reinitialize MSE pipeline with new resolution.
     * Tears down old MediaSource and creates a fresh one.
     */
    _reinit(keyframeData) {
        this._pendingReinit = true;
        this._pendingKeyframe = keyframeData;

        // Tear down old pipeline
        const oldMs = this.mediaSource;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this.appending = false;
        this.initSegmentSent = false;
        this.ready = false;
        this.sequenceNumber = 0;
        this.baseDecodeTime = 0;

        if (oldMs) {
            try {
                if (oldMs.readyState === 'open') oldMs.endOfStream();
            } catch (_) {}
        }

        // Create new MediaSource
        this.mediaSource = new MediaSource();

        this.mediaSource.addEventListener('sourceopen', () => {
            console.log('[MSE] Reinit: MediaSource opened');
            this._pendingReinit = false;

            // Process the pending keyframe
            if (this._pendingKeyframe) {
                const kf = this._pendingKeyframe;
                this._pendingKeyframe = null;
                const kfNalData = new Uint8Array(kf, 1);

                if (!this._createSourceBuffer()) return;
                this._sendInitSegment();

                const segment = this._createMediaSegment(kfNalData, true);
                if (segment.length > 0) {
                    this._appendBuffer(segment);
                    this.frameCount++;
                }
            }
        });

        this.mediaSource.addEventListener('sourceclose', () => {
            this.ready = false;
        });

        // Revoke old URL and set new one
        if (this.video.src) {
            try { URL.revokeObjectURL(this.video.src); } catch (_) {}
        }
        this.video.src = URL.createObjectURL(this.mediaSource);
        this.video.muted = true;
        this.video.autoplay = true;
        this.video.playsInline = true;
    }

    _createSourceBuffer() {
        if (this.sourceBuffer) return true;
        if (!this.mediaSource || this.mediaSource.readyState !== 'open') return false;

        try {
            // Build codec string from actual SPS bytes
            const codec = 'video/mp4; codecs="' + this.codecString + '"';
            console.log('[MSE] Creating SourceBuffer with codec:', codec);

            this.sourceBuffer = this.mediaSource.addSourceBuffer(codec);
            this.sourceBuffer.addEventListener('updateend', () => {
                this.appending = false;
                // Mark ready only after init segment is processed
                if (!this.ready && this.initSegmentSent) {
                    this.ready = true;
                    console.log('[MSE] Init segment processed, ready for media segments');
                }
                this._flushQueue();
            });
            this.sourceBuffer.addEventListener('error', (e) => {
                console.error('[MSE] SourceBuffer error event:', e);
            });
            console.log('[MSE] SourceBuffer created');
            return true;
        } catch (e) {
            console.error('[MSE] Failed to create SourceBuffer:', e);
            return false;
        }
    }

    _extractSpsPps(data) {
        const nals = this._parseNalUnits(data);
        for (const nal of nals) {
            const type = nal[0] & 0x1F;
            if (type === 7) { // SPS
                this.sps = new Uint8Array(nal); // copy, not view
                this._parseSps(this.sps);
                // Build codec string from SPS: avc1.XXYYZZ
                const profile = this.sps[1];
                const compat = this.sps[2];
                const level = this.sps[3];
                this.codecString = 'avc1.' +
                    profile.toString(16).padStart(2, '0') +
                    compat.toString(16).padStart(2, '0') +
                    level.toString(16).padStart(2, '0');
                console.log('[MSE] SPS found: codec=' + this.codecString +
                    ' size=' + this.sps.length + ' w=' + this.width + ' h=' + this.height);
            } else if (type === 8) { // PPS
                this.pps = new Uint8Array(nal); // copy, not view
                console.log('[MSE] PPS found: size=' + this.pps.length);
            }
        }
    }

    _parseSps(sps) {
        // Minimal SPS parsing for width/height using Exp-Golomb decoding
        if (sps.length < 4) return;

        try {
            const reader = new BitReader(sps);
            reader.skip(8); // NAL header
            const profileIdc = reader.readBits(8);
            reader.skip(8); // constraint flags + reserved
            reader.skip(8); // level_idc

            reader.readUEG(); // seq_parameter_set_id

            if (profileIdc === 100 || profileIdc === 110 || profileIdc === 122 ||
                profileIdc === 244 || profileIdc === 44 || profileIdc === 83 ||
                profileIdc === 86 || profileIdc === 118 || profileIdc === 128) {
                const chromaFormatIdc = reader.readUEG();
                if (chromaFormatIdc === 3) reader.skip(1); // separate_colour_plane_flag
                reader.readUEG(); // bit_depth_luma_minus8
                reader.readUEG(); // bit_depth_chroma_minus8
                reader.skip(1);   // qpprime_y_zero_transform_bypass_flag
                const seqScalingMatrixPresent = reader.readBits(1);
                if (seqScalingMatrixPresent) {
                    const count = chromaFormatIdc !== 3 ? 8 : 12;
                    for (let i = 0; i < count; i++) {
                        if (reader.readBits(1)) { // scaling_list_present
                            const size = i < 6 ? 16 : 64;
                            let last = 8, next = 8;
                            for (let j = 0; j < size; j++) {
                                if (next !== 0) {
                                    const delta = reader.readSEG();
                                    next = (last + delta + 256) % 256;
                                }
                                last = next === 0 ? last : next;
                            }
                        }
                    }
                }
            }

            reader.readUEG(); // log2_max_frame_num_minus4
            const picOrderCntType = reader.readUEG();
            if (picOrderCntType === 0) {
                reader.readUEG(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType === 1) {
                reader.skip(1);   // delta_pic_order_always_zero_flag
                reader.readSEG(); // offset_for_non_ref_pic
                reader.readSEG(); // offset_for_top_to_bottom_field
                const n = reader.readUEG();
                for (let i = 0; i < n; i++) reader.readSEG();
            }

            reader.readUEG(); // max_num_ref_frames
            reader.skip(1);   // gaps_in_frame_num_value_allowed_flag

            const picWidthInMbsMinus1 = reader.readUEG();
            const picHeightInMapUnitsMinus1 = reader.readUEG();
            const frameMbsOnlyFlag = reader.readBits(1);
            if (!frameMbsOnlyFlag) reader.skip(1); // mb_adaptive_frame_field_flag

            reader.skip(1); // direct_8x8_inference_flag

            let cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            const frameCroppingFlag = reader.readBits(1);
            if (frameCroppingFlag) {
                cropLeft = reader.readUEG();
                cropRight = reader.readUEG();
                cropTop = reader.readUEG();
                cropBottom = reader.readUEG();
            }

            this.width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2;
            this.height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1)) * 16 -
                (cropTop + cropBottom) * 2;
        } catch (e) {
            console.warn('[MSE] SPS parse error, using defaults:', e);
            this.width = this.width || 1080;
            this.height = this.height || 1080;
        }
    }

    _parseNalUnits(data) {
        const nals = [];
        let i = 0;
        while (i < data.length) {
            let scLen = 0;
            if (i + 3 < data.length && data[i] === 0 && data[i+1] === 0) {
                if (i + 3 < data.length && data[i+2] === 0 && i + 4 <= data.length && data[i+3] === 1) scLen = 4;
                else if (data[i+2] === 1) scLen = 3;
            }
            if (scLen === 0) { i++; continue; }

            const nalStart = i + scLen;
            let nalEnd = data.length;
            for (let j = nalStart + 1; j < data.length - 2; j++) {
                if (data[j] === 0 && data[j+1] === 0 &&
                    (data[j+2] === 1 || (data[j+2] === 0 && j+3 < data.length && data[j+3] === 1))) {
                    nalEnd = j;
                    break;
                }
            }
            if (nalEnd > nalStart) {
                nals.push(data.subarray(nalStart, nalEnd));
            }
            i = nalEnd;
        }
        return nals;
    }

    _sendInitSegment() {
        if (!this.sps || !this.pps) return;
        if (!this.sourceBuffer) return;
        if (this.mediaSource.readyState !== 'open') return;

        const initSeg = this._createInitSegment(this.sps, this.pps);
        console.log('[MSE] Sending init segment (' + initSeg.length + ' bytes), ' +
            'codec=' + this.codecString + ', ' + this.width + 'x' + this.height);
        this._appendBuffer(initSeg);
        this.initSegmentSent = true;
        // ready=true is set in updateend handler after init segment is processed
    }

    /**
     * Create fMP4 init segment (ftyp + moov).
     */
    _createInitSegment(sps, pps) {
        const width = this.width || 1080;
        const height = this.height || 1080;

        const ftyp = this._box('ftyp',
            this._str('isom'),
            this._u32(0x200),
            this._str('isom'),
            this._str('iso2'),
            this._str('avc1'),
            this._str('mp41')
        );

        // avcC box
        const avcC = this._box('avcC',
            new Uint8Array([
                1,            // configurationVersion
                sps[1],       // AVCProfileIndication
                sps[2],       // profile_compatibility
                sps[3],       // AVCLevelIndication
                0xFF,         // lengthSizeMinusOne = 3 (4-byte NAL length)
                0xE1,         // numOfSequenceParameterSets = 1
                (sps.length >> 8) & 0xFF, sps.length & 0xFF,
                ...sps,
                1,            // numOfPictureParameterSets = 1
                (pps.length >> 8) & 0xFF, pps.length & 0xFF,
                ...pps
            ])
        );

        // VisualSampleEntry (avc1)
        const avc1 = this._box('avc1',
            new Uint8Array(6),     // reserved
            this._u16(1),          // data_reference_index
            new Uint8Array(16),    // pre_defined + reserved + pre_defined
            this._u16(width),      // width
            this._u16(height),     // height
            this._u32(0x00480000), // horizresolution 72dpi
            this._u32(0x00480000), // vertresolution 72dpi
            this._u32(0),          // reserved
            this._u16(1),          // frame_count
            new Uint8Array(32),    // compressorname
            this._u16(0x0018),     // depth
            this._i16(-1),         // pre_defined
            avcC
        );

        const stbl = this._box('stbl',
            this._fullbox('stsd', 0, 0, this._u32(1), avc1),
            this._fullbox('stts', 0, 0, this._u32(0)),
            this._fullbox('stsc', 0, 0, this._u32(0)),
            this._fullbox('stsz', 0, 0, this._u32(0), this._u32(0)),
            this._fullbox('stco', 0, 0, this._u32(0))
        );

        const dinf = this._box('dinf',
            this._fullbox('dref', 0, 0, this._u32(1),
                this._fullbox('url ', 0, 1)
            )
        );

        const minf = this._box('minf',
            this._fullbox('vmhd', 0, 1, new Uint8Array(8)),
            dinf,
            stbl
        );

        const mdia = this._box('mdia',
            this._fullbox('mdhd', 0, 0,
                this._u32(0), this._u32(0),   // creation/modification time
                this._u32(90000),              // timescale (90kHz)
                this._u32(0),                  // duration
                this._u16(0x55C4),             // language = 'und'
                this._u16(0)                   // pre_defined
            ),
            this._fullbox('hdlr', 0, 0,
                this._u32(0),                  // pre_defined
                this._str('vide'),             // handler_type
                new Uint8Array(12),            // reserved
                this._str('VideoHandler'), new Uint8Array(1)
            ),
            minf
        );

        const trak = this._box('trak',
            this._fullbox('tkhd', 0, 3,
                this._u32(0), this._u32(0),     // creation/modification time
                this._u32(1),                   // track_ID
                this._u32(0),                   // reserved
                this._u32(0),                   // duration
                new Uint8Array(8),              // reserved
                this._u16(0),                   // layer
                this._u16(0),                   // alternate_group
                this._u16(0),                   // volume
                new Uint8Array(2),              // reserved
                this._matrix(),                 // matrix
                this._u32(width << 16),         // width (fixed-point 16.16)
                this._u32(height << 16)         // height (fixed-point 16.16)
            ),
            mdia
        );

        const mvex = this._box('mvex',
            this._fullbox('trex', 0, 0,
                this._u32(1),    // track_ID
                this._u32(1),    // default_sample_description_index
                this._u32(0),    // default_sample_duration
                this._u32(0),    // default_sample_size
                this._u32(0)     // default_sample_flags
            )
        );

        const moov = this._box('moov',
            this._fullbox('mvhd', 0, 0,
                this._u32(0), this._u32(0),   // creation/modification time
                this._u32(90000),              // timescale
                this._u32(0),                  // duration
                this._u32(0x00010000),         // rate = 1.0
                this._u16(0x0100),             // volume = 1.0
                new Uint8Array(10),            // reserved
                this._matrix(),                // matrix
                new Uint8Array(24),            // pre_defined
                this._u32(2)                   // next_track_ID
            ),
            trak,
            mvex
        );

        return this._concat(ftyp, moov);
    }

    /**
     * Create fMP4 media segment (moof + mdat) wrapping NAL units.
     */
    _createMediaSegment(nalData, isKeyFrame) {
        const avccData = this._annexBToAvcc(nalData);
        if (avccData.length === 0) return new Uint8Array(0);

        // sample_flags: depends_on / is_depended_on / has_redundancy / is_non_sync
        const flags = isKeyFrame ? 0x02000000 : 0x01010000;

        const tfhd = this._fullbox('tfhd', 0, 0x020000, // default-base-is-moof
            this._u32(1)  // track_ID
        );

        const tfdt = this._fullbox('tfdt', 1, 0,
            this._u64(this.baseDecodeTime)
        );

        // trun flags: 0x001 data-offset + 0x100 duration + 0x200 size + 0x400 sample-flags
        const trun = this._fullbox('trun', 0, 0x000701,
            this._u32(1),                        // sample_count
            this._u32(0),                        // data_offset (patched below)
            this._u32(this.frameDuration),        // sample_duration
            this._u32(avccData.length),           // sample_size
            this._u32(flags)                      // sample_flags
        );

        const moof = this._box('moof',
            this._fullbox('mfhd', 0, 0, this._u32(++this.sequenceNumber)),
            this._box('traf', tfhd, tfdt, trun)
        );

        const mdat = this._box('mdat', avccData);

        const segment = this._concat(moof, mdat);

        // Patch data_offset: offset from moof start to mdat payload
        const dataOffset = moof.length + 8; // 8 = mdat box header
        const trunOffset = this._findTrunDataOffset(segment);
        if (trunOffset >= 0) {
            segment[trunOffset]   = (dataOffset >> 24) & 0xFF;
            segment[trunOffset+1] = (dataOffset >> 16) & 0xFF;
            segment[trunOffset+2] = (dataOffset >> 8) & 0xFF;
            segment[trunOffset+3] = dataOffset & 0xFF;
        }

        this.baseDecodeTime += this.frameDuration;
        return segment;
    }

    _findTrunDataOffset(data) {
        // Find 'trun' box and return position of the data_offset field
        for (let i = 0; i < data.length - 16; i++) {
            if (data[i] === 0x74 && data[i+1] === 0x72 &&
                data[i+2] === 0x75 && data[i+3] === 0x6E) {
                // 'trun' at i
                // layout after 'trun': version(1) + flags(3) + sample_count(4) + data_offset(4)
                return i + 4 + 4 + 4; // skip trun_name(4) + version_flags(4) + sample_count(4)
            }
        }
        return -1;
    }

    _annexBToAvcc(data) {
        const nals = this._parseNalUnits(data);
        // Filter out SPS/PPS (type 7,8) and AUD (type 9) — already in init segment
        const videoNals = nals.filter(n => {
            const type = n[0] & 0x1F;
            return type !== 7 && type !== 8 && type !== 9;
        });

        if (videoNals.length === 0) return new Uint8Array(0);

        let totalSize = 0;
        for (const nal of videoNals) totalSize += 4 + nal.length;

        const result = new Uint8Array(totalSize);
        let offset = 0;
        for (const nal of videoNals) {
            result[offset]   = (nal.length >> 24) & 0xFF;
            result[offset+1] = (nal.length >> 16) & 0xFF;
            result[offset+2] = (nal.length >> 8) & 0xFF;
            result[offset+3] = nal.length & 0xFF;
            result.set(nal, offset + 4);
            offset += 4 + nal.length;
        }
        return result;
    }

    _maintainLiveEdge() {
        if (!this.video || !this.sourceBuffer || !this.ready) return;
        if (this.sourceBuffer.updating) return;

        try {
            const buf = this.sourceBuffer.buffered;
            if (buf.length > 0) {
                const end = buf.end(buf.length - 1);
                if (end - this.video.currentTime > 0.3) {
                    this.video.currentTime = end - 0.05;
                }
                // Remove old data to prevent memory buildup
                if (buf.start(0) < end - 5 && !this.sourceBuffer.updating) {
                    try {
                        this.sourceBuffer.remove(0, end - 3);
                    } catch (_) {}
                }
            }
        } catch (_) {
            // SourceBuffer may have been removed
        }
    }

    _appendBuffer(data) {
        if (!this.sourceBuffer) return;
        if (this.mediaSource.readyState !== 'open') return;
        this.queue.push(data);
        this._flushQueue();
    }

    _flushQueue() {
        if (this.appending || !this.sourceBuffer || this.queue.length === 0) return;
        if (this.sourceBuffer.updating) return;
        if (this.mediaSource.readyState !== 'open') {
            this.queue = [];
            return;
        }

        this.appending = true;
        const data = this.queue.shift();
        try {
            this.sourceBuffer.appendBuffer(data);
        } catch (e) {
            console.error('[MSE] appendBuffer error:', e);
            this.appending = false;
            // Don't spam error callback on every frame
            if (this.queue.length === 0) {
                this.onError(e);
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
        this.ready = false;
        this.initSegmentSent = false;
        this.sps = null;
        this.pps = null;
        this.sequenceNumber = 0;
        this.baseDecodeTime = 0;
        const ms = this.mediaSource;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this._pendingReinit = false;
        this._pendingKeyframe = null;
        if (ms) {
            try {
                if (ms.readyState === 'open') ms.endOfStream();
            } catch (_) {}
            if (this.video && this.video.src) {
                try { URL.revokeObjectURL(this.video.src); } catch (_) {}
            }
        }
    }

    // ---- MP4 Box helpers ----

    _box(type, ...payloads) {
        const payload = this._concat(...payloads);
        const size = 8 + payload.length;
        const box = new Uint8Array(size);
        box[0] = (size >> 24) & 0xFF;
        box[1] = (size >> 16) & 0xFF;
        box[2] = (size >> 8) & 0xFF;
        box[3] = size & 0xFF;
        box[4] = type.charCodeAt(0);
        box[5] = type.charCodeAt(1);
        box[6] = type.charCodeAt(2);
        box[7] = type.charCodeAt(3);
        box.set(payload, 8);
        return box;
    }

    _fullbox(type, version, flags, ...payloads) {
        const header = new Uint8Array(4);
        header[0] = version;
        header[1] = (flags >> 16) & 0xFF;
        header[2] = (flags >> 8) & 0xFF;
        header[3] = flags & 0xFF;
        return this._box(type, header, ...payloads);
    }

    _concat(...arrays) {
        const filtered = arrays.filter(a => a && a.length > 0);
        if (filtered.length === 0) return new Uint8Array(0);
        if (filtered.length === 1) return filtered[0] instanceof Uint8Array ? filtered[0] : new Uint8Array(filtered[0]);

        let totalLen = 0;
        for (const a of filtered) totalLen += a.length;
        const result = new Uint8Array(totalLen);
        let offset = 0;
        for (const a of filtered) {
            result.set(a instanceof Uint8Array ? a : new Uint8Array(a), offset);
            offset += a.length;
        }
        return result;
    }

    _str(s) {
        const a = new Uint8Array(s.length);
        for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
        return a;
    }

    _u16(v) { return new Uint8Array([(v >> 8) & 0xFF, v & 0xFF]); }
    _i16(v) { return this._u16(v & 0xFFFF); }
    _u32(v) { return new Uint8Array([(v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF]); }
    _u64(v) {
        const hi = Math.floor(v / 0x100000000);
        const lo = v >>> 0;
        return new Uint8Array([
            (hi >> 24) & 0xFF, (hi >> 16) & 0xFF, (hi >> 8) & 0xFF, hi & 0xFF,
            (lo >> 24) & 0xFF, (lo >> 16) & 0xFF, (lo >> 8) & 0xFF, lo & 0xFF
        ]);
    }

    _matrix() {
        const m = new Uint8Array(36);
        m[0]=0;m[1]=1;m[2]=0;m[3]=0;       // 0x00010000
        m[16]=0;m[17]=1;m[18]=0;m[19]=0;    // 0x00010000
        m[32]=0x40;m[33]=0;m[34]=0;m[35]=0; // 0x40000000
        return m;
    }
}

/**
 * Bit reader for SPS parsing (Exp-Golomb coded)
 */
class BitReader {
    constructor(data) {
        this.data = data;
        this.bytePos = 0;
        this.bitPos = 0;
    }

    readBits(n) {
        let val = 0;
        for (let i = 0; i < n; i++) {
            if (this.bytePos >= this.data.length) return val;
            val = (val << 1) | ((this.data[this.bytePos] >> (7 - this.bitPos)) & 1);
            this.bitPos++;
            if (this.bitPos === 8) {
                this.bytePos++;
                this.bitPos = 0;
            }
        }
        return val;
    }

    skip(n) { this.readBits(n); }

    readUEG() {
        let zeros = 0;
        while (this.readBits(1) === 0 && zeros < 31) zeros++;
        if (zeros === 0) return 0;
        return (1 << zeros) - 1 + this.readBits(zeros);
    }

    readSEG() {
        const v = this.readUEG();
        return (v & 1) ? ((v + 1) >> 1) : -(v >> 1);
    }
}
