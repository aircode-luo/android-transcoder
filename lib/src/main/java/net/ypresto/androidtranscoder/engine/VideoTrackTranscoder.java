/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import net.ypresto.androidtranscoder.format.MediaFormatExtraConstants;

import java.io.IOException;
import java.nio.ByteBuffer;

// Refer: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/ExtractDecodeEditEncodeMuxTest.java
public class VideoTrackTranscoder implements TrackTranscoder {
    private static final String TAG = "VideoTrackTranscoder";
    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final MediaExtractor mExtractor;
    private final int mTrackIndex;
    private final MediaFormat mOutputFormat;
    private final QueuedMuxer mMuxer;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mDecoder;
    private MediaCodec mEncoder;
    private ByteBuffer[] mDecoderInputBuffers;
    private ByteBuffer[] mEncoderOutputBuffers;
    private MediaFormat mActualOutputFormat;
    private OutputSurface mDecoderOutputSurfaceWrapper;
    private InputSurface mEncoderInputSurfaceWrapper;
    private boolean mIsExtractorEOS;
    private boolean mIsDecoderEOS; //  EOS: End of Stream
    private boolean mIsEncoderEOS;
    private boolean mDecoderStarted;
    private boolean mEncoderStarted;
    private long mWrittenPresentationTimeUs; // 编码完成写入缓冲区的视频数据的时长

    public VideoTrackTranscoder(MediaExtractor extractor, int trackIndex,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mExtractor = extractor;
        mTrackIndex = trackIndex;
        mOutputFormat = outputFormat;
        mMuxer = muxer;
    }

    @Override
    public void setup() {
        mExtractor.selectTrack(mTrackIndex);
        try {
            // 编码器
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInputSurfaceWrapper = new InputSurface(mEncoder.createInputSurface());
        mEncoderInputSurfaceWrapper.makeCurrent();
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderOutputBuffers = mEncoder.getOutputBuffers();

        MediaFormat inputFormat = mExtractor.getTrackFormat(mTrackIndex);
        // TODO: 2022/1/18 这个做什么的？
        if (inputFormat.containsKey(MediaFormatExtraConstants.KEY_ROTATION_DEGREES)) {
            // Decoded video is rotated automatically in Android 5.0 lollipop.
            // Turn off here because we don't want to encode rotated one.
            // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
            inputFormat.setInteger(MediaFormatExtraConstants.KEY_ROTATION_DEGREES, 0);
        }
        mDecoderOutputSurfaceWrapper = new OutputSurface();
        try {
            mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mDecoder.configure(inputFormat, mDecoderOutputSurfaceWrapper.getSurface(), null, 0);
        mDecoder.start();
        mDecoderStarted = true;
        mDecoderInputBuffers = mDecoder.getInputBuffers();
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @Override
    public boolean stepPipeline() {
        boolean busy = false;

        int status;
        // TODO: 2022/1/16 不是应该先读取 drainExtractor
        // 首次执行 stepPipeline 方法时，因为没有解码数据，返回状态 DRAIN_STATE_NONE
        while (drainEncoder(0) != DRAIN_STATE_NONE) busy = true;
        do {
            status = drainDecoder(0);
            if (status != DRAIN_STATE_NONE) busy = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);
        while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true;

        return busy;
    }

    @Override
    public long getWrittenPresentationTimeUs() {
        return mWrittenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return mIsEncoderEOS;
    }

    // TODO: CloseGuard
    @Override
    public void release() {
        if (mDecoderOutputSurfaceWrapper != null) {
            mDecoderOutputSurfaceWrapper.release();
            mDecoderOutputSurfaceWrapper = null;
        }
        if (mEncoderInputSurfaceWrapper != null) {
            mEncoderInputSurfaceWrapper.release();
            mEncoderInputSurfaceWrapper = null;
        }
        if (mDecoder != null) {
            if (mDecoderStarted) mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    private int drainExtractor(long timeoutUs) {
        if (mIsExtractorEOS) return DRAIN_STATE_NONE;

        int trackIndex = mExtractor.getSampleTrackIndex();
        if (trackIndex >= 0 && trackIndex != mTrackIndex) { // 判断是否是当前的视频轨道
            return DRAIN_STATE_NONE;
        }
        // result 输入缓存的 index，通过getInputBuffers()得到的是输入缓存数组，通过index和输入缓存数组可以得到当前请求的输入缓存
        int result = mDecoder.dequeueInputBuffer(timeoutUs);
        if (result < 0) return DRAIN_STATE_NONE;

        //TODO 视频缓存读完了？
        if (trackIndex < 0) {
            mIsExtractorEOS = true;
            // 当你将一个带有end-of-stream marker标记的输入缓存入队列时，MediaCodec将转入End-of-Stream子状态。在这种状态下，MediaCodec
            // 不再接收之后的输入缓存，但它仍然产生输出缓存直到end-of-stream标记输出。
            mDecoder.queueInputBuffer(result, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return DRAIN_STATE_NONE;
        }
        // 读取采样数据到 mDecoderInputBuffers[result] 输入缓冲区，每次只读取一帧数据
        int sampleSize = mExtractor.readSampleData(mDecoderInputBuffers[result], 0);
        boolean isKeyFrame = (mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        // 把 mDecoderInputBuffers[result] 输入缓冲区数据进行解码
        mDecoder.queueInputBuffer(result, 0, sampleSize, mExtractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
        //在MediaExtractor执行完一次readSampleData方法后，需要调用advance()去跳到下一个sample(即下一帧)，然后再次读取数据
        mExtractor.advance();

        return DRAIN_STATE_CONSUMED;
    }

    private int drainDecoder(long timeoutUs) {
        if (mIsDecoderEOS) return DRAIN_STATE_NONE;

        int result = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mEncoder.signalEndOfInputStream();
            mIsDecoderEOS = true;
            mBufferInfo.size = 0;
        }

        // TODO: 2022/1/18 为啥要渲染呢？通过 Surface 传递数据？
        boolean doRender = (mBufferInfo.size > 0);
        // NOTE: doRender will block if buffer (of encoder) is full.
        // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
        mDecoder.releaseOutputBuffer(result, doRender);
        if (doRender) {
            mDecoderOutputSurfaceWrapper.awaitNewImage();
            mDecoderOutputSurfaceWrapper.drawImage();
            mEncoderInputSurfaceWrapper.setPresentationTime(mBufferInfo.presentationTimeUs * 1000);
            mEncoderInputSurfaceWrapper.swapBuffers();
        }
        return DRAIN_STATE_CONSUMED;
    }

    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;

        // 从输出缓冲区取数据进行编码
        //  1. INFO_TRY_AGAIN_LATER=-1 等待超时，有可能没数据
        //  2. INFO_OUTPUT_FORMAT_CHANGED=-2 媒体格式更改
        //  3. INFO_OUTPUT_BUFFERS_CHANGED=-3 缓冲区已更改（过时）
        //  4. 大于等于0的为缓冲区数据下标
        int result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);

        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (mActualOutputFormat != null)
                    throw new RuntimeException("Video output format changed twice.");
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(QueuedMuxer.SampleType.VIDEO, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderOutputBuffers = mEncoder.getOutputBuffers();
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        mMuxer.writeSampleData(QueuedMuxer.SampleType.VIDEO, mEncoderOutputBuffers[result], mBufferInfo);
        mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs;
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }
}
