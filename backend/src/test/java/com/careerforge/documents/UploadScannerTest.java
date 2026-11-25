package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UploadScannerTest {
    @Test
    void readsScannerVerdictAndFailsClosedOnUnrecognizedReplies() throws Exception {
        try (var server = new ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))) {
            var messages = new String[]{"stream: OK", "stream: synthetic-signature FOUND", "stream: fixture ERROR"};
            var checked = CompletableFuture.runAsync(() -> {
                for (String verdict : messages) {
                    try (var connection = server.accept()) {
                        var data = new DataInputStream(connection.getInputStream());
                        assertEquals("zINSTREAM\0", new String(data.readNBytes(10), StandardCharsets.US_ASCII));
                        assertEquals("fixture", new String(data.readNBytes(data.readInt()), StandardCharsets.US_ASCII));
                        assertEquals(0, data.readInt());
                        connection.getOutputStream().write((verdict + "\0").getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
            });
            var scan = new UploadScanner("127.0.0.1", server.getLocalPort());
            var bytes = "fixture".getBytes(StandardCharsets.US_ASCII);
            scan.scan(bytes);
            assertEquals("FILE_SCAN_REJECTED", assertThrows(ApiError.class, () -> scan.scan(bytes)).code);
            assertEquals("FILE_SCAN_UNAVAILABLE", assertThrows(ApiError.class, () -> scan.scan(bytes)).code);
            checked.get(5, TimeUnit.SECONDS);
        }
    }
}
