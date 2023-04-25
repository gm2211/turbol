/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import java.io.{File, FileInputStream, InputStreamReader, PrintWriter}
import java.nio.charset.{Charset, StandardCharsets}
import scala.collection.mutable
import scala.io.{Codec, Source}
import scala.util.{Try, Using}

object FileUtils extends FileUtils // Allow ._ import
trait FileUtils {
  import ExpressionUtils.*
  extension (file: File) {
    def inputStream: FileInputStream = new FileInputStream(file)
    
    def usingStreamReader[T]: (InputStreamReader => T) => Try[T] =
      Using(InputStreamReader(file.inputStream))
      
    def usingStreamWriter: (PrintWriter => Unit) => Try[Unit] =
      Using(PrintWriter(file))

    def readLines(charset: Charset = StandardCharsets.UTF_8): Seq[String] = {
      val lines = mutable.ListBuffer[String]()
      forEachLine((line, _) => lines.append(line).ignoreRetValue(), charset)
      lines.toList
    }

    def forEachLine(
      lineWithIndexProcessor: (String, Int) => Unit,
      charset: Charset = StandardCharsets.UTF_8
    ): Try[Unit] = {
      Using(Source.fromFile(file)(Codec(charset))) { source =>
        source.getLines().zipWithIndex.foreach(lineWithIndexProcessor.tupled)
      }
    }
  }
}
