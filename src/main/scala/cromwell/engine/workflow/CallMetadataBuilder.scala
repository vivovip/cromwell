package cromwell.engine.workflow

import cromwell.engine.ExecutionIndex._
import cromwell.engine.backend.{CallLogs, CallMetadata}
import cromwell.engine.db.ExecutionDatabaseKey
import cromwell.engine.db.slick._
import cromwell.engine.{EnhancedFullyQualifiedName, ExecutionEventEntry, SymbolStoreEntry, _}
import org.joda.time.DateTime

import scala.language.postfixOps

/**
  * Builds call metadata suitable for return as part of a workflow metadata request.
  */
object CallMetadataBuilder {

  // The various data passed into the `build` method has a very different shape from what the workflow/metadata
  // endpoint needs to serve.  As an intermediate step to producing data in the correct format, this class aggregates
  // data by `ExecutionDatabaseKey`, which is a useful prerequisite for building results in the final data format.
  private case class AssembledCallMetadata(key: ExecutionDatabaseKey,
                                           execution: Execution,
                                           inputs: Traversable[SymbolStoreEntry] = Seq.empty,
                                           outputs: Option[Traversable[SymbolStoreEntry]] = None,
                                           streams: Option[CallLogs] = None,
                                           backend: Option[String] = None,
                                           jobId: Option[String] = None,
                                           backendStatus: Option[String] = None,
                                           executionEvents: Seq[ExecutionEventEntry] = Seq.empty)

  // Types used in interim steps of the construction of the call metadata.
  // Map from an `ExecutionDatabaseKey` to the interim `AssembledCallMetadata` format.
  private type ExecutionMap = Map[ExecutionDatabaseKey, AssembledCallMetadata]
  // A function that transforms from one `ExecutionMap` to another.
  private type ExecutionMapTransformer = ExecutionMap => ExecutionMap

  //TODO: BACKEND - This shouldn't be here. Should be gone once we remove backend dependency from the DB layer
  object BackendValues {
    // FIXME needs to traverse the list of pluggable backends rather than hardcoding a list.
    def extract(infos: ExecutionInfosByExecution): BackendValues = {
      def extractValue(key: String): Option[String] = infos.executionInfos collectFirst { case i if i.key == key => i.value } flatten

      BackendValues("Local", jobId = extractValue("PID"))
    }
  }
  // Case class to homogenize the datatypes for each supported backend.
  case class BackendValues(name: String, jobId: Option[String] = None, status: Option[String] = None)

  /**
    * Function to build the "base" transformer, this needs to run first in the sequence of transformers since it builds
    * the initial `ExecutionMap` entries.
    */
  private def buildBaseTransformer(executions: Traversable[Execution], executionKeys: Traversable[ExecutionDatabaseKey]): ExecutionMapTransformer =
  // The input ExecutionMap is ignored in this transformer!
    _ =>
      (for {
        execution <- executions
        key = ExecutionDatabaseKey(execution.callFqn, execution.index.toIndex)
        if !execution.callFqn.isScatter && !key.isCollector(executionKeys)
      } yield key -> AssembledCallMetadata(key, execution)).toMap


  /**
    * Function to build a transformer that adds inputs data to the entries in the input `ExecutionMap`.
    */
  private def buildInputsTransformer(callInputs: Traversable[SymbolStoreEntry]): ExecutionMapTransformer =
    executionMap => {
      // Remove collector entries for the purposes of this endpoint.
      val inputsNoCollectors = filterCollectorSymbols(callInputs)
      val inputsByKey = inputsNoCollectors.groupBy(i => ExecutionDatabaseKey(i.scope, i.key.index))

      for {
        (inputKey, inputs) <- inputsByKey
        // Inputs always have index None, but execution entries may have real indexes.  Look for executions by
        // FQN only, then give them copies of the inputs with matching indexes.
        (executionKey, assembledMetadata) <- executionMap
        if executionKey.fqn == inputKey.fqn
        indexedInputs = inputs map { case SymbolStoreEntry(key, wdlType, maybeWdlValue, maybeSymbolHash) =>
          new SymbolStoreEntry(key.copy(index = executionKey.index), wdlType, maybeWdlValue, maybeSymbolHash)
        }
      } yield executionKey -> assembledMetadata.copy(inputs = indexedInputs)
    }

  /**
    * Function to build a transformer that adds outputs data to the entries in the input `ExecutionMap`.
    */
  private def buildOutputsTransformer(callOutputs: Traversable[SymbolStoreEntry]): ExecutionMapTransformer =
    executionMap => {
      // Remove collector entries for the purposes of this endpoint.
      val outputsNoCollectors = filterCollectorSymbols(callOutputs)
      val outputsByKey = outputsNoCollectors.groupBy(o => ExecutionDatabaseKey(o.scope, o.key.index))
      for {
        (key, outputs) <- outputsByKey
        baseMetadata = executionMap.get(key).get
      } yield key -> baseMetadata.copy(outputs = Option(outputs))
    }

  /**
    * Function to build a transformer that adds outputs data to the entries in the input `ExecutionMap`.
    */
  private def buildExecutionEventsTransformer(eventsMap: Map[ExecutionDatabaseKey, Seq[ExecutionEventEntry]]): ExecutionMapTransformer =
    executionMap => {
      for {
        (key, events) <- eventsMap
        baseMetadata = executionMap.get(key).get
      } yield key -> baseMetadata.copy(executionEvents = events)
    }

  /**
   * Function to build a transformer that adds job data to the entries in the input `ExecutionMap`.
   */
  private def buildExecutionInfoTransformer(executionsAndInfos: Traversable[ExecutionInfosByExecution]): ExecutionMapTransformer =
    executionMap => {
      val allExecutions = executionsAndInfos map { _.execution }
      for {
        ei <- executionsAndInfos
        e = ei.execution
        if !e.isScatter && !e.isCollector(allExecutions)
        baseMetadata = executionMap.get(e.toKey).get
        backendValues = BackendValues.extract(ei)
      } yield e.toKey -> baseMetadata.copy(backend = e.backendType, jobId = backendValues.jobId, backendStatus = backendValues.status)
    }.toMap

  /**
    * Function to build a transformer that adds standard streams data to the entries in the input `ExecutionMap`.
    */
  private def buildStreamsTransformer(standardStreamsMap: Map[FullyQualifiedName, Seq[Seq[CallLogs]]]): ExecutionMapTransformer =
    executionMap => {
      val databaseKeysWithNoneIndexes = executionMap.keys groupBy { _.fqn } filter {
        case (fqn, edks) => edks forall { _.index.isEmpty }
      }

      def indexForFqn(fqn: FullyQualifiedName, rawIndex: Int): ExecutionIndex = {
        if (databaseKeysWithNoneIndexes.contains(fqn)) None else rawIndex.toIndex
      }

      for {
        (fqn, seqOfSeqOfStreams) <- standardStreamsMap.toTraversable
        (logsForAttempt, index) <- seqOfSeqOfStreams.zipWithIndex
        // Increment all attempt indices because attempts are one-based
        (streams, attempt) <- logsForAttempt.zipWithIndex map { case (l, a) => (l, a + 1) }
        key = ExecutionDatabaseKey(fqn, indexForFqn(fqn, index), attempt)
        baseMetadata = executionMap.get(key).get
      } yield key -> baseMetadata.copy(streams = Option(streams))
    }.toMap

  /** Remove symbols corresponding to collectors. */
  private def filterCollectorSymbols(symbols: Traversable[SymbolStoreEntry]): Traversable[SymbolStoreEntry] = {
    // Squash duplicate ExecutionDatabaseKeys.
    val databaseKeys = symbols map { s => ExecutionDatabaseKey(s.scope, s.key.index) } toSet
    // Look for FQNs with more than one ExecutionDatabaseKey per FQN.
    val fqnsWithCollectors = databaseKeys.groupBy(_.fqn).collect({ case (fqn, keys) if keys.size > 1 => fqn }).toSet
    // We know which FQNs with None indexes correspond to collectors, so filter matching symbols.
    symbols.filterNot { s => s.key.index.isEmpty && fqnsWithCollectors.contains(s.scope) }
  }

  /**
   *  Construct the map of `FullyQualifiedName`s to `Seq[CallMetadata]` that contains all the call metadata available
   *  for the specified parameters.
   */
  def build(infosByExecution: Traversable[ExecutionInfosByExecution],
            standardStreamsMap: Map[FullyQualifiedName, Seq[Seq[CallLogs]]],
            callInputs: Traversable[SymbolStoreEntry],
            callOutputs: Traversable[SymbolStoreEntry],
            executionEvents: Map[ExecutionDatabaseKey, Seq[ExecutionEventEntry]]): Map[FullyQualifiedName, Seq[CallMetadata]] = {

    val executionKeys = infosByExecution map { x => ExecutionDatabaseKey(x.execution.callFqn, x.execution.index.toIndex) }

    // Define a sequence of ExecutionMap transformer functions.
    val executionMapTransformers = Seq(
      buildBaseTransformer(infosByExecution map { _.execution }, executionKeys),
      buildInputsTransformer(callInputs),
      buildOutputsTransformer(callOutputs),
      buildExecutionEventsTransformer(executionEvents),
      buildExecutionInfoTransformer(infosByExecution),
      buildStreamsTransformer(standardStreamsMap)
    )

    // Fold a zero ExecutionMap across this Seq of functions.
    val executionMap = executionMapTransformers.foldLeft(Map.empty: ExecutionMap) {
      case (map, transformer) => map ++ transformer(map) }

    // Convert from the convenience AssembledCallMetadata format to the CallMetadata format
    // that the endpoint needs to serve up.
    def constructCallMetadata(metadata: AssembledCallMetadata): CallMetadata = {
      val inputsMap = metadata.inputs map { entry => entry.key.name -> entry.wdlValue.get } toMap
      val outputsMap = for {
        outputs <- metadata.outputs.toSeq
        entry <- outputs
      } yield entry.key.name -> entry.wdlValue.get

      CallMetadata(
        inputs = inputsMap,
        executionStatus = metadata.execution.status,
        backend = metadata.backend,
        backendStatus = metadata.backendStatus,
        outputs = Option(outputsMap.toMap),
        start = metadata.execution.startDt map { new DateTime(_) },
        end = metadata.execution.endDt map { new DateTime(_) },
        jobId = metadata.jobId,
        returnCode = metadata.execution.rc,
        shardIndex = metadata.execution.index,
        stdout = metadata.streams map { _.stdout },
        stderr = metadata.streams map { _.stderr },
        backendLogs = metadata.streams flatMap { _.backendLogs },
        executionEvents = metadata.executionEvents,
        attempt = metadata.execution.attempt)
    }

    // The CallMetadatas need to be grouped by FQN and sorted within an FQN by index.
    for {
      (key, fqnGroupedMetadatas) <- executionMap.values.groupBy(_.key.fqn)
      fqnGroupedAndSortedMetadatas = fqnGroupedMetadatas.toSeq.sortBy(_.key.index)
    } yield key.fqn -> (fqnGroupedAndSortedMetadatas map constructCallMetadata)
  }
}
