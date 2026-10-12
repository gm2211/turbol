/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}

class NowcastHandoffTest extends BaseTest {
  private def nowcast(dataDir: Path, valid: Long, complete: Boolean = true): Path = {
    val dir = dataDir.resolve("nowcast").resolve(valid.toString)
    Files.createDirectories(dir)
    Files.write(dir.resolve("35000.u8"), Array.tabulate[Byte](300_000)(i => (i % 251).toByte)): Unit
    Files.write(dir.resolve("1000.u8"), Array[Byte](1, 2, 3)): Unit
    if (complete) Files.writeString(dir.resolve("COMPLETE"), "url"): Unit
    dir
  }

  private def packed(dir: Path): Array[Byte] = {
    val out = ByteArrayOutputStream()
    NowcastHandoff.write(dir, out)
    out.toByteArray
  }

  test("the new instance gets the running one's newest complete nowcast, file for file") {
    val old = Files.createTempDirectory("old")
    nowcast(old, 100)
    nowcast(old, 200)
    nowcast(old, 300, complete = false)
    val dir = NowcastHandoff.latest(old).get
    dir.getFileName.toString shouldBe "200"

    val fresh = Files.createTempDirectory("new")
    val file = Files.createTempFile("handoff", ".bin")
    Files.write(file, packed(dir)): Unit
    NowcastHandoff.unpack(file, fresh) shouldBe 200L
    val copy = fresh.resolve("nowcast/200")
    for (name <- Seq("35000.u8", "1000.u8", "COMPLETE"))
      Files.readAllBytes(copy.resolve(name)) shouldBe Files.readAllBytes(dir.resolve(name))
    NowcastHandoff.latest(fresh) shouldBe Some(copy)
  }

  test("a cut-off transfer or an older instance's web page leaves nothing behind") {
    val old = Files.createTempDirectory("old")
    val bytes = packed(nowcast(old, 100))
    val fresh = Files.createTempDirectory("new")
    val file = Files.createTempFile("handoff", ".bin")

    Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 20)): Unit
    an[RuntimeException] should be thrownBy NowcastHandoff.unpack(file, fresh)
    NowcastHandoff.latest(fresh) shouldBe None
    Files.exists(fresh.resolve("nowcast/100")) shouldBe false

    Files.writeString(file, "<!DOCTYPE html><html><body>turbol</body></html>"): Unit
    an[IllegalArgumentException] should be thrownBy NowcastHandoff.unpack(file, fresh)
    NowcastHandoff.latest(fresh) shouldBe None
  }
}
