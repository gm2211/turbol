/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import ucar.nc2.grib.grib2.Grib2RecordScanner
import ucar.unidata.io.RandomAccessFile

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.immutable.SortedMap
import scala.util.Using

/**
 * Decodes GTG/GTGN GRIB2 files (JPEG2000-packed, read with NetCDF-Java) into one-byte-per-point layer files that are
 * then memory-mapped, so the heap only holds small buffers.
 */
object GribDecoder {
  // GRIB2 discipline 0 / category 19 / parameter 30 = EDPARM, the blended EDR field in both GTGN and GTG.
  private val edrDiscipline = 0
  private val edrCategory = 19
  private val edrParameter = 30

  /** Altitude levels are stored in whole metres; the published ladder is 100 ft then every 1,000 ft. */
  def metresToLevelFt(metres: Double): Int = (math.round(metres / 0.3048 / 100.0) * 100).toInt

  /**
   * Decode every EDPARM message in `gribFile` whose level is in `keepLevelsFt` (all when empty) into
   * `outputDir/<levelFt>.u8` and return the memory-mapped layers keyed by level in feet.
   */
  def decodeToLayers(gribFile: Path, outputDir: Path, keepLevelsFt: Set[Int] = Set.empty): SortedMap[Int, EdrLayer] = {
    Files.createDirectories(outputDir)
    val written = Using.resource(new RandomAccessFile(gribFile.toString, "r")) { raf =>
      val scanner = new Grib2RecordScanner(raf)
      val levels = Vector.newBuilder[Int]
      while (scanner.hasNext) {
        val record = scanner.next()
        val pds = record.getPDS
        val isEdr = record.getDiscipline == edrDiscipline &&
          pds.getParameterCategory == edrCategory &&
          pds.getParameterNumber == edrParameter
        val levelFt = metresToLevelFt(pds.getLevelValue1)
        if (isEdr && (keepLevelsFt.isEmpty || keepLevelsFt.contains(levelFt))) {
          val values: Array[Float] = record.readData(raf)
          require(
            values.length == LambertGrid.nx * LambertGrid.ny,
            s"unexpected grid size ${values.length} in $gribFile"
          )
          val bytes = new Array[Byte](values.length)
          var k = 0
          while (k < values.length) {
            bytes(k) = EdrLayer.encode(values(k))
            k += 1
          }
          Files.write(outputDir.resolve(s"$levelFt.u8"), bytes)
          levels += levelFt
        }
      }
      levels.result()
    }
    SortedMap.from(written.map(levelFt => levelFt -> mapLayer(outputDir.resolve(s"$levelFt.u8"), levelFt)))
  }

  def mapLayer(file: Path, levelFt: Int): EdrLayer =
    Using.resource(FileChannel.open(file, StandardOpenOption.READ)) { channel =>
      EdrLayer(levelFt, channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
    }
}
