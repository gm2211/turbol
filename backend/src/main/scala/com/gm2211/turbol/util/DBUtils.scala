/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.logging.BackendLogging
import doobie.postgres.*
import doobie.postgres.implicits.*
import doobie.syntax.SqlInterpolator.SingleFragment
import doobie.util.Read
import doobie.util.fragment.Fragment
import doobie.util.log.*
import doobie.util.query.Query0
import doobie.util.update.Update0

object DBUtils extends DBUtils // Allows .* imports
trait DBUtils extends BackendLogging {
  private val logHandler: LogHandler = new LogHandler({
    case success @ Success(_, _, _, _) => log.debug("Successful query", unsafe("log-line", success))
    case failure @ ProcessingFailure(_, _, _, _, _) => log.warn("Processing failure", unsafe("log-line", failure))
    case failure @ ExecFailure(_, _, _, _) => log.warn("Execution failure", unsafe("log-line", failure))
  })

  extension (fragment: Fragment) {
    def updateWithLogger: Update0 = {
      fragment.updateWithLogHandler(logHandler)
    }

    def queryWithLogger[T: Read]: Query0[T] = {
      fragment.queryWithLogHandler[T](logHandler)
    }
  }

  extension (sc: StringContext) {
    def doob(args: Any*): Fragment = {
      if (args.isEmpty) {
        return Fragment.const(sc.parts.mkString.strip())
      }

      def valueToFragment(value: Any): Fragment = {
        if (value == null) {
          return SingleFragment.fromWrite("null").fr
        }
        value match {
          case i: Int => SingleFragment.fromWrite(i).fr
          case d: Double => SingleFragment.fromWrite(d).fr
          case s: String =>
            s.toIntOption
              .map(SingleFragment.fromWrite(_).fr)
              .orElse(s.toDoubleOption.map(SingleFragment.fromWrite(_).fr))
              .orElse(Some(SingleFragment.fromWrite(s).fr))
              .get
          case l: List[_] => SingleFragment.fromWrite(l.asInstanceOf[List[String]]).fr
          case _ => SingleFragment.fromWrite(value.toString).fr
        }
      }
      sc.parts
        // Empty string if 'parts' shorted than 'args', StringContextArgsDummyValue if 'parts' longer than 'args'
        .zipAll(args, "", StringContextArgsDummyValue)
        .foldLeft(Fragment.empty) { case (accumulator: Fragment, (part: String, args: Any)) =>
          args match {
            case colNames: List[_] if colNames.headOption.exists(_.isInstanceOf[SqlCol]) =>
              accumulator ++ mkFrag(part, colNames.map(_.asInstanceOf[SqlCol].value).mkString(", "))
            case tableName: SqlTable =>
              accumulator ++ mkFrag(part, tableName.value)
            case colName: SqlCol =>
              accumulator ++ mkFrag(part, colName.value)
            case lit: SqlLit =>
              accumulator ++ mkFrag(part, lit.value)
            case StringContextArgsDummyValue =>
              accumulator ++ mkFrag(part)
            case optArg: Option[Any] =>
              accumulator ++ mkFrag(part.strip()) ++ valueToFragment(optArg.orNull)
            case arg: Any =>
              accumulator ++ mkFrag(part.strip()) ++ valueToFragment(arg)
          }
        }
    }

    private def mkFrag(part: String, arg: String): Fragment = mkFrag(part, Some(arg))
    private def mkFrag(part: String, maybeArg: Option[String] = None): Fragment = {
      maybeArg
        .map(arg => Fragment.const(" " ++ part.strip() ++ " " ++ arg))
        .getOrElse(Fragment.const(" " ++ part.strip()))
    }
  }
}

case object StringContextArgsDummyValue
case class SqlTable(value: String) {
  override def toString: String = value
}
case class SqlCol(value: String) {
  override def toString: String = value
}
case class SqlLit(value: String) {
  override def toString: String = value
}
