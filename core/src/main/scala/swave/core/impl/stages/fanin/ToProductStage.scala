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

package swave.core.impl.stages.fanin

import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.{ InportList, Outport }
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class ToProductStage(val pipeElemType: String,
                                         subs: InportList, f: Array[AnyRef] ⇒ AnyRef) extends FanInStage
  with PipeElem.FanIn.ToProduct {

  requireArg(subs.nonEmpty)

  def pipeElemParams: List[Any] = Nil

  val members = {
    val size = subs.size
    requireArg(size <= 64, "fanInToProduct is not supported for types with more than 64 members")
    new Array[AnyRef](size)
  }

  private def fullMask = (1L << members.length) - 1

  private def requestNext() = subs.foreach(_.in.request(1)) // TODO: avoid function allocation

  @tailrec private def cancelStillActive(rem: InportList, compMask: Long): Unit =
    if (rem ne null) {
      if ((compMask & 1) == 0) rem.in.cancel()
      cancelStillActive(rem.tail, compMask >> 1)
    }

  connectFanInAndSealWith(subs) { (ctx, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  /**
   * @param out the active downstream
   */
  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      requestNext()
      collectingMembers(out, fullMask, 0, 0)
    })

  /**
   * Awaiting one element from all upstreams to complete a product.
   *
   * @param out           the active downstream
   * @param pendingMask   bitmask holding a 1-bit for every input which we haven't received the current element from, > 0
   * @param completedMask bitmask holding a 1-bit for every input which has already completed, >= 0
   * @param remaining     number of elements already requested by downstream but not yet delivered, >= 0
   */
  def collectingMembers(out: Outport, pendingMask: Long, completedMask: Long, remaining: Long): State = state(
    request = (n, _) ⇒ collectingMembers(out, pendingMask, completedMask, remaining ⊹ n),

    cancel = _ => {
      cancelStillActive(subs, completedMask)
      stop()
    },

    onNext = (elem, from) ⇒ {
      val ix = subs indexOf from
      members(ix) = elem
      val newPendingMask = pendingMask & ~(1L << ix)
      if (newPendingMask == 0) {
        if (remaining > 0) {
          out.onNext(f(members))
          if (completedMask == 0) {
            requestNext()
            collectingMembers(out, fullMask, completedMask, remaining - 1)
          } else { cancelStillActive(subs, completedMask); stopComplete(out) }
        } else awaitingDemand(out, completedMask)
      } else collectingMembers(out, newPendingMask, completedMask, remaining)
    },

    onComplete = from => {
      val bit = 1L << (subs indexOf from)
      if ((pendingMask & bit) == 0) {
        // upstream completed but we've already seen its member for the next element
        collectingMembers(out, pendingMask, completedMask | bit, remaining)
      } else {
        cancelStillActive(subs, completedMask | bit)
        stopComplete(out)
      }
    },

    onError = (e, from) => {
      val bit = 1L << (subs indexOf from)
      cancelStillActive(subs, completedMask | bit)
      stopError(e, out)
    })

  /**
   * All members for one product present. Awaiting demand from downstream.
   *
   * @param out           the active downstream
   * @param completedMask bitmask holding a 1-bit for every input which has already completed, >= 0
   */
  def awaitingDemand(out: Outport, completedMask: Long): State = state(
    request = (n, _) ⇒ {
      out.onNext(f(members))
      if (completedMask == 0) {
        requestNext()
        collectingMembers(out, fullMask, 0, (n - 1).toLong)
      } else {
        cancelStillActive(subs, completedMask)
        stopComplete(out)
      }
    },

    cancel = _ => {
      cancelStillActive(subs, completedMask)
      stop()
    },

    onComplete = from => {
      val bit = 1L << (subs indexOf from)
      cancelStillActive(subs, completedMask | bit)
      awaitingDemand(out, -1L)
    },

    onError = (e, from) => {
      val bit = 1L << (subs indexOf from)
      cancelStillActive(subs, completedMask | bit)
      stopError(e, out)
    })
}

