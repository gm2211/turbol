/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import ucar.nc2.grib.GribNumbers
import ucar.nc2.grib.grib2.Grib2Record
import ucar.unidata.io.RandomAccessFile

/**
 * Unpacks GRIB2 data template 5.3 (complex packing with spatial differencing, what GTG, GTGN and GFS use) into an array
 * the caller reuses, with the same arithmetic as NetCDF-Java's `Grib2DataReader.getData3`, so values match it bit for
 * bit. `record.readData` allocates two to three grid-sized arrays per field instead: about 18 MB per GTG level, close
 * to 1 GB per nowcast, which on prod's tenth of a CPU meant a run of 0.5-1 s full GCs every time new data landed.
 */
object ComplexPacking {

  /**
   * Unpack `record` into `out` and return true, or return false (leaving `out` in an unspecified state) for anything
   * this doesn't handle, so the caller falls back to `record.readData`.
   */
  def readInto(record: Grib2Record, raf: RandomAccessFile, out: Array[Float]): Boolean = {
    val drs = record.getDataRepresentationSection
    val total = record.getGDSsection.getNumberPoints
    val scanMode = record.getScanMode
    if (drs.getDataTemplate != 3 || total != out.length || (scanMode != 0 && scanMode != 64) || record.getGDS.isThin)
      return false

    // Section 5, template 5.3 (octets 12-49).
    raf.seek(drs.getStartingPosition + 11)
    val r = raf.readFloat()
    val e = GribNumbers.int2(raf)
    val d = GribNumbers.int2(raf)
    val bitsGroupRef = raf.read()
    raf.skipBytes(2)
    val missingManagement = raf.read()
    raf.skipBytes(8)
    val groups = GribNumbers.int4(raf)
    val refGroupWidth = raf.read()
    val bitsGroupWidth = raf.read()
    val refGroupLength = GribNumbers.int4(raf)
    val lengthIncrement = raf.read()
    val lastGroupLength = GribNumbers.int4(raf)
    val bitsGroupLength = raf.read()
    val order = raf.read()
    val descriptorBits = raf.read() * 8
    val dataPoints = drs.getDataPoints

    val bitmap = record.getBitmapSection.getBitmap(raf)
    if (missingManagement != 0 || groups <= 0 || descriptorBits <= 0 || (order != 1 && order != 2)) return false
    if (if (bitmap == null) dataPoints != total else bitmap.length * 8 < total || dataPoints > total) return false

    val bits = new Bits(raf, record.getDataSection.getStartingPosition + 5)
    def signed(): Int = {
      val negative = bits.read(1) == 1
      val magnitude = bits.read(descriptorBits - 1)
      if (negative) -magnitude else magnitude
    }
    val first = signed()
    val second = if (order == 2) signed() else 0
    val minimum = signed()

    val groupRef = new Array[Int](groups)
    if (bitsGroupRef != 0) {
      bits.alignToByte(); var i = 0; while (i < groups) { groupRef(i) = bits.read(bitsGroupRef); i += 1 }
    }
    val groupWidth = new Array[Int](groups)
    if (bitsGroupWidth != 0) {
      bits.alignToByte(); var i = 0; while (i < groups) { groupWidth(i) = bits.read(bitsGroupWidth); i += 1 }
    }
    val groupLength = new Array[Int](groups)
    if (bitsGroupLength != 0) {
      bits.alignToByte(); var i = 0; while (i < groups) { groupLength(i) = bits.read(bitsGroupLength); i += 1 }
    }
    var sum = 0L
    var i = 0
    while (i < groups) {
      groupWidth(i) += refGroupWidth
      groupLength(i) = groupLength(i) * lengthIncrement + refGroupLength
      i += 1
    }
    groupLength(groups - 1) = lastGroupLength
    i = 0
    while (i < groups) { sum += groupLength(i); i += 1 }
    if (sum != dataPoints) return false

    // The packed values, then undo the differencing. Float throughout, as NetCDF-Java does.
    bits.alignToByte()
    var count = 0
    i = 0
    while (i < groups) {
      val width = groupWidth(i)
      val ref = groupRef(i)
      var j = 0
      while (j < groupLength(i)) {
        out(count) = (if (width != 0) bits.read(width) + ref else ref).toFloat
        count += 1
        j += 1
      }
      i += 1
    }
    out(0) = first.toFloat
    if (order == 1) {
      var k = 1
      while (k < dataPoints) { out(k) += minimum; out(k) = out(k) + out(k - 1); k += 1 }
    } else {
      if (dataPoints > 1) out(1) = second.toFloat
      var k = 2
      while (k < dataPoints) { out(k) += minimum; out(k) = out(k) + (2 * out(k - 1)) - out(k - 2); k += 1 }
    }
    val dd = math.pow(10, d.toDouble).toFloat
    val ee = math.pow(2.0, e.toDouble).toFloat
    var k = 0
    while (k < dataPoints) { out(k) = (r + (out(k) * ee)) / dd; k += 1 }

    // Spread the packed values over the bitmap's points, back to front so it works in place.
    if (bitmap != null) {
      var packed = dataPoints - 1
      var p = total - 1
      while (p >= 0) {
        if (GribNumbers.testBitIsSet(bitmap(p / 8), p % 8)) {
          if (packed < 0) return false
          out(p) = out(packed)
          packed -= 1
        } else out(p) = Float.NaN
        p -= 1
      }
      if (packed != -1) return false
    }
    true
  }

  /** Big-endian bit reader over the file, like NetCDF-Java's `BitReader`. */
  final private class Bits(raf: RandomAccessFile, start: Long) {
    raf.seek(start)
    private var buffer = 0
    private var left = 0

    def alignToByte(): Unit = left = 0

    def read(count: Int): Int = {
      var result = 0
      var need = count
      while (need > 0) {
        if (left == 0) {
          val b = raf.read()
          if (b < 0) throw java.io.EOFException()
          buffer = b
          left = 8
        }
        val take = math.min(need, left)
        result = (result << take) | ((buffer >> (left - take)) & ((1 << take) - 1))
        need -= take
        left -= take
      }
      result
    }
  }
}
