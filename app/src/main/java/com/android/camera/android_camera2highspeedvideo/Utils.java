package com.android.camera.android_camera2highspeedvideo;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by hzd on 2019/7/23.
 */

public class Utils {
    private static final String TAG = "Utils";

    static long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    static byte[] LoadFromFile(String fileName) {

        File file = new File(fileName);
        byte[] fileData = new byte[(int) file.length()];

        try {
            DataInputStream dis = new DataInputStream(new FileInputStream(file));
            dis.readFully(fileData);
            dis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fileData;
    }

    static boolean saveYUVFile(String fullpath, byte[] Data, boolean flag) {
        boolean success = false;
        if (null != fullpath && Data != null) {
            try {
                FileOutputStream fs = new FileOutputStream(fullpath, flag);
                fs.write(Data);
                fs.close();
                success = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return success;
    }

    static void createDirectory(String dir) {
        File file = new File(dir);
        if (!file.exists()) {
            boolean mkdir = file.mkdir();
        } else {
            if (!dir.endsWith(File.separator)) {
                dir = dir + File.separator;
            }
            File dirFile = new File(dir);
            File[] files = dirFile.listFiles();
            for (File fileTmp : files) {
                int pointIndex = fileTmp.toString().indexOf('.');
                String fileSufix = fileTmp.toString().substring(pointIndex);
                if (fileSufix.equals(".NV21") || fileSufix.equals(".nv21") || fileSufix.equals(".NV12") || fileSufix.equals(".nv12") || fileSufix.equals(".h264") || fileSufix.equals(".mp4")) {
                    boolean delete = fileTmp.delete();
                }
            }
        }
    }

    static byte[] transformNV21FromImage(Image image) {
        Log.d(TAG, "transformNV21FromImage: in");
        Rect crop = image.getCropRect();    // CropRect specifies a rectangular area within the image, only pixels in this area are valid
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();   // plane #0 must be Y，#1 must be U，#2 must be V
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        int channelOffset = 0;
        int outputStride = 1;   // The interval at which data is written, that is, the step size, and 1 means no interval.

        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0: // Y
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1: // U
                    channelOffset = width * height + 1;     // Since the NV21 V component is in the front and the U component is in the back, the starting offset of U has to +1.
                    outputStride = 2;
                    break;
                case 2: // V
                    channelOffset = width * height;
                    outputStride = 2;
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }

    static void guessFileWH(String strFile, int[] wh) {
        Log.i(TAG, "guessFileWH in = " + strFile);
        // 4_6144x3456_output_317_ISO602.yuyv
        // ax8_IMG_20150101_192924_0_2976x3968.yuyv
        wh[0] = -1;    //width
        wh[1] = -1;    //height
        if (strFile == null) {
            return;
        }
        int index_X = strFile.lastIndexOf('x');
        if (index_X == -1) {
            index_X = strFile.lastIndexOf('X');
        }
        if (index_X != -1) {
            int iStart = 0;
            for (iStart = index_X - 1; iStart >= 0; iStart--) {
                if (!isDigital(strFile.charAt(iStart))) {
                    break;
                }
            }
            if (iStart >= 0) {
                String sWidth = strFile.substring(iStart + 1, index_X);
                try {
                    wh[0] = Integer.parseInt(sWidth);
                } catch (NumberFormatException e) {
                    wh[0] = 0;
                }
            }
            int iEnd = 0;
            for (iEnd = index_X + 1; iEnd < strFile.length(); iEnd++) {
                if (!isDigital(strFile.charAt(iEnd))) {
                    break;
                }
            }
            if (iEnd < strFile.length()) {
                String sHeight = strFile.substring(index_X + 1, iEnd);
                try {
                    wh[1] = Integer.parseInt(sHeight);
                } catch (NumberFormatException e) {
                    wh[1] = 0;
                }
            }
        }
        Log.i(TAG, "guessFileWH out " + wh[0] + "x" + wh[1]);
    }

    private static boolean isDigital(char c) {
        return c >= '0' && c <= '9';
    }

    public static void Nv21ToI420(byte[] data, byte[] dstData, int w, int h) {

        int size = w * h;
        // Y
        System.arraycopy(data, 0, dstData, 0, size);
        for (int i = 0; i < size / 4; i++) {
            dstData[size + i] = data[size + i * 2 + 1]; //U
            dstData[size + size / 4 + i] = data[size + i * 2]; //V
        }
    }

    public static void Nv21ToYuv420SP(byte[] data, byte[] dstData, int w, int h) {
        int size = w * h;
        // Y
        System.arraycopy(data, 0, dstData, 0, size);

        for (int i = 0; i < size / 4; i++) {
            dstData[size + i * 2] = data[size + i * 2 + 1]; //U
            dstData[size + i * 2 + 1] = data[size + i * 2]; //V
        }
    }
}
