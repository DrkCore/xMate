package org.xutils.http.body;


import android.text.TextUtils;

import org.xutils.common.Callback;
import org.xutils.http.ProgressHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: wyouflf
 * Time: 2014/05/30
 */
public class MultipartBody implements ProgressBody {

    private static byte[] BOUNDARY_PREFIX_BYTES = "--------7da3d81520810".getBytes();
    private static byte[] END_BYTES = "\r\n".getBytes();
    private static byte[] TWO_DASHES_BYTES = "--".getBytes();
    private byte[] boundaryPostfixBytes;
    private String contentType;
    private String charset;

    private Map<String, Object> multipartParams;
    private long total = 0;
    private long current = 0;

    public MultipartBody(Map<String, Object> multipartParams, String charset) {
        this.multipartParams = multipartParams;
        this.charset = charset;
        generateContentType();

        // calc total
        CounterOutputStream counter = new CounterOutputStream();
        try {
            this.writeTo(counter);
            this.total = counter.total.get();
        } catch (IOException e) {
            this.total = -1;
        }
    }

    private ProgressHandler callBackHandler;

    @Override
    public void setProgressHandler(ProgressHandler progressHandler) {
        this.callBackHandler = progressHandler;
    }

    private void generateContentType() {
        String boundaryPostfix = Double.toHexString(Math.random() * 0xFFFF);
        boundaryPostfixBytes = boundaryPostfix.getBytes();
        contentType = "multipart/form-data; boundary=" + new String(BOUNDARY_PREFIX_BYTES) + boundaryPostfix;
    }

    @Override
    public long getContentLength() {
        return -1;
    }

    /**
     * only change subType:
     * "multipart/subType; boundary=xxx..."
     *
     * @param subType "form-data" or "related"
     */
    @Override
    public void setContentType(String subType) {
        int index = contentType.indexOf(";");
        this.contentType = "multipart/" + subType + contentType.substring(index);
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {

        if (callBackHandler != null && !callBackHandler.updateProgress(total, current, true)) {
            throw new Callback.CancelledException("upload stopped!");
        }

        for (Map.Entry<String, Object> kv : multipartParams.entrySet()) {
            String name = kv.getKey();
            Object value = kv.getValue();
            if (!TextUtils.isEmpty(name) && value != null) {
                if (!writeEntry(out, name, value, charset, boundaryPostfixBytes)) {
                    throw new Callback.CancelledException("upload stopped!");
                }
            }
        }
        writeLine(out, TWO_DASHES_BYTES, BOUNDARY_PREFIX_BYTES, boundaryPostfixBytes, TWO_DASHES_BYTES);
        out.flush();

        if (callBackHandler != null) {
            callBackHandler.updateProgress(total, total, true);
        }
    }

    private boolean writeEntry(OutputStream out,
                               String name, Object value,
                               String charset, byte[] boundaryPostfixBytes) throws IOException {
        writeLine(out, TWO_DASHES_BYTES, BOUNDARY_PREFIX_BYTES, boundaryPostfixBytes);

        String fileName = "";
        String contentType = null;
        if (value instanceof BodyEntityWrapper) {
            BodyEntityWrapper wrapper = (BodyEntityWrapper) value;
            value = wrapper.getObject();
            fileName = wrapper.getFileName();
            contentType = wrapper.getContentType();
        }

        if (value instanceof File) {
            File file = (File) value;
            if (TextUtils.isEmpty(fileName)) {
                fileName = file.getName();
            }
            if (TextUtils.isEmpty(contentType)) {
                contentType = FileBody.getFileContentType(file);
            }
            writeLine(out, buildContentDisposition(name, fileName));
            writeLine(out, buildContentType(value, contentType, charset));
            writeLine(out); // 内容前空一行
            if (!writeFile(out, file)) {
                return false;
            }
        } else {
            writeLine(out, buildContentDisposition(name, fileName));
            writeLine(out, buildContentType(value, contentType, charset));
            writeLine(out); // 内容前空一行
            if (value instanceof InputStream) {
                if (!writeStreamAndCloseIn(out, (InputStream) value)) {
                    return false;
                }
            } else {
                byte[] content;
                if (value instanceof byte[]) {
                    content = (byte[]) value;
                } else {
                    content = String.valueOf(value).getBytes(charset);
                }
                writeLine(out, content);
                current += content.length;
                if (callBackHandler != null && !callBackHandler.updateProgress(total, current, false)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void writeLine(OutputStream out, byte[]... bs) throws IOException {
        if (bs != null) {
            for (byte[] b : bs) {
                out.write(b);
            }
        }
        out.write(END_BYTES);
    }

    private boolean writeFile(OutputStream out, File file) throws IOException {
        if (out instanceof CounterOutputStream) {
            ((CounterOutputStream) out).addCount(file.length());
            return true;
        }
        return writeStreamAndCloseIn(out, new FileInputStream(file));
    }

    private boolean writeStreamAndCloseIn(OutputStream out, InputStream in) throws IOException {
        if (out instanceof CounterOutputStream) {
            ((CounterOutputStream) out).addCount(in.available());
            return true;
        }
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) >= 0) {
            out.write(buf, 0, len);
            current += len;
            if (callBackHandler != null && !callBackHandler.updateProgress(total, current, false)) {
                return false;
            }
        }
        in.close();
        out.write(END_BYTES);
        return true;
    }

    private static byte[] buildContentDisposition(String name, String fileName) {
        StringBuilder result = new StringBuilder("Content-Disposition: form-data");
        result.append("; name=\"").append(name.replace("\"", "%22")).append("\"");
        if (!TextUtils.isEmpty(fileName)) {
            result.append("; filename=\"").append(fileName.replace("\"", "%22")).append("\"");
        }
        return result.toString().getBytes();
    }

    private static byte[] buildContentType(Object value, String contentType, String charset) {
        StringBuilder result = new StringBuilder("Content-Type: ");
        if (TextUtils.isEmpty(contentType)) {
            if (value instanceof String) {
                contentType = "text/plain; charset:" + charset;
            } else {
                contentType = "application/octet-stream";
            }
        } else {
            contentType = contentType.replaceFirst("\\/jpg$", "/jpeg");
        }
        result.append(contentType);
        return result.toString().getBytes();
    }

    private class CounterOutputStream extends OutputStream {

        final AtomicLong total = new AtomicLong(0);

        public CounterOutputStream() {
        }

        public void addCount(long count) {
            total.addAndGet(count);
        }

        @Override
        public void write(int oneByte) throws IOException {
            total.incrementAndGet();
        }

        @Override
        public void write(byte[] buffer) throws IOException {
            total.addAndGet(buffer.length);
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {
            total.addAndGet(count);
        }
    }
}