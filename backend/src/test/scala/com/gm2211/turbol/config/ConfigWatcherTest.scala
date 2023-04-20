package com.gm2211.turbol.config

import _root_.io.circe.Decoder
import com.gm2211.turbol.util.CatsUtils
import com.gm2211.turbol.util.MoreExecutors.*
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.*

import java.nio.file.*
import java.util.concurrent.Executors

final class ConfigWatcherTest extends AnyFunSuite with Matchers with CatsUtils {
  test("Refreshable config produced by watcher should call subscriber when watched config is updated") {
    val testPath: Path = Files.createTempFile("test", ".conf")
    
    Files.writeString(testPath, 0.toString, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    
    val refreshableConfig =
      ConfigWatcher.watchConfig[Int](testPath)
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
