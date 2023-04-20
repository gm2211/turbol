/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import java.io.{File, FileInputStream, InputStreamReader}
import java.nio.charset.{Charset, StandardCharsets}
import scala.io.{Codec, Source}
import scala.util.{Try, Using}

object FileUtils extends FileUtils // Allow ._ import
trait FileUtils {
  extension (file: File) {
    def inputStream: FileInputStream = new FileInputStream(file)
    def usingStreamReader[T]: (InputStreamReader => T) => Try[T] =
      Using(new InputStreamReader(file.inputStream))

    def readLines(charset: Charset = StandardCharsets.UTF_8): Seq[String] =
      Using(Source.fromFile(file)(Codec(charset))) { source =>
        source.getLines().toSeq
      }.getOrElse(Seq.empty)
  }
}
