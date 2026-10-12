/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

import cats.effect.IO
import com.gm2211.turbol.turbulence.Http

import scala.concurrent.duration.*

/**
 * Render's free tier stops an instance after 15 minutes without inbound requests, which also stops the turbulence
 * refresh. Pinging our own public URL (RENDER_EXTERNAL_URL, set by Render) every 10 minutes keeps it running.
 */
final class KeepAwake(publicUrl: String, every: FiniteDuration = 10.minutes) extends BackgroundJob {
  override def run(): IO[Unit] =
    (IO.sleep(every) >> ping.handleErrorWith(e => IO(log.warn("Keep-awake ping failed", unsafe("error", e))))).foreverM

  private def ping: IO[Unit] =
    IO.blocking(Http.getString(s"${publicUrl.stripSuffix("/")}/api/config", attempts = 1)).void
}

object KeepAwake {
  /** The service's public URL on Render; unset elsewhere. */
  def publicUrl: Option[String] = sys.env.get("RENDER_EXTERNAL_URL").filter(_.nonEmpty)

  def fromEnv: Option[KeepAwake] = publicUrl.map(KeepAwake(_))
}
