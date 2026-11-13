package hath.base;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

public class RegressionTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
        checks++;
    }
    private static void ioFailure(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch(IOException expected) { checks++; }
    }
    interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hath-regression-");
        Settings.parseArgs(new String[] {"--data-dir=" + root.resolve("data"),
            "--cache-dir=" + root.resolve("cache"), "--log-dir=" + root.resolve("log"),
            "--download-dir=" + root.resolve("download"), "--temp-dir=" + root.resolve("tmp")});
        Settings.initializeDirectories();
        try {
            throttles();
            cacheFiles();
            metadataDeadline(root);
            archives(root);
            metrics();
            overload();
            rpcSignatures();
            System.out.println("PASS: " + checks + " regression checks");
        } finally {
            CacheFileLookup.shutdown();
            try(java.util.stream.Stream<Path> paths = Files.walk(root)) {
                java.util.Iterator<Path> files = paths.sorted(java.util.Comparator.reverseOrder()).iterator();
                while(files.hasNext()) Files.deleteIfExists(files.next());
            }
        }
    }

    private static void throttles() {
        Settings.updateSetting("throttle_bytes", "1000000");
        Settings.updateSetting("upload_throttle_bytes", "0");
        Settings.updateSetting("speedtest_throttle_bytes", "0");
        check(Settings.getThrottleBytesPerSec() == 1000000, "Upload fallback failed");
        check(Settings.getSpeedTestThrottleBytesPerSec() == 1000000, "Speedtest fallback failed");
        Settings.updateSetting("upload_throttle_bytes", "2000000");
        Settings.updateSetting("speedtest_throttle_bytes", "3000000");
        check(Settings.getThrottleBytesPerSec() == 2000000, "Upload limit lost");
        check(Settings.getSpeedTestThrottleBytesPerSec() == 3000000, "Separate speedtest limit lost");
        check(!Settings.updateSetting("upload_throttle_bytes", "-1"), "Negative limit accepted");
        check(Settings.getThrottleBytesPerSec() == 2000000, "Invalid setting replaced valid limit");
    }

    private static void cacheFiles() throws Exception {
        byte[] contents = new byte[100000];
        for(int i=0; i<contents.length; i++) contents[i] = (byte)i;
        String hash = Tools.binaryToHex(MessageDigest.getInstance("SHA-1").digest(contents));
        HVFile hv = HVFile.getHVFileFromFileid(hash + "-100000-jpg");
        Path file = hv.getLocalFilePath();
        Files.createDirectories(file.getParent());
        Files.write(file, contents);
        HTTPResponseProcessorFile good = new HTTPResponseProcessorFile(null, hv, true);
        check(good.initialize() == 200, "Complete cache file failed initialization");
        java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        while(received.size() < contents.length) {
            ByteBuffer buffer = good.getPreparedTCPBuffer();
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); received.write(chunk);
        }
        good.cleanup(); // The upstream inline digest must accept this without accessing a client.
        check(Arrays.equals(contents, received.toByteArray()), "Cache data changed in transit");
        Files.write(file, new byte[16]);
        HTTPResponseProcessorFile shortFile = new HTTPResponseProcessorFile(null, hv, true);
        check(shortFile.initialize() == 200, "Short file setup failed");
        long start = System.nanoTime();
        ioFailure(() -> shortFile.getPreparedTCPBuffer(), "Truncated cache file was accepted");
        check(System.nanoTime()-start < TimeUnit.SECONDS.toNanos(1), "Truncated cache read stalled");
        shortFile.cleanup();
        Files.write(file, new byte[0]);
        HTTPResponseProcessorFile empty = new HTTPResponseProcessorFile(null, hv, false);
        check(empty.initialize() == 500, "Empty cache file accepted"); empty.cleanup();
    }

    private static void metadataDeadline(Path root) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        File stalled = new File("unused-test-path") {
            public boolean exists() {
                // Model filesystem I/O that ignores interruption.
                boolean done = false;
                while(!done) try { release.await(); done = true; } catch(InterruptedException ignored) {}
                return true;
            }
            public long length() { return 123; }
        };
        long start = System.nanoTime();
        try { ioFailure(() -> CacheFileLookup.size(stalled, 30, TimeUnit.MILLISECONDS), "Slow lookup did not time out"); }
        finally { release.countDown(); }
        check(System.nanoTime()-start < TimeUnit.SECONDS.toNanos(1), "Metadata timeout blocked session");
        Path actual = root.resolve("lookup"); Files.write(actual, new byte[23]);
        check(CacheFileLookup.size(actual.toFile()) == 23, "Lookup did not recover after timeout");
        check(CacheFileLookup.size(root.resolve("missing").toFile()) == -1, "Missing cache file misclassified");
    }

    private static void archives(Path root) throws Exception {
        Path gallery = root.resolve("gallery"); Files.createDirectories(gallery.resolve("sub"));
        Files.write(gallery.resolve("001.jpg"), new byte[] {1,2,3});
        Files.write(gallery.resolve("sub/galleryinfo.txt"), new byte[] {4,5});
        Path zip = GalleryArchive.create(gallery);
        try(ZipFile archive = new ZipFile(zip.toFile())) {
            check(archive.size() == 2, "Archive omitted files");
            check(Arrays.equals(archive.getInputStream(archive.getEntry("001.jpg")).readAllBytes(), new byte[] {1,2,3}), "ZIP data differs");
            check(archive.getEntry("sub/galleryinfo.txt") != null, "Relative archive path changed");
        }
        check(!Files.exists(gallery), "Completed source directory was not removed");
        Path broken = root.resolve("broken"); Files.createDirectory(broken);
        Files.write(broken.resolve("001.jpg"), new byte[] {9});
        Path originalZip = root.resolve("broken.zip"); Files.write(originalZip, new byte[] {8,7});
        Path outside = root.resolve("outside"); Files.write(outside, new byte[] {6});
        Files.createSymbolicLink(broken.resolve("002.jpg"), outside);
        ioFailure(() -> GalleryArchive.create(broken), "Symbolic link was archived");
        check(Files.exists(broken.resolve("001.jpg")), "Archive failure deleted source file");
        check(Arrays.equals(Files.readAllBytes(originalZip), new byte[] {8,7}), "Archive failure replaced previous archive");
        check(Files.exists(outside), "Archive failure affected external file");
        try(java.util.stream.Stream<Path> paths = Files.list(root)) {
            check(paths.noneMatch(p -> p.getFileName().toString().startsWith(".hath-gallery-")), "Temporary archive leaked");
        }
    }

    private static void metrics() throws Exception {
        Settings.updateSetting("metrics_name", "監控quote\"slash\\line\nnext");
        Stats.programStarted(); Stats.bytesSent(1234); Stats.setLastOverload(1700000000000L);
        HTTPMetricsResponse response = new HTTPMetricsResponse(null);
        response.parseRequest("GET /metrics HTTP/1.1");
        check(response.getResponseStatusCode() == 200, "Metrics endpoint broke");
        HTTPResponseProcessor processor = response.getHTTPResponseProcessor();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        while(body.size() < processor.getContentLength()) {
            ByteBuffer buffer = processor.getPreparedTCPBuffer();
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); body.write(bytes);
        }
        String metrics = body.toString("UTF-8");
        check(metrics.contains("監控quote\\\"slash\\\\line\\nnext"), "Prometheus labels are not escaped");
        check(metrics.contains("eh_hath_transfer_tx_bytes"), "Transfer metrics missing");
        check(metrics.contains("eh_hath_last_overload"), "Overload metric missing");
        check(metrics.contains("eh_hath_staic_range_count"), "Existing dashboard metric name changed");
    }

    private static void overload() {
        Stats.setOpenConnections(281);
        HTTPResponse response = new HTTPResponse(null);
        response.parseRequest("GET /h/test/test HTTP/1.1", false);
        response.getHTTPResponseProcessor();
        check(response.getResponseStatusCode() == 503, "Overload escaped session cleanup or returned success");
        Stats.setOpenConnections(0);
    }

    private static void rpcSignatures() throws Exception {
        String body = "test-setting=123";
        String signature = Tools.getSHA1String("123-" + Settings.getClientKey() + "\n" + body);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/valid", exchange -> {
            byte[] data = ("OK-123-" + signature + "\n" + body).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.createContext("/tampered", exchange -> {
            byte[] data = ("OK-123-" + signature + "\nchanged=999").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            check(ServerResponse.getServerResponse(new java.net.URL(url + "/valid"), null).getResponseStatus() == ServerResponse.RESPONSE_STATUS_OK, "Valid RPC signature rejected");
            check("RESPONSE_HASH_FAILED".equals(ServerResponse.getServerResponse(new java.net.URL(url + "/tampered"), null).getFailCode()), "Modified RPC payload accepted");
        } finally { server.stop(0); }
    }
}
