package com.gm2211.turbol.backend.storage.stores

import doobie.ConnectionIO

trait DBStore {
  def createTableIfNotExists(): ConnectionIO[Unit]
}
