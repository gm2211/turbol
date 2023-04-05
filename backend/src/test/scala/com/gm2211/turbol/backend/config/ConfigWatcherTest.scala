package com.gm2211.turbol.backend.config

import com.gm2211.turbol.backend.config.ConfigWatcher
import com.gm2211.turbol.backend.config.runtime.{LoggingConfig, RuntimeConfig}
import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.util.{MoreExecutors, ZIOUtils}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.*
import zio.*
import zio.stream.*

import java.io.{File, IOException}
import java.nio.file.*
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

class ConfigWatcherTest extends AnyFlatSpec with Matchers with ZIOUtils with BackendLogging {
  given zio.Runtime[Any] = zio.Runtime.default

  "ConfigWatcher" should "watch config file" in {
    val testPath: Path = Files.createTempFile("test", ".conf")
    val configWatcher = ConfigWatcher.watchConfig(testPath, "")(using Executors.newFixedThreadPool(1))
    val countDownLatch = new java.util.concurrent.CyclicBarrier(2)
    var counter = 0

    configWatcher.onUpdate{a =>
      counter += 1
    }(using Executors.newFixedThreadPool(1))

    ZIO
      .loop(0)(_ < 2, _ + 1) { i =>
        ZIO.attemptBlocking{
          log.info(s"Writing file: $i")
          
          Files.writeString(testPath, s"Foo ${i}", StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
      }
      .forkDaemon
      .runUnsafe(using Executors.newFixedThreadPool(1))

    eventually(timeout(50.seconds), interval(100.millis)) {
      counter shouldBe 2
    }
  }
}
