package core

import java.time.OffsetDateTime
import java.util.UUID
import scala.util.Random

import akka.actor.FSM.Failure
import akka.actor._
import api._
import com.fasterxml.jackson.annotation.JsonValue
import config.Config.defaultSettings._
import core.CoordinatorActor.Start
import core.clients.{ChronosService, JobClientService}
import core.model.JobToChronos
import core.model.JobResult
import core.validation.KFoldCrossValidation
import core.validation.ValidationPoolManager
import core.validation.Scores
import dao.{JobResultsDAL}
import models.{ChronosJob, Container, EnvironmentVariable => EV}
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{JsString, _}

import scala.concurrent.duration._

import eu.hbp.mip.messages.external.{Algorithm, Validation => ApiValidation}
import eu.hbp.mip.messages.validation._

/**
  * We use the companion object to hold all the messages that the ``CoordinatorActor``
  * receives.
  */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: JobDto) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[Start] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  type WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobError = JobClientService.JobError

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  type Result = core.model.JobResult
  val Result = core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[ErrorResponse] = ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(jsonFormat1(ErrorResponse))
  }

  import JobResult._
  implicit val resultFormat: JsonFormat[Result] = JobResult.jobResultFormat
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)

  def props(chronosService: ActorRef, resultDatabase: JobResultsDAL, federationDatabase: Option[JobResultsDAL], jobResultsFactory: JobResults.Factory): Props =
    federationDatabase.map(fd => Props(classOf[FederationCoordinatorActor], chronosService, resultDatabase, fd, jobResultsFactory))
      .getOrElse(Props(classOf[LocalCoordinatorActor], chronosService, resultDatabase, jobResultsFactory))

}

sealed trait State
case object WaitForNewJob extends State
case object WaitForChronos extends State
case object WaitForNodes extends State
case object RequestFinalResult extends State
case object RequestIntermediateResults extends State

trait StateData {
  def job: JobDto
}
case object Uninitialized extends StateData {
  def job = throw new IllegalAccessException()
}
case class WaitingForNodesData(job: JobDto, replyTo: ActorRef, remainingNodes: Set[String] = Set(), totalNodeCount: Int) extends StateData
case class WaitLocalData(job: JobDto, replyTo: ActorRef) extends StateData

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of making two requests and then aggregating them together:
  *  - One request to Chronos to start the job
  *  - Then a separate request in the database for the results, repeated until enough results are present
  */
trait CoordinatorActor extends Actor with ActorLogging with LoggingFSM[State, StateData] {

  val repeatDuration = 200.milliseconds

  def chronosService: ActorRef
  def resultDatabase: JobResultsDAL
  def jobResultsFactory: JobResults.Factory

  startWith(WaitForNewJob, Uninitialized)

  when (WaitForChronos) {
    case Event(Ok, data: WaitLocalData) => goto(RequestFinalResult) using data
    case Event(e: Error, data: WaitLocalData) =>
      val msg: String = e.message
      data.replyTo ! Error(msg)
      stop(Failure(msg))
    case Event(e: Timeout @unchecked, data: WaitLocalData) =>
      val msg: String = "Timeout while connecting to Chronos"
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  when (RequestFinalResult, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitLocalData) => {
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        data.replyTo ! jobResultsFactory(results)
        stop()
      } else {
        stay() forMax repeatDuration
      }
    }
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("Received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }


  def transitions: TransitionHandler = {

    case _ -> WaitForChronos =>
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(nextStateData.job)
      chronosService ! Schedule(chronosJob)

  }

  onTransition( transitions )

}

class LocalCoordinatorActor(val chronosService: ActorRef, val resultDatabase: JobResultsDAL,
                            val jobResultsFactory: JobResults.Factory) extends CoordinatorActor {
  log.info ("Local coordinator actor started...")

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      goto(WaitForChronos) using WaitLocalData(job, sender())
    }
  }

  when (WaitForNodes) {
    case _ => stop(Failure("Unexpected state WaitForNodes"))
  }

  when (RequestIntermediateResults) {
    case _ => stop(Failure("Unexpected state RequestIntermediateResults"))
  }

  initialize()

}

class FederationCoordinatorActor(val chronosService: ActorRef, val resultDatabase: JobResultsDAL, val federationDatabase: JobResultsDAL,
                                 val jobResultsFactory: JobResults.Factory) extends CoordinatorActor {

  import CoordinatorActor._

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      import config.Config
      val replyTo = sender()
      val nodes = job.nodes.filter(_.isEmpty).getOrElse(Config.jobs.nodes)

      log.warning(s"List of nodes: ${nodes.mkString(",")}")

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = None))
        }
        goto(WaitForNodes) using WaitingForNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(WaitForChronos) using WaitLocalData(job, replyTo)
      }
    }
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when (WaitForNodes) {
    case Event(WorkerJobComplete(node), data: WaitingForNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    case Event(WorkerJobError(node, message), data: WaitingForNodesData) => {
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    }
  }

  when (RequestIntermediateResults, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitingForNodesData) => {
      val results = federationDatabase.findJobResults(data.job.jobId)
      if (results.size == data.totalNodeCount) {
        data.job.federationDockerImage.fold {
          data.replyTo ! PutJobResults(results)
          stop()
        } { federationDockerImage =>
          val parameters = Map(
            "PARAM_query" -> s"select data from job_result_nodes where job_id='${data.job.jobId}'"
          )
          goto(WaitForChronos) using WaitLocalData(data.job.copy(dockerImage = federationDockerImage, parameters = parameters), data.replyTo)
        }
      } else {
        stay() forMax repeatDuration
      }
    }
  }

  initialize()

}


/**
  * We use the companion object to hold all the messages that the ``ExperimentCoordinatorActor``
  * receives.
  */
object ExperimentActor {

  // FSM States
  case object WaitForNewJob extends State
  case object WaitForWorkers extends State

  // FSM Data
  case class Data(
                   job: Job,
                   replyTo: ActorRef,
                   results: collection.mutable.Map[Algorithm, String],
                   algorithms: Seq[Algorithm]
                 )

  // Incoming messages
  case class Job(
                  jobId: String,
                  inputDb: Option[String],
                  algorithms: Seq[Algorithm],
                  validations: Seq[ApiValidation],
                  parameters: Map[String, String]
                )

  case class Start(job: Job) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import ApiJsonSupport._
    implicit val jobFormat = jsonFormat5(Job.apply)
    override def marshaller: ToResponseMarshaller[Start] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  // Output messages: JobResult containing the experiment PFA
  type Result = core.model.JobResult
  val Result = core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {

    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._

    override def marshaller: ToResponseMarshaller[ErrorResponse] = ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(jsonFormat1(ErrorResponse))
  }

  import JobResult._

  implicit val resultFormat: JsonFormat[Result] = JobResult.jobResultFormat
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)
}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val chronosService: ActorRef, val resultDatabase: JobResultsDAL, val federationDatabase: Option[JobResultsDAL],
                      val jobResultsFactory: JobResults.Factory) extends Actor with ActorLogging with LoggingFSM[State, Option[ExperimentActor.Data]] {

  import ExperimentActor.{WaitForNewJob, Start, _}

  def reduceAndStop(data: ExperimentActor.Data): State = {

    //TODO WP3 Save the results in results DB

    // Concatenate results while respecting received algorithms order
    val output = JsArray(data.algorithms.map(a => JsObject("code" -> JsString(a.code), "name" -> JsString(a.name), "data" -> JsonParser(data.results.get(a).get))).toVector)

    data.replyTo ! jobResultsFactory(Seq(JobResult(data.job.jobId, "",  OffsetDateTime.now(), Some(output.compactPrint), None, "pfa_json", "")))
    stop
  }

  startWith(ExperimentActor.WaitForNewJob, None)

  when (ExperimentActor.WaitForNewJob) {
    case Event(Start(job), _) => {
      val replyTo = sender()

      val algorithms = job.algorithms
      val validations = job.validations

      log.warning(s"List of algorithms: ${algorithms.mkString(",")}")

      if (algorithms.nonEmpty) {

        // Spawn an AlgorithmActor for every algorithm
        for (a <- algorithms) {
          val jobId = UUID.randomUUID().toString
          val subjob = AlgorithmActor.Job(jobId, Some(defaultDb), a, validations, job.parameters)
          val worker = context.actorOf(Props(classOf[AlgorithmActor], chronosService, resultDatabase, federationDatabase, RequestProtocol))
          worker ! AlgorithmActor.Start(subjob)
        }

        goto(WaitForWorkers) using Some(Data(job, replyTo, collection.mutable.Map(), algorithms))
      } else {
        stay
      }
    }
  }

  when (WaitForWorkers) {
    case Event(AlgorithmActor.ResultResponse(algorithm, results), Some(data: Data)) => {
      data.results(algorithm) = results
      if (data.results.size == data.algorithms.length) reduceAndStop(data) else stay
    }
    case Event(AlgorithmActor.ErrorResponse(algorithm, message), Some(data: Data)) => {
      log.error(message)
      data.results(algorithm) = message
      if (data.results.size == data.algorithms.length) reduceAndStop(data) else stay
    }
  }

  initialize()
}

/**
  * We use the companion object to hold all the messages that the ``ValidationActor``
  * receives.
  */
object AlgorithmActor {

  // FSM States
  case object WaitForNewJob extends State
  case object WaitForWorkers extends State

  // FSM Data
  case class Data(job: Job, replyTo: ActorRef, var model: Option[String], results: collection.mutable.Map[ApiValidation, String], validationCount: Int)

  // Incoming messages
  case class Job(
                  jobId: String,
                  inputDb: Option[String],
                  algorithm: Algorithm,
                  validations: Seq[ApiValidation],
                  parameters: Map[String, String]
                )
  case class Start(job: Job)

  case class ResultResponse(algorithm: Algorithm, data: String)
  case class ErrorResponse(algorithm: Algorithm,  message: String)

  // TODO not sure if useful
  /*implicit val resultFormat = jsonFormat2(ResultResponse.apply)
  implicit val errorResponseFormat = jsonFormat2(ErrorResponse.apply)*/
}

class AlgorithmActor(val chronosService: ActorRef, val resultDatabase: JobResultsDAL, val federationDatabase: Option[JobResultsDAL],
                     val jobResultsFactory: JobResults.Factory) extends Actor with ActorLogging with LoggingFSM[State, Option[AlgorithmActor.Data]] {

  import AlgorithmActor._

  def reduceAndStop(data: AlgorithmActor.Data): State = {

    val validations = JsArray(data.results.map({case (key, value) => JsObject("code" -> JsString(key.code), "name" -> JsString(key.name), "data" -> JsonParser(value))}).toVector)

    // TODO Do better by merging JsObject (not yet supported by Spray...)
    val pfa = data.model.get.replaceFirst("\"cells\":\\{", "\"cells\":{\"validations\":" + validations.compactPrint + ",")

    data.replyTo ! AlgorithmActor.ResultResponse(data.job.algorithm, pfa)
    stop
  }

  startWith(AlgorithmActor.WaitForNewJob, None)

  when (AlgorithmActor.WaitForNewJob) {
    case Event(AlgorithmActor.Start(job), _) => {
      val replyTo = sender()

      val algorithm = job.algorithm
      val validations = if (isPredictive(algorithm.code)) job.validations else List()

      val parameters = job.parameters ++ FunctionsInOut.algoParameters(algorithm)

      log.warning(s"List of validations: ${validations.size}")

      // Spawn a LocalCoordinatorActor
      val jobId = UUID.randomUUID().toString
      val subjob = JobDto(jobId, dockerImage(algorithm.code), None, None, Some(defaultDb), parameters, None)
      val worker = context.actorOf(CoordinatorActor.props(chronosService, resultDatabase, None, jobResultsFactory))
      worker ! CoordinatorActor.Start(subjob)

      // Spawn a CrossValidationActor for every validation
      for (v <- validations) {
        val jobId = UUID.randomUUID().toString
        val subjob = CrossValidationActor.Job(jobId, job.inputDb, algorithm, v, parameters)
        val validationWorker = context.actorOf(Props(classOf[CrossValidationActor], chronosService, resultDatabase, federationDatabase, jobResultsFactory))
        validationWorker ! CrossValidationActor.Start(subjob)
      }

      goto(WaitForWorkers) using Some(Data(job, replyTo, None, collection.mutable.Map(), validations.size))
    }
  }

  when (WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), Some(data: Data)) => {
      data.model = Some(pfa.compactPrint)
      if (data.results.size == data.validationCount) reduceAndStop(data) else stay
    }
    case Event(CoordinatorActor.ErrorResponse(message), Some(data: Data)) => {
      log.error(message)
      // We cannot trained the model we notify supervisor and we stop
      context.parent ! ErrorResponse(data.job.algorithm, message)
      stop
    }
    case Event(CrossValidationActor.ResultResponse(validation, results), Some(data: Data)) => {
      data.results(validation) = results
      if ((data.results.size == data.validationCount) && data.model.isDefined) reduceAndStop(data) else stay
    }
    case Event(CrossValidationActor.ErrorResponse(validation, message), Some(data: Data)) => {
      log.error(message)
      data.results(validation) = message
      if ((data.results.size == data.validationCount) && data.model.isDefined) reduceAndStop(data) else stay
    }
  }

  initialize()
}

// TODO This code will be common to all Akka service in containers -> put it as a small common lib!
class RemotePathExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getPath(actor: Actor) = {
    actor.self.path.toStringWithAddress(system.provider.getDefaultAddress)
  }
}
object RemotePathExtension extends ExtensionKey[RemotePathExtensionImpl]

/**
  * We use the companion object to hold all the messages that the ``ValidationActor``
  * receives.
  */
object CrossValidationActor {

  // FSM States
  case object WaitForNewJob extends State
  case object WaitForWorkers extends State

  // FSM Data
  case class Data(job: Job, replyTo: ActorRef, var validation: KFoldCrossValidation, workers: Map[ActorRef, String], var variableType: String, var average: (List[String], List[String]), var results: collection.mutable.Map[String, Scores], foldsCount: Int)

  // Incoming messages
  case class Job(
                  jobId: String,
                  inputDb: Option[String],
                  algorithm: Algorithm,
                  validation: ApiValidation,
                  parameters: Map[String, String]
                )
  case class Start(job: Job)


  // Output Messages
  case class ResultResponse(validation: ApiValidation, data: String)
  case class ErrorResponse(validation: ApiValidation,  message: String)

  // TODO not sure if useful
  /*implicit val resultFormat = jsonFormat2(ResultResponse.apply)
  implicit val errorResponseFormat = jsonFormat2(ErrorResponse.apply)*/
}

/**
  *
  * TODO Better Integration with Spark!
  *
  * @param chronosService
  * @param resultDatabase
  * @param federationDatabase
  * @param jobResultsFactory
  */
class CrossValidationActor(val chronosService: ActorRef, val resultDatabase: JobResultsDAL, val federationDatabase: Option[JobResultsDAL],
                           val jobResultsFactory: JobResults.Factory) extends Actor with ActorLogging with LoggingFSM[State, Option[CrossValidationActor.Data]] {

  def adjust[A, B](m: Map[A, B], k: A)(f: B => B) = m.updated(k, f(m(k)))

  def reduceAndStop(data: CrossValidationActor.Data): State = {

    import core.validation.ScoresProtocol._

    // Aggregation of results from all folds
    val jsonValidation = JsObject(
      "type" -> JsString("KFoldCrossValidation"),
      "average" -> Scores(data.average._1, data.average._2, data.variableType).toJson,
      "folds" -> new JsObject(data.results.mapValues(s => s.toJson).toMap)
    )

    data.replyTo ! CrossValidationActor.ResultResponse(data.job.validation, jsonValidation.compactPrint)
    stop
  }

  import CrossValidationActor._

  startWith(CrossValidationActor.WaitForNewJob, None)

  when (CrossValidationActor.WaitForNewJob) {
    case Event(CrossValidationActor.Start(job), _) => {
      val replyTo = sender()

      val algorithm = job.algorithm
      val validation = job.validation

      log.warning(s"List of folds: ${validation.parameters("k")}")

      val k = validation.parameters("k").toInt

      // TODO For now only kfold cross-validation
      val xvalidation = KFoldCrossValidation(job, k)
      val workers: collection.mutable.Map[ActorRef, String] = collection.mutable.Map()

      // For every fold
      xvalidation.partition.foreach({case (fold, (s, n)) => {
        // Spawn a LocalCoordinatorActor for that one particular fold
        val jobId = UUID.randomUUID().toString
        // TODO To be removed in WP3
        val parameters = adjust(job.parameters, "PARAM_query")((x: String) => x + " EXCEPT ALL (" + x + s" OFFSET ${s} LIMIT ${n})")
        val subjob = JobDto(jobId, dockerImage(algorithm.code), None, None, Some(defaultDb), parameters, None)
        val worker = context.actorOf(CoordinatorActor.props(chronosService, resultDatabase, federationDatabase, jobResultsFactory))
        workers(worker) = fold
        worker ! CoordinatorActor.Start(subjob)
      }})
      goto(WaitForWorkers) using Some(Data(job, replyTo, xvalidation, workers.toMap, null, (Nil, Nil), collection.mutable.Map(), k))
    }
  }

  when (WaitForWorkers) {
    case Event(JsonMessage(pfa: JsValue), Some(data: Data)) => {
      // Validate the results
      log.info("Received result from local method.")
      val model = pfa.toString()
      val fold = data.workers(sender)
      val testData = data.validation.getTestSet(fold)._1.map(d => d.compactPrint)

      // Send to a random (simple load balancing) validation node of the pool
      val validationPool = ValidationPoolManager.validationPool
      //TODO If validationPool.size == 0, we cannot perform the cross validation we should throw an error!
      val sendTo = context.actorSelection(validationPool.toList(Random.nextInt(validationPool.size)))
      log.info("Send a validation work for fold " + fold + " to pool agent: " + sendTo)
      sendTo ! ValidationQuery(fold, model, testData)
      stay
    }
    case Event(ValidationResult(fold, variableType, results), Some(data: Data)) => {
      log.info("Received validation results for fold " + fold + ".")
      // Score the results
      val groundTruth = data.validation.getTestSet(fold)._2.map(x => x.asJsObject.fields.toList.head._2.compactPrint)
      data.results(fold) = Scores(results, groundTruth, variableType)

      // TODO To be improved with new Spark integration
      // Update the average score
      data.variableType = variableType
      data.average = (data.average._1 ::: results,  data.average._2 ::: groundTruth)

      // If we have validated all the fold we finish!
      if (data.results.size == data.foldsCount) reduceAndStop(data) else stay
    }
    case Event(ValidationError(message), Some(data: Data)) => {
      log.error(message)
      // On testing fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
    }
    case Event(Error(message), Some(data: Data)) => {
      log.error(message)
      // On training fold fails, we notify supervisor and we stop
      context.parent ! CrossValidationActor.ErrorResponse(data.job.validation, message)
      stop
    }
  }

  initialize()
}

