package com.gm2211.turbol.backend.storage

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.unsafe.IORuntime
import cats.implicits.*
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.backend.config.runtime.DatabaseConfig
import com.gm2211.turbol.backend.objects.internal.errors.CredentialsNeedRefreshing
import com.gm2211.turbol.backend.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB, TxnCapability}
import com.gm2211.turbol.backend.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.backend.storage.stores.AirportsStoreImpl
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.syntax.*

import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext

trait TransactionManager {
  def readOnly[T](
    action: CanReadDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): IO[T]

  def readWrite[T](
    action: CanReadDB.type ?=> CanWriteToDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): IO[T]

  def readWriteVoid(
    action: CanReadDB.type ?=> CanWriteToDB.type ?=> TransactionalStores => ConnectionIO[Any]
  ): IO[Unit]
}

class TransactionManagerImpl(
  stores: TransactionalStores,
  transactorProvider: DBTransactorProvider
) extends TransactionManager {
  override def readOnly[T](
    action: CanReadDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): IO[T] = {
    transactionally { stores =>
      given CanReadDB.type = CanReadDB

      action(stores)
    }
  }

  override def readWrite[T](
    action: CanReadDB.type ?=> CanWriteToDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): IO[T] = {
    transactionally{ stores =>
      given CanReadDB.type = CanReadDB

      given CanWriteToDB.type = CanWriteToDB

      action(stores)
    }
  }

  override def readWriteVoid(
    action: CanReadDB.type ?=> CanWriteToDB.type ?=> TransactionalStores => ConnectionIO[Any]
  ): IO[Unit] = {
    readWriteVoid(action).map(_ => ())
  }

  private def transactionally[T](
    action: TransactionalStores => ConnectionIO[T]
  ): IO[T] = {
    transactionally(action, forceRefreshClient = false)
      .recoverWith {
        // TODO(gm2211): let's figure out the retry backoff story here
        case CredentialsNeedRefreshing => transactionally(action, forceRefreshClient = true)
        case throwable: Throwable => IO.raiseError(throwable)
      }
  }

  private def transactionally[T](
    action: TransactionalStores => ConnectionIO[T],
    forceRefreshClient: Boolean
  ): IO[T] = {
    for {
      transactor <- transactorProvider.transactor(forceRefresh = forceRefreshClient)
      result <- action(stores).transact(transactor)
    } yield result
  }
}
