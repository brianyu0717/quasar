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

package quasar.physical.marklogic.xquery

import quasar.Predef._
import quasar.NameGenerator
import quasar.physical.marklogic.xml.namespaces._

import eu.timepit.refined.auto._
import scalaz.syntax.monad._

object ejson {
  import syntax._, expr.{attribute, element, func}, axes.child
  import FunctionDecl.{FunctionDecl1, FunctionDecl2}

  val ejs = NamespaceDecl(ejsonNs)

  val ejsonN    = ejs name ejsonEjson.local
  val arrayEltN = ejs name ejsonArrayElt.local
  val typeAttrN = ejs name ejsonType.local

  // <ejson:ejson ejson:type="null" />
  def null_[F[_]: PrologW]: F[XQuery] =
    (ejsonN.xs[F] |@|  typeAttrN.xs) { (ejsxs, tpexs) =>
      element { ejsxs } { attribute { tpexs } { "null".xs } }
    }

  // ejson:array-concat($arr1 as element(), $arr2 as element()) as element(ejson:ejson)
  def arrayConcat[F[_]: NameGenerator: PrologW]: F[FunctionDecl2] =
    (ejs.name("array-concat").qn[F] |@| ejsonN.qn |@| arrayEltN.qn) { (fname, ename, aelt) =>
      declare(fname)(
        $("arr1") as SequenceType(s"element()"),
        $("arr2") as SequenceType(s"element()")
      ).as(SequenceType(s"element($ename)")) { (arr1: XQuery, arr2: XQuery) =>
        mkArray[F] apply mkSeq_(arr1 `/` child(aelt), arr2 `/` child(aelt))
      }
    }.join

  // ejson:is-array($node as node()) as xs:boolean
  def isArray[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("is-array").qn[F] |@| typeAttrN.qn) { (fname, tname) =>
      declare(fname)(
        $("node") as SequenceType("node()")
      ).as(SequenceType("xs:boolean")) { node =>
        fn.not(fn.empty(node(axes.attribute(tname) === "array".xs)))
      }
    }

  // ejson:is-object($node as node()) as xs:boolean
  def isObject[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("is-object").qn[F] |@| typeAttrN.qn) { (fname, tname) =>
      declare(fname)(
        $("node") as SequenceType("node()")
      ).as(SequenceType("xs:boolean")) { node =>
        fn.not(fn.empty(node(axes.attribute(tname) === "object".xs)))
      }
    }

  // ejson:make-array($elements as element(ejson:array-element)*) as element(ejson:ejson)
  def mkArray[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("make-array").qn[F] |@| ejsonN.qn |@| ejsonN.xs |@| arrayEltN.qn |@| typeAttrN.xs) {
      (fname, ename, ejsxs, aelt, tpexs) =>

      declare(fname)(
        $("elements") as SequenceType(s"element($aelt)*")
      ).as(SequenceType(s"element($ename)")) { elts =>
        element { ejsxs } { mkSeq_(attribute { tpexs } { "array".xs }, elts) }
      }
    }

  // ejson:make-array-element($value as item()*) as element(ejson:array-element)
  def mkArrayElt[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("make-array-element").qn[F] |@| arrayEltN.qn |@| arrayEltN.xs) { (fname, aelt, aeltxs) =>
      declare(fname)(
        $("value") as SequenceType.Top
      ).as(SequenceType(s"element($aelt)")) { value =>
        element { aeltxs } { value }
      }
    }

  // ejson:make-object($entries as element()*) as element(ejson:ejson)
  def mkObject[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("make-object").qn[F] |@| ejsonN.qn |@| ejsonN.xs |@| typeAttrN.xs) {
      (fname, ename, ejsxs, tpexs) =>

      declare(fname)(
        $("entries") as SequenceType(s"element()*")
      ).as(SequenceType(s"element($ename)")) { entries =>
        element { ejsxs } { mkSeq_(attribute { tpexs } { "object".xs }, entries) }
      }
    }

  // ejson:make-object-entry($key as xs:string*, $value as item()*) as element()
  def mkObjectEntry[F[_]: PrologW]: F[FunctionDecl2] =
    ejs.name("make-object-entry").qn[F] map { fname =>
      declare(fname)(
        $("key") as SequenceType("xs:string"),
        $("value") as SequenceType.Top
      ).as(SequenceType(s"element()")) { (key, value) =>
        element { key } { value }
      }
    }

  // ejson:seq-to-array($items as item()*) as element(ejson:ejson)
  def seqToArray[F[_]: NameGenerator: PrologW]: F[FunctionDecl1] =
    (ejs.name("seq-to-array").qn[F] |@| ejsonN.qn) { (fname, ename) =>
      declare(fname)(
        $("items") as SequenceType("item()*")
      ).as(SequenceType(s"element($ename)")) { items: XQuery =>
        val x = "$x"

        for {
          arrElt <- mkArrayElt[F] apply x.xqy
          arr    <- mkArray[F] apply fn.map(func(x) { arrElt }, items)
        } yield arr
      }
    }.join

  // ejson:singleton-array($item as item()*) as element(ejson:ejson)
  def singletonArray[F[_]: PrologW]: F[FunctionDecl1] =
    (ejs.name("singleton-array").qn[F] |@| ejsonN.qn) { (fname, ename) =>
      declare(fname)(
        $("item") as SequenceType.Top
      ).as(SequenceType(s"element($ename)")) { item: XQuery =>
        mkArrayElt[F].apply(item) flatMap (xqy => mkArray[F].apply(xqy))
      }
    }.join

  // ejson:singleton-object($key as xs:string, $value as item()*) as element(ejson:ejson)
  def singletonObject[F[_]: PrologW]: F[FunctionDecl2] =
    (ejs.name("singleton-object").qn[F] |@| ejsonN.qn) { (fname, ename) =>
      declare(fname)(
        $("key") as SequenceType("xs:string"),
        $("value") as SequenceType.Top
      ).as(SequenceType(s"element($ename)")) { (key: XQuery, value: XQuery) =>
        mkObjectEntry[F].apply(key, value) flatMap (xqy => mkObject[F].apply(xqy))
      }
    }.join
}
