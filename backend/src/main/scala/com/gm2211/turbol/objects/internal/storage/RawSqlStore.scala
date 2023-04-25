/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.util.DBUtils
import doobie.util.fragment.Fragment
import doobie.{ConnectionIO, Read}

trait RawSqlStore {
  def executeUpdate(query: String): ConnectionIO[Unit]
  def executeUpdate(fragment: Fragment): ConnectionIO[Unit]
  def executeQuery[T: Read](query: String): ConnectionIO[T]
  def executeQueryList[T: Read](query: String): ConnectionIO[List[T]]
  def executeQuery[T: Read](fragment: Fragment): ConnectionIO[T]
}
final class RawSqlStoreImpl extends RawSqlStore with BackendLogging with DBUtils {
  override def executeUpdate(query: String): ConnectionIO[Unit] = {
    Fragment.const(query).updateWithLogger.run.map(_ => ())
  }
  override def executeUpdate(fragment: Fragment): ConnectionIO[Unit] = {
    fragment.updateWithLogger.run.map(_ => ())
  }
  override def executeQuery[T: Read](query: String): ConnectionIO[T] = {
    Fragment.const(query).queryWithLogger[T].unique
  }
  override def executeQueryList[T: Read](query: String): ConnectionIO[List[T]] = {
    Fragment.const(query).queryWithLogger[T].to[List]
  }
  override def executeQuery[T: Read](fragment: Fragment): ConnectionIO[T] = {
    fragment.queryWithLogger[T].unique
  }
}
