/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.logging

import com.gm2211.turbol.backend.logging.{Arg, SafeArg, UnsafeArg}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait BackendLogger {
  def debug(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def debug(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def info(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def info(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def info(
      message: String,
      cause: Throwable,
      args: Arg[_]*
  )(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def warn(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def warn(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def error(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
  def error(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit
}

final class BackendLoggerImpl(private val delegate: Logger)
    extends BackendLogger {
  override def debug(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenDebugEnabled {
      logWithArgs(delegate.debug(_), message, args)
    }
  override def debug(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenDebugEnabled {
      delegate.debug(message, cause)
    }
  override def info(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenInfoEnabled {
      logWithArgs(delegate.info(_), message, args)
    }
  override def info(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenInfoEnabled {
      delegate.info(message, cause)
    }
  override def info(
      message: String,
      cause: Throwable,
      args: Arg[_]*
  )(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenInfoEnabled {
      logWithArgs(delegate.info(_), message, args)
    }
  override def warn(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenWarnEnabled {
      logWithArgs(delegate.warn(_), message, args)
    }
  override def warn(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenWarnEnabled {
      delegate.warn(message, cause)
    }
  override def error(message: String, args: Arg[_]*)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenErrorEnabled {
      logWithArgs(delegate.error(_), message, args)
    }
  override def error(message: String, cause: Throwable)(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit =
    delegate.whenErrorEnabled {
      delegate.error(message, cause)
    }

  private def logWithArgs(
      loggerMethod: String => Unit,
      message: => String,
      args: => Seq[Arg[_]]
  )(implicit
      line: sourcecode.Line,
      file: sourcecode.File
  ): Unit = {
    val safeArgs: Seq[SafeArg[_]] = args.collect { case arg: SafeArg[_] => arg }
    val unsafeArgs: Seq[UnsafeArg[_]] = args.collect { case arg: UnsafeArg[_] =>
      arg
    }
    val fileAndLineNum: String =
      BackendLogging.getFileNameAndLineNumString(file, line)

    loggerMethod(
      s"$fileAndLineNum $message, safe: ${toString(safeArgs)}, unsafe: ${toString(unsafeArgs)}"
    )
  }

  private def toString(args: Seq[Arg[_]]): String = {
    val argsString = args
      .map(arg => s"${arg.name} -> ${arg.value}")
      .mkString(", ")
    s"{$argsString}"
  }
}

trait BackendLogging { self =>
  protected val log: BackendLogger = new BackendLoggerImpl(
    Logger(LoggerFactory.getLogger(clazz))
  )

  protected def clazz: Class[_] = self.getClass
  def safe[T](name: String, value: T): SafeArg[T] = SafeArg(name, value)
  def unsafe[T](name: String, value: T): UnsafeArg[T] = UnsafeArg(name, value)
}
object BackendLogging {
  def getFileNameAndLineNumString(
      file: sourcecode.File,
      line: sourcecode.Line
  ): String = {
    val filename = file.value.split("/").last
    val fileAndLineNum: String = s"[file: $filename, line: ${line.value}]"
    fileAndLineNum
  }
}
