/*
 * Copyright 2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.hermes.infoFileComparison

import scopt.OptionParser
import za.co.absa.hermes.utils.CaseClassUtils

import scala.util.{Failure, Success, Try}

/**
  * This is a class for configuration provided by the command line parameters
  *
  * Note: scopt requires all fields to have default values.
  *       Even if a field is mandatory it needs a default value.
  */
case class InfoComparisonArguments(newPath: String = "",
                                   refPath: String = "",
                                   outPath: String = "") extends CaseClassUtils

object InfoComparisonArguments {

  def getCmdLineArguments(args: Array[String]): Try[InfoComparisonArguments] = {
    val parser = new CmdParser("spark-submit [spark options] TestUtils.jar")

    parser.parse(args, InfoComparisonArguments())  match {
      case Some(config) => Success(config)
      case _            => Failure(new IllegalArgumentException("Wrong options provided. List can be found above"))
    }
  }

  private class CmdParser(programName: String) extends OptionParser[InfoComparisonArguments](programName) {
    head("\n_INFO File Comparison", "")
    var newPath: Option[String] = None
    var refPath: Option[String] = None
    var outPath: Option[String] = None

    opt[String]("new-path")
      .required
      .action((value, config) => {
        newPath = Some(value)
        config.copy(newPath = value)})
      .text("Path to the new _INFO file, just generated and to be tested.")
      .validate(value =>
        if (refPath.isDefined && refPath.get.equalsIgnoreCase(value)) {
          failure("std-path and ref-path can not be equal")
        } else if (outPath.isDefined && outPath.get.equalsIgnoreCase(value)) {
          failure("std-path and out-path can not be equal")
        } else {
          success
        }
      )

    opt[String]("ref-path")
      .required
      .action((value, config) => {
        refPath = Some(value)
        config.copy(refPath = value)})
      .text("Path to supposedly correct _INFO file.")
      .validate(value =>
        if (newPath.isDefined && newPath.get.equalsIgnoreCase(value)) {
          failure("ref-path and std-path can not be equal")
        } else if (outPath.isDefined && outPath.get.equalsIgnoreCase(value)) {
          failure("ref-path and out-path can not be equal")
        } else {
          success
        }
      )

    opt[String]("out-path")
      .required
      .action((value, config) => {
        outPath = Some(value)
        config.copy(outPath = value)})
      .text("Path to where the `InfoFileComparisonJob` will save the differences in JSON format.")
      .validate(value =>
        if (newPath.isDefined && newPath.get.equalsIgnoreCase(value)) {
          failure("out-path and std-path can not be equal")
        } else if (refPath.isDefined && refPath.get.equalsIgnoreCase(value)) {
          failure("out-path and ref-path can not be equal")
        } else {
          success
        }
      )

    help("help")
      .text("prints this usage text")
  }
}

