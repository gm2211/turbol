package com.gm2211.turbol.backend.config

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.backend.config.ConfigWatcher
import com.gm2211.turbol.backend.config.runtime.{LoggingConfig, RuntimeConfig}
import com.gm2211.turbol.backend.util.CatsUtils
import com.gm2211.turbol.backend.util.MoreExecutors.*
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.*

import java.io.{File, IOException}
import java.nio.file.*
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

final class ConfigWatcherTest extends AnyFunSuite with Matchers with CatsUtils {
  test("Refreshable config produced by watcher should call subscriber when watched config is updated") {
    val testPath: Path = Files.createTempFile("test", ".conf")
    val refreshableConfig = ConfigWatcher.watchConfig(testPath, 0)
    var counter = 0

    refreshableConfig.onUpdate { _ => counter += 1 }(using Executors.newFixedThreadPool(1).toExecutionContext)

    eventually(timeout(5.seconds), interval(100.millis)) {
      counter shouldBe 1
    }

    (1 to 2).foreach { i =>
      Files.writeString(testPath, s"$i", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      eventually(timeout(5.seconds), interval(100.millis)) {
        counter shouldBe i + 1
      }
    }

    refreshableConfig.get shouldBe 12
  }
}
