package com.example.cameraapp.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;

import java.nio.ByteBuffer;

public class YuvToRgbConverter {

    private final RenderScript rs;
    private final ScriptIntrinsicYuvToRGB script;

    private byte[] yuvBuffer;
    private Allocation inputAllocation;
    private Allocation outputAllocation;

    public YuvToRgbConverter(Context context) {
        rs = RenderScript.create(context);
        script = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
    }

    public synchronized void yuvToRgb(Image image, Bitmap output) {
        int totalSize = 0;
        for (Image.Plane plane : image.getPlanes()) {
            totalSize += plane.getBuffer().remaining();
        }

        if (yuvBuffer == null || yuvBuffer.length != totalSize) {
            yuvBuffer = new byte[totalSize];
        }

        imageToByteArray(image, yuvBuffer);

        if (inputAllocation == null || inputAllocation.getType().getX() != yuvBuffer.length) {
            Element elem = Element.U8(rs);
            Type type = new Type.Builder(rs, elem).setX(yuvBuffer.length).create();
            inputAllocation = Allocation.createTyped(rs, type, Allocation.USAGE_SCRIPT);
        }

        if (outputAllocation == null || outputAllocation.getType().getX() != output.getWidth() || outputAllocation.getType().getY() != output.getHeight()) {
            outputAllocation = Allocation.createFromBitmap(rs, output);
        }

        inputAllocation.copyFrom(yuvBuffer);
        script.setInput(inputAllocation);
        script.forEach(outputAllocation);
        outputAllocation.copyTo(output);
    }

    private void imageToByteArray(Image image, byte[] output) {
        int offset = 0;
        for (Image.Plane plane : image.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();
            int length = buffer.remaining();
            buffer.get(output, offset, length);
            offset += length;
        }
    }
}
