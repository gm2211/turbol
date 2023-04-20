package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.util.DBUtils
import doobie.ConnectionIO

trait DBStore extends DBUtils {
  def createTableIfNotExists(): ConnectionIO[Any]
}
