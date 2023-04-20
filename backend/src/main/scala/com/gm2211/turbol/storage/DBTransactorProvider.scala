package com.gm2211.turbol.storage

import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.effect.{Deferred, IO, Ref, Resource}
import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.runtime.{DatabaseConfig, H2, Postgres}
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.objects.internal.storage.db.DBCredentials
import com.gm2211.turbol.util.CatsUtils
import com.gm2211.turbol.util.MoreExecutors.*
import doobie.hikari.HikariTransactor

import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext

trait DBTransactorProvider {
  def awaitInitialized(): Unit
  def transactor(forceRefresh: Boolean = false): IO[HikariTransactor[IO]]
}

final class DbTransactorProviderImpl(
  dbConfig: Refreshable[DatabaseConfig],
  appSecrets: Refreshable[AppSecrets]
)(
  using ioExecutor: ExecutionContext
) extends DBTransactorProvider
    with BackendLogging
    with CatsUtils {
  private val initialized: Deferred[IO, Unit] = Deferred.unsafe[IO, Unit]
  private val transactorAndReleaserRef: Ref[IO, (HikariTransactor[IO], IO[Unit])] =
    Ref.unsafe[IO, (HikariTransactor[IO], IO[Unit])]((null, null))

  dbConfig
    .zipWith(appSecrets)
    .onUpdate((conf, secrets) => initDb(conf, secrets))(
      using ioExecutor
    )

  override def awaitInitialized(): Unit = {
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
    val connectionString = dbConfig.databaseType match {
      case Postgres =>
        s"jdbc:postgresql://${dbConfig.hostname}:${dbConfig.port}/" +
          s"${dbConfig.databaseName}" +
          s"?user=${dbConfig.adminUser}" +
          s"&password=${appSecrets.dbAdminPassword}"
      case H2 =>
        s"jdbc:h2:mem:${dbConfig.databaseName};" +
          s"MODE=PostgreSQL;" +
          s"DB_CLOSE_DELAY=-1;" +
          s"DATABASE_TO_LOWER=TRUE;" +
          s"USER=${dbConfig.adminUser};" +
          s"PASSWORD=${appSecrets.dbAdminPassword}"
    }
    val transactor = for {
      transactor <- HikariTransactor.newHikariTransactor[IO](
        dbConfig.databaseType match {
          case Postgres => "org.postgresql.Driver"
          case H2 => "org.h2.Driver"
        },
        connectionString,
        dbConfig.adminUser,
        appSecrets.dbAdminPassword,
        ioExecutor
      )
      _ <- Resource.pure { log.info("Initializing DB", unsafe("dbConfig", dbConfig)) }
    } yield transactor

    val transactorAndReleaser: (HikariTransactor[IO], IO[Unit]) = transactor
      .allocated
      .evalOn(ioExecutor)
      .unsafeRunSync()

    val updateRef = for {
      oldTransactorAndRelease <- transactorAndReleaserRef.get
      _ <- Option(oldTransactorAndRelease._2).getOrElse(IO.unit) // release
      _ <- transactorAndReleaserRef.set(transactorAndReleaser)
      _ <- IO { log.info("Updated db ref") }
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
