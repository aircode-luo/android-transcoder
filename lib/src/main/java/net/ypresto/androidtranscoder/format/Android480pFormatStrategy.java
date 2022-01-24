package net.ypresto.androidtranscoder.format;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

public class Android480pFormatStrategy implements MediaFormatStrategy {
    public static final int AUDIO_BITRATE_AS_IS = -1;
    public static final int AUDIO_CHANNELS_AS_IS = -1;
    private static final String TAG = "420pFormatStrategy";
    private static final int LONGER_LENGTH = 640;
    private static final int SHORTER_LENGTH = 480;
    private static final int DEFAULT_VIDEO_BITRATE = 700 * 1000; // From Nexus 4 Camera in 480p
    private final int mVideoBitrate;
    private final int mAudioBitrate;
    private final int mAudioChannels;

    public Android480pFormatStrategy() {
        this(DEFAULT_VIDEO_BITRATE);
    }

    public Android480pFormatStrategy(int videoBitrate) {
        this(videoBitrate, AUDIO_BITRATE_AS_IS, AUDIO_CHANNELS_AS_IS);
    }

    public Android480pFormatStrategy(int videoBitrate, int audioBitrate, int audioChannels) {
        mVideoBitrate = videoBitrate;
        mAudioBitrate = audioBitrate;
        mAudioChannels = audioChannels;
    }

    @Override
    public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
        int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int longer, shorter, outWidth, outHeight;
        if (width >= height) {
            longer = width;
            shorter = height;
            outWidth = LONGER_LENGTH;
            outHeight = SHORTER_LENGTH;
        } else {
            shorter = width;
            longer = height;
            outWidth = SHORTER_LENGTH;
            outHeight = LONGER_LENGTH;
        }

        // TODO: 2022/1/15 为什么要限制输入的视频比是 16/9
        if (longer * 9 != shorter * 16) {
            throw new OutputFormatUnavailableException("This video is not 16:9, and is not able to transcode. (" + width + "x" + height + ")");
        }
        if (shorter <= SHORTER_LENGTH) {
            Log.d(TAG, "This video is less or equal to 480p, pass-through. (" + width + "x" + height + ")");
            return null;
        }

        // 视频格式 avc 是 H.264 的不同叫法
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight);
        // From Nexus 4 Camera in 480p
        format.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitrate);
        // 帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return format;
    }

    @Override
    public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
        // 默认不压缩音频
        if (mAudioBitrate == AUDIO_BITRATE_AS_IS || mAudioChannels == AUDIO_CHANNELS_AS_IS) return null;

        // Use original sample rate, as resampling is not supported yet.
        final MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), mAudioChannels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitrate);
        return format;
    }
}
