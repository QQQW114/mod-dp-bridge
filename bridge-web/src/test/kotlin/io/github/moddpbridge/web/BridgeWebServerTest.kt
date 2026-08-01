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
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
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
    fun `runtime readiness is disabled by default and fails closed on binding and pinned jar checks`() {
        val root = createTempDirectory("mod-dp-bridge-runtime-config")
        val fakeServer = Files.write(root.resolve("server.jar"), byteArrayOf(1, 2, 3))
        val properties = mapOf(
            "java.home" to System.getProperty("java.home"),
            "java.class.path" to System.getProperty("java.class.path"),
        )

        val disabled = WebConfig.fromEnvironment(
            environment = mapOf("MOD_DP_BRIDGE_WORK_DIR" to root.resolve("disabled").toString()),
            systemProperties = properties,
        )
        assertFalse(disabled.runtimeReady)
        assertEquals("runtime_execution_disabled", disabled.runtimeReason)

        val nonLoopback = WebConfig.fromEnvironment(
            environment = mapOf(
                "MOD_DP_BRIDGE_WORK_DIR" to root.resolve("non-loopback").toString(),
                "MOD_DP_BRIDGE_ENABLE_RUNTIME" to "true",
                "MOD_DP_BRIDGE_HOST" to "0.0.0.0",
                "MOD_DP_BRIDGE_SERVER_JAR" to fakeServer.toString(),
            ),
            systemProperties = properties,
        )
        assertFalse(nonLoopback.runtimeReady)
        assertEquals("runtime_requires_loopback_binding", nonLoopback.runtimeReason)

        val wrongJar = WebConfig.fromEnvironment(
            environment = mapOf(
                "MOD_DP_BRIDGE_WORK_DIR" to root.resolve("wrong-jar").toString(),
                "MOD_DP_BRIDGE_ENABLE_RUNTIME" to "true",
                "MOD_DP_BRIDGE_HOST" to "127.0.0.1",
                "MOD_DP_BRIDGE_SERVER_JAR" to fakeServer.toString(),
            ),
            systemProperties = properties,
        )
        assertFalse(wrongJar.runtimeReady)
        assertEquals("runtime_server_jar_sha256_mismatch", wrongJar.runtimeReason)
    }

    @Test
    fun `health rejects cross-site mutation serves static page and keeps runtime globally disabled`() {
        withServer { base, work ->
            val health = get(base.resolve("api/health"))
            assertEquals(200, health.statusCode())
            assertContains(health.body(), "\"status\":\"ok\"")
            assertContains(health.body(), "\"runtimeReady\":false")
            assertContains(health.body(), "\"runtimeReason\":\"runtime_execution_disabled\"")

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

            val boundary = "----mod-dp-bridge-disabled-runtime"
            val runtimeRequest = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Origin", "${base.scheme}://${base.authority}")
                .header(REQUEST_ID_HEADER, requestId())
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        multipart(
                            boundary,
                            TestPart("mode", null, "runtime".toByteArray()),
                            TestPart("modJar", "trusted.jar", byteArrayOf(1)),
                            TestPart("allowModExecution", null, "true".toByteArray()),
                        ),
                    ),
                )
                .build()
            val runtimeRejected = client.send(runtimeRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(503, runtimeRejected.statusCode())
            assertContains(runtimeRejected.body(), "runtime_execution_disabled")
            Files.list(work).use { roots -> assertFalse(roots.findAny().isPresent) }
        }
    }

    @Test
    fun `job creation requires a unique canonical request id and rejects legacy mod field`() {
        withServer { base, _ ->
            val boundary = "----mod-dp-bridge-request-id"
            val body = multipart(boundary, "fixture.zip", fixtureArchive())
            fun request(id: String? = null, requestBody: ByteArray = body): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(base.resolve("api/jobs"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .header("Origin", "${base.scheme}://${base.authority}")
                id?.let { builder.header(REQUEST_ID_HEADER, it) }
                return client.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
            }

            assertEquals(400, request().statusCode())
            assertEquals(400, request("not-a-uuid").statusCode())

            val id = requestId()
            val created = request(id)
            assertEquals(201, created.statusCode(), created.body())
            assertContains(created.body(), "\"id\":\"$id\"")
            assertContains(created.body(), "\"requestId\":\"$id\"")
            assertEquals(409, request(id).statusCode(), "request IDs are single-use during retention")

            val legacyBoundary = "----mod-dp-bridge-legacy-mod"
            val legacyBody = multipart(
                legacyBoundary,
                TestPart("mode", null, "static".toByteArray()),
                TestPart("mod", "fixture.zip", fixtureArchive()),
            )
            val legacy = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=$legacyBoundary")
                .header("Origin", "${base.scheme}://${base.authority}")
                .header(REQUEST_ID_HEADER, requestId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(legacyBody))
                .build()
            val rejected = client.send(legacy, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(400, rejected.statusCode(), rejected.body())
            assertContains(rejected.body(), "Unknown multipart field 'mod'")
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
                .header(REQUEST_ID_HEADER, requestId())
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
            val textEntries = StringBuilder()
            ZipInputStream(logs.body().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += entry.name
                    if (entry.name.substringAfterLast('.', "") in setOf("log", "json", "txt", "md")) {
                        textEntries.append(zip.readAllBytes().toString(StandardCharsets.UTF_8))
                    }
                }
            }
            assertTrue("web-process.log" in entries)
            assertTrue("output/logs/conversion.log" in entries)
            assertTrue("report.json" in entries)
        }
    }

    @Test
    fun `runtime upload requires jar source and acknowledgement and launches fixed command`() {
        val work = createTempDirectory("mod-dp-bridge-web-runtime-test")
        val serverJar = Files.write(work.resolve("official-server.jar"), byteArrayOf(1, 2, 3))
        BridgeWebServer(testConfig(work, fakeRuntimeCliClasspath(), serverJar, runtimeReady = true)).use { server ->
            server.start()
            val base = URI("http://127.0.0.1:${server.address.port}/")
            val health = get(base.resolve("api/health"))
            assertContains(health.body(), "\"runtimeReady\":true")
            assertFalse(health.body().contains(serverJar.toString()), "health must not disclose the configured server path")

            val boundary = "----mod-dp-bridge-runtime-boundary"
            val body = multipart(
                boundary,
                TestPart("mode", null, "runtime".toByteArray(StandardCharsets.UTF_8)),
                TestPart("allowModExecution", null, "true".toByteArray(StandardCharsets.UTF_8)),
                TestPart("modJar", "ExampleMod.jar", byteArrayOf(0x50, 0x4b, 1, 2, 3)),
                TestPart("source", "ExampleMod-source.zip", byteArrayOf(0x50, 0x4b, 4, 5, 6)),
            )
            val request = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Origin", "${base.scheme}://${base.authority}")
                .header(REQUEST_ID_HEADER, requestId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val created = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(201, created.statusCode(), created.body())
            assertContains(created.body(), "\"mode\":\"runtime\"")
            assertContains(created.body(), "\"sourceFileName\":\"ExampleMod-source.zip\"")
            val id = assertNotNull(Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1))

            val terminal = waitForTerminal(base.resolve("api/jobs/$id"))
            assertContains(terminal, "\"status\":\"succeeded\"")
            assertContains(terminal, "\"resultAvailable\":true")
            val arguments = Files.readAllLines(work.resolve(id).resolve("command-args.txt"), StandardCharsets.UTF_8)
            assertEquals("runtime-convert", arguments.first())
            assertTrue("--mod-jar" in arguments)
            assertTrue("--source" in arguments)
            assertTrue("--server-jar" in arguments)
            assertTrue("--allow-mod-execution" in arguments)
            assertTrue("--runtime-timeout" in arguments)
            assertTrue("--hybrid-max-rounds" in arguments)
            assertEquals(serverJar.toString(), arguments[arguments.indexOf("--server-jar") + 1])

            val result = getBytes(base.resolve("api/jobs/$id/download/result"))
            assertEquals(200, result.statusCode())
            assertEquals('P'.code.toByte(), result.body()[0])
            assertEquals('K'.code.toByte(), result.body()[1])

            val logs = getBytes(base.resolve("api/jobs/$id/download/logs"))
            assertEquals(200, logs.statusCode())
            val entries = mutableSetOf<String>()
            val textEntries = StringBuilder()
            ZipInputStream(logs.body().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += entry.name
                    if (entry.name.substringAfterLast('.', "") in setOf("log", "json", "txt", "md")) {
                        textEntries.append(zip.readAllBytes().toString(StandardCharsets.UTF_8))
                    }
                }
            }
            assertTrue("output/logs/conversion.log" in entries)
            assertTrue("runtime-pipeline.json" in entries)
            assertTrue("runtime-snapshot.json" in entries)
            assertTrue("source-index-report.json" in entries)
            assertTrue("runtime-mapping.json" in entries)
            assertTrue("hybrid-report.json" in entries)
            val jobRoot = work.resolve(id).toAbsolutePath().normalize().toString()
            assertFalse(textEntries.contains(serverJar.toString()), "logs ZIP must redact the operator Server JAR path")
            assertFalse(textEntries.contains(jobRoot), "logs ZIP must redact the per-job root")
            assertContains(textEntries, "[job]")

            val report = get(base.resolve("api/jobs/$id/report"))
            assertEquals(200, report.statusCode())
            assertFalse(report.body().contains(jobRoot), "report API must redact the per-job root")
            assertContains(report.body(), "[job]")

            val jarOnlyBoundary = "----mod-dp-bridge-runtime-jar-only"
            val jarOnlyRequest = HttpRequest.newBuilder(base.resolve("api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=$jarOnlyBoundary")
                .header("Origin", "${base.scheme}://${base.authority}")
                .header(REQUEST_ID_HEADER, requestId())
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        multipart(
                            jarOnlyBoundary,
                            TestPart("mode", null, "runtime".toByteArray(StandardCharsets.UTF_8)),
                            TestPart("modJar", "JarOnly.jar", byteArrayOf(0x50, 0x4b, 7)),
                            TestPart("allowModExecution", null, "true".toByteArray(StandardCharsets.UTF_8)),
                        ),
                    ),
                )
                .build()
            val jarOnlyCreated = client.send(jarOnlyRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            assertEquals(201, jarOnlyCreated.statusCode(), jarOnlyCreated.body())
            assertContains(jarOnlyCreated.body(), "\"sourceFileName\":null")
            val jarOnlyId = assertNotNull(
                Regex("\"id\":\"([^\"]+)\"").find(jarOnlyCreated.body())?.groupValues?.get(1),
            )
            assertContains(waitForTerminal(base.resolve("api/jobs/$jarOnlyId")), "\"status\":\"succeeded\"")
            val jarOnlyArguments = Files.readAllLines(
                work.resolve(jarOnlyId).resolve("command-args.txt"),
                StandardCharsets.UTF_8,
            )
            assertFalse("--source" in jarOnlyArguments)
        }
    }

    @Test
    fun `runtime multipart rejects missing consent duplicates wrong extensions unknown fields and total overflow`() {
        val work = createTempDirectory("mod-dp-bridge-web-runtime-reject-test")
        val serverJar = Files.write(work.resolve("official-server.jar"), byteArrayOf(1))
        BridgeWebServer(
            testConfig(work, fakeRuntimeCliClasspath(), serverJar, runtimeReady = true, maxUploadBytes = 8),
        ).use { server ->
            server.start()
            val base = URI("http://127.0.0.1:${server.address.port}/")
            val cases = listOf(
                arrayOf(
                    TestPart("mode", null, "runtime".toByteArray()),
                    TestPart("modJar", "mod.jar", byteArrayOf(1)),
                ),
                arrayOf(
                    TestPart("mode", null, "runtime".toByteArray()),
                    TestPart("allowModExecution", null, "true".toByteArray()),
                    TestPart("modJar", "mod.zip", byteArrayOf(1)),
                ),
                arrayOf(
                    TestPart("mode", null, "runtime".toByteArray()),
                    TestPart("allowModExecution", null, "true".toByteArray()),
                    TestPart("modJar", "one.jar", byteArrayOf(1)),
                    TestPart("modJar", "two.jar", byteArrayOf(2)),
                ),
                arrayOf(
                    TestPart("mode", null, "runtime".toByteArray()),
                    TestPart("allowModExecution", null, "true".toByteArray()),
                    TestPart("mystery", null, "value".toByteArray()),
                    TestPart("modJar", "mod.jar", byteArrayOf(1)),
                ),
                arrayOf(
                    TestPart("mode", null, "runtime".toByteArray()),
                    TestPart("allowModExecution", null, "true".toByteArray()),
                    TestPart("modJar", "mod.jar", ByteArray(6) { 1 }),
                    TestPart("source", "source.zip", ByteArray(6) { 2 }),
                ),
            )
            cases.forEachIndexed { index, parts ->
                val boundary = "----mod-dp-bridge-reject-$index"
                val request = HttpRequest.newBuilder(base.resolve("api/jobs"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .header("Origin", "${base.scheme}://${base.authority}")
                    .header(REQUEST_ID_HEADER, requestId())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, *parts)))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                assertTrue(response.statusCode() in setOf(400, 413, 415), "case $index unexpectedly returned ${response.statusCode()}: ${response.body()}")
                assertContains(response.body(), "upload_rejected")
            }
            assertContains(get(base.resolve("api/jobs")).body(), "\"jobs\":[]")
            Files.list(work).use { roots ->
                assertEquals(setOf(serverJar), roots.toList().toSet(), "rejected uploads must be removed completely")
            }
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
        assertFalse(directJob.snapshot().reportAvailable)
        assertFalse(directJob.snapshot().resultAvailable)

        val work = createTempDirectory("mod-dp-bridge-web-preparation-test")
        JobManager(testConfig(work)).use { manager ->
            val reservation = assertNotNull(manager.reserveUpload(requestId()))
            val input = reservation.inputDirectory.resolve("fixture.zip")
            Files.write(input, fixtureArchive())
            Files.writeString(reservation.root.resolve("output"), "blocks output directory creation")
            val job = manager.submit(
                reservation,
                ConversionUpload(
                    ConversionMode.STATIC,
                    UploadedFile(input, "fixture.zip", Files.size(input)),
                ),
            )
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
    fun `result discovery trusts only contained nonsymlink report artifact with matching hash and readable zip`() {
        val root = createTempDirectory("mod-dp-bridge-web-result-contract")
        val job = ConversionJob(
            id = "00000000-0000-0000-0000-000000000001",
            originalFileName = "fixture.jar",
            input = root.resolve("fixture.jar"),
            root = root,
            mode = ConversionMode.RUNTIME,
            executionAcknowledged = true,
        )
        Files.createDirectories(job.outputDirectory)
        val declared = job.outputDirectory.resolve("declared.zip")
        Files.write(declared, fixtureArchive())
        Files.write(job.outputDirectory.resolve("decoy.zip"), fixtureArchive())
        writeArtifactReport(job.outputDirectory, declared, testSha256(declared))
        job.finish(0)
        assertEquals(JobStatus.SUCCEEDED, job.snapshot().status)
        assertEquals(declared, job.findResult())

        Files.write(declared, byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3))
        assertNull(job.findResult(), "hash mismatch must hide an otherwise named ZIP")

        val outside = Files.write(root.resolve("outside.zip"), fixtureArchive())
        writeArtifactReport(job.outputDirectory, outside, testSha256(outside))
        assertNull(job.findResult(), "report paths outside the job output directory must be rejected")

        val link = job.outputDirectory.resolve("linked.zip")
        val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (linked) {
            writeArtifactReport(job.outputDirectory, link, testSha256(outside))
            assertNull(job.findResult(), "symbolic-link artifacts must not be downloadable")
        }
    }

    @Test
    fun `exit zero fails closed when terminal report or declared artifact is invalid`() {
        fun job(suffix: String): ConversionJob {
            val root = createTempDirectory("mod-dp-bridge-terminal-$suffix")
            return ConversionJob(
                id = requestId(),
                originalFileName = "fixture.zip",
                input = root.resolve("fixture.zip"),
                root = root,
            ).also { Files.createDirectories(it.outputDirectory) }
        }

        val missing = job("missing")
        missing.finish(0)
        assertEquals(JobStatus.FAILED, missing.snapshot().status)
        assertFalse(missing.snapshot().resultAvailable)

        val malformed = job("malformed")
        Files.writeString(malformed.outputDirectory.resolve("report.json"), "not-json")
        malformed.finish(0)
        assertEquals(JobStatus.FAILED, malformed.snapshot().status)

        val wrongHash = job("hash")
        val zip = Files.write(wrongHash.outputDirectory.resolve("fixture.zip"), fixtureArchive())
        writeArtifactReport(wrongHash.outputDirectory, zip, "0".repeat(64))
        wrongHash.finish(0)
        assertEquals(JobStatus.FAILED, wrongHash.snapshot().status)
        assertFalse(wrongHash.snapshot().resultAvailable)
        assertContains(wrongHash.snapshot().message.orEmpty(), "SHA-256")
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
                    .header(REQUEST_ID_HEADER, requestId())
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
                assertFailsWith<IOException> { manager.reserveUpload(requestId()) }
            } finally {
                Files.deleteIfExists(work)
                Files.createDirectories(work)
            }

            val capacity = 3
            val reservations = (1..capacity).map { assertNotNull(manager.reserveUpload(requestId())) }
            assertNull(manager.reserveUpload(requestId()), "a failed directory creation must not consume a queue slot")
            reservations.forEach(manager::abandonUpload)
        }
    }

    @Test
    fun `pending cancellation transfers atomically and preexisting workspace is never deleted`() {
        val work = createTempDirectory("mod-dp-bridge-web-pending-cancel")
        JobManager(testConfig(work)).use { manager ->
            val id = requestId()
            val reservation = assertNotNull(manager.reserveUpload(id))
            assertNull(manager.cancelRequest(id), "pending uploads do not yet have a job object")
            val input = Files.write(reservation.inputDirectory.resolve("fixture.zip"), fixtureArchive())
            val job = manager.prepare(
                reservation,
                ConversionUpload(ConversionMode.STATIC, UploadedFile(input, "fixture.zip", Files.size(input))),
            )
            assertEquals(JobStatus.CANCELLED, job.snapshot().status)
            manager.start(job)
            assertEquals(JobStatus.CANCELLED, job.snapshot().status)
            assertFailsWith<UploadException> { manager.reserveUpload(id) }

            val existingId = requestId()
            val existingRoot = work.resolve(existingId)
            Files.createDirectory(existingRoot)
            val sentinel = Files.writeString(existingRoot.resolve("sentinel.txt"), "keep")
            val error = assertFailsWith<UploadException> { manager.reserveUpload(existingId) }
            assertEquals(409, error.statusCode)
            assertEquals("keep", Files.readString(sentinel), "a preexisting job directory must never be removed")
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
        serverJar: Path? = null,
        runtimeReady: Boolean = false,
        maxUploadBytes: Long = 8L * 1024L * 1024L,
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
            maxUploadBytes = maxUploadBytes,
            maxExpandedMiB = 32,
            maxArchiveEntries = 1000,
            maxConcurrentJobs = 1,
            maxQueuedJobs = 2,
            maxSseClients = 2,
            retention = Duration.ofHours(1),
            serverJar = serverJar,
            serverTimeoutSeconds = 10,
            runtimeEnabled = runtimeReady,
            runtimeReady = runtimeReady,
            runtimeReason = if (runtimeReady) null else "runtime_execution_disabled",
            runtimeTimeoutSeconds = 10,
            hybridMaxRounds = 8,
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

    private fun fakeRuntimeCliClasspath(): String {
        val output = createTempDirectory("mod-dp-bridge-fake-runtime-cli")
        val source = output.resolve("src/io/github/moddpbridge/cli/MainKt.java")
        source.parent.createDirectories()
        Files.writeString(
            source,
            """
            package io.github.moddpbridge.cli;

            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.security.MessageDigest;
            import java.util.HexFormat;
            import java.util.zip.ZipEntry;
            import java.util.zip.ZipOutputStream;

            public final class MainKt {
                public static void main(String[] args) throws Exception {
                    Files.write(Path.of("command-args.txt"), java.util.List.of(args), StandardCharsets.UTF_8);
                    if (args.length == 0 || !args[0].equals("runtime-convert")) System.exit(64);
                    Path out = Path.of(argument(args, "--output"));
                    Files.createDirectories(out.resolve("logs"));
                    Files.writeString(
                        out.resolve("logs/conversion.log"),
                        "server=" + argument(args, "--server-jar") + "\\nout=" + out.toAbsolutePath().normalize() + "\\n",
                        StandardCharsets.UTF_8
                    );
                    Path zip = out.resolve("fixture-dp-v159.7.zip").toAbsolutePath().normalize();
                    try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
                        stream.putNextEntry(new ZipEntry("content/items/fixture.hjson"));
                        stream.write("{color: ff00ffff}".getBytes(StandardCharsets.UTF_8));
                        stream.closeEntry();
                    }
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
                    String report = "{" +
                        "\"schemaVersion\":1," +
                        "\"target\":{\"id\":\"mindustry-v159.7\",\"gameVersion\":\"v159.7\"}," +
                        "\"source\":{\"kind\":\"modArchive\",\"name\":\"fixture\"}," +
                        "\"status\":\"success\"," +
                        "\"summary\":{}," +
                        "\"outputs\":[{\"kind\":\"dataPackZip\",\"path\":" + json(zip.toString()) +
                        ",\"sizeBytes\":" + Files.size(zip) + ",\"sha256\":\"" + hash + "\"}]}";
                    Files.writeString(out.resolve("report.json"), report, StandardCharsets.UTF_8);
                    for (String name : new String[]{
                        "runtime-pipeline.json", "runtime-snapshot.json", "source-index-report.json",
                        "runtime-mapping.json", "hybrid-report.json"
                    }) Files.writeString(out.resolve(name), "{}\n", StandardCharsets.UTF_8);
                    System.out.println("mod-dp-bridge local runtime pipeline started");
                    System.out.println("Starting isolated runtime extraction. Supplied Mod bytecode will execute in a child JVM.");
                    System.out.println("Indexing optional source provenance: fixture");
                    System.out.println("Mapping the earliest available typed registration snapshot and exact release-JAR assets to inert v159.7 declarations.");
                    System.out.println("Runtime pipeline status: completed");
                }

                private static String argument(String[] args, String name) {
                    for (int i = 0; i + 1 < args.length; i++) if (args[i].equals(name)) return args[i + 1];
                    throw new IllegalArgumentException("Missing " + name);
                }

                private static String json(String value) {
                    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                }
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK compiler is required for this test")
        val result = compiler.run(null, null, null, "-d", output.toString(), source.toString())
        check(result == 0) { "Failed to compile runtime test CLI fixture (javac exit $result)" }
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

    private fun writeArtifactReport(output: Path, zip: Path, sha256: String) {
        Files.writeString(
            output.resolve("report.json"),
            jsonObject(
                "schemaVersion" to "1",
                "target" to jsonObject(
                    "id" to jsonString("mindustry-v159.7"),
                    "gameVersion" to jsonString("v159.7"),
                ),
                "source" to jsonObject(
                    "kind" to jsonString("modArchive"),
                    "name" to jsonString("fixture"),
                ),
                "status" to jsonString("success"),
                "summary" to "{}",
                "outputs" to "[" + jsonObject(
                    "kind" to jsonString("dataPackZip"),
                    "path" to jsonString(zip.toAbsolutePath().normalize().toString()),
                    "sizeBytes" to Files.size(zip).toString(),
                    "sha256" to jsonString(sha256),
                ) + "]",
            ),
            StandardCharsets.UTF_8,
        )
    }

    private fun testSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun multipart(boundary: String, filename: String, content: ByteArray): ByteArray {
        return multipart(
            boundary,
            TestPart("mode", null, "static".toByteArray(StandardCharsets.UTF_8)),
            TestPart("file", filename, content),
        )
    }

    private fun multipart(boundary: String, vararg parts: TestPart): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEachIndexed { index, part ->
            output.write("--$boundary\r\n".toByteArray(StandardCharsets.US_ASCII))
            val filename = part.filename?.let { "; filename=\"$it\"" }.orEmpty()
            output.write(
                "Content-Disposition: form-data; name=${part.name}$filename\r\n".toByteArray(
                    StandardCharsets.US_ASCII,
                ),
            )
            if (part.filename != null) output.write("Content-Type: application/octet-stream\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(part.content)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (index == parts.lastIndex) {
                output.write("--$boundary--\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
        }
        return output.toByteArray()
    }

    private data class TestPart(val name: String, val filename: String?, val content: ByteArray)

    private fun requestId(): String = UUID.randomUUID().toString()

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
