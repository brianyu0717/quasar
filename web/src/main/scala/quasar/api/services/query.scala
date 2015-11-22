package quasar.api.services

import argonaut._, Argonaut._
import org.http4s.headers.Accept
import org.http4s._
import org.http4s.dsl._
import org.http4s.server._
import org.http4s.argonaut._
import pathy.Path._
import quasar._
import quasar.Predef._
import quasar.api.{MessageFormat, Destination, AsDirPath}
import quasar.{Variables, fs}
import quasar.fs._
import quasar.sql.{ParsingPathError, ParsingError, SQLParser, Query}

import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scalaz.~>

object query {

  implicit val QueryDecoder = new QueryParamDecoder[Query] {
    def decode(value: QueryParameterValue): ValidationNel[ParseFailure, Query] =
      Query(value.value).successNel[ParseFailure]
  }

  // https://github.com/puffnfresh/wartremover/issues/149
  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NonUnitStatements"))
  object QueryParam extends QueryParamDecoderMatcher[Query]("q")

  def formatParsingError(error: ParsingError): Task[Response] = error match {
    case ParsingPathError(e) => ???
    case _ => BadRequest(error.message)
  }

  private val QueryParameterMustContainQuery = BadRequest("The request must contain a query")
  private val POSTContentMustContainQuery    = BadRequest("The body of the POST must contain a query")
  private val DestinationHeaderMustExist     = BadRequest("The '" + Destination.name + "' header must be specified")

  def translateSemanticErrors(error: SemanticErrors): Task[Response] = ???

  private def vars(req: Request) = Variables(req.params.map { case (k, v) => (VarName(k), VarValue(v)) })

  def service[S[_]: Functor](f: S ~> Task)(implicit R: ReadFile.Ops[S],
                                                    W: WriteFile.Ops[S],
                                                    M: ManageFile.Ops[S],
                                                    Q: QueryFile.Ops[S]): HttpService = {

    val removePhaseResults = new (FileSystemErrT[PhaseResultT[Free[S,?], ?], ?] ~> FileSystemErrT[Free[S,?], ?]) {
      def apply[A](t: FileSystemErrT[PhaseResultT[Free[S,?], ?], A]): FileSystemErrT[Free[S,?], A] =
        EitherT[Free[S,?],FileSystemError,A](t.run.value)
    }

    HttpService {
      case req @ GET -> AsDirPath(path) :? QueryParam(query) => {

        SQLParser.parseInContext(query, fs.convert(path)).fold(
          formatParsingError,
          expr => queryPlan(expr, Variables(Map())).run.value.fold(
            errs => translateSemanticErrors(errs),
            logicalPlan => {
              val requestedFormat = MessageFormat.fromAccept(req.headers.get(Accept))
              formatQuasarDataStreamAsHttpResponse(f)(
                data = Q.evaluate(logicalPlan).translate[FileSystemErrT[Free[S,?], ?]](removePhaseResults),
                format = requestedFormat)
            }
          )
        )
      }
      case GET -> _ => QueryParameterMustContainQuery
      case req @ POST -> AsDirPath(path) =>
        EntityDecoder.decodeString(req).flatMap { query =>
          if (query == "") POSTContentMustContainQuery
          else {
            req.headers.get(Destination).fold(DestinationHeaderMustExist) { destination =>
              val parseRes = SQLParser.parseInContext(Query(query),fs.convert(path)).leftMap(formatParsingError)
              val destinationFile = posixCodec.parsePath(
                relFile => \/-(\/-(relFile)),
                absFile => \/-(-\/(absFile)),
                relDir => -\/(BadRequest("Destination must be a file")),
                absDir => -\/(BadRequest("Destination must be a file")))(destination.value)
              // Add path of query if destination is a relative file or else just jump through Sandbox hoop
              val absDestination = destinationFile.flatMap(_.bimap(
                absFile => sandbox(rootDir, absFile).map(file0 => rootDir </> file0) \/> InternalServerError("Could not sandbox file"),
                relFile => sandbox(currentDir, relFile).map(file0 => currentDir </> file0).map(file1 => path </> file1) \/> InternalServerError("Could not sandbox file")
              ).merge)
              val resultOrError = (parseRes |@| absDestination)((expr, out) => {
                // Unwrap the 3 level Monad Transformer, convert from Free to Task
                Q.executeQuery(expr, vars(req), out).run.run.run.foldMap(f).flatMap { case (phases, result) =>
                  result.fold(
                    translateSemanticErrors,
                    _.fold(
                      fileSystemErrorResponse,
                      resultFile => {
                        Ok(Json.obj(
                          "out" := posixCodec.printPath(ResultFile.resultFile.get(resultFile)),
                          "phases" := phases
                        ))
                      }
                    )
                  )
                }
              })
              resultOrError.merge
            }
          }
        }
    }
  }

  def compileService[S[_]: Functor](f: S ~> Task)(implicit Q: QueryFile.Ops[S]): HttpService = {
    HttpService{
      case GET -> AsDirPath(path) :? QueryParam(query) =>
        SQLParser.parseInContext(query, fs.convert(path)).fold(
          formatParsingError,
          expr => {
            Q.explainQuery(expr,Variables(Map())).run.foldMap(f).flatMap(_.fold(
              translateSemanticErrors,
              phases =>
                phases.lastOption.map{
                  case PhaseResult.Tree(name, value)    => Ok(Json(name := value))
                  case PhaseResult.Detail(name, value)  => Ok(name + "\n" + value)
                }.getOrElse(InternalServerError("no plan"))
            ))
          }
        )
      case GET -> _ => QueryParameterMustContainQuery
    }
  }
}
