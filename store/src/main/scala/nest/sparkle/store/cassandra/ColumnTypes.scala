package nest.sparkle.store.cassandra

import scala.reflect.runtime.universe._
import nest.sparkle.store.cassandra.serializers._
import nest.sparkle.util.TaggedKeyValueExtractor
import spray.json.JsValue

object ColumnTypes {
  /** the column types in the store are currently fixed, so that we can prepare CQL statement
    * variants for each type of column
    */
  val supportedColumnTypes: Seq[SerializeInfo[_, _]] = List(
    createSerializationInfo[Long, Long](),
    createSerializationInfo[Long, Double](),
    createSerializationInfo[Long, Int](),
    createSerializationInfo[Long, Boolean](),
    createSerializationInfo[Long, String](),
    createSerializationInfo[Long, JsValue]()
  )

  case class UnsupportedColumnType[T, U](keySerial: CanSerialize[T], valueSerial: CanSerialize[U])
    extends RuntimeException(s"${keySerial.columnType}-${valueSerial.columnType}")
  
  /** retrieve the serialization info for one of the supported column types */
  def serializationInfo[T: CanSerialize, U: CanSerialize](): SerializeInfo[T, U] = {
    val keySerialize = implicitly[CanSerialize[T]]
    val valueSerialize = implicitly[CanSerialize[U]]

    val found = supportedColumnTypes.find{ info =>
      info.domain == keySerialize && info.range == valueSerialize
    } orElse { throw UnsupportedColumnType(keySerialize, valueSerialize) }
    found.get.asInstanceOf[SerializeInfo[T, U]]
  }

  /** return some serialization info for the types provided */
  private def createSerializationInfo[T: CanSerialize, U: CanSerialize](): SerializeInfo[T, U] = {
    val domainSerializer = implicitly[CanSerialize[T]]
    val rangeSerializer = implicitly[CanSerialize[U]]

    // cassandra storage types for the argument and value
    val argumentStoreType = domainSerializer.columnType
    val valueStoreType = rangeSerializer.columnType
    val tableName = argumentStoreType + "0" + valueStoreType

    assert(validateTableName(tableName), s"invalid table name $tableName")

    SerializeInfo(domainSerializer, rangeSerializer, tableName)
  }

  // Cassandra table name length limit per http://cassandra.apache.org/doc/cql3/CQL.html#createTableStmt
  private final val maxTableNameLength = 32

  private[cassandra] def validateTableName(tableName: String): Boolean = {
    !tableName.isEmpty && (tableName.length <= maxTableNameLength) && tableName.forall(_.isLetterOrDigit)
  }

  /** holder for serialization info for given domain and range types */
  case class SerializeInfo[T, U](domain: CanSerialize[T], range: CanSerialize[U], tableName: String)
}

/** extractors for type pairs of supported column types */
object LongDoubleSerializers extends SerializerExtractor[Long, Double]
object LongLongSerializers extends SerializerExtractor[Long, Long]
object LongIntSerializers extends SerializerExtractor[Long, Int]
object LongBooleanSerializers extends SerializerExtractor[Long, Boolean]
object LongStringSerializers extends SerializerExtractor[Long, String]
object LongJsValueSerializers extends SerializerExtractor[Long, JsValue]

/** a pair of cassandra serializers for key and value */
case class KeyValueSerializers[T, U](keySerializer: CanSerialize[T], valueSerializer: CanSerialize[U])

/** support for writing an extractor from TaggedKeyValue to a KeyValueSerializer */
class SerializerExtractor[T: CanSerialize: TypeTag, U: CanSerialize: TypeTag]
    extends TaggedKeyValueExtractor[T, U, KeyValueSerializers[T, U]] {
  def cast: KeyValueSerializers[T, U] = KeyValueSerializers(implicitly[CanSerialize[T]], implicitly[CanSerialize[U]])
}