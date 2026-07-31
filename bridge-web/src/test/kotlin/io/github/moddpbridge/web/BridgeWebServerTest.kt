package io.github.moddpbridge.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeWebServerTest {
    @Test
    fun `health rejects cross-site mutation and serves static page`() {
        withServer { base, _ ->
            val health = get(base.resolve("api/health"))
            assertEquals(200, health.statusCode())
            assertContains(health.body(), "\"status\":\"ok\"")

            val page = get(base)
            assertEquals(200, page.statusCode())
            assertContains(page.body(), "MOD")

            val rebound = rawGet(base.port, "rebind.invalid")
            assertContains(rebound.lineSequence().first(), " 421 ")
            assertContains(rebound, "host_not_allowed")

            val crossSite = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Origin", "https://attacker.invalid")
                .header("Sec-Fetch-Site", "cross-site")
                .POST(HttpRequest.BodyPublishers.ofString("not multipart"))
                .build()
            val rejected = client.send(crossSite, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(403, rejected.statusCode())
            assertContains(rejected.body(), "cross_site_request_rejected")
        }
    }

    @Test
    fun `uploaded mod runs in isolated cli and exposes result report and logs`() {
        withServer { base, _ ->
            val archive = fixtureArchive()
            val boundary = "----mod-dp-bridge-test-boundary"
            val multipart = multipart(boundary, "fixture.zip", archive)
            val request = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Origin", "${base.scheme}://${base.authority}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build()
            val created = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(201, created.statusCode(), created.body())
            val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1)
            assertNotNull(id)

            val jobUri = base.resolve("api/jobs/$id")
            val finalStatus = waitForTerminal(jobUri)
            assertContains(finalStatus, "\"status\":\"succeeded\"")
            assertContains(finalStatus, "\"progress\":100")
            assertContains(finalStatus, "\"resultAvailable\":true")

            val events = get(base.resolve("api/jobs/$id/events"))
            assertEquals(200, events.statusCode())
            assertContains(events.body(), "event: snapshot")
            assertContains(events.body(), "event: log")
            assertContains(events.body(), "event: status")
            assertTrue(events.body().indexOf("event: log") < events.body().lastIndexOf("event: snapshot"))

            val report = get(base.resolve("api/jobs/$id/report"))
            assertEquals(200, report.statusCode(), report.body())
            assertContains(report.body(), "\"schemaVersion\"")

            val result = getBytes(base.resolve("api/jobs/$id/download/result"))
            assertEquals(200, result.statusCode())
            assertTrue(result.body().size > 100)
            assertEquals('P'.code.toByte(), result.body()[0])
            assertEquals('K'.code.toByte(), result.body()[1])

            val logs = getBytes(base.resolve("api/jobs/$id/download/logs"))
            assertEquals(200, logs.statusCode())
            val entries = mutableSetOf<String>()
            ZipInputStream(logs.body().inputStream()).use { zip ->
                while (true) entries += zip.nextEntry?.name ?: break
            }
            assertTrue("web-process.log" in entries)
            assertTrue("converter/conversion.log" in entries)
            assertTrue("report.json" in entries)
        }
    }

    @Test
    fun `preparation failures terminate and running artifacts stay hidden`() {
        val directRoot = createTempDirectory("mod-dp-bridge-web-artifact-test")
        val directJob = ConversionJob(
            id = "00000000-0000-0000-0000-000000000000",
            originalFileName = "fixture.zip",
            input = directRoot.resolve("fixture.zip"),
            root = directRoot,
        )
        Files.createDirectories(directJob.outputDirectory)
        Files.writeString(directJob.outputDirectory.resolve("report.json"), "{}")
        Files.write(directJob.outputDirectory.resolve("fixture-dp-v159.7.zip"), fixtureArchive())
        assertFalse(directJob.snapshot().reportAvailable)
        assertFalse(directJob.snapshot().resultAvailable)
        directJob.cancelBeforeStart()
        assertTrue(directJob.snapshot().reportAvailable)
        assertTrue(directJob.snapshot().resultAvailable)

        val work = createTempDirectory("mod-dp-bridge-web-preparation-test")
        JobManager(testConfig(work)).use { manager ->
            val reservation = assertNotNull(manager.reserveUpload())
            val input = reservation.inputDirectory.resolve("fixture.zip")
            Files.write(input, fixtureArchive())
            Files.writeString(reservation.root.resolve("output"), "blocks output directory creation")
            val job = manager.submit(reservation, UploadedFile(input, "fixture.zip", Files.size(input)))
            repeat(100) {
                if (job.snapshot().status.terminal) return@repeat
                Thread.sleep(20)
            }
            val snapshot = job.snapshot()
            assertEquals(JobStatus.FAILED, snapshot.status)
            assertEquals(100, snapshot.progress)
            assertTrue(snapshot.logsAvailable)
        }
    }

    @Test
    fun `running job cancellation terminates process tree and exposes only completed logs`() {
        val work = createTempDirectory("mod-dp-bridge-web-cancel-test")
        var parentPid: Long? = null
        var childPid: Long? = null
        try {
            BridgeWebServer(testConfig(work, fakeCliClasspath())).use { server ->
                server.start()
                val base = URI("http://127.0.0.1:${server.address.port}/")
                val boundary = "----mod-dp-bridge-cancel-boundary"
                val request = HttpRequest.newBuilder(base.resolve("api/jobs"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .header("Origin", "${base.scheme}://${base.authority}")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, "fixture.zip", fixtureArchive())))
                    .build()
                val created = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                assertEquals(201, created.statusCode(), created.body())
                val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1)
                assertNotNull(id)

                val jobRoot = work.resolve(id)
                val runningParentPid = waitForPid(jobRoot.resolve("fake-parent.pid"))
                val runningChildPid = waitForPid(jobRoot.resolve("fake-child.pid"))
                parentPid = runningParentPid
                childPid = runningChildPid
                assertTrue(ProcessHandle.of(runningParentPid).orElseThrow().isAlive)
                assertTrue(ProcessHandle.of(runningChildPid).orElseThrow().isAlive)

                val running = get(base.resolve("api/jobs/$id"))
                assertEquals(200, running.statusCode())
                assertContains(running.body(), "\"status\":\"running\"")
                assertEquals(404, get(base.resolve("api/jobs/$id/report")).statusCode())
                assertEquals(404, get(base.resolve("api/jobs/$id/download/result")).statusCode())
                assertEquals(404, get(base.resolve("api/jobs/$id/download/logs")).statusCode())

                val cancel = HttpRequest.newBuilder(base.resolve("api/jobs/$id/cancel"))
                    .header("Origin", "${base.scheme}://${base.authority}")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                val accepted = client.send(cancel, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                assertEquals(202, accepted.statusCode(), accepted.body())

                val terminal = waitForTerminal(base.resolve("api/jobs/$id"))
                assertContains(terminal, "\"status\":\"cancelled\"")
                assertContains(terminal, "\"progress\":100")
                assertContains(terminal, "\"resultAvailable\":false")
                assertContains(terminal, "\"reportAvailable\":false")
                assertContains(terminal, "\"logsAvailable\":true")
                waitForProcessExit(runningParentPid)
                waitForProcessExit(runningChildPid)

                val report = get(base.resolve("api/jobs/$id/report"))
                assertEquals(404, report.statusCode())
                assertContains(report.body(), "report_not_ready")
                val result = get(base.resolve("api/jobs/$id/download/result"))
                assertEquals(404, result.statusCode())
                assertContains(result.body(), "result_not_ready")

                val logs = getBytes(base.resolve("api/jobs/$id/download/logs"))
                assertEquals(200, logs.statusCode())
                val entries = mutableSetOf<String>()
                ZipInputStream(logs.body().inputStream()).use { zip ->
                    while (true) entries += zip.nextEntry?.name ?: break
                }
                assertEquals(setOf("web-process.log"), entries)
            }
        } finally {
            listOfNotNull(childPid, parentPid).forEach { pid ->
                ProcessHandle.of(pid).ifPresent { handle -> if (handle.isAlive) handle.destroyForcibly() }
            }
        }
    }

    @Test
    fun `failed upload directory creation rolls back reservation capacity`() {
        val work = createTempDirectory("mod-dp-bridge-web-reservation-test")
        JobManager(testConfig(work)).use { manager ->
            Files.delete(work)
            Files.writeString(work, "blocks child directory creation")
            try {
                assertFailsWith<IOException> { manager.reserveUpload() }
            } finally {
                Files.deleteIfExists(work)
                Files.createDirectories(work)
            }

            val capacity = 3
            val reservations = (1..capacity).map { assertNotNull(manager.reserveUpload()) }
            assertNull(manager.reserveUpload(), "a failed directory creation must not consume a queue slot")
            reservations.forEach(manager::abandonUpload)
        }
    }

    @Test
    fun `concurrent event publishers retain monotonically ordered sequence ids`() {
        val publisherCount = 8
        val eventsPerPublisher = 10_000
        val expectedEvents = publisherCount * eventsPerPublisher
        val events = JobEventBus(maxHistory = expectedEvents)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(publisherCount)
        try {
            val publishers = (1..publisherCount).map {
                executor.submit {
                    start.await()
                    repeat(eventsPerPublisher) { events.publish("test", "{}") }
                }
            }
            start.countDown()
            publishers.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val subscription = events.subscribe(null)
        try {
            val sequences = subscription.replay.map(ServerEvent::sequence)
            assertEquals(expectedEvents, sequences.size)
            assertEquals(1L, sequences.first())
            assertEquals(expectedEvents.toLong(), sequences.last())
            assertTrue(
                sequences.zipWithNext().all { (left, right) -> right == left + 1L },
                "event history must be delivered in sequence-id order",
            )
        } finally {
            events.unsubscribe(subscription.queue)
        }
    }

    private fun waitForTerminal(uri: URI): String {
        repeat(120) {
            val response = get(uri)
            assertEquals(200, response.statusCode())
            if (Regex("\"status\":\"(succeeded|failed|cancelled)\"").containsMatchIn(response.body())) {
                return response.body()
            }
            Thread.sleep(100)
        }
        error("Conversion job did not finish in time")
    }

    private fun withServer(block: (URI, Path) -> Unit) {
        val work = createTempDirectory("mod-dp-bridge-web-test")
        BridgeWebServer(testConfig(work)).use { server ->
            server.start()
            block(URI("http://127.0.0.1:${server.address.port}/"), work)
        }
    }

    private fun testConfig(
        work: Path,
        cliClasspath: String = System.getProperty("modDpBridge.testRuntimeClasspath")
            ?: error("Test runtime classpath was not configured"),
    ): WebConfig {
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        ).toString()
        return WebConfig(
            host = "127.0.0.1",
            port = 0,
            allowedHosts = setOf("localhost", "127.0.0.1", "::1"),
            workDirectory = work,
            maxUploadBytes = 8L * 1024L * 1024L,
            maxExpandedMiB = 32,
            maxArchiveEntries = 1000,
            maxConcurrentJobs = 1,
            maxQueuedJobs = 2,
            maxSseClients = 2,
            retention = Duration.ofHours(1),
            serverJar = null,
            serverTimeoutSeconds = 10,
            javaCommand = java,
            cliClasspath = cliClasspath,
        )
    }

    private fun waitForPid(path: Path): Long {
        repeat(200) {
            val pid = runCatching { Files.readString(path).trim().toLong() }.getOrNull()
            if (pid != null) return pid
            Thread.sleep(25)
        }
        error("Process marker was not created: $path")
    }

    private fun waitForProcessExit(pid: Long) {
        repeat(200) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false).not()) return
            Thread.sleep(25)
        }
        error("Process $pid remained alive after cancellation")
    }

    private fun fakeCliClasspath(): String {
        val output = createTempDirectory("mod-dp-bridge-fake-cli")
        val source = output.resolve("src/io/github/moddpbridge/cli/MainKt.java")
        source.parent.createDirectories()
        Files.writeString(
            source,
            """
            package io.github.moddpbridge.cli;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.Locale;

            public final class MainKt {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0 && args[0].equals("child")) {
                        Files.writeString(Path.of("fake-child.pid"), Long.toString(ProcessHandle.current().pid()));
                        Thread.sleep(Long.MAX_VALUE);
                        return;
                    }

                    Files.writeString(Path.of("fake-parent.pid"), Long.toString(ProcessHandle.current().pid()));
                    String executable = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                            ? "java.exe"
                            : "java"
                    ).toString();
                    new ProcessBuilder(
                        executable,
                        "-cp",
                        System.getProperty("java.class.path"),
                        MainKt.class.getName(),
                        "child"
                    ).start();
                    System.out.println("Reading input: cancellation fixture");
                    System.out.flush();
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK compiler is required for this test")
        val result = compiler.run(null, null, null, "-d", output.toString(), source.toString())
        check(result == 0) { "Failed to compile cancellation test CLI fixture (javac exit $result)" }
        return output.toString()
    }

    private fun fixtureArchive(): ByteArray {
        val root = sequenceOf(
            Path.of("fixtures/self-authored/minimal-data-mod"),
            Path.of("../fixtures/self-authored/minimal-data-mod"),
        ).map(Path::toAbsolutePath).first(Files::isDirectory)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    val name = root.relativize(path).joinToString("/")
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    Files.copy(path, zip)
                    zip.closeEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun multipart(boundary: String, filename: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(
            "Content-Disposition: form-data; name=file; filename=\"$filename\"\r\n".toByteArray(
                StandardCharsets.US_ASCII,
            ),
        )
        output.write("Content-Type: application/zip\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(content)
        output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.US_ASCII))
        return output.toByteArray()
    }

    private fun get(uri: URI): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(uri).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
    )

    private fun getBytes(uri: URI): HttpResponse<ByteArray> = client.send(
        HttpRequest.newBuilder(uri).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    private fun rawGet(port: Int, host: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII)
        writer.write("GET /api/jobs HTTP/1.1\r\n")
        writer.write("Host: $host\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getInputStream().readAllBytes().toString(StandardCharsets.UTF_8)
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
