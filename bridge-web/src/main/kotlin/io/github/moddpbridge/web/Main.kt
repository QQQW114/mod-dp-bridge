package io.github.moddpbridge.web

import java.util.concurrent.CountDownLatch

fun main() {
    val config = WebConfig.fromEnvironment()
    val server = BridgeWebServer(config)
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            runCatching { server.close() }
            stopped.countDown()
        }, "bridge-web-shutdown"),
    )
    server.start()
    val address = server.address
    println("mod-dp-bridge WebUI listening on http://${displayHost(address.hostString)}:${address.port}/")
    println("Work directory: ${config.workDirectory}")
    println("Allowed HTTP hosts: ${config.allowedHosts.sorted().joinToString()}")
    println("Upload limit: ${config.maxUploadBytes / (1024L * 1024L)} MiB; concurrent jobs: ${config.maxConcurrentJobs}")
    stopped.await()
}

private fun displayHost(host: String): String = if (':' in host && !host.startsWith('[')) "[$host]" else host
