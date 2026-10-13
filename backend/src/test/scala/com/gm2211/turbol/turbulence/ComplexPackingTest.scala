/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest
import ucar.nc2.grib.grib2.Grib2RecordScanner
import ucar.unidata.io.RandomAccessFile

import java.nio.file.Paths
import scala.util.Using

class ComplexPackingTest extends BaseTest {
  private def bits(values: Array[Float]): Seq[Int] =
    Vector.tabulate(values.length)(i => java.lang.Float.floatToRawIntBits(values(i)))

  // One message each, cut from real NOMADS files: a GTG forecast level (with a bitmap) and a GFS 0.25 degree field.
  Seq("grib/gtg-forecast-level.grib2", "grib/gfs-field.grib2").foreach { resource =>
    test(s"unpacks $resource exactly as NetCDF-Java does, into the array it's given") {
      val path = Paths.get(getClass.getClassLoader.getResource(resource).toURI).toString
      Using.resource(new RandomAccessFile(path, "r")) { raf =>
        val scanner = new Grib2RecordScanner(raf)
        val record = scanner.next()
        val expected: Array[Float] = record.readData(raf)
        val out = new Array[Float](expected.length)
        ComplexPacking.readInto(record, raf, out) shouldBe true
        bits(out) shouldBe bits(expected)
        ComplexPacking.readInto(record, raf, new Array[Float](expected.length + 1)) shouldBe false
      }
    }
  }

  test("unpacks a JPEG2000 GTGN level exactly as NetCDF-Java does, with OpenJPEG") {
    assume(Jpeg2000.available, "opj_decompress isn't installed (apt install libopenjp2-tools)")
    val path = Paths.get(getClass.getClassLoader.getResource("grib/gtgn-nowcast-level.grib2").toURI)
    val workDir = java.nio.file.Files.createTempDirectory("j2k")
    Using.resource(new RandomAccessFile(path.toString, "r")) { raf =>
      val record = new Grib2RecordScanner(raf).next()
      val expected: Array[Float] = record.readData(raf)
      val out = new Array[Float](expected.length)
      Jpeg2000.readInto(record, raf, out, workDir) shouldBe true
      bits(out) shouldBe bits(expected)
      ComplexPacking.readInto(record, raf, out) shouldBe false
      java.nio.file.Files.list(workDir).count() shouldBe 0L
    }
  }
}
