/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.logging.BackendLogging

import java.io.{
  BufferedInputStream,
  BufferedOutputStream,
  DataInputStream,
  DataOutputStream,
  EOFException,
  OutputStream
}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/**
 * Hands the decoded nowcast from the running instance to the one replacing it, so a deploy doesn't leave the map
 * without turbulence. The disk doesn't survive a deploy, and downloading and decoding a GTGN file on a tenth of a CPU
 * took the new instance about 4 minutes; meanwhile Render keeps sending the public URL to the old instance until the
 * new one answers, so the new one asks the old one for its nowcast before it starts serving.
 *
 * The format is the nowcast directory's files back to back, each as its name (UTF), length and bytes, with the
 * COMPLETE marker last so a cut-off transfer is never loaded.
 */
object NowcastHandoff extends BackendLogging {
  val path = "/api/turbulence/handoff/nowcast"
  private val completeMarker = "COMPLETE"
  // An older instance answers unknown paths with the frontend's index.html: this tells the two apart.
  private val magic = "turbol-nowcast-handoff-1"

  /** The newest complete nowcast directory under `dataDir`. */
  def latest(dataDir: Path): Option[Path] =
    TurbulenceDataUpdater
      .subdirs(dataDir.resolve("nowcast"))
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)

  def write(dir: Path, out: OutputStream): Unit = {
    val data = DataOutputStream(BufferedOutputStream(out, 64 * 1024))
    data.writeUTF(magic)
    data.writeLong(dir.getFileName.toString.toLong)
    val files = Using.resource(Files.list(dir))(_.iterator().asScala.toVector).filter(Files.isRegularFile(_))
    val (marker, layers) = files.partition(_.getFileName.toString == completeMarker)
    (layers ++ marker).foreach { file =>
      data.writeUTF(file.getFileName.toString)
      data.writeLong(Files.size(file))
      Files.copy(file, data): Unit
    }
    data.flush()
  }

  /**
   * Fetch the running instance's nowcast into `dataDir`, where the updater's loadCached() finds it. Returns its valid
   * time (epoch seconds) if one arrived whole; anything else (no other instance, an older one without this endpoint,
   * a dropped connection) leaves the fresh start to NOMADS as before.
   */
  def fetch(publicUrl: String, dataDir: Path): Option[Long] = {
    val nowcastDir = dataDir.resolve("nowcast")
    Files.createDirectories(nowcastDir)
    val tmp = Files.createTempFile(nowcastDir, "handoff", ".bin")
    val result = Try {
      Http.download(s"${publicUrl.stripSuffix("/")}$path", tmp, attempts = 1)
      unpack(tmp, dataDir)
    }
    Files.deleteIfExists(tmp): Unit
    result.fold(
      e => { log.info("No nowcast from the running instance", unsafe("reason", e.getMessage)); None },
      valid => { log.info("Took over the running instance's nowcast", safe("valid", valid)); Some(valid) }
    )
  }

  /** Unpack what [[write]] wrote into `dataDir`'s nowcast directory; returns its valid time (epoch seconds). */
  def unpack(file: Path, dataDir: Path): Long =
    Using.resource(DataInputStream(BufferedInputStream(Files.newInputStream(file), 64 * 1024))) { in =>
      require(Try(in.readUTF()).toOption.contains(magic), "the running instance has no handoff endpoint")
      val valid = in.readLong()
      val dir = dataDir.resolve("nowcast").resolve(valid.toString)
      TurbulenceDataUpdater.deleteRecursively(dir)
      Files.createDirectories(dir)
      var complete = false
      try {
        while (!complete) {
          val name = in.readUTF()
          require(!name.contains('/') && !name.contains('\\') && !name.startsWith("."), s"bad file name $name")
          val size = in.readLong()
          Using.resource(Files.newOutputStream(dir.resolve(name)))(out => copy(in, out, size))
          complete = name == completeMarker
        }
      } catch {
        case _: EOFException => ()
      }
      if (!complete) {
        TurbulenceDataUpdater.deleteRecursively(dir)
        throw RuntimeException("the transfer ended early")
      }
      valid
    }

  private def copy(in: DataInputStream, out: OutputStream, size: Long): Unit = {
    val buffer = new Array[Byte](64 * 1024)
    var left = size
    while (left > 0) {
      val n = in.read(buffer, 0, math.min(buffer.length.toLong, left).toInt)
      if (n < 0) throw EOFException()
      out.write(buffer, 0, n)
      left -= n
    }
  }
}
