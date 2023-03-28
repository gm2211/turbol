/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util
import com.gm2211.turbol.backend.logging.BackendLogging
import io.circe.derivation.Configuration
import io.circe.{Decoder, Encoder, Json}

import java.io.{File, InputStreamReader}
import java.nio.file.{Path, Paths}
import scala.io.Source
import scala.util.{Failure, Success, Try, Using}

trait ConfigSerialization extends BackendLogging {
  import io.circe.parser.decode as decodeJson
  import io.circe.syntax.*
  import io.circe.yaml.parser.parse as decodeYaml
  import io.circe.yaml.syntax.*

  implicit val customConfig: Configuration =
    Configuration.default
      .withKebabCaseMemberNames
      .withDefaults // uses defaults if values are missing in config
      .withStrictDecoding                                       // like FailOnUnknown in Jackson
      .withDiscriminator("case-class-type")

  // This way we can deserialize primitives as their corresponding wrappers (if the wrappers extend AnyVal)

  extension [T](value: T) {
    def toYaml(implicit e: Encoder[T]): YamlSyntax = value.asJson.asYaml
  }
  implicit class CirceYamlFile(file: File) extends FileUtils {
    def fromYaml[T](implicit d: Decoder[T]): Try[T] = {
      file.usingStreamReader { inputStreamReader =>
        for
          yamlOrError <- Try { decodeYaml(inputStreamReader) }
          yaml        <- yamlOrError.toTry
          obj         <- yaml.as[T].toTry
        yield obj
      }.flatten
    }
  }
  implicit class CirceYamlSource(source: Source) {
    def fromYaml[T](implicit d: Decoder[T]): Try[T] = {
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
