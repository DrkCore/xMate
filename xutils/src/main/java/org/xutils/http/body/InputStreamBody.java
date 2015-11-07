package org.xutils.http.body;

import android.text.TextUtils;

import org.xutils.common.Callback;
import org.xutils.http.ProgressHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * Author: wyouflf
 * Time: 2014/05/30
 */
public class InputStreamBody implements ProgressBody {

    private InputStream content;
    private String contentType;

    private long total;
    private long current = 0;

    private ProgressHandler callBackHandler;

    public InputStreamBody(BodyEntityWrapper<InputStream> wrapper) {
        this(wrapper.getObject(), wrapper.getContentType());
    }

    public InputStreamBody(InputStream inputStream) {
        this(inputStream, null);
    }

    public InputStreamBody(InputStream inputStream, String contentType) {
        this.content = inputStream;
        this.contentType = contentType;
        try {
            this.total = inputStream.available();
        } catch (IOException e) {
            this.total = -1;
        }
    }

    @Override
    public void setProgressHandler(ProgressHandler progressHandler) {
        this.callBackHandler = progressHandler;
    }

    @Override
    public long getContentLength() {
        return total;
    }

    @Override
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String getContentType() {
        return TextUtils.isEmpty(contentType) ? "application/octet-stream" : contentType;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        if (callBackHandler != null && !callBackHandler.updateProgress(total, current, true)) {
            throw new Callback.CancelledException("upload stopped!");
        }

        byte[] buffer = new byte[1024];
        try {
            int len = 0;
            while ((len = content.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                current += len;
                if (callBackHandler != null && !callBackHandler.updateProgress(total, current, false)) {
                    throw new Callback.CancelledException("upload stopped!");
                }
            }
            out.flush();

            if (callBackHandler != null) {
                callBackHandler.updateProgress(total, total, true);
            }
        } finally {
            try {
                content.close();
            } catch (Throwable ignored) {
            }
        }
    }
}