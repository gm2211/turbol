/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.storage

import cats.*
import cats.effect.*
import cats.implicits.*
import com.gm2211.turbol.objects.internal.errors.CredentialsNeedRefreshing
import com.gm2211.turbol.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB}
import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.util.CatsUtils
import doobie.*
import doobie.implicits.*

import scala.util.Try

trait TransactionManager {
  def awaitInitialized(): Try[Unit]

  def readOnly[T](
    action: CanReadDB.type ?=> TransactionalStores => ConnectionIO[T]
  ): Try[T]

  def readWrite[T](
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[T]
  ): Try[T]

  def readWriteVoid(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[Unit]
  ): Try[Unit]

  def readWriteVoidSeq(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => Seq[ConnectionIO[Unit]]
  ): Try[Unit]
}

class TransactionManagerImpl(
  stores: TransactionalStores,
  transactorProvider: DBTransactorProvider
) extends TransactionManager
    with CatsUtils {
  override def awaitInitialized(): Try[Unit] = {
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
  ): Try[T] = Try {
    transactionally { stores =>
      given CanReadDB.type = CanReadDB
      action(stores)
    }.evalOnIOExec().unsafeRunSync()
  }

  override def readWrite[T](
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[T]
  ): Try[T] = Try {
    transactionally { stores =>
      given CanReadDB.type = CanReadDB
      given CanWriteToDB.type = CanWriteToDB
      action(stores)
    }.evalOnIOExec().unsafeRunSync()
  }

  override def readWriteVoid(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => ConnectionIO[Unit]
  ): Try[Unit] = readWrite(action)

  def readWriteVoidSeq(
    action: (CanReadDB.type, CanWriteToDB.type) ?=> TransactionalStores => Seq[ConnectionIO[Unit]]
  ): Try[Unit] = {
    readWrite[Seq[Unit]] { txn => action(txn).sequence }.map(_ => ())
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
