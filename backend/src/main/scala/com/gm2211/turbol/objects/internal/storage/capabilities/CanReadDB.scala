/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.capabilities

import doobie.ConnectionIO

case object CanReadDB extends TxnCapability {
  extension (action: ConnectionIO[Unit]) {
    def just: Seq[ConnectionIO[Unit]] = Seq(action)
    def thenDo(other: ConnectionIO[Unit]): Seq[ConnectionIO[Unit]] = Seq(action, other)
  }
  extension (seq: Seq[ConnectionIO[Unit]]) {
    def thenDo(other: ConnectionIO[Unit]): Seq[ConnectionIO[Unit]] = seq :+ other
  }
}
