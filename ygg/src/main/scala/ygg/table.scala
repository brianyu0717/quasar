/*
 * Copyright 2014–2016 SlamData Inc.
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

package ygg

import ygg.common._
import scalaz._, Scalaz._, Ordering._

package object table {
  type TransSpec1                 = trans.TransSpec1
  type TransSpec[A <: SourceType] = trans.TransSpec[A]
  type SourceType                 = trans.SourceType

  type NeedSlices = NeedStreamT[Slice]
  type NeedTable  = Need[Table]
  type RowId      = Int
  type Identity   = Long
  type Identities = Array[Identity]
  type ColumnMap  = Map[ColumnRef, Column]

  val IdentitiesOrder: Ord[Identities] = Ord order fullIdentityOrdering

  def composeSliceTransform(spec: TransSpec1): SliceTransform1[_]             = SliceTransform.composeSliceTransform(spec)
  def composeSliceTransform2(spec: TransSpec[SourceType]): SliceTransform2[_] = SliceTransform.composeSliceTransform2(spec)

  def prefixIdentityOrdering(ids1: Identities, ids2: Identities, prefixLength: Int): Cmp = {
    0 until prefixLength foreach { i =>
      ids1(i) ?|? ids2(i) match {
        case EQ  => ()
        case cmp => return cmp
      }
    }
    EQ
  }
  def prefixIdentityOrder(prefixLength: Int): Ord[Identities] =
    Ord order (prefixIdentityOrdering(_, _, prefixLength))

  def identityValueOrder[A: Ord](idOrder: Ord[Identities]): Ord[Identities -> A] =
    Ord order ((x, y) => idOrder.order(x._1, y._1) |+| (x._2 ?|? y._2))

  def fullIdentityOrdering(ids1: Identities, ids2: Identities): Cmp =
    prefixIdentityOrder(ids1.length min ids2.length)(ids1, ids2)

  def tupledIdentitiesOrder[A](ord: Ord[Identities]): Ord[Identities -> A] = ord contramap (_._1)
  def valueOrder[A](ord: Ord[A]): Ord[Identities -> A]                     = ord contramap (_._2)

  implicit def liftCF2(f: CF2) = new CF2Like {
    def applyl(cv: CValue) = CF1("builtin::liftF2::applyl")(f(Column const cv, _))
    def applyr(cv: CValue) = CF1("builtin::liftF2::applyr")(f(_, Column const cv))
    def andThen(f1: CF1)   = CF2("builtin::liftF2::andThen")((c1, c2) => f(c1, c2) flatMap f1.apply)
  }
}
