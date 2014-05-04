package nest.sparkle.time.protocol
import spray.httpx.SprayJsonSupport._
import nest.sparkle.time.protocol.RequestJson.StreamRequestMessageFormat
import com.typesafe.config.Config
import nest.sparkle.store.Store
import nest.sparkle.util.ConfigUtil.modifiedConfig
import spray.json.JsObject
import spray.json.JsValue
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import nest.sparkle.store.Column
import spray.json._

case class TestingSelectorParams(columnPath: String)

object TestingSelectorParamsFormat extends DefaultJsonProtocol {
  implicit val testingSelectorFormat = jsonFormat1(TestingSelectorParams)
}

class TestingSelector(rootConfig: Config, store: Store) extends CustomSourceSelector {
  /** match selector parameters of the form: { columnPath: "myPath" } */
  object SelectorParameters {
    def unapply(params: JsObject): Option[String] = {
      params.fields.toList.headOption match {
        case Some((name, JsString(path))) if name == "columnPath" => Some(path)
        case _                                                    => None
      }
    }
  }

  override def selectColumns(selectorParameters: JsObject): Seq[Future[Column[_, _]]] = {
    selectorParameters match {
      case SelectorParameters(columnPath) => Seq(store.column(columnPath))
      case x                              => Seq(Future.failed(MalformedSourceSelector(x.toString)))
    }
  }

  override def name = getClass.getName
}

class TestCustomSelector extends TestStore with StreamRequestor with TestDataService {
  import TestingSelectorParamsFormat._
  import scala.collection.JavaConverters._

  lazy val testSelectorClassName = classOf[TestingSelector].getName
  
  override def configOverrides = {
    val selectors = Seq(s"$testSelectorClassName").asJava
    super.configOverrides :+ "sparkle-time-server.custom-selectors" -> selectors
  }

  test("custom source selector") {
    val testSelectorJson = TestingSelectorParams(testColumnPath).toJson.asInstanceOf[JsObject]
    val customSelector = CustomSelector(testSelectorClassName, testSelectorJson)
    val requestMessage = streamRequest("Raw", SelectCustom(customSelector))
    Post("/v1/data", requestMessage) ~> v1protocol ~> check {
      val events = streamDataEvents(response)
      events.length shouldBe 2
      events(0).value shouldBe 1
      events(1).value shouldBe 2
    }
  }
}