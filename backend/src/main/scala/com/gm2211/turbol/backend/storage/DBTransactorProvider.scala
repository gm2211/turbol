package com.gm2211.turbol.backend.storage

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.gm2211.turbol.backend.objects.internal.storage.db.DBCredentials
import doobie.hikari.HikariTransactor

trait DBTransactorProvider {
  def transactor(forceRefresh: Boolean = false): IO[HikariTransactor[IO]]
}

final class DBConfigProviderImpl(using IORuntime) extends DBTransactorProvider {
  lazy val transactor: HikariTransactor[IO] = {
    for {
      //      credentials <- IO.delay[DBCredentials](credentialsProvider.getCredentials)
      manager <- HikariTransactor.newHikariTransactor[IO](
        "org.h2.Driver", // driver classname
        "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", // url
        "", // credentials.userName,
        "", // credentials.password,
        IORuntime.createDefaultBlockingExecutionContext("txn-manager")._1
      )
    } yield manager
  }.use(_ => IO.never).unsafeRunSync()

  override def transactor(forceRefresh: Boolean): IO[HikariTransactor[IO]] = {
    IO.pure(transactor)
  }
}
