/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import io.circe.{Decoder, Json, ParsingFailure}

import java.io.*
import java.nio.charset.{Charset, StandardCharsets}
import scala.collection.mutable
import scala.io.{Codec, Source}
import scala.util.{Try, Using}

object FileUtils extends FileUtils // Allow ._ import
trait FileUtils {
  import ExpressionUtils.*
  extension (file: File) {
    def inputStream: FileInputStream = {
      new FileInputStream(file)
    }

    def usingStreamReader[T](action: InputStreamReader => T): Try[T] = {
      Using(InputStreamReader(file.inputStream))(action)
    }

    def usingStreamWriter: (PrintWriter => Unit) => Try[Unit] = {
      Using(PrintWriter(file))
    }

    def readAs[T: Decoder](decodingFunction: (Reader => Either[ParsingFailure, Json])): Try[T] = {
      file.usingStreamReader { inputStreamReader =>
        for
          yamlOrError <- Try { decodingFunction(inputStreamReader) }
          yaml <- yamlOrError.toTry
          obj <- yaml.as[T].toTry
        yield obj
      }.flatten
    }

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
