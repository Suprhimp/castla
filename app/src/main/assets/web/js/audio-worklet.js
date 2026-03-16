/**
 * Castla - PCM Audio Worklet Processor
 *
 * Receives raw PCM Float32 interleaved stereo samples via port.postMessage,
 * buffers them in a queue, and pulls them in process() for gapless playback.
 */
class PCMPlayerProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.queue = [];     // Queue of Float32Array chunks (interleaved stereo)
        this.queueOffset = 0; // Read position within the first chunk
        this.channels = 2;

        this.port.onmessage = (e) => {
            if (e.data.type === 'samples') {
                this.queue.push(e.data.samples);
            } else if (e.data.type === 'config') {
                this.channels = e.data.channels || 2;
            } else if (e.data.type === 'clear') {
                this.queue = [];
                this.queueOffset = 0;
            }
        };
    }

    process(inputs, outputs, parameters) {
        const output = outputs[0];
        if (!output || output.length === 0) return true;

        const left = output[0];
        const right = output.length > 1 ? output[1] : null;
        const frameCount = left.length; // typically 128 frames

        for (let i = 0; i < frameCount; i++) {
            if (this.queue.length === 0) {
                // Buffer underrun — output silence
                left[i] = 0;
                if (right) right[i] = 0;
                continue;
            }

            const chunk = this.queue[0];

            if (this.channels === 2) {
                left[i] = chunk[this.queueOffset];
                if (right) right[i] = chunk[this.queueOffset + 1];
                this.queueOffset += 2;
            } else {
                // Mono
                left[i] = chunk[this.queueOffset];
                if (right) right[i] = chunk[this.queueOffset];
                this.queueOffset += 1;
            }

            if (this.queueOffset >= chunk.length) {
                this.queue.shift();
                this.queueOffset = 0;
            }
        }

        return true; // Keep processor alive
    }
}

registerProcessor('pcm-player', PCMPlayerProcessor);
