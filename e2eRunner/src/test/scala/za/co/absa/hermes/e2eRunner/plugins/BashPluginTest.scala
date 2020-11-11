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

package za.co.absa.hermes.e2eRunner.plugins

import org.scalatest.FunSuite

class BashPluginTest extends FunSuite {
  private val plugin = new BashPlugin()

  test("Plugin - name") {
    assert(plugin.name == "BashPlugin")
  }

  test("Plugin - performAction") {
    val shouldPass = true
    val order = 111
    val testName = "UnitTest"
    val args = Array("echo", "Something Something")

    val expectedResult = BashJobsResult(args, "Something Something\n", order, testName, shouldPass, Map.empty)

    val result = plugin.performAction(args, order, testName)
      .copy(additionalInfo = Map.empty)

    assert(expectedResult == result)
  }
}
