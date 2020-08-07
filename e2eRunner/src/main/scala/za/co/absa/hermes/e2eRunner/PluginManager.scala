/*
 * Copyright 2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.hermes.e2eRunner

import java.io.File

import org.clapper.classutil.ScalaCompat.LazyList
import org.clapper.classutil.{ClassFinder, ClassInfo}

import scala.util.{Failure, Success, Try}

class PluginManager(private val plugins: Map[String, String]) {
  def getPlugin(name: String): Plugin = {
    val className = if (plugins.keySet.contains(name)) {
      plugins(name)
    } else throw PluginNotFound(name)

    Class.forName(className).newInstance().asInstanceOf[Plugin]
  }

  def getPluginNames: Set[String] = plugins.keySet

  def runWithDefinitions(pluginDefinitions: Seq[TestDefinition]): Seq[PluginResult] = {
    val sortedPD = pluginDefinitions.sortBy(pd => (pd.order, pd.pluginName))
    sortedPD.zipWithIndex.map {
      case (td, i) =>
        scribe.info(s"Running ${td.name}")
        val plugin: Plugin = getPlugin(td.pluginName)
        val tryExecution = tryExecute(td, i, plugin)

        tryExecution match {
          case Success(value) => value
          case Failure(exception) =>  FailedPluginResult(td.args, exception, i, td.name)
        }
    }
  }

  private def tryExecute(td: TestDefinition, i: Int, plugin: Plugin): Try[PluginResult] = Try {
      val result: PluginResult = plugin.performAction(td.args, i, td.name)
      if (td.writeArgs.isDefined) result.write(td.writeArgs.get)
      result.logResult()
      result
    }
}

object PluginManager {
  def apply(plugins: Map[String, String]): PluginManager = new PluginManager(plugins)

  def apply(classpath: Seq[File] = Seq(new File("."))): PluginManager = {
    classpath.foreach(x => println(x.getName))
    val finder: ClassFinder = ClassFinder(classpath)
    val classes: LazyList[ClassInfo] = finder.getClasses
    val classMap = ClassFinder.classInfoMap(classes)
    val plugins: Iterator[ClassInfo] = ClassFinder.concreteSubclasses("za.co.absa.hermes.e2eRunner.Plugin", classMap)

    val pluginMap: Map[String, String] = plugins.foldLeft(Map.empty[String, String]) {
    (acc, value) =>
      val plugin: Plugin = Class.forName(value.name).newInstance().asInstanceOf[Plugin]
      acc + (plugin.name -> value.name)
    }

    PluginManager(pluginMap)
  }

}
