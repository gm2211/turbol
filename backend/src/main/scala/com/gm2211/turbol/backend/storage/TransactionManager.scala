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
    action: TransactionalStores => ConnectionIO[T]
  ): IO[T]

  def readWrite[T](
    action: TransactionalStores => ConnectionIO[T]
  ): IO[T]

  def readWriteVoid(
    action: TransactionalStores => ConnectionIO[Any]
  ): IO[Unit]
}

class SlickSqlTransactionManager(
  storesFactory: Set[TxnCapability] => TransactionalStores,
  transactorProvider: DBTransactorProvider
) extends TransactionManager {
  private val readOnlyStores = storesFactory(Set(CanReadDB))
  private val readWriteStores = storesFactory(Set(CanReadDB, CanWriteToDB))

  override def readOnly[T](
    action: TransactionalStores => ConnectionIO[T]
  ): IO[T] = {
    transactionally(action)(readOnlyStores)
  }

  override def readWrite[T](
    action: TransactionalStores => ConnectionIO[T]
  ): IO[T] = {
    transactionally(action)(readWriteStores)
  }

  override def readWriteVoid(
    action: TransactionalStores => ConnectionIO[Any]
  ): IO[Unit] = {
    transactionally(action)(readWriteStores).map(_ => ())
  }

  private def transactionally[T](
    action: TransactionalStores => ConnectionIO[T]
  )(
    stores: TransactionalStores
  ): IO[T] = {
    transactionally(action, forceRefreshClient = false)(stores)
      .recoverWith {
        // TODO(gm2211): let's figure out the retry backoff story here
        case CredentialsNeedRefreshing => transactionally(action, forceRefreshClient = true)(stores)
        case throwable: Throwable => IO.raiseError(throwable)
      }
  }

  private def transactionally[T](
    action: TransactionalStores => ConnectionIO[T],
    forceRefreshClient: Boolean
  )(
    stores: TransactionalStores
  ): IO[T] = {
    for {
      transactor <- transactorProvider.transactor(forceRefresh = forceRefreshClient)
      result <- action(stores).transact(transactor)
    } yield result
  }
}
