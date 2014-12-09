package nest.sparkle.loader.kafka

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.concurrent.{Future, ExecutionContext, promise}
import scala.reflect.runtime.universe._
import scala.language.existentials
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal
import scala.util.control.Exception.nonFatalCatch

import com.typesafe.config.Config

import kafka.consumer.ConsumerTimeoutException

import nest.sparkle.loader._
import nest.sparkle.loader.Loader.{Events, LoadingTransformer, TaggedBlock}
import nest.sparkle.store.WriteableStore
import nest.sparkle.store.cassandra.RecoverCanSerialize
import nest.sparkle.util.{Instrumented, Log, Instance, ConfigUtil, Watched}
import nest.sparkle.util.KindCast.castKind

/**
 * A runnable that reads a topic from a KafkaReader that returns messages containing Avro encoded
 * records that contains an array of values and writes the values to the Sparkle store.
 */
class KafkaTopicLoader[K: TypeTag]( val rootConfig: Config,
                                    val store: WriteableStore,
                                    val topic: String,
                                    val decoder: KafkaKeyValues
) (implicit execution: ExecutionContext)
  extends Watched[ColumnUpdate[K]]
  with Runnable
  with Instrumented
  with Log
{  
  private val loaderConfig = ConfigUtil.configForSparkle(rootConfig).getConfig("kafka-loader")

  /** Evidence for key serializing when writing to the store */
  private implicit val keySerialize = RecoverCanSerialize.tryCanSerialize[K](typeTag[K]).get
  
  val reader = KafkaReader(topic, rootConfig, None)(decoder)
  
  val transformer = makeTransformer()
  
  /** Current Kafka iterator */
  private var currentIterator: Option[Iterator[Try[TaggedBlock]]] = None
  
  /** Commit interval millis for compare */
  val commitTime = loaderConfig.getDuration("commit-interval", TimeUnit.MILLISECONDS)
  
  /** Number of Kafka messages to process before flushing the store and committing offsets */
  val messageBatchSize = loaderConfig.getInt("message-batch-size")
  
  /** Number of events to write to the log when tracing writes */
  val NumberOfEventsToTrace = 3

  /** Set to false to shutdown in an orderly manner */
  @volatile
  private var keepRunning = true
  
  /** Promise backing shutdown future */
  private val shutdownPromise = promise[Unit]()
  
  /** Allows any other code to take action when this loader terminates */
  val shutdownFuture = shutdownPromise.future
  
  // Metrics
  private val metricPrefix = topic.replace(".", "_").replace("*", "") 
  
  /** Read rate */
  private val readMetric = metrics.meter("kafka-messages-read", metricPrefix)

  /**
   * Main method of the loader.
   * 
   * Reads from a Kafka iterator and writes to the store.
   * 
   * Writes are queued to the store and flushed after N messages are read or the Kafka iterator
   * times out. Since each loader instance locks the store for queueing followed by a flush each
   * loader can commit the Kafka offsets guaranteeing no data loss.
   * 
   * Note the *all* of the loaders will eventually synchronize on the flush call as it performs
   * an exclusive lock to prevent any of the loaders from queueing data will a flush is in progress.
   * Only one of the loaders will actually perform the store writing. When the others get the 
   * exclusive lock there will be no data for them to write. Only when the last loader exits the
   * flush call will the other loaders be able to acquire the queue lock (a shared lock) and begin
   * queueing more events.
   */
  override def run(): Unit = {
    
    try {
      log.info("Loader for {} started", topic)

      while (keepRunning) {
        store.acquireEnqueueLock()
        try {
          (1 to messageBatchSize).foreach { _ =>
            iterator.next() match {
              case Success(block)                         =>
                readMetric.mark()
                enqueueBlock(block)
              case Failure(err: ConsumerTimeoutException) =>
                log.trace("consumer timeout reading {}", topic)
                throw err
              case Failure(err)                           =>
                // Some other Kafka reading error. Discard iterator and try again.
                discardIterator()
            }
          }
        } catch {
          case err: ConsumerTimeoutException => // no data available, flush whatever was queued
        } finally {
          store.releaseEnqueueLock()
        }
        
        store.flush()
        
        commitKafkaOffsets()  // All of our messages data has safely been written to the store
      }

      log.info(s"$topic loader is terminating")

      commitKafkaOffsets() // commit any outstanding offsets
      reader.close()

      log.info(s"$topic loader has terminated")
    } finally {
      shutdownPromise.success(())
    }
  }
  
  /** Shutdown this loader nicely */
  def shutdown(): Future[Unit] = {
    keepRunning = false
    shutdownFuture  // this is public but convenient to return to caller
  }

  /** Create the transformer, if any, for this topic.
    * The first pattern to match is used.
    */
  private def makeTransformer(): Option[LoadingTransformer] = {
    val transformerList = loaderConfig.getConfigList("transformers").asScala.toSeq
    val transformerConfig = transformerList find { configEntry =>
      val regex = configEntry.getString("match").r
      regex.pattern.matcher(topic).matches()
    }
    
    transformerConfig.map { configEntry =>
      val className = configEntry.getString("transformer")
      val transformer: LoadingTransformer = Instance.byName(className)(rootConfig)
      transformer
    }
  }

  /**
   * Return the current iterator if it exists or create a new one and return it.
   * 
   * Note that this method will block the current thread until a new iterator can be obtained.
   * This will happen if Kafka or Zookeeper are down or failing.
   * 
   * @return iterator
   */
  private def iterator: Iterator[Try[TaggedBlock]] = {
    currentIterator.getOrElse {
      val kafkaIterator = KafkaIterator[ArrayRecordColumns](reader)(decoder)
      
      val decodeIterator = kafkaIterator map { tryMessageAndMetadata =>
        tryMessageAndMetadata flatMap { messageAndMetadata =>
          KeyValueColumnConverter.convertMessage(decoder, messageAndMetadata.message())
        }
      }
      
      // Use transformer if one exists
      val iter = transformer map { _ =>
        decodeIterator map {tryBlock => 
          tryBlock flatMap {block => transform(block)}
        }
      } getOrElse decodeIterator
      
      currentIterator = Some(iter)
      iter
    }
  }
  
  private def discardIterator(): Unit = {
    currentIterator = None
    reader.close()  // Ensure Kafka connection is closed.
  }
  
  /** 
   * Commit the topic offsets
   * 
   * If the commit fails the iterator is closed and re-opened. Any messages already queued and/or
   * written to the store will be re-read and re-written. Since the writes are idempotent this 
   * causes no problem other than duplicate work.
   */
  private def commitKafkaOffsets(): Unit = {
    nonFatalCatch withTry {
      reader.commit()
    } recover {
      case err => 
        log.error(s"Unhandled exception committing kafka offsets for $topic", err)
        // State of kafka connection is unknown. Discard iterator, new one will be created
        discardIterator()
    }
  }
  
  /** Transform the block. Only called if transformer is not None */
  private def transform(block: TaggedBlock): Try[TaggedBlock] = {
    try {
      Success(transformer.get.transform(block))
    } catch {
      case NonFatal(err) => Failure(err)
    }
  }
  
  private def enqueueBlock(block: TaggedBlock)(implicit keyType: TypeTag[K]): Unit = {
    block.map { slice => 
      def withFixedType[U](): Unit = {
        implicit val valueType = slice.valueType
        log.trace(
          s"loading ${slice.events.length} events to column: ${slice.columnPath} keyType: $keyType valueType: $valueType"
        )
        
        val valueSerialize = RecoverCanSerialize.optCanSerialize[U](valueType).get
        val castEvents: Events[K,U] = castKind(slice.events)
        store.enqueue(slice.columnPath, castEvents)(keySerialize, valueSerialize, execution)
      }
      withFixedType()
    }
  }
}
