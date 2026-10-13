/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import ucar.nc2.grib.GribNumbers
import ucar.nc2.grib.grib2.Grib2Record
import ucar.unidata.io.RandomAccessFile

import java.io.InputStream
import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

/**
 * Unpacks GRIB2 data template 5.40 (JPEG2000, what the GTGN nowcast uses) with OpenJPEG's `opj_decompress` into an
 * array the caller reuses, applying the same scaling as NetCDF-Java's `Grib2DataReader.getData40`, so values match it
 * bit for bit. NetCDF-Java's pure-Java decoder allocated about 40 MB per level, 2.2 GB per 51-level nowcast every 15
 * minutes; on prod's tenth of a CPU that was 16 full GCs per nowcast, each stalling every request. The native decoder
 * allocates nothing on the heap, runs in its own process (inheriting the caller's low CPU priority), and is several
 * times faster.
 */
object Jpeg2000 {
  private val tool = "opj_decompress"

  /** Whether `opj_decompress` is installed (it is in the Docker image; elsewhere callers fall back to NetCDF-Java). */
  lazy val available: Boolean = Try {
    val process = new ProcessBuilder(tool, "-h")
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    process.waitFor()
  }.isSuccess

  /**
   * Unpack `record` into `out` and return true, or return false (leaving `out` in an unspecified state) for anything
   * this doesn't handle, so the caller falls back to `record.readData`. Uses two scratch files in `workDir`.
   */
  def readInto(record: Grib2Record, raf: RandomAccessFile, out: Array[Float], workDir: Path): Boolean = {
    val drs = record.getDataRepresentationSection
    val total = record.getGDSsection.getNumberPoints
    val scanMode = record.getScanMode
    if (
      !available || drs.getDataTemplate != 40 || total != out.length || (scanMode != 0 && scanMode != 64) ||
      record.getGDS.isThin
    ) return false

    // Section 5, template 5.40 (octets 12-20).
    raf.seek(drs.getStartingPosition + 11)
    val r = raf.readFloat()
    val e = GribNumbers.int2(raf)
    val d = GribNumbers.int2(raf)
    val bitsPerValue = raf.read()
    val dataPoints = drs.getDataPoints
    val bitmap = record.getBitmapSection.getBitmap(raf)
    if (bitsPerValue == 0) return false
    if (if (bitmap == null) dataPoints != total else bitmap.length * 8 < total || dataPoints > total) return false

    val codestream = Files.createTempFile(workDir, "level", ".j2k")
    val image = workDir.resolve(codestream.getFileName.toString.stripSuffix(".j2k") + ".pgx")
    // opj_decompress names a single component's output <name>_0.pgx.
    val decoded = workDir.resolve(codestream.getFileName.toString.stripSuffix(".j2k") + "_0.pgx")
    try {
      val section = record.getDataSection
      Using.resource(Files.newOutputStream(codestream)) { stream =>
        val buffer = new Array[Byte](64 * 1024)
        raf.seek(section.getStartingPosition + 5)
        var left = section.getMsgLength - 5
        while (left > 0) {
          val read = raf.read(buffer, 0, math.min(left, buffer.length))
          if (read < 0) throw java.io.EOFException()
          stream.write(buffer, 0, read)
          left -= read
        }
      }
      val process = new ProcessBuilder(tool, "-i", codestream.toString, "-o", image.toString, "-quiet")
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      if (process.waitFor() != 0) return false
      val result = if (Files.exists(decoded)) decoded else image
      if (!Files.exists(result)) return false

      val dd = math.pow(10, d.toDouble).toFloat
      val ee = math.pow(2.0, e.toDouble).toFloat
      val in = new Samples(Files.newInputStream(result))
      try {
        Pgx.header(in).filter(_.samples == dataPoints) match {
          case None => false
          case Some(pgx) =>
            var p = 0
            while (p < total) {
              out(p) =
                if (bitmap == null || GribNumbers.testBitIsSet(bitmap(p / 8), p % 8)) (r + pgx.read(in) * ee) / dd
                else Float.NaN
              p += 1
            }
            in.read() == -1
        }
      } finally in.close()
    } finally {
      Files.deleteIfExists(codestream): Unit
      Files.deleteIfExists(image): Unit
      Files.deleteIfExists(decoded): Unit
    }
  }

  /** An unsynchronised buffered byte reader: the decoded image is read a byte at a time. */
  final private class Samples(in: InputStream) extends AutoCloseable {
    private val buffer = new Array[Byte](64 * 1024)
    private var position = 0
    private var limit = 0

    def read(): Int = {
      if (position == limit) {
        limit = math.max(in.read(buffer), 0)
        position = 0
      }
      if (limit == 0) -1
      else {
        position += 1
        buffer(position - 1) & 0xff
      }
    }

    def close(): Unit = in.close()
  }

  /** OpenJPEG's PGX: a text header ("PG ML + 6 677205 1") then one sample per point, big- or little-endian. */
  final private case class Pgx(bigEndian: Boolean, signed: Boolean, depth: Int, samples: Long) {
    private val bytes = if (depth <= 8) 1 else if (depth <= 16) 2 else 4

    def read(in: Samples): Int = {
      var value = 0
      var k = 0
      while (k < bytes) {
        val b = in.read()
        if (b < 0) throw java.io.EOFException()
        value = if (bigEndian) (value << 8) | b else value | (b << (8 * k))
        k += 1
      }
      if (signed && bytes < 4) (value << (32 - 8 * bytes)) >> (32 - 8 * bytes) else value
    }
  }

  private object Pgx {
    private val headerLine = """^PG\s+(ML|LM)\s*([+-]?)\s*(\d+)\s+(\d+)\s+(\d+)\s*$""".r

    def header(in: Samples): Option[Pgx] = {
      val line = new StringBuilder
      var b = in.read()
      while (b >= 0 && b != '\n' && line.length < 200) { line.append(b.toChar); b = in.read() }
      headerLine.findFirstMatchIn(line.toString).flatMap { m =>
        val depth = m.group(3).toInt
        Option.when(depth > 0 && depth <= 32)(Pgx(
          m.group(1) == "ML",
          m.group(2) == "-",
          depth,
          m.group(4).toLong * m.group(5).toLong
        ))
      }
    }
  }
}
