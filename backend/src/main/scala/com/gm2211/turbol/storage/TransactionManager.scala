package com.gm2211.turbol.storage

import cats.*
import cats.effect.*
import com.gm2211.turbol.objects.internal.errors.CredentialsNeedRefreshing
import com.gm2211.turbol.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB}
import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.util.CatsUtils
import doobie.*
import doobie.implicits.*

trait TransactionManager {
  def awaitInitialized(): Unit

  def readOnly[T](
    action: CanReadDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): T

  def readWrite[T](
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[T]
  ): T

  def readWriteVoid(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[Unit]
  ): Unit
}

class TransactionManagerImpl(
  stores: TransactionalStores,
  transactorProvider: DBTransactorProvider
) extends TransactionManager
    with CatsUtils {
  override def awaitInitialized(): Unit = {
    transactorProvider.awaitInitialized()
    readWriteVoid { txn =>
      txn
        .map(_.createTableIfNotExists())
        .reduce((existing, next) => existing.flatMap(_ => next))
        .map(_ => ())
    }
  }

  override def readOnly[T](
    action: CanReadDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): T = {
    transactionally { stores =>
      given CanReadDB.type = CanReadDB

      action(stores)
    }.evalOnIOExec().unsafeRunSync()
  }

  override def readWrite[T](
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[T]
  ): T = {
    transactionally { stores =>
      given CanReadDB.type = CanReadDB

      given CanWriteToDB.type = CanWriteToDB

      action(stores)
    }.evalOnIOExec().unsafeRunSync()
  }

  override def readWriteVoid(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[Unit]
  ): Unit = {
    readWrite(action)
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
