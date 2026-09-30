package io.github.aleixrodriala.quadern.auth

import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * A tiny HTTP server on localhost that catches the OAuth redirect
 * (`http://localhost:1455/auth/callback?code=...`). The browser is on the same phone, so
 * "localhost" is this app. Listens on IPv4 and IPv6 loopback because browsers may try either.
 */
class LoopbackServer(
    private val port: Int,
    private val onCallback: (pathAndQuery: String) -> Boolean,
) : Closeable {
    private val sockets = mutableListOf<ServerSocket>()

    fun start() {
        for (host in listOf("127.0.0.1", "::1")) {
            runCatching {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName(host), port))
                sockets += s
                thread(name = "oauth-loopback-$host", isDaemon = true) { acceptLoop(s) }
            }.onFailure { Log.w(TAG, "Could not listen on $host:$port", it) }
        }
        if (sockets.isEmpty()) throw java.io.IOException("Port $port is busy. Close other sign-in attempts and try again.")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            thread(isDaemon = true) { handle(client) }
        }
    }

    private fun handle(socket: Socket): Unit = socket.use { s ->
        try {
            s.soTimeout = 10_000
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return@use
            // Drain headers so the browser doesn't see a reset.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val (status, body) = if (target.startsWith("/auth/callback")) {
                val ok = onCallback(target)
                if (ok) "200 OK" to SUCCESS_PAGE else "400 Bad Request" to FAILURE_PAGE
            } else {
                "404 Not Found" to ""
            }
            val bytes = body.toByteArray()
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        } catch (_: SocketTimeoutException) {
        } catch (e: Exception) {
            Log.w(TAG, "Loopback request failed", e)
        }
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private companion object {
        const val TAG = "LoopbackServer"
        const val STYLE = """
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;
                   justify-content:center;background:#fff;color:#111}
              @media (prefers-color-scheme:dark){body{background:#111;color:#eee} a{background:#eee!important;color:#111!important}}
              main{text-align:center;padding:24px;max-width:420px}
              h1{font-size:26px;margin:0 0 8px} p{color:#777;font-size:16px;line-height:1.5}
              a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:999px;background:#111;color:#fff;
                text-decoration:none;font-weight:600}
            </style>"""
        const val SUCCESS_PAGE = """<!doctype html><html><head><title>Signed in</title>$STYLE</head><body><main>
            <h1>You're signed in</h1><p>Quadern can now transcribe with your ChatGPT subscription.</p>
            <a href="quadern://auth/done">Back to Quadern</a></main></body></html>"""
        const val FAILURE_PAGE = """<!doctype html><html><head><title>Sign-in failed</title>$STYLE</head><body><main>
            <h1>That didn't work</h1><p>Go back to Quadern and try signing in again.</p>
            <a href="quadern://auth/failed">Back to Quadern</a></main></body></html>"""
    }
}
