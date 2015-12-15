package quasar
package regression

import quasar.Predef._
import quasar.fp._

import argonaut._, Argonaut._
import scalaz.syntax.std.map._

case class RegressionTest(
  name:      String,
  backends:  Map[BackendName, SkipDirective],
  data:      Option[String],
  query:     String,
  variables: Map[String, String],
  expected:  ExpectedResult
)

object RegressionTest {
  import DecodeResult.{ok, fail}

  implicit val RegressionTestDecodeJson: DecodeJson[RegressionTest] =
    DecodeJson(c => for {
      name          <-  (c --\ "name").as[String]
      backends      <-  if ((c --\ "backends").succeeded)
                          (c --\ "backends").as[Map[String, SkipDirective]]
                            .map(_ mapKeys (BackendName(_)))
                        else ok(Map[BackendName, SkipDirective]())
      data          <-  optional[String](c --\ "data")
      query         <-  (c --\ "query").as[String]
      variables     <-  orElse(c --\ "variables", Map.empty[String, String])
      ignoredFields <-  orElse(c --\ "ignoredFields", List.empty[String])
      rows          <-  (c --\ "expected").as[List[Json]]
      predicate     <-  (c --\ "predicate").as[Predicate]
    } yield RegressionTest(name, backends, data, query, variables, ExpectedResult(rows, predicate, ignoredFields)))
}