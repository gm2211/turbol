package com.gm2211.turbol.objects.internal.storage

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.util.DBUtils
import doobie.ConnectionIO
import doobie.util.fragment.Fragment

trait RawSqlStore {
  def executeQuery(query: String): ConnectionIO[Unit]
  def executeQuery(fragment: Fragment): ConnectionIO[Unit]
}
final class RawSqlStoreImpl extends RawSqlStore with BackendLogging with DBUtils {
  override def executeQuery(query: String): ConnectionIO[Unit] = {
    Fragment.const(query).updateWithLogger.run.map(_ => ())
  }
  override def executeQuery(fragment: Fragment): ConnectionIO[Unit] = {
    fragment.updateWithLogger.run.map(_ => ())
  }
}
