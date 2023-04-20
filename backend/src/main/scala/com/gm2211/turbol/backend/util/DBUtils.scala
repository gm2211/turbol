package com.gm2211.turbol.backend.util

import com.gm2211.logging.BackendLogging
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
}
