/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import java.nio.file.{Path, Paths}
import scala.io.Source

object StringUtils extends StringUtils // Allow ._ import
trait StringUtils {

  extension (string: String) {
    def toOption: Option[String] = if string.strip().nonEmpty then Some(string) else None

    def quotesStripped: String = stripped('"')

    def stripped(charToStrip: Char): String = {
      if (string.isEmpty) string
      else if (string.head == charToStrip && string.last == charToStrip) string.tail.init
      else string
    }
    
    def asResource: Source = {
      Source.fromURL(getClass.getClassLoader.getResource(string))
    }
    
    def asPath: Path = {
      Paths.get(string)
    }

    def withoutInitial(charToRemove: Char): String = {
      if (string.isEmpty) string
      else if (string.head == charToRemove) string.tail
      else string
    }

    def withoutFinal(charToRemove: Char): String = {
      if (string.isEmpty) string
      else if (string.last == charToRemove) string.init
      else string
    }
  }
}
