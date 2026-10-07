/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{ProxySelector, URI}
import java.nio.file.Path
import java.time.Duration
import scala.util.{Failure, Success, Try}

/**
 * Small blocking HTTP helper shared by the NOMADS and live-traffic clients. Forces HTTP/1.1 because NOMADS sends a
 * malformed content-length over HTTP/2, and honours the JVM proxy settings (https.proxyHost) when present.
 */
object Http {
  private val client: HttpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .proxy(ProxySelector.getDefault)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  private val userAgent = "turbol/1.0 (+https://github.com/gm2211/turbol)"

  private def request(url: String, timeout: Duration, range: Option[(Long, Long)] = None): HttpRequest = {
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", userAgent)
    range.foreach { case (start, end) => builder.header("Range", s"bytes=$start-$end") }
    builder.GET().build()
  }

  /** GET with retries and exponential backoff (2s, 4s, 8s): NOMADS drops connections intermittently. */
  private def withRetries[T](attempts: Int)(call: => T): T = {
    var lastError: Throwable = null
    var attempt = 0
    while (attempt < attempts) {
      Try(call) match {
        case Success(value) => return value
        case Failure(e: NotFound) => throw e
        case Failure(e) =>
          lastError = e
          attempt += 1
          if (attempt < attempts) Thread.sleep(2000L << (attempt - 1))
      }
    }
    throw lastError
  }

  final class NotFound(url: String) extends RuntimeException(s"404 for $url")

  private def check[T](url: String, response: HttpResponse[T]): T = response.statusCode() match {
    case 200 | 206 => response.body()
    case 404 => throw NotFound(url)
    case code => throw RuntimeException(s"HTTP $code for $url")
  }

  def getString(url: String, timeout: Duration = Duration.ofSeconds(30), attempts: Int = 3): String =
    withRetries(attempts) {
      check(url, client.send(request(url, timeout), HttpResponse.BodyHandlers.ofString()))
    }

  def getBytes(url: String, range: Option[(Long, Long)] = None, attempts: Int = 4): Array[Byte] =
    withRetries(attempts) {
      val bytes =
        check(url, client.send(request(url, Duration.ofSeconds(90), range), HttpResponse.BodyHandlers.ofByteArray()))
      range.foreach { case (start, end) =>
        if (bytes.length != end - start + 1) throw RuntimeException(s"short range read for $url: ${bytes.length} bytes")
      }
      bytes
    }

  def download(url: String, destination: Path, attempts: Int = 4): Path =
    withRetries(attempts) {
      check(url, client.send(request(url, Duration.ofMinutes(5)), HttpResponse.BodyHandlers.ofFile(destination)))
    }
}
