/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

object StringUtils extends StringUtils // Allow ._ import
trait StringUtils {
  private val stringWithQuotesRegex = """^"(.*)"$""".r

  extension (string: String) {
    def toOption: Option[String] = if string.strip().nonEmpty then Some(string) else None

    def quotesStripped: String = string match {
      case stringWithQuotesRegex(str) => str
      case _ => string
    }
  }
}
