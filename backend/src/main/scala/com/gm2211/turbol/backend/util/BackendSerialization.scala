/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import io.circe.derivation.Configuration
import io.circe.parser.decode as decodeJson
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

import scala.util.Try

trait BackendSerialization:
  implicit val customConfig: Configuration = Configuration
    .default
    .withDefaults
    .withDiscriminator("@type") // same as @JsonTypeInfo(property = "@type")

  extension [T](value: T) {
    def toJson(implicit e: Encoder[T]): Json = value.asJson
  }
  extension (string: String) {
    def fromJson[T](implicit decoder: Decoder[T]): Try[T] = Try {
      decodeJson(string)(decoder)
    }.flatMap(_.toTry)
  }

  extension (json: Json) {
    def parse[T](implicit d: Decoder[T]): Try[T] =
      Try { json.as[T] }.flatMap(_.toTry)
  }
