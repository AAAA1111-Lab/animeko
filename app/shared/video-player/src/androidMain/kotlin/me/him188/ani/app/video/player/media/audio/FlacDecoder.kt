/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [SimpleDecoder] backed by the bundled FLAC software decoder.
 *
 * Media3 ships no FLAC software decoder, and [androidx.media3.exoplayer.mediacodec.MediaCodecSelector]
 * cannot help: it only orders decoders the platform already reports, so on a device whose only
 * FLAC decoder is a hardware one that chokes on 24-bit streams there is nothing to select. Owning
 * the decode is the only way in.
 *
 * The decoder is a thin adapter: every input buffer holds exactly one FLAC frame as produced by
 * the container extractor, which [FlacDecoderNative.nativeDecodeFrame] wraps into a self-contained
 * stream and decodes. There is no internal sample queue and no cross-frame state, which keeps the
 * output aligned with the input timestamps that Media3 hands over 鈥?including across seeks, since
 * Media3 flushes the decoder and the extractor then continues from the new position.
 *
 * Output is interleaved 32-bit PCM, which covers 16/20/24-bit sources losslessly.
 */
@OptIn(UnstableApi::class)
internal class FlacDecoder private constructor() : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FlacDecoderException>(
    Array(OUTPUT_BUFFER_COUNT) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL) },
    Array(OUTPUT_BUFFER_COUNT) { FlacDecoderOutputBuffer() },
) {
    private var streamInfo: ByteArray? = null
    private var streamInfoBuffer: ByteBuffer? = null
    private var outputFormat: Format? = null
    private var channels: Int = 0
    private var sampleRate: Int = 0
    private var outputBufferCapacity: Int = 0

    /** Reassembles frames across container samples; see [FlacFrameAccumulator]. */
    private val accumulator = FlacFrameAccumulator()

    /**
     * Staging for the frame handed to the native decoder: the JNI edge reads direct buffers, and
     * the accumulator produces plain arrays.
     */
    private var frameBuffer: ByteBuffer = ByteBuffer.allocateDirect(FRAME_BUFFER_SIZE)

    /**
     * PCM staging for one decoded frame. Must stay direct: [FlacDecoderNative] reads it with
     * `GetDirectBufferAddress`, which is null for a heap buffer.
     */
    private var pcmBuffer: ByteBuffer = ByteBuffer.allocateDirect(PCM_STAGING_BYTES)

    /** Heap mirror of [pcmBuffer]'s usable region, used to assemble one container sample's PCM. */
    private val pcmBytes = ByteArray(PCM_STAGING_BYTES)

    override fun getName(): String = "ani-flac"

    /**
     * Records the codec configuration and the output shape.
     *
     * [configurationData] is the bare 34-byte STREAMINFO body Media3 reports for the track; see
     * [FlacDecoderNative] for why that is the only shape that has to be handled.
     */
    fun configure(format: Format, configurationData: ByteArray?) {
        val raw = configurationData
            ?: throw FlacDecoderException("FLAC track has no codec configuration (csd-0)")
        // Extractors report the codec configuration in several shapes (bare STREAMINFO, a block
        // header plus STREAMINFO, STREAMINFO plus other metadata); see FlacStreamInfo.
        val data = FlacStreamInfo.extractStreamInfo(raw)
            ?: throw FlacDecoderException(
                "FLAC codec configuration of ${raw.size} bytes contains no STREAMINFO",
            )
        val channelCount = format.channelCount
        val sampleRateHz = format.sampleRate
        if (channelCount <= 0 || channelCount > MAX_CHANNELS) {
            throw FlacDecoderException("Unsupported FLAC channel count: $channelCount")
        }
        if (sampleRateHz <= 0) {
            throw FlacDecoderException("Unsupported FLAC sample rate: $sampleRateHz")
        }

        streamInfo = data
        streamInfoBuffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
            put(data)
            flip()
        }
        channels = channelCount
        this.sampleRate = sampleRateHz
        outputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRateHz)
            .build()
        // dr_flac decodes at most maxBlockSize frames per call, and a frame never exceeds the
        // FLAC maximum block size, so this bound holds for every frame.
        outputBufferCapacity = MAX_BLOCK_SIZE * channelCount * BYTES_PER_SAMPLE
    }

    /** The PCM format produced by this decoder. Set by [configure] before decoding starts. */
    fun requireOutputFormat(): Format =
        outputFormat ?: throw FlacDecoderException("FLAC decoder used before configure()")

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        FlacDecoderOutputBuffer().also { it.decoder = this }

    /**
     * Returns a consumed buffer to this decoder's pool, called by [FlacDecoderOutputBuffer] through
     * its owner. The parent method is protected, so this indirection is what makes the callback
     * reachable from the buffer.
     */
    internal fun recycle(outputBuffer: FlacDecoderOutputBuffer) {
        releaseOutputBuffer(outputBuffer)
    }

    override fun createUnexpectedDecodeException(error: Throwable): FlacDecoderException =
        FlacDecoderException("Unexpected FLAC decode failure", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): FlacDecoderException? {
        if (reset) {
            // A reset means the stream was flushed (seek or format change); whatever was buffered
            // is no longer part of a continuous frame sequence.
            accumulator.reset()
        }
        if (inputBuffer.isEndOfStream) {
            // The last frame of a stream is only recognisable once nothing more can arrive.
            accumulator.endOfInput()
        }
        val input = inputBuffer.data ?: return null
        val frameSize = input.remaining()
        if (frameSize <= 0) {
            return null
        }
        val info = streamInfo
        val infoBuffer = streamInfoBuffer
        if (info == null || infoBuffer == null) {
            return FlacDecoderException("FLAC decoder used before configure()")
        }

        // A container sample is not a frame: it can hold a fragment of one or parts of several,
        // so collect bytes first and only hand whole frames to the native decoder.
        //
        // The staging array is taken from the buffer instead of copying through a fixed scratch
        // array: BUFFER_REPLACEMENT_MODE_NORMAL guarantees a backing array that always fits, while
        // a fixed array silently throws BufferUnderflowException on any larger sample.
        val sampleBytes = input.array()
        if (sampleBytes == null) {
            return FlacDecoderException("FLAC input buffer has no backing array")
        }
        val sampleOffset = input.arrayOffset() + input.position()
        input.position(input.limit())
        accumulator.append(sampleBytes, sampleOffset, frameSize)

        // One input sample can complete more than one frame, and a frame left behind here would be
        // emitted later carrying the *next* sample's timestamp. Drain everything the accumulator
        // can produce and concatenate it into this sample's output.
        //
        // The native edge reads the PCM target with GetDirectBufferAddress, so that target must be a
        // *direct* buffer. Wrapping a heap ByteArray makes the address null and every frame fails
        // with "output is not direct" 鈥?which is exactly how this broke on device. `init` hands out
        // a direct buffer, but only one frame fits per call, hence this reusable staging buffer.
        val capacity = outputBufferCapacity
        if (pcmBuffer.capacity() < capacity) {
            pcmBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        }

        var decodedBytes: ByteArray? = null
        var totalBytes = 0
        while (true) {
            val frame = accumulator.nextFrame() ?: break
            if (frame.isEmpty()) {
                // The accumulator dropped a partial frame while re-aligning after a seek. Skip the
                // fragment: decoding it would fail the whole track, and after a seek losing a few
                // milliseconds is far better than stopping playback.
                continue
            }
            if (frame.size > frameBuffer.capacity()) {
                return FlacDecoderException("FLAC frame of ${frame.size} bytes exceeds the buffer")
            }
            frameBuffer.clear()
            frameBuffer.put(frame, 0, frame.size)
            frameBuffer.position(0)
            frameBuffer.limit(frame.size)

            if (totalBytes + capacity > pcmBytes.size) {
                return FlacDecoderException(
                    "FLAC sample produced more than ${pcmBytes.size} bytes of PCM",
                )
            }
            pcmBuffer.clear()
            val framesInThisFrame = try {
                FlacDecoderNative.nativeDecodeFrame(
                    streamInfoBuffer = infoBuffer,
                    streamInfoSize = info.size,
                    frameBuffer = frameBuffer,
                    frameSize = frame.size,
                    outputBuffer = pcmBuffer,
                    outputCapacity = capacity,
                )
            } catch (error: Throwable) {
                return FlacDecoderException("FLAC software decode failed", error)
            }
            if (framesInThisFrame <= 0) {
                // The frame came from a container that handed over something this decoder cannot
                // read. Fail rather than emit silence, so the failure stays visible.
                return FlacDecoderException(
                    "FLAC software decode produced no samples for a ${frame.size} byte frame",
                )
            }
            val framesInThisFrameBytes = framesInThisFrame * channels * BYTES_PER_SAMPLE

            val target = decodedBytes
            if (target == null) {
                decodedBytes = ByteArray(framesInThisFrameBytes).also {
                    pcmBuffer.position(0)
                    pcmBuffer.get(it, 0, framesInThisFrameBytes)
                }
            } else {
                val grown = ByteArray(totalBytes + framesInThisFrameBytes)
                target.copyInto(grown, 0, 0, totalBytes)
                pcmBuffer.position(0)
                pcmBuffer.get(grown, totalBytes, framesInThisFrameBytes)
                decodedBytes = grown
            }
            totalBytes += framesInThisFrameBytes
        }

        val decoded = decodedBytes
        if (decoded == null) {
            // No whole frame yet; the next container sample completes it.
            //
            // SimpleDecoder queues whatever output buffer it handed over unless the decoder marks
            // it skipped, and a buffer whose data was never initialised fails later in the audio
            // sink with a NullPointerException on that buffer. Marking it skipped makes Media3 drop
            // it, which is exactly "this input produced nothing".
            //
            // The end-of-stream buffer is the exception: SimpleDecoder puts the end-of-stream flag
            // on the output *before* decoding, and a skipped buffer is released instead of queued,
            // so skipping there would swallow the end of the stream.
            if (accumulator.bufferedBytes > MAX_BUFFERED_FRAME_BYTES) {
                accumulator.reset()
                return FlacDecoderException("FLAC frame exceeded $MAX_BUFFERED_FRAME_BYTES bytes")
            }
            if (!inputBuffer.isEndOfStream) {
                outputBuffer.shouldBeSkipped = true
            }
            return null
        }

        val output = outputBuffer.init(inputBuffer.timeUs, decoded.size).order(ByteOrder.nativeOrder())
        output.put(decoded, 0, decoded.size)
        output.position(0)
        output.limit(decoded.size)
        return null
    }

    override fun release() {
        streamInfo = null
        streamInfoBuffer = null
        outputFormat = null
        super.release()
    }

    companion object {
        /** FLAC's maximum block size (RFC 9639 搂9.1.1). */
        private const val MAX_BLOCK_SIZE = 65535
        private const val BYTES_PER_SAMPLE = 4
        private const val MAX_CHANNELS = 8
        private const val OUTPUT_BUFFER_COUNT = 4

        /**
         * PCM staging size. One container sample can complete several frames, so this must hold more
         * than a single [MAX_BLOCK_SIZE] worth; 8 channels of 32-bit PCM at the maximum block size
         * is ~2 MiB, and a sample carrying more than that is refused rather than truncated.
         */
        private const val PCM_STAGING_BYTES = 8 * 1024 * 1024

        /**
         * Upper bound for a single frame. FLAC frames are at most a few tens of kilobytes; the
         * ceiling exists so a stream that never yields a parsable frame cannot grow the
         * accumulator without bound.
         */
        private const val MAX_BUFFERED_FRAME_BYTES = 4 * 1024 * 1024

        /** Native staging for one frame; comfortably above any real frame. */
        private const val FRAME_BUFFER_SIZE = 1024 * 1024

        /**
         * Whether the bundled decoder is actually loadable. Callers must check this before
         * preferring software decoding: the native library exists for every shipped ABI, but a
         * split APK or an unsupported ABI would otherwise turn every FLAC track into a
         * PlaybackException instead of falling back to the platform decoder.
         */
        val isAvailable: Boolean by lazy {
            runCatching {
                // Touching the object runs its static initializer, which loads the library.
                FlacDecoderNative
            }.isSuccess
        }

        /**
         * Creates a decoder for [format], or null when the track is not something this decoder
         * can handle 鈥?in which case the caller falls back to Media3's hardware path.
         */
        fun createOrNull(format: Format, cryptoConfig: CryptoConfig?): FlacDecoder? {
            if (!isAvailable) return null
            if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return null
            // FLAC is never DRM protected; anything encrypted belongs on the platform path.
            if (cryptoConfig != null) return null
            val configuration = format.initializationData.firstOrNull() ?: return null
            return try {
                FlacDecoder().apply {
                    configure(format, configuration)
                }
            } catch (error: FlacDecoderException) {
                null
            }
        }
    }
}

/**
 * Releases the buffer back to its decoder, which is how Media3 recycles decoded PCM.
 *
 * The owner must call back into the decoder, not into the buffer: `release()` is what invokes the
 * owner, so an owner that calls `release()` again recurses until the stack overflows 鈥?which is
 * exactly how this crashed on device with a StackOverflowError.
 *
 * The decoder is injected by [FlacDecoder.createOutputBuffer], because the buffers are constructed
 * before the decoder exists; a null target can only be observed before that injection.
 */
/**
 * Releases the buffer back to its decoder, which is how Media3 recycles decoded PCM.
 *
 * The owner must call back into the decoder, not into the buffer: `release()` is what invokes the
 * owner, so an owner that calls `release()` again recurses until the stack overflows 鈥?which is
 * exactly how this crashed on device with a StackOverflowError.
 *
 * [decoder] is set by [FlacDecoder.createOutputBuffer], since the buffers are built during
 * [SimpleDecoder]'s construction and cannot reach the decoder yet.
 */
internal class FlacDecoderOutputBuffer : SimpleDecoderOutputBuffer(
    Owner<SimpleDecoderOutputBuffer> { buffer -> (buffer as FlacDecoderOutputBuffer).onReleased() },
) {
    var decoder: FlacDecoder? = null

    internal fun onReleased() {
        decoder?.recycle(this)
    }
}

internal class FlacDecoderException : androidx.media3.decoder.DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
