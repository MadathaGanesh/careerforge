package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component
public class UploadScanner {
    private final String host;
    private final int port;

    public UploadScanner(@Value("${app.clamav-host:}") String host, @Value("${app.clamav-port:3310}") int port) {
        this.host = host;
        this.port = port;
    }

    public void scan(byte[] content) {
        if (host.isBlank()) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(10000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += 65536) {
                int length = Math.min(65536, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
            }
            output.writeInt(0);
            output.flush();
            var reply = new String(socket.getInputStream().readNBytes(512), StandardCharsets.US_ASCII)
                    .replace("\0", "").strip();
            if (reply.equals("stream: OK")) return;
            if (reply.endsWith(" FOUND")) {
                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_SCAN_REJECTED", "The file did not pass the upload safety scan.");
            }
        } catch (IOException ignored) {
            // A configured scanner must return an explicit clean result.
        }
        throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "FILE_SCAN_UNAVAILABLE", "The upload safety scan is unavailable. Try again later.");
    }
}
