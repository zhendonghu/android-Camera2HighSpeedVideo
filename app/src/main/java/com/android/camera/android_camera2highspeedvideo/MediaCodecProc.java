package com.android.camera.android_camera2highspeedvideo;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import static android.media.MediaCodec.CONFIGURE_FLAG_ENCODE;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

/**
 * Created by hzd on 2019/7/23.
 */

public class MediaCodecProc {
    private static final String TAG = "MediaCodecProc";
    private static final int ASVL_PAF_NV21 = 0x802;
    private static final int ASVL_PAF_I420 = 0x601;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private String mFilePath;
    private String mStrDecodeOutputDir;
    private String mOutputDir;
    private int mWidth;
    private int mHeight;
    private int mVideoFps;
    private int mVideoUpRatio;
    private int mFormat;
    private EncodeThread mEncodeThread;

    /**
     *
     * @param filePath      High-speed video storage path
     * @param videoFPS      used in my own algorithm processing, the relevant part is not public, deleted
     * @param videoUpRatio  same as videoFPS
     */
    public MediaCodecProc(String filePath, int videoFPS, int videoUpRatio) {
        mFilePath       = filePath;
        mVideoFps       = videoFPS;
        mVideoUpRatio   = videoUpRatio;
        mFormat         = ASVL_PAF_NV21;
        int lastIndex = mFilePath.lastIndexOf("/");
        mOutputDir = mFilePath.substring(0, lastIndex);
//        mEncodeThread   = new EncodeThread(mOutputDir);
//        new Thread(mEncodeThread).start();
        mStrDecodeOutputDir = mOutputDir + "/YUV_Frame";
        Utils.createDirectory(mStrDecodeOutputDir);
        Utils.createDirectory(mOutputDir + "/Output");
    }

    public void process() {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(mFilePath);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                Log.d(TAG, "No video track found in " + mFilePath);
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);     // Get the encoded information of the video
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            MediaCodecInfo.CodecCapabilities caps = decoder.getCodecInfo().getCapabilitiesForType(mime);
            for (int format : caps.colorFormats) {
                // Get the frame format supported by the decoder supporting video/avc
                // 2135033992：  COLOR_FormatYUV420Flexible
                // 21：          COLOR_FormatYUV420SemiPlanar
                if (format > 100)
                    Log.d(TAG, "Supported format: " + Integer.toHexString(format));
                else
                    Log.d(TAG, "Supported format: 0x" + format);
            }
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                // Specified frame format
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                Log.d(TAG, "set decode color format to type " + decodeColorFormat);
            } else {
                Log.d(TAG, "unable to set decode color format, color format type " + decodeColorFormat + " not supported");
            }
            decodeImageToFrames(decoder, extractor, mediaFormat);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void decodeImageToFrames(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.configure(mediaFormat, null, null, 0);
        decoder.start();
        mWidth = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int outputFrameCount = 0;

        mEncodeThread = new EncodeThread(mOutputDir);
        new Thread(mEncodeThread).start();
        mEncodeThread.setDimension(mWidth, mHeight);

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    assert inputBuffer != null;
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);
                if (doRender) {
                    Image image = decoder.getOutputImage(outputBufferId);
//                    dumpYUVFrame(image, outputFrameCount, width, height);

                    switch (mFormat) {
                        case ASVL_PAF_NV21:
                            byte[] arr = Utils.transformNV21FromImage(image);
                            // TODO You can do some processing on the data here, such as inserting frames, etc.
                            /* your codes */

                            mEncodeThread.push(arr);
                            break;
                        case ASVL_PAF_I420:
                            break;
                    }

                    outputFrameCount++;
                    assert image != null;
                    image.close();
                    decoder.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }
        mEncodeThread.quitThread();
    }

    // Get the track number where the video is located
    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                return i;
            }
        }
        return -1;
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int format : caps.colorFormats) {
            if (format == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void dumpYUVFrame(Image image, int index, int width, int height) {
        String fileName;
        switch (mFormat) {
            case ASVL_PAF_NV21:
                fileName = mStrDecodeOutputDir + "/" + "output_" + index + "_" + width + "x" + height + ".nv21";
                Utils.saveYUVFile(fileName, Utils.transformNV21FromImage(image), false);
                break;
            case ASVL_PAF_I420:
                fileName = mStrDecodeOutputDir + "/" + "output_" + index + "_" + width + "x" + height + ".yuv";
                // TODO transform image to I420
//                Utils.saveYUVFile(fileName, Utils.transformNV21FromImage(image), false);
                break;
        }
    }

    public class EncodeThread implements Runnable {

        private static final int ENCODE_FPS = 30;
        private static final String ENCODE_MIME_TYPE = MIMETYPE_VIDEO_AVC;

        private String mOutputDir;
        private LinkedBlockingQueue<byte[]> mImageQueue;
        private boolean mState;
        private int mWidth;
        private int mHeight;
        private int mTrackIndex = -10;

        EncodeThread(String outputDir) {
            mOutputDir = outputDir;
            mImageQueue = new LinkedBlockingQueue<>();
            mState = true;
        }

        public void setDimension(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        public void push(byte[] data) {
            try {
                mImageQueue.put(data);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void quitThread() {
            mState = false;
        }

        @Override
        public void run() {
            MediaCodec codec = null;
            MediaMuxer mediaMuxer = null;
            MediaFormat mediaFormat = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long presentationTimeUs = 132;

            try {
//            getCodecName();
                mediaFormat = createEncodeMediaFormat(mWidth, mHeight, ENCODE_FPS);
//            codec = MediaCodec.createByCodecName(CODEC_ENCODE_NAME);
                codec = MediaCodec.createEncoderByType(mediaFormat.getString(MediaFormat.KEY_MIME));
                codec.configure(mediaFormat, null, null, CONFIGURE_FLAG_ENCODE);
                codec.start();
                mediaMuxer = new MediaMuxer(mOutputDir + "/output.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                while (true) {
                    if (!mState && mImageQueue.size() <= 0) {
                        break;
                    }
                    int inputBufferId = codec.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                    if (inputBufferId >= 0) {
                        try {
                            byte[] data = mImageQueue.take();
                            byte[] data1 = new byte[mWidth * mHeight * 3 / 2];
                            Utils.Nv21ToYuv420SP(data, data1, mWidth, mHeight);
                            ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                            assert inputBuffer != null;
                            inputBuffer.clear();
                            inputBuffer.put(data1);
                            codec.queueInputBuffer(inputBufferId, 0, data1.length, presentationTimeUs, 0);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    int outputBufferId = codec.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
                    if (outputBufferId >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                        MediaFormat bufferFormat = codec.getOutputFormat(outputBufferId); // option A
                        // bufferFormat is identical to outputFormat
                        // outputBuffer is ready to be processed or rendered.
                        assert outputBuffer != null;
                        byte[] arr = new byte[outputBuffer.remaining()];
                        outputBuffer.get(arr);
//                    Utils.saveYUVFile(mOutputDir + "/encode_output.h264", arr, true);
                        // TODO MP4 The resulting frame rate is not constant 30fps
                        presentationTimeUs += 1000 * 1000 / ENCODE_FPS;
                        // TODO No audio information added
                        mediaMuxer.writeSampleData(mTrackIndex, outputBuffer, info);
                        outputBuffer.clear();
                        codec.releaseOutputBuffer(outputBufferId, false);
                    } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Subsequent data will conform to new format.
                        // Can ignore if using getOutputFormat(outputBufferId)
                        mTrackIndex = mediaMuxer.addTrack(codec.getOutputFormat());
                        mediaMuxer.start();
                    }
                }
                mImageQueue.clear();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
                if (mediaMuxer != null && mTrackIndex != -1) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                    mediaMuxer = null;
                }
            }
        }

        private MediaFormat createEncodeMediaFormat(int width, int height, int fps) {
        /* Another way to create mediaFormat
        MediaFormat mediaFormat = new MediaFormat();
        // Use H264 encoding
        mediaFormat.setString(MediaFormat.KEY_MIME, mEncodeMimetype);
        mediaFormat.setInteger(MediaFormat.KEY_WIDTH, width);
        mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, height);
        */

            // Use H264 encoding
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(ENCODE_MIME_TYPE, width, height);
            // Set the video input color format   TODO Specific format support depends on device support, currently NV21 to YUV420SP
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            // Set the video bit rate
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * fps * 300);
            // Set video fps
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            // Set video I frame interval
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                /**
                 * Optional configuration, set the rate mode
                 * BITRATE_MODE_VBR：Constant mass
                 * BITRATE_MODE_VBR：Variable bit rate
                 * BITRATE_MODE_CBR：Constant bit rate
                 */
                mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                /**
                 * Optional configuration, set H264 Profile
                 * Need to do compatibility check
                 */
                mediaFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                /**
                 * Optional configuration, setting H264 Level
                 * Need to do compatibility check
                 */
                mediaFormat.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31);
            }

            return mediaFormat;
        }
    }
}
