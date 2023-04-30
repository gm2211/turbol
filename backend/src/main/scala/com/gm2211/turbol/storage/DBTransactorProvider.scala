/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.storage

import cats.effect.{Deferred, IO, Ref, Resource}
import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.runtime.DatabaseConfig
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.util.MoreExecutors.given
import com.gm2211.turbol.util.{CatsUtils, MoreExecutors, Scheduler}
import com.softwaremill.tagging.*
import doobie.hikari.HikariTransactor

import scala.concurrent.ExecutionContext
import scala.util.Try

trait DBTransactorProvider {
  def awaitInitialized(): Try[Unit]
  def transactor(forceRefresh: Boolean = false): IO[HikariTransactor[IO]]
}

final class DBTransactorProviderImpl(
  dbConfig: Refreshable[DatabaseConfig],
  appSecrets: Refreshable[AppSecrets],
  scheduler: Scheduler @@ DBTransactorProvider
) extends DBTransactorProvider
    with BackendLogging
    with CatsUtils {

  private val initialized: Deferred[IO, Unit] = Deferred.unsafe[IO, Unit]
  private val transactorAndReleaserRef: Ref[IO, (HikariTransactor[IO], IO[Unit])] =
    Ref.unsafe[IO, (HikariTransactor[IO], IO[Unit])]((null, null))

  dbConfig
    .zipWith(appSecrets)(using scheduler)
    .onUpdate((conf, secrets) => initDb(conf, secrets)(using scheduler))(using scheduler)

  override def awaitInitialized(): Try[Unit] = Try {
    initialized
      .get
      .map(_ => log.info("Done waiting till db initialization"))
      .evalOnIOExec()
      .unsafeRunSync()
  }

  override def transactor(forceRefresh: Boolean): IO[HikariTransactor[IO]] = {
    transactorAndReleaserRef.get.map(_._1)
  }

  private def initDb(
    dbConfig: DatabaseConfig,
    appSecrets: AppSecrets
  )(
    using ioExecutor: ExecutionContext
  ): Unit = {
    val connectionString =
      s"jdbc:postgresql://${dbConfig.hostname}:${dbConfig.port}/" +
        s"${dbConfig.databaseName}" +
        s"?user=${dbConfig.adminUser}" +
        s"&password=${appSecrets.dbAdminPassword}"
    val transactor = for {
      transactor <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        connectionString,
        dbConfig.adminUser,
        appSecrets.dbAdminPassword,
        ioExecutor
      )
      _ <- Resource.pure { log.info("Initializing DB", unsafe("dbConfig", dbConfig)) }
    } yield transactor

    val updateRef: IO[Unit] = for {
      oldTransactorAndRelease <- transactorAndReleaserRef.get
      transactorAndReleaser <- transactor.allocated
      _ <- Option(oldTransactorAndRelease._2).getOrElse(IO.unit) // Release old transactor, if any
      _ <- transactorAndReleaserRef.set(transactorAndReleaser)
      _ <- IO { log.info("Updated db ref", unsafe("connection-string", connectionString)) }
    } yield ()

    updateRef
      .evalOn(ioExecutor)
      .unsafeRunAsync {
        case Left(e) => log.error("Failed to initialize DB", e)
        case Right(_) =>
          initialized
            .complete(())
            .map(notified => log.info("Notified listeners of db init completion", safe("successful", notified)))
            .runIO()
      }
  }
}
