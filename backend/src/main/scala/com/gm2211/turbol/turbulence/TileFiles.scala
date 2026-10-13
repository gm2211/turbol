/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.logging.BackendLogging

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Rendered map tiles on disk, for nginx to serve (deployment/render/nginx.conf) without the JVM. On a tenth of a CPU
 * each tile request through the JVM cost ~7 ms of CPU: an opening map's ~30 tiles used the CPU quota for 2 s, and the
 * app's own scripts, sent by nginx in the same container, stalled behind them. Laid out as
 * `<frame id>/<level>/<z>/<x>/<y>.png`, as in the tile URLs, with `latest` linking to the current nowcast's frame.
 * Off (every call a no-op) unless TURBOL_TILE_DIR names the directory.
 */
object TileFiles extends BackendLogging {
  private val dir: Option[Path] = sys.env.get("TURBOL_TILE_DIR").filter(_.nonEmpty).map(Paths.get(_))
  private val latest = new AtomicReference[String]("")
  /** Frames kept on disk besides `latest`: the map's forecast slider asks for recent ones by id. */
  private val framesKept = 24

  /** Saves a tile if it isn't on disk yet; never fails the caller. */
  def write(frameId: String, levelFt: Int, z: Int, x: Int, y: Int, png: Array[Byte]): Unit =
    dir.filter(_ => validId(frameId)).foreach { root =>
      Try {
        val file = root.resolve(s"$frameId/$levelFt/$z/$x/$y.png")
        if (!Files.exists(file)) {
          Files.createDirectories(file.getParent)
          val tmp = Files.createTempFile(file.getParent, ".tile", ".tmp")
          Files.write(tmp, png)
          // Temp files start owner-only; nginx's workers may run as another user.
          Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"))
          Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING): Unit
        }
      }.failed.foreach(e => log.warn("Couldn't save a tile for nginx", e))
    }

  /** Points `latest` at the current nowcast's frame (atomically) and drops the oldest frames' tiles. */
  def pointLatest(frameId: String): Unit =
    dir.filter(_ => validId(frameId) && latest.getAndSet(frameId) != frameId).foreach { root =>
      Try {
        Files.createDirectories(root.resolve(frameId))
        val link = root.resolve(s".latest-${System.nanoTime()}")
        Files.createSymbolicLink(link, Paths.get(frameId))
        Files.move(
          link,
          root.resolve("latest"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        ): Unit
        val frames =
          Files.list(root).iterator().asScala.toList.filter(p => Files.isDirectory(p) && !Files.isSymbolicLink(p))
        frames
          .filterNot(_.getFileName.toString == frameId)
          .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
          .drop(framesKept)
          .foreach(delete)
      }.failed.foreach(e => log.warn("Couldn't point nginx's latest tiles at the new nowcast", e))
    }

  private def validId(frameId: String): Boolean = frameId.nonEmpty &&
    frameId.forall(c => c.isLetterOrDigit || "_.-".contains(c)) && frameId != "latest"

  private def delete(p: Path): Unit =
    Files.walk(p).iterator().asScala.toList.reverse.foreach(f => Try(Files.delete(f)))
}
