/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.logging.BackendLogging
import io.circe.derivation.Configuration
import io.circe.{Decoder, Json}

import java.io.File
import scala.io.Source
import scala.util.{Failure, Success, Try}

trait ConfigSerialization extends BackendLogging {
  import io.circe.yaml.parser.parse as decodeYaml

  given customConfig: Configuration =
    Configuration
      .default
      .withKebabCaseMemberNames
      .withDefaults // uses defaults if values are missing in config
      .withStrictDecoding // like FailOnUnknown in Jackson
      .withDiscriminator("case-class-type")

  extension (file: File) {
    def fromYaml[T: Decoder]: Try[T] = {
      import FileUtils.*
      file.usingStreamReader { inputStreamReader =>
        for
          yamlOrError <- Try { decodeYaml(inputStreamReader) }
          yaml <- yamlOrError.toTry
          obj <- yaml.as[T].toTry
        yield obj
      }.flatten
    }
  }

  extension (source: Source) {
    def fromYaml[T: Decoder]: Try[T] = {
      def logFailureAndConvertToScala(message: String, failureMessage: String): Failure[T] = {
        log.info(message, unsafe("failure", failureMessage))
        Failure(new RuntimeException(failureMessage))
      }

      decodeYaml(source.mkString) match {
        case Left(parsingFailure) =>
          logFailureAndConvertToScala("Error while parsing yaml", parsingFailure.underlying.toString)
        case Right(json) =>
          json.as[T] match {
            case Left(decodingFailure) =>
              logFailureAndConvertToScala("Error while converting yaml to json", decodingFailure.toString())
            case Right(value) =>
              Success(value)
          }
      }
    }
  }

  override def clazz: Class[_] = classOf[ConfigSerialization]
}
