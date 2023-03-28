package com.gm2211.turbol.backend.logging

import java.nio.file.Path

trait Args {

  def configPath(path: Path): SafeArg[String] = {
    SafeArg("configPath", path.toString)
  }
}
