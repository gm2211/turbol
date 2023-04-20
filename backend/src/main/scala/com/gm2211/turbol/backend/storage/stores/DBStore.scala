package com.gm2211.turbol.backend.storage.stores

import com.gm2211.turbol.backend.util.DBUtils
import doobie.ConnectionIO

trait DBStore extends DBUtils {
  def createTableIfNotExists(): ConnectionIO[Any]
}
