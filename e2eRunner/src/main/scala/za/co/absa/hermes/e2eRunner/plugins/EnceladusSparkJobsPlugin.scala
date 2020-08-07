package za.co.absa.hermes.e2eRunner.plugins

import scala.sys.process._
import za.co.absa.hermes.e2eRunner.{Plugin, PluginResult}
import za.co.absa.hermes.utils.HelperFunctions

case class EnceladusSparkJobsResult(arguments: Array[String],
                                    returnedValue: String,
                                    order: Int,
                                    testName: String,
                                    passed: Boolean,
                                    additionalInfo: Map[String, String])
  extends PluginResult(arguments, returnedValue, order, testName, passed, additionalInfo) {

  override def logResult(): Unit = {
    scribe.info("Job Finished. In theory succesfully")
  }

  override def write(writeArgs: Seq[String]): Unit = {
    scribe.warn("EnceladusSparkJobs plugin does not support write for the result")
  }
}


class EnceladusSparkJobsPlugin extends Plugin {

  override def name: String = "EnceladusSparkJobs"

  override def performAction(args: Array[String], actualOrder: Int, testName: String): PluginResult = {
    def runBashCmd(bashCmd: String): String = {
      (s"echo $bashCmd" #| "bash").!!
    }

    val (confTime, returnValue) = HelperFunctions.calculateTime { runBashCmd(args.mkString(" ")) }
    val additionalInfo = Map("elapsedTimeInMilliseconds" -> confTime.toString)
    EnceladusSparkJobsResult(args, returnValue, actualOrder, testName, passed = true, additionalInfo)
  }
}
