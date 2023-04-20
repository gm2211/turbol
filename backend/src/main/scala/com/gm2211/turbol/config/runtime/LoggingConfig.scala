/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.config.runtime

import ch.qos.logback.classic.Level
import com.gm2211.turbol.util.ConfigSerialization
import io.circe.derivation.ConfiguredDecoder
import io.circe.{Decoder, HCursor}

import java.util.Locale
import scala.annotation.unused

final case class LoggingConfig(
  rootLoggerLevel: LogLevel = Info,
  levelByClassName: Map[String, LogLevel] = Map()
)

sealed trait LogLevel {
  private[config] lazy val strRepr = level.levelStr
  val level: Level
  override def toString: String = strRepr
}
case object Info extends LogLevel { override val level: Level = Level.INFO }
case object Debug extends LogLevel { override val level: Level = Level.DEBUG }

object LoggingConfig extends ConfigSerialization {
  given decoder: Decoder[LoggingConfig] = ConfiguredDecoder.derived[LoggingConfig]
}

@unused // Used by circe
object LogLevel {
  given decoder: Decoder[LogLevel] = (cursor: HCursor) => {
    cursor.value.asString.getOrElse("").toUpperCase(Locale.ROOT) match {
      case Info.strRepr => Right(Info)
      case Debug.strRepr => Right(Debug)
      case _ => Right(Info) // default to info
    }
  }
}
