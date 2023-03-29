/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config

import ch.qos.logback.classic.Level
import io.circe.{Decoder, HCursor}

import java.util.Locale

final case class LoggingConfig(
  rootLoggerLevel: LogLevel = Info,
  levelByClassName: Map[String, LogLevel] = Map()
)

sealed trait LogLevel { val logbackLevel: Level }
object Info extends LogLevel {
  private[config] val stringRepr: String = "INFO"
  override val logbackLevel: Level = Level.INFO
  override def toString: String = stringRepr
}
object Debug extends LogLevel {
  private[config] val stringRepr: String = "DEBUG"
  override val logbackLevel: Level = Level.DEBUG
  override def toString: String = stringRepr
}
object LogLevel {
  implicit val decodeLogLevel: Decoder[LogLevel] = (cursor: HCursor) => {
    cursor.value.asString.getOrElse("").toUpperCase(Locale.US) match {
      case Info.stringRepr => Right(Info)
      case Debug.stringRepr => Right(Debug)
      case _ => Right(Info) // default to info
    }
  }
}
