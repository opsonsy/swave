/*
 * Copyright © 2016 Mathias Doenitz
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

package swave.core.impl.stages.fanout

import swave.core.PipeElem

private[core] final class FirstAvailableStage(eagerCancel: Boolean) extends FanOutStage
    with PipeElem.FanOut.FirstAvailable {

  def pipeElemType: String = "fanOutFirstAvailable"
  def pipeElemParams: List[Any] = eagerCancel :: Nil

  val interceptingStates: Int = 0
  override def stateName: String = ???
}

